package io.github.varun51.mcp.inference;

import static io.github.varun51.mcp.core.TkSchema.mtkEnum;
import static io.github.varun51.mcp.core.TkSchema.mtkField;
import static io.github.varun51.mcp.core.TkSchema.mtkObject;
import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkArgs;
import io.github.varun51.mcp.core.TkServerLauncher;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.core.TkToolException;
import io.github.varun51.mcp.tokenizer.TkBpeTokenizer;
import io.github.varun51.mcp.tokenizer.TkEncoding;
import io.github.varun51.mcp.tokenizer.TkVocabStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class TkInferenceServer {

    static final int _TKMAX_MESSAGES = 1000;
    static final int _TKMAX_TOTAL_CHARS = 1_000_000;
    static final int _TKMAX_TOKENS = 1_000_000;
    static final int _TKMAX_CONTEXT_WINDOW = 10_000_000;
    static final int _TKMAX_TIMEOUT_SECONDS = 600;
    static final int _TKMAX_NAME_CHARS = 256;
    static final int _TKLIST_TIMEOUT_SECONDS = 30;
    private static final int _TKDEFAULT_TIMEOUT_SECONDS = 120;
    private static final String _TKDEFAULT_ENCODING = "cl100k_base";
    private static final int _TKTOKENS_PER_MESSAGE = 3;
    private static final int _TKREPLY_PRIMING_TOKENS = 3;
    private static final List<String> _TKROLES = List.of("system", "user", "assistant", "tool");

    private TkInferenceServer() {
    }

    public static void main(String[] tkArgs) {
        TkVocabStore tkStore = new TkVocabStore(TkVocabStore.mtkDefaultDirectory());
        TkServerLauncher.mtkStartStdio("mcp-inference", "0.1.0", mtkTools(new TkInferenceClient(System::getenv, Duration.ofMillis(500)), tkStore::mtkTokenizer));
    }

    public static List<TkTool> mtkTools(TkInferenceClient tkClient, Function<String, TkBpeTokenizer> tkTokenizers) {
        Map<String, Object> tkMessage = mtkObject(Map.of("role", mtkEnum("Message role", _TKROLES.toArray(String[]::new)),
                "content", mtkField("string", "Message text"), "tool_call_id", mtkField("string", "Tool call id answered by a tool message")), "role", "content");
        Map<String, Object> tkProperties = Map.of(
                "model", mtkField("string", "Model id served by the inference server"),
                "messages", Map.of("type", "array", "items", tkMessage, "description", "Chat messages, at most " + _TKMAX_MESSAGES),
                "max_tokens", mtkField("integer", "Maximum tokens to generate"),
                "temperature", mtkField("number", "Sampling temperature between 0 and 2"),
                "timeout_seconds", mtkField("integer", "Overall deadline including retries, default " + _TKDEFAULT_TIMEOUT_SECONDS),
                "context_window", mtkField("integer", "Model context window; when set, prompts whose tokens plus max_tokens exceed it are rejected before any request"),
                "encoding", mtkEnum("Tokenizer used for the context_window check, default " + _TKDEFAULT_ENCODING,
                        TkEncoding._TKALL.stream().map(TkEncoding::tkName).toArray(String[]::new)));
        return List.of(
                new TkTool("complete", "Run a chat completion against the OpenAI-compatible server at TK_INFERENCE_URL, retrying 429 and 5xx responses",
                        mtkObject(tkProperties, "model", "messages"),
                        tkCall -> mtkComplete(tkClient, tkTokenizers, tkCall)),
                new TkTool("list_models", "List the model ids served at TK_INFERENCE_URL",
                        mtkObject(Map.of()),
                        tkCall -> {
                            Object tkData = tkClient.mtkExchange("/v1/models", null, _TKLIST_TIMEOUT_SECONDS).get("data");
                            TkToolException.mtkCheck(tkData instanceof List<?>, "inference response has no model list");
                            List<Object> tkIds = ((List<?>) tkData).stream()
                                    .map(tkModel -> tkModel instanceof Map<?, ?> tkMap ? tkMap.get("id") : null)
                                    .filter(Objects::nonNull)
                                    .map(Object.class::cast)
                                    .toList();
                            return mtkResult("models", tkIds, "count", tkIds.size());
                        }));
    }

    private static Object mtkComplete(TkInferenceClient tkClient, Function<String, TkBpeTokenizer> tkTokenizers, TkArgs tkCall) {
        String tkModel = tkCall.mtkString("model");
        TkToolException.mtkCheck(!tkModel.isBlank() && tkModel.length() <= _TKMAX_NAME_CHARS, "model must be 1 to " + _TKMAX_NAME_CHARS + " characters");
        List<Map<String, Object>> tkMessages = mtkMessages(tkCall.mtkList("messages"));
        int tkMaxTokens = tkCall.mtkInt("max_tokens", 0);
        TkToolException.mtkCheck(!tkCall.mtkHas("max_tokens") || (tkMaxTokens >= 1 && tkMaxTokens <= _TKMAX_TOKENS), "max_tokens must be between 1 and " + _TKMAX_TOKENS);
        int tkTimeout = tkCall.mtkInt("timeout_seconds", _TKDEFAULT_TIMEOUT_SECONDS);
        TkToolException.mtkCheck(tkTimeout >= 1 && tkTimeout <= _TKMAX_TIMEOUT_SECONDS, "timeout_seconds must be between 1 and " + _TKMAX_TIMEOUT_SECONDS);
        Map<String, Object> tkRequest = mtkResult("model", tkModel, "messages", tkMessages, "stream", false);
        if (tkMaxTokens > 0) {
            tkRequest.put("max_tokens", tkMaxTokens);
        }
        if (tkCall.mtkHas("temperature")) {
            double tkTemperature = tkCall.mtkDouble("temperature");
            TkToolException.mtkCheck(tkTemperature >= 0 && tkTemperature <= 2, "temperature must be between 0 and 2");
            tkRequest.put("temperature", tkTemperature);
        }
        if (tkCall.mtkHas("context_window")) {
            int tkWindow = tkCall.mtkInt("context_window");
            TkToolException.mtkCheck(tkWindow >= 1 && tkWindow <= _TKMAX_CONTEXT_WINDOW, "context_window must be between 1 and " + _TKMAX_CONTEXT_WINDOW);
            TkBpeTokenizer tkTokenizer = tkTokenizers.apply(tkCall.mtkString("encoding", _TKDEFAULT_ENCODING));
            long tkPrompt = _TKREPLY_PRIMING_TOKENS + tkMessages.stream()
                    .mapToLong(tkMessage -> _TKTOKENS_PER_MESSAGE + tkTokenizer.mtkEncode((String) tkMessage.get("role")).size() + tkTokenizer.mtkEncode((String) tkMessage.get("content")).size())
                    .sum();
            TkToolException.mtkCheck(tkPrompt + tkMaxTokens <= tkWindow,
                    "prompt (" + tkPrompt + " tokens) plus max_tokens (" + tkMaxTokens + ") exceeds context_window (" + tkWindow + ")");
        }
        long tkStart = System.nanoTime();
        Map<String, Object> tkResponse = tkClient.mtkExchange("/v1/chat/completions", tkRequest, tkTimeout);
        long tkLatency = (System.nanoTime() - tkStart) / 1_000_000;
        TkToolException.mtkCheck(tkResponse.get("choices") instanceof List<?> tkChoices && !tkChoices.isEmpty() && tkChoices.get(0) instanceof Map<?, ?>,
                "inference response has no choices");
        Map<?, ?> tkChoice = (Map<?, ?>) ((List<?>) tkResponse.get("choices")).get(0);
        Object tkContent = tkChoice.get("message") instanceof Map<?, ?> tkReply ? tkReply.get("content") : null;
        return mtkResult("content", tkContent, "finish_reason", tkChoice.get("finish_reason"), "usage", tkResponse.get("usage"), "latency_ms", tkLatency);
    }

    private static List<Map<String, Object>> mtkMessages(List<Object> tkRaw) {
        TkToolException.mtkCheck(!tkRaw.isEmpty() && tkRaw.size() <= _TKMAX_MESSAGES, "messages must contain 1 to " + _TKMAX_MESSAGES + " entries");
        List<Map<String, Object>> tkMessages = new ArrayList<>();
        long tkChars = 0;
        for (int tkIndex = 0; tkIndex < tkRaw.size(); tkIndex++) {
            String tkPrefix = "messages[" + tkIndex + "]";
            TkToolException.mtkCheck(tkRaw.get(tkIndex) instanceof Map<?, ?>, tkPrefix + " must be an object");
            Map<?, ?> tkMessage = (Map<?, ?>) tkRaw.get(tkIndex);
            TkToolException.mtkCheck(tkMessage.get("role") instanceof String tkRole && _TKROLES.contains(tkRole), tkPrefix + ".role must be one of " + _TKROLES);
            TkToolException.mtkCheck(tkMessage.get("content") instanceof String, tkPrefix + ".content must be a string");
            Map<String, Object> tkClean = mtkResult("role", tkMessage.get("role"), "content", tkMessage.get("content"));
            if (tkMessage.get("tool_call_id") != null) {
                TkToolException.mtkCheck(tkMessage.get("tool_call_id") instanceof String tkId && tkId.length() <= _TKMAX_NAME_CHARS,
                        tkPrefix + ".tool_call_id must be a string of at most " + _TKMAX_NAME_CHARS + " characters");
                tkClean.put("tool_call_id", tkMessage.get("tool_call_id"));
            }
            tkChars += ((String) tkClean.get("content")).length();
            TkToolException.mtkCheck(tkChars <= _TKMAX_TOTAL_CHARS, "messages too large, limit is " + _TKMAX_TOTAL_CHARS + " characters");
            tkMessages.add(tkClean);
        }
        return tkMessages;
    }
}
