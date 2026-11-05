package io.github.varun51.mcp.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.tokenizer.TkBpeTokenizer;
import io.github.varun51.mcp.tokenizer.TkEncoding;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TkInferenceServerTest {

    private static final String _TKSECRET = "sk-test-secret-0123456789";
    private static final String _TKOK = """
            {"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}""";
    private static final List<Map<String, Object>> _TKMESSAGES = List.of(Map.of("role", "system", "content", "be brief"), Map.of("role", "user", "content", "hello"));
    private static final TkBpeTokenizer _TKBYTES = mtkByteTokenizer();

    private final Map<String, String> tkEnvironment = new HashMap<>();
    private TkMockServer tkMock;
    private Map<String, TkTool> tkTools;

    @BeforeEach
    void mtkStart() throws IOException {
        tkMock = new TkMockServer();
        tkEnvironment.put("TK_INFERENCE_URL", tkMock.mtkUrl() + "/");
        tkEnvironment.put("TK_INFERENCE_API_KEY", _TKSECRET);
        TkInferenceClient tkClient = new TkInferenceClient(tkEnvironment::get, Duration.ofMillis(1));
        Map<String, TkTool> tkByName = new HashMap<>();
        TkInferenceServer.mtkTools(tkClient, tkName -> {
            TkEncoding.mtkNamed(tkName);
            return _TKBYTES;
        }).forEach(tkTool -> tkByName.put(tkTool.tkName(), tkTool));
        tkTools = tkByName;
    }

    @AfterEach
    void mtkStop() {
        tkMock.close();
    }

    @Test
    void mtkCompletesChatAndForwardsParameters() throws IOException {
        tkMock.mtkReply(200, _TKOK);
        Map<String, Object> tkResult = mtkOk("complete", Map.of("model", "llama-3", "messages", _TKMESSAGES, "max_tokens", 64, "temperature", 0.2));
        assertEquals("Hi there", tkResult.get("content"));
        assertEquals("stop", tkResult.get("finish_reason"));
        assertEquals(Map.of("prompt_tokens", 12, "completion_tokens", 3, "total_tokens", 15), tkResult.get("usage"));
        assertTrue(((Number) tkResult.get("latency_ms")).longValue() >= 0);
        TkSeen tkSeen = tkMock.tkSeen.get(0);
        assertEquals("POST /v1/chat/completions", tkSeen.tkMethod() + " " + tkSeen.tkPath());
        assertEquals("Bearer " + _TKSECRET, tkSeen.tkAuthorization());
        Map<String, Object> tkSent = McpJsonDefaults.getMapper().readValue(tkSeen.tkBody(), new TypeRef<Map<String, Object>>() {
        });
        assertEquals(Map.of("model", "llama-3", "messages", _TKMESSAGES, "stream", false, "max_tokens", 64, "temperature", 0.2), tkSent);
    }

    @Test
    void mtkListsModelIds() {
        tkMock.mtkReply(200, "{\"object\":\"list\",\"data\":[{\"id\":\"llama-3\"},{\"id\":\"qwen\"},{\"object\":\"model\"}]}");
        assertEquals(Map.of("models", List.of("llama-3", "qwen"), "count", 2), mtkOk("list_models", Map.of()));
        assertEquals("GET /v1/models", tkMock.tkSeen.get(0).tkMethod() + " " + tkMock.tkSeen.get(0).tkPath());
    }

    @Test
    void mtkOmitsAuthorizationWithoutApiKey() {
        tkEnvironment.remove("TK_INFERENCE_API_KEY");
        tkMock.mtkReply(200, _TKOK);
        mtkOk("complete", Map.of("model", "m", "messages", _TKMESSAGES));
        assertNull(tkMock.tkSeen.get(0).tkAuthorization());
    }

    @Test
    void mtkPassesThroughNullContentAndToolCallIds() throws IOException {
        tkMock.mtkReply(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":\"tool_calls\"}]}");
        Map<String, Object> tkResult = mtkOk("complete", Map.of("model", "m", "messages", List.of(Map.of("role", "tool", "content", "42", "tool_call_id", "call_1"))));
        assertNull(tkResult.get("content"));
        assertEquals("tool_calls", tkResult.get("finish_reason"));
        assertTrue(new String(tkMock.tkSeen.get(0).tkBody(), StandardCharsets.UTF_8).contains("\"tool_call_id\":\"call_1\""));
    }

    @Test
    void mtkRetriesRateLimitsAndServerErrors() {
        tkMock.mtkReply(429, "{}");
        tkMock.mtkReply(503, "{}");
        tkMock.mtkReply(200, _TKOK);
        assertEquals("Hi there", mtkOk("complete", Map.of("model", "m", "messages", _TKMESSAGES)).get("content"));
        assertEquals(3, tkMock.tkSeen.size());
    }

    @Test
    void mtkGivesUpAfterThreeRetries() {
        tkMock.tkFallback = new TkReply(500, "{}", false);
        assertEquals("inference server returned HTTP 500 (attempts: 4)", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES)));
        assertEquals(4, tkMock.tkSeen.size());
    }

    @Test
    void mtkNeverRetriesClientErrorsAndNeverEchoesTheirBody() {
        tkMock.mtkReply(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + _TKSECRET + " for prompt hello\"}}");
        String tkError = mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES));
        assertEquals("inference server returned HTTP 401", tkError);
        assertEquals(1, tkMock.tkSeen.size());
        tkMock.mtkReply(404, "{}");
        assertEquals("inference server returned HTTP 404", mtkError("list_models", Map.of()));
        assertEquals(2, tkMock.tkSeen.size());
    }

    @Test
    void mtkCancelsRequestsThatExceedTheTimeout() {
        tkMock.tkFallback = new TkReply(200, _TKOK, true);
        long tkStart = System.nanoTime();
        assertEquals("request cancelled after 1 seconds", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "timeout_seconds", 1)));
        long tkElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tkStart);
        assertTrue(tkElapsed >= 900 && tkElapsed < 10_000, "elapsed " + tkElapsed);
    }

    @Test
    void mtkRejectsOverBudgetPromptsWithoutAnyRequest() {
        String tkError = mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "max_tokens", 9, "context_window", 40));
        assertEquals("prompt (32 tokens) plus max_tokens (9) exceeds context_window (40)", tkError);
        assertFalse(tkError.contains("hello") || tkError.contains("be brief"));
        assertEquals("prompt (32 tokens) plus max_tokens (0) exceeds context_window (31)",
                mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "context_window", 31)));
        assertEquals("unknown encoding: gpt2, expected one of [cl100k_base, o200k_base]",
                mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "context_window", 100, "encoding", "gpt2")));
        assertEquals(0, tkMock.tkSeen.size());
        tkMock.mtkReply(200, _TKOK);
        mtkOk("complete", Map.of("model", "m", "messages", _TKMESSAGES, "max_tokens", 8, "context_window", 40));
        assertEquals(1, tkMock.tkSeen.size());
    }

    @Test
    void mtkRequiresAValidInferenceUrl() {
        tkEnvironment.remove("TK_INFERENCE_URL");
        assertEquals("TK_INFERENCE_URL is not set", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES)));
        assertEquals("TK_INFERENCE_URL is not set", mtkError("list_models", Map.of()));
        tkEnvironment.put("TK_INFERENCE_URL", "file:///etc/passwd");
        assertEquals("TK_INFERENCE_URL must be an http or https URL", mtkError("list_models", Map.of()));
        tkEnvironment.put("TK_INFERENCE_URL", "http://user:hunter2@[bad");
        assertEquals("TK_INFERENCE_URL must be an http or https URL", mtkError("list_models", Map.of()));
        assertEquals(0, tkMock.tkSeen.size());
    }

    @Test
    void mtkRejectsMalformedApiKeysWithoutEchoingThem() {
        tkEnvironment.put("TK_INFERENCE_API_KEY", "sk-abc\r\nX-Injected: 1");
        String tkError = mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES));
        assertEquals("TK_INFERENCE_API_KEY contains invalid characters", tkError);
        assertEquals(0, tkMock.tkSeen.size());
    }

    @Test
    void mtkValidatesMessages() {
        assertEquals("messages must contain 1 to 1000 entries", mtkError("complete", Map.of("model", "m", "messages", List.of())));
        assertEquals("messages must contain 1 to 1000 entries",
                mtkError("complete", Map.of("model", "m", "messages", Collections.nCopies(1001, Map.of("role", "user", "content", "x")))));
        assertEquals("messages[1].role must be one of [system, user, assistant, tool]",
                mtkError("complete", Map.of("model", "m", "messages", List.of(Map.of("role", "user", "content", "a"), Map.of("role", "developer", "content", "b")))));
        assertEquals("messages[0].content must be a string", mtkError("complete", Map.of("model", "m", "messages", List.of(Map.of("role", "user")))));
        assertEquals("messages[0] must be an object", mtkError("complete", Map.of("model", "m", "messages", List.of("hello"))));
        assertEquals("messages[0].tool_call_id must be a string of at most 256 characters",
                mtkError("complete", Map.of("model", "m", "messages", List.of(Map.of("role", "tool", "content", "a", "tool_call_id", 7)))));
        String tkHalf = "x".repeat(TkInferenceServer._TKMAX_TOTAL_CHARS / 2 + 1);
        assertEquals("messages too large, limit is 1000000 characters",
                mtkError("complete", Map.of("model", "m", "messages", List.of(Map.of("role", "user", "content", tkHalf), Map.of("role", "user", "content", tkHalf)))));
        assertEquals(0, tkMock.tkSeen.size());
    }

    @Test
    void mtkCapsNumericArguments() {
        assertEquals("model must be 1 to 256 characters", mtkError("complete", Map.of("model", " ", "messages", _TKMESSAGES)));
        assertEquals("model must be 1 to 256 characters", mtkError("complete", Map.of("model", "m".repeat(257), "messages", _TKMESSAGES)));
        assertEquals("max_tokens must be between 1 and 1000000", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "max_tokens", 0)));
        assertEquals("max_tokens must be between 1 and 1000000", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "max_tokens", 1_000_001)));
        assertEquals("temperature must be between 0 and 2", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "temperature", 2.5)));
        assertEquals("timeout_seconds must be between 1 and 600", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "timeout_seconds", 601)));
        assertEquals("timeout_seconds must be between 1 and 600", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "timeout_seconds", 0)));
        assertEquals("context_window must be between 1 and 10000000", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES, "context_window", 0)));
        assertEquals("missing argument: model", mtkError("complete", Map.of("messages", _TKMESSAGES)));
        assertEquals(0, tkMock.tkSeen.size());
    }

    @Test
    void mtkRejectsOversizedAndMalformedResponses() {
        tkMock.mtkReply(200, "x".repeat(TkInferenceClient._TKMAX_RESPONSE_BYTES + 1));
        assertEquals("inference response exceeds 8388608 bytes", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES)));
        tkMock.mtkReply(200, "not json");
        assertEquals("inference server returned invalid JSON", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES)));
        tkMock.mtkReply(200, "{\"choices\":[]}");
        assertEquals("inference response has no choices", mtkError("complete", Map.of("model", "m", "messages", _TKMESSAGES)));
        tkMock.mtkReply(200, "{\"models\":[]}");
        assertEquals("inference response has no model list", mtkError("list_models", Map.of()));
    }

    @Test
    void mtkReportsUnreachableServersWithoutLeakingTheUrl() throws IOException {
        String tkClosed;
        try (TkMockServer tkGone = new TkMockServer()) {
            tkClosed = tkGone.mtkUrl();
        }
        tkEnvironment.put("TK_INFERENCE_URL", tkClosed);
        String tkError = mtkError("list_models", Map.of());
        assertEquals("cannot reach the inference server: ConnectException", tkError);
    }

    @Test
    void mtkResultsNeverContainTheApiKey() {
        tkMock.mtkReply(200, _TKOK);
        CallToolResult tkResult = tkTools.get("complete").mtkCall(Map.of("model", "m", "messages", _TKMESSAGES));
        assertFalse(((TextContent) tkResult.content().get(0)).text().contains(_TKSECRET));
        assertFalse(String.valueOf(tkResult.structuredContent()).contains(_TKSECRET));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mtkOk(String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get(tkName).mtkCall(tkArgs);
        assertEquals(false, tkResult.isError(), () -> ((TextContent) tkResult.content().get(0)).text());
        return (Map<String, Object>) tkResult.structuredContent();
    }

    private String mtkError(String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get(tkName).mtkCall(tkArgs);
        assertEquals(true, tkResult.isError());
        String tkText = ((TextContent) tkResult.content().get(0)).text();
        assertFalse(tkText.contains(_TKSECRET));
        return tkText;
    }

    private static TkBpeTokenizer mtkByteTokenizer() {
        StringBuilder tkVocabulary = new StringBuilder();
        for (int tkByte = 0; tkByte < 256; tkByte++) {
            tkVocabulary.append(Base64.getEncoder().encodeToString(new byte[] {(byte) tkByte})).append(' ').append(tkByte).append('\n');
        }
        return TkBpeTokenizer.mtkParse(tkVocabulary.toString().getBytes(StandardCharsets.US_ASCII), "\\S+|\\s+");
    }

    private record TkReply(int tkStatus, String tkBody, boolean tkHang) {
    }

    private record TkSeen(String tkMethod, String tkPath, String tkAuthorization, byte[] tkBody) {
    }

    private static final class TkMockServer implements AutoCloseable {

        private final HttpServer tkServer;
        private final ExecutorService tkExecutor = Executors.newVirtualThreadPerTaskExecutor();
        private final Queue<TkReply> tkReplies = new ConcurrentLinkedQueue<>();
        private final List<TkSeen> tkSeen = new CopyOnWriteArrayList<>();
        private final CountDownLatch tkRelease = new CountDownLatch(1);
        private volatile TkReply tkFallback = new TkReply(500, "{}", false);

        TkMockServer() throws IOException {
            tkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            tkServer.setExecutor(tkExecutor);
            tkServer.createContext("/", tkExchange -> {
                byte[] tkBody = tkExchange.getRequestBody().readAllBytes();
                tkSeen.add(new TkSeen(tkExchange.getRequestMethod(), tkExchange.getRequestURI().getPath(),
                        tkExchange.getRequestHeaders().getFirst("Authorization"), tkBody));
                TkReply tkReply = Objects.requireNonNullElse(tkReplies.poll(), tkFallback);
                try {
                    if (tkReply.tkHang()) {
                        tkRelease.await(30, TimeUnit.SECONDS);
                    }
                    byte[] tkOut = tkReply.tkBody().getBytes(StandardCharsets.UTF_8);
                    tkExchange.sendResponseHeaders(tkReply.tkStatus(), tkOut.length);
                    try (OutputStream tkStream = tkExchange.getResponseBody()) {
                        tkStream.write(tkOut);
                    }
                } catch (InterruptedException tkInterrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", tkInterrupted);
                } finally {
                    tkExchange.close();
                }
            });
            tkServer.start();
        }

        String mtkUrl() {
            return "http://127.0.0.1:" + tkServer.getAddress().getPort();
        }

        void mtkReply(int tkStatus, String tkBody) {
            tkReplies.add(new TkReply(tkStatus, tkBody, false));
        }

        @Override
        public void close() {
            tkRelease.countDown();
            tkServer.stop(0);
            tkExecutor.shutdownNow();
        }
    }
}
