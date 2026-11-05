package io.github.varun51.mcp.inspector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.varun51.mcp.core.TkTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TkInspectorServerTest {

    @TempDir
    Path tkRoot;

    @TempDir
    Path tkOutside;

    private Map<String, TkTool> mtkTools() {
        return TkInspectorServer.mtkTools(List.of(tkRoot)).stream().collect(Collectors.toMap(TkTool::tkName, tkTool -> tkTool));
    }

    @Test
    void mtkInspectsSafetensorsHeaders() throws Exception {
        String tkHeader = "{\"__metadata__\":{\"format\":\"pt\"},\"embed.weight\":{\"dtype\":\"BF16\",\"shape\":[10,4],\"data_offsets\":[0,80]},"
                + "\"norm.weight\":{\"dtype\":\"F32\",\"shape\":[4],\"data_offsets\":[80,96]}}";
        Path tkFile = mtkSafetensors(tkHeader, 96);
        Map<String, Object> tkResult = mtkOk("inspect_model_file", Map.of("path", tkFile.toString()));
        assertEquals("safetensors", tkResult.get("format"));
        assertEquals(2, tkResult.get("tensors"));
        assertEquals(44L, ((Number) tkResult.get("parameters")).longValue());
        assertEquals(Map.of("BF16", 40L, "F32", 4L), mtkLongs((Map<?, ?>) tkResult.get("parameters_by_dtype")));
        assertEquals(Map.of("format", "pt"), tkResult.get("metadata"));
    }

    @Test
    void mtkInspectsGgufHeaders() throws Exception {
        ByteArrayOutputStream tkOut = new ByteArrayOutputStream();
        tkOut.writeBytes("GGUF".getBytes(StandardCharsets.US_ASCII));
        mtkInt(tkOut, 3);
        mtkLong(tkOut, 2);
        mtkLong(tkOut, 2);
        mtkString(tkOut, "general.architecture");
        mtkInt(tkOut, 8);
        mtkString(tkOut, "llama");
        mtkString(tkOut, "llama.context_length");
        mtkInt(tkOut, 4);
        mtkInt(tkOut, 4096);
        mtkTensor(tkOut, "token_embd.weight", new long[] {4096, 32000}, 12);
        mtkTensor(tkOut, "output_norm.weight", new long[] {4096}, 0);
        Path tkFile = Files.write(tkRoot.resolve("model.gguf"), tkOut.toByteArray());
        Map<String, Object> tkResult = mtkOk("inspect_model_file", Map.of("path", tkFile.toString()));
        assertEquals("gguf", tkResult.get("format"));
        assertEquals("llama", tkResult.get("architecture"));
        assertEquals(4096L * 32000 + 4096, ((Number) tkResult.get("parameters")).longValue());
        assertEquals(Map.of("Q4_K", 4096L * 32000, "F32", 4096L), mtkLongs((Map<?, ?>) tkResult.get("parameters_by_type")));
        assertEquals(4096L, ((Number) ((Map<?, ?>) tkResult.get("metadata")).get("llama.context_length")).longValue());
    }

    @Test
    void mtkRejectsMalformedFiles() throws Exception {
        assertEquals("tensor x has inconsistent data offsets",
                mtkError("inspect_model_file", Map.of("path", mtkSafetensors("{\"x\":{\"dtype\":\"F32\",\"shape\":[4],\"data_offsets\":[0,8]}}", 16).toString())));
        assertEquals("safetensors header is not valid JSON", mtkError("inspect_model_file", Map.of("path", mtkSafetensors("{broken", 0).toString())));
        assertEquals("model header is malformed", mtkError("inspect_model_file", Map.of("path", mtkSafetensors("{\"x\":[1,2]}", 0).toString())));
        Path tkHuge = Files.write(tkRoot.resolve("huge.safetensors"), ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.MAX_VALUE).array());
        assertEquals("file is neither safetensors nor GGUF", mtkError("inspect_model_file", Map.of("path", tkHuge.toString())));
        Path tkTruncated = Files.write(tkRoot.resolve("cut.gguf"), "GGUF\u0003\u0000\u0000\u0000".getBytes(StandardCharsets.US_ASCII));
        assertEquals("GGUF file is truncated", mtkError("inspect_model_file", Map.of("path", tkTruncated.toString())));
    }

    @Test
    void mtkConfinesReadsToAllowedRoots() throws Exception {
        Path tkSecret = Files.writeString(tkOutside.resolve("secret.safetensors"), "x");
        assertEquals("path is outside the allowed model roots", mtkError("inspect_model_file", Map.of("path", tkSecret.toString())));
        Path tkLink = Files.createSymbolicLink(tkRoot.resolve("link.safetensors"), tkSecret);
        assertEquals("path is outside the allowed model roots", mtkError("inspect_model_file", Map.of("path", tkLink.toString())));
        assertEquals("path is outside the allowed model roots", mtkError("inspect_model_file", Map.of("path", tkRoot.resolve("..").resolve(tkOutside.getFileName()).resolve("secret.safetensors").toString())));
    }

    @Test
    void mtkEstimatesInferenceMemoryForLlama3Style8B() {
        Map<String, Object> tkResult = mtkOk("estimate_inference_memory", Map.of("parameters_billions", 8.0, "weight_dtype", "bf16",
                "layers", 32, "kv_heads", 8, "head_dim", 128, "context_length", 8192, "batch_size", 1));
        assertEquals(16e9 / (1L << 30), (double) tkResult.get("weights_gib"), 1e-9);
        assertEquals(1.0, (double) tkResult.get("kv_cache_gib"), 1e-9);
    }

    @Test
    void mtkEstimatesTrainingMemoryWithZeroStages() {
        double tkUnpartitioned = (double) mtkOk("estimate_training_memory", Map.of("parameters_billions", 7.5, "optimizer", "adamw", "zero_stage", 0)).get("per_gpu_total_gib");
        assertEquals(16 * 7.5e9 / (1L << 30), tkUnpartitioned, 1e-9);
        double tkStage3 = (double) mtkOk("estimate_training_memory", Map.of("parameters_billions", 7.5, "optimizer", "adamw", "zero_stage", 3, "gpus", 64)).get("per_gpu_total_gib");
        assertEquals(tkUnpartitioned / 64, tkStage3, 1e-9);
        double tkStage1 = (double) mtkOk("estimate_training_memory", Map.of("parameters_billions", 7.5, "optimizer", "adamw", "zero_stage", 1, "gpus", 64)).get("per_gpu_total_gib");
        assertEquals((4 + 12.0 / 64) * 7.5e9 / (1L << 30), tkStage1, 1e-9);
    }

    @Test
    void mtkRejectsInvalidEstimates() {
        assertEquals("zero_stage must be between 0 and 3", mtkError("estimate_training_memory", Map.of("parameters_billions", 1.0, "optimizer", "adamw", "zero_stage", 4)));
        assertEquals("layers must be positive", mtkError("estimate_inference_memory", Map.of("parameters_billions", 1.0, "weight_dtype", "fp16",
                "layers", 0, "kv_heads", 8, "head_dim", 128, "context_length", 10)));
        assertTrue(mtkError("estimate_inference_memory", Map.of("parameters_billions", 1.0, "weight_dtype", "fp7",
                "layers", 1, "kv_heads", 8, "head_dim", 128, "context_length", 10)).startsWith("dtype must be one of"));
    }

    private Path mtkSafetensors(String tkHeader, int tkDataBytes) throws Exception {
        byte[] tkJson = tkHeader.getBytes(StandardCharsets.UTF_8);
        ByteBuffer tkBuffer = ByteBuffer.allocate(8 + tkJson.length + tkDataBytes).order(ByteOrder.LITTLE_ENDIAN);
        tkBuffer.putLong(tkJson.length).put(tkJson);
        return Files.write(Files.createTempFile(tkRoot, "model", ".safetensors"), tkBuffer.array());
    }

    private static Map<String, Long> mtkLongs(Map<?, ?> tkValues) {
        return tkValues.entrySet().stream().collect(Collectors.toMap(tkEntry -> (String) tkEntry.getKey(), tkEntry -> ((Number) tkEntry.getValue()).longValue()));
    }

    private static void mtkTensor(ByteArrayOutputStream tkOut, String tkName, long[] tkShape, int tkType) {
        mtkString(tkOut, tkName);
        mtkInt(tkOut, tkShape.length);
        for (long tkDimension : tkShape) {
            mtkLong(tkOut, tkDimension);
        }
        mtkInt(tkOut, tkType);
        mtkLong(tkOut, 0);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mtkOk(String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = mtkTools().get(tkName).mtkCall(tkArgs);
        assertEquals(false, tkResult.isError(), () -> ((TextContent) tkResult.content().get(0)).text());
        return (Map<String, Object>) tkResult.structuredContent();
    }

    private String mtkError(String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = mtkTools().get(tkName).mtkCall(tkArgs);
        assertEquals(true, tkResult.isError());
        return ((TextContent) tkResult.content().get(0)).text();
    }
}
