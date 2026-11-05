package io.github.varun51.mcp.fit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.varun51.mcp.core.TkTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TkWillItFitServerTest {

    private static final String _TKTOKEN = "hf_" + "testtoken";
    private final Map<String, byte[]> tkRoutes = new ConcurrentHashMap<>();
    private final Map<String, String> tkAuthSeen = new ConcurrentHashMap<>();
    private HttpServer tkHub;
    private HttpServer tkCdn;

    @BeforeEach
    void mtkStartHub() throws Exception {
        tkCdn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tkCdn.createContext("/", tkExchange -> {
            tkAuthSeen.put("cdn", String.valueOf(tkExchange.getRequestHeaders().getFirst("Authorization")));
            mtkSend(tkExchange, 200, mtkConfig(2, 4, 2, 64, 1000));
        });
        tkCdn.start();
        tkHub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tkHub.createContext("/", tkExchange -> {
            String tkPath = tkExchange.getRequestURI().getPath();
            tkAuthSeen.put(tkPath, String.valueOf(tkExchange.getRequestHeaders().getFirst("Authorization")));
            if (tkPath.equals("/org/redirected/resolve/main/config.json")) {
                tkExchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + tkCdn.getAddress().getPort() + "/signed/config.json");
                mtkSend(tkExchange, 302, new byte[0]);
                return;
            }
            byte[] tkBody = tkRoutes.get(tkPath);
            mtkSend(tkExchange, tkPath.contains("/org/gated") ? 401 : tkBody == null ? 404 : 200, tkBody == null ? new byte[0] : tkBody);
        });
        tkHub.start();
        byte[] tkShardOne = mtkSafetensors("{\"embed\":{\"dtype\":\"BF16\",\"shape\":[1024,1024],\"data_offsets\":[0,2097152]}}", 2097152);
        byte[] tkShardTwo = mtkSafetensors("{\"norm\":{\"dtype\":\"F32\",\"shape\":[256,256],\"data_offsets\":[0,262144]}}", 262144);
        tkRoutes.put("/api/models/org/dense/tree/main", mtkListing(Map.of("model-00001-of-00002.safetensors", (long) tkShardOne.length,
                "model-00002-of-00002.safetensors", (long) tkShardTwo.length, "config.json", 120L, "README.md", 10L)));
        tkRoutes.put("/org/dense/resolve/main/model-00001-of-00002.safetensors", tkShardOne);
        tkRoutes.put("/org/dense/resolve/main/model-00002-of-00002.safetensors", tkShardTwo);
        tkRoutes.put("/org/dense/resolve/main/config.json", mtkConfig(2, 4, 2, 64, 1000));
        tkRoutes.put("/api/models/org/quant/tree/main", mtkListing(Map.of("model.Q4_K_M.gguf", 1_000_000L, "model.Q8_0.gguf", 2_000_000L,
                "big-F16-00001-of-00002.gguf", 1_500_000L, "big-F16-00002-of-00002.gguf", 1_500_000L, "mmproj-model-f16.gguf", 900_000L)));
        tkRoutes.put("/org/quant/resolve/main/model.Q4_K_M.gguf", mtkGguf());
        tkRoutes.put("/api/models/org/redirected/tree/main", tkRoutes.get("/api/models/org/dense/tree/main"));
        tkRoutes.put("/org/redirected/resolve/main/model-00001-of-00002.safetensors", tkShardOne);
        tkRoutes.put("/org/redirected/resolve/main/model-00002-of-00002.safetensors", tkShardTwo);
    }

    @AfterEach
    void mtkStopHub() {
        tkHub.stop(0);
        tkCdn.stop(0);
    }

    private Map<String, TkTool> mtkTools(Optional<TkWillItFitServer.TkGpu> tkGpu) {
        TkHubClient tkClient = new TkHubClient(URI.create("http://127.0.0.1:" + tkHub.getAddress().getPort()), _TKTOKEN);
        return TkWillItFitServer.mtkTools(new TkFitPlanner(tkClient), () -> tkGpu).stream().collect(Collectors.toMap(TkTool::tkName, tkTool -> tkTool));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mtkComputesExactFitFromShardedSafetensorsHeaders() {
        Map<String, Object> tkResult = mtkOk(mtkTools(Optional.empty()), Map.of("model", "org/dense", "vram_gb", 0.003, "overhead_gb", 0.0));
        Map<String, Object> tkVariant = ((List<Map<String, Object>>) tkResult.get("variants")).get(0);
        assertEquals("safetensors BF16", tkVariant.get("variant"));
        assertEquals(Math.round((2097152 + 262144) / TkFitPlanner._TKGIB * 100) / 100.0, tkVariant.get("weights_gib"));
        assertEquals(true, tkVariant.get("fits"));
        assertEquals(1000L, tkVariant.get("max_context"));
        assertEquals(Map.of("layers", 2L, "kv_heads", 2L, "head_dim", 16L, "max_context", 1000L, "source", "config.json"), tkResult.get("architecture"));
        assertEquals("safetensors BF16", tkResult.get("best_fit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mtkReportsMaximumContextWhenFullContextDoesNotFit() {
        Map<String, Object> tkResult = mtkOk(mtkTools(Optional.empty()), Map.of("model", "org/dense", "vram_gb", 0.0024, "overhead_gb", 0.0));
        Map<String, Object> tkVariant = ((List<Map<String, Object>>) tkResult.get("variants")).get(0);
        assertEquals(false, tkVariant.get("fits"));
        assertEquals(850L, tkVariant.get("max_context"));
        assertNull(tkResult.get("best_fit"));
        assertTrue(((String) tkResult.get("verdict")).startsWith("Nothing fits"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mtkRanksGgufQuantizationsUsingGgufMetadataWhenConfigIsMissing() {
        Map<String, Object> tkResult = mtkOk(mtkTools(Optional.empty()), Map.of("model", "org/quant", "vram_gb", 0.0025, "context_length", 1000, "overhead_gb", 0.0));
        List<Map<String, Object>> tkVariants = (List<Map<String, Object>>) tkResult.get("variants");
        assertEquals(List.of("F16", "Q8_0", "Q4_K_M"), tkVariants.stream().map(tkRow -> tkRow.get("variant")).toList());
        assertEquals(List.of(false, true, true), tkVariants.stream().map(tkRow -> tkRow.get("fits")).toList());
        assertEquals("Q8_0", tkResult.get("best_fit"));
        assertEquals("gguf:model.Q4_K_M.gguf", ((Map<String, Object>) tkResult.get("architecture")).get("source"));
    }

    @Test
    void mtkScalesKvCacheWithBatchAndQuantizedCache() {
        Map<String, Object> tkFp16 = mtkOk(mtkTools(Optional.empty()), Map.of("model", "org/dense", "vram_gb", 1.0, "context_length", 1000, "batch_size", 4));
        Map<String, Object> tkQ8 = mtkOk(mtkTools(Optional.empty()), Map.of("model", "org/dense", "vram_gb", 1.0, "context_length", 1000, "batch_size", 4, "kv_cache_dtype", "q8_0"));
        assertEquals(Math.round(256.0 * 1000 * 4 / TkFitPlanner._TKGIB * 100) / 100.0, mtkFirst(tkFp16).get("kv_cache_gib"));
        assertTrue((double) mtkFirst(tkQ8).get("kv_cache_gib") <= (double) mtkFirst(tkFp16).get("kv_cache_gib"));
    }

    @Test
    void mtkNeverForwardsTheTokenAcrossHosts() {
        mtkOk(mtkTools(Optional.empty()), Map.of("model", "org/redirected", "vram_gb", 1.0));
        assertEquals("Bearer " + _TKTOKEN, tkAuthSeen.get("/api/models/org/redirected/tree/main"));
        assertEquals("null", tkAuthSeen.get("cdn"));
    }

    @Test
    void mtkUsesDetectedGpuWhenVramIsOmitted() {
        Map<String, TkTool> tkTools = mtkTools(Optional.of(new TkWillItFitServer.TkGpu("Test GPU", 24.0, "test")));
        assertEquals(24.0, mtkOk(tkTools, Map.of("model", "org/dense")).get("vram_gib"));
        assertEquals("Test GPU", ((Map<?, ?>) tkTools.get("detect_gpu").mtkCall(Map.of()).structuredContent()).get("name"));
        assertEquals("no GPU detected: pass vram_gb, for example 24 for an RTX 4090", mtkError(mtkTools(Optional.empty()), Map.of("model", "org/dense")));
    }

    @Test
    void mtkRejectsInvalidAndUnavailableModels() {
        Map<String, TkTool> tkTools = mtkTools(Optional.empty());
        String tkInvalid = "model must be a Hugging Face repo id such as Qwen/Qwen2.5-7B-Instruct";
        for (String tkModel : List.of("../etc", "org/model/../../x", "org", "a b/c", "org/..")) {
            assertEquals(tkInvalid, mtkError(tkTools, Map.of("model", tkModel, "vram_gb", 8)));
        }
        assertEquals("invalid revision: main/../x", mtkError(tkTools, Map.of("model", "org/dense", "vram_gb", 8, "revision", "main/../x")));
        assertEquals("model not found on the Hub: org/missing@main", mtkError(tkTools, Map.of("model", "org/missing", "vram_gb", 8)));
        assertTrue(mtkError(tkTools, Map.of("model", "org/gated", "vram_gb", 8)).endsWith("the model may be gated: set HF_TOKEN"));
        assertTrue(mtkError(tkTools, Map.of("model", "org/dense", "vram_gb", 8, "kv_cache_dtype", "fp4")).startsWith("kv_cache_dtype must be one of"));
    }

    @Test
    void mtkParsesNvidiaSmiOutput() {
        assertEquals(Optional.of(new TkWillItFitServer.TkGpu("NVIDIA GeForce RTX 4090", 23.99, "nvidia-smi")),
                TkWillItFitServer.mtkParseNvidia("NVIDIA GeForce RTX 4090, 24564\n"));
        assertEquals(Optional.of(new TkWillItFitServer.TkGpu("2 x NVIDIA H100 80GB HBM3", 159.29, "nvidia-smi")),
                TkWillItFitServer.mtkParseNvidia("NVIDIA H100 80GB HBM3, 81559\nNVIDIA H100 80GB HBM3, 81559\n"));
        assertEquals(Optional.empty(), TkWillItFitServer.mtkParseNvidia("No devices were found\n"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mtkFirst(Map<String, Object> tkResult) {
        return ((List<Map<String, Object>>) tkResult.get("variants")).get(0);
    }

    private static byte[] mtkConfig(int tkLayers, int tkHeads, int tkKvHeads, int tkHidden, int tkMaxPositions) {
        return ("{\"num_hidden_layers\":" + tkLayers + ",\"num_attention_heads\":" + tkHeads + ",\"num_key_value_heads\":" + tkKvHeads
                + ",\"hidden_size\":" + tkHidden + ",\"max_position_embeddings\":" + tkMaxPositions + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] mtkListing(Map<String, Long> tkFiles) {
        return tkFiles.entrySet().stream().map(tkEntry -> "{\"type\":\"file\",\"path\":\"" + tkEntry.getKey() + "\",\"size\":" + tkEntry.getValue() + "}")
                .collect(Collectors.joining(",", "[", "]")).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] mtkSafetensors(String tkHeader, int tkDataBytes) {
        byte[] tkJson = tkHeader.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + tkJson.length + tkDataBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(tkJson.length).put(tkJson).array();
    }

    private static byte[] mtkGguf() {
        ByteArrayOutputStream tkOut = new ByteArrayOutputStream();
        tkOut.writeBytes("GGUF".getBytes(StandardCharsets.US_ASCII));
        mtkInt(tkOut, 3);
        mtkLong(tkOut, 0);
        mtkLong(tkOut, 6);
        mtkStringEntry(tkOut, "general.architecture", "llama");
        mtkIntEntry(tkOut, "llama.block_count", 2);
        mtkIntEntry(tkOut, "llama.attention.head_count", 4);
        mtkIntEntry(tkOut, "llama.attention.head_count_kv", 2);
        mtkIntEntry(tkOut, "llama.embedding_length", 64);
        mtkIntEntry(tkOut, "llama.context_length", 2048);
        return tkOut.toByteArray();
    }

    private static void mtkStringEntry(ByteArrayOutputStream tkOut, String tkKey, String tkValue) {
        mtkString(tkOut, tkKey);
        mtkInt(tkOut, 8);
        mtkString(tkOut, tkValue);
    }

    private static void mtkIntEntry(ByteArrayOutputStream tkOut, String tkKey, int tkValue) {
        mtkString(tkOut, tkKey);
        mtkInt(tkOut, 4);
        mtkInt(tkOut, tkValue);
    }

    private static void mtkString(ByteArrayOutputStream tkOut, String tkValue) {
        byte[] tkBytes = tkValue.getBytes(StandardCharsets.UTF_8);
        mtkLong(tkOut, tkBytes.length);
        tkOut.writeBytes(tkBytes);
    }

    private static void mtkInt(ByteArrayOutputStream tkOut, int tkValue) {
        tkOut.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(tkValue).array());
    }

    private static void mtkLong(ByteArrayOutputStream tkOut, long tkValue) {
        tkOut.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(tkValue).array());
    }

    private static void mtkSend(com.sun.net.httpserver.HttpExchange tkExchange, int tkStatus, byte[] tkBody) throws java.io.IOException {
        tkExchange.sendResponseHeaders(tkStatus, tkBody.length == 0 ? -1 : tkBody.length);
        tkExchange.getResponseBody().write(tkBody);
        tkExchange.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mtkOk(Map<String, TkTool> tkTools, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get("will_it_fit").mtkCall(tkArgs);
        assertEquals(false, tkResult.isError(), () -> ((TextContent) tkResult.content().get(0)).text());
        return (Map<String, Object>) tkResult.structuredContent();
    }

    private String mtkError(Map<String, TkTool> tkTools, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get("will_it_fit").mtkCall(tkArgs);
        assertEquals(true, tkResult.isError());
        return ((TextContent) tkResult.content().get(0)).text();
    }
}
