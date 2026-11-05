package io.github.varun51.mcp.quantization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.varun51.mcp.core.TkTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TkQuantizationServerTest {

    private static Map<String, Object> tkReference;
    private final TkTool tkAnalyze = TkQuantizationServer.mtkTools().get(0);

    @BeforeAll
    static void mtkLoadReference() throws Exception {
        try (InputStream tkIn = TkQuantizationServerTest.class.getResourceAsStream("/quantization-reference.json")) {
            tkReference = McpJsonDefaults.getMapper().readValue(tkIn.readAllBytes(), new TypeRef<>() {
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mtkRoundTripsMatchMlDtypesBitForBit() {
        List<Number> tkInputs = (List<Number>) tkReference.get("inputs");
        assertTrue(tkInputs.size() > 400);
        for (String tkFormat : List.of("fp16", "bf16", "fp8_e4m3", "fp8_e5m2")) {
            List<Number> tkExpected = (List<Number>) tkReference.get(tkFormat);
            for (int tkIndex = 0; tkIndex < tkInputs.size(); tkIndex++) {
                float tkInput = Float.intBitsToFloat((int) tkInputs.get(tkIndex).longValue());
                int tkActual = Float.floatToIntBits(TkFormats.mtkRoundTrip(tkFormat, tkInput));
                assertEquals((int) tkExpected.get(tkIndex).longValue(), tkActual, tkFormat + " of " + tkInput);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mtkAnalysisMatchesNumpyReference() {
        List<Object> tkTensor = (List<Object>) tkReference.get("tensor");
        for (Map<String, Object> tkCase : (List<Map<String, Object>>) tkReference.get("analyses")) {
            Map<String, Object> tkArgs = new HashMap<>(Map.of("values", tkTensor, "formats", List.of(tkCase.get("format"))));
            if (tkCase.containsKey("block_size")) {
                tkArgs.put("power_of_two_scales", tkCase.get("power_of_two_scales"));
                if (((Number) tkCase.get("block_size")).intValue() < tkTensor.size()) {
                    tkArgs.put("block_size", tkCase.get("block_size"));
                }
            }
            Map<String, Object> tkActual = mtkFormats(mtkOk(tkArgs)).get(0);
            for (String tkMetric : List.of("max_abs_error", "mse", "snr_db")) {
                double tkExpected = ((Number) tkCase.get(tkMetric)).doubleValue();
                assertEquals(tkExpected, (double) tkActual.get(tkMetric), Math.abs(tkExpected) * 1e-9, tkCase + " " + tkMetric);
            }
            assertEquals(tkCase.get("overflow_count"), tkActual.get("overflow_count"), tkCase.toString());
            assertEquals(tkCase.get("underflow_to_zero_count"), tkActual.get("underflow_to_zero_count"), tkCase.toString());
        }
    }

    @Test
    void mtkAnalyzesAllFormatsByDefault() {
        Map<String, Object> tkResult = mtkOk(Map.of("values", List.of(1, 2, 3, -4)));
        assertEquals(4, tkResult.get("count"));
        assertNull(tkResult.get("block_size"));
        List<Map<String, Object>> tkFormats = mtkFormats(tkResult);
        assertEquals(List.of("fp16", "bf16", "fp8_e4m3", "fp8_e5m2", "int8"), tkFormats.stream().map(tkFormat -> tkFormat.get("format")).toList());
        Map<String, Object> tkFp16 = tkFormats.get(0);
        assertEquals(0.0, tkFp16.get("max_abs_error"));
        assertEquals(0.0, tkFp16.get("mse"));
        assertNull(tkFp16.get("snr_db"));
        assertNull(tkFp16.get("scales"));
        assertEquals(65504.0, tkFp16.get("max_finite"));
        assertEquals(Map.of("granularity", "per_tensor", "count", 1, "min", 4.0 / 127, "max", 4.0 / 127, "power_of_two", false), tkFormats.get(4).get("scales"));
        assertEquals((double) Float.intBitsToFloat(0x7F7F0000), tkFormats.get(1).get("max_finite"));
    }

    @Test
    void mtkReportsOverflowOnlyForUnscaledFormats() {
        List<Map<String, Object>> tkFormats = mtkFormats(mtkOk(Map.of("values", List.of(70000, 1), "formats", List.of("fp16", "bf16", "fp8_e4m3"))));
        assertEquals(1, tkFormats.get(0).get("overflow_count"));
        assertNull(tkFormats.get(0).get("max_abs_error"));
        assertNull(tkFormats.get(0).get("mse"));
        assertNull(tkFormats.get(0).get("snr_db"));
        assertEquals(0, tkFormats.get(1).get("overflow_count"));
        assertEquals(0, tkFormats.get(2).get("overflow_count"));
        assertTrue((double) tkFormats.get(2).get("max_abs_error") > 0);
    }

    @Test
    void mtkCountsUnderflowToZero() {
        List<Map<String, Object>> tkFormats = mtkFormats(mtkOk(Map.of("values", List.of(1e-8, -1e-8, 1), "formats", List.of("fp16", "bf16", "int8"))));
        assertEquals(2, tkFormats.get(0).get("underflow_to_zero_count"));
        assertEquals(0, tkFormats.get(1).get("underflow_to_zero_count"));
        assertEquals(2, tkFormats.get(2).get("underflow_to_zero_count"));
    }

    @Test
    void mtkScalesPerBlockAndRoundsPowerOfTwoScalesUp() {
        Map<String, Object> tkBlocks = mtkFormats(mtkOk(Map.of("values", List.of(1, 2, 100, 200, 5), "formats", List.of("int8"), "block_size", 2))).get(0);
        assertEquals(Map.of("granularity", "per_block", "count", 3, "min", 2.0 / 127, "max", 200.0 / 127, "power_of_two", false), tkBlocks.get("scales"));
        Map<String, Object> tkExact = mtkFormats(mtkOk(Map.of("values", List.of(448, 1), "formats", List.of("fp8_e4m3"), "power_of_two_scales", true))).get(0);
        assertEquals(1.0, ((Map<?, ?>) tkExact.get("scales")).get("max"));
        Map<String, Object> tkRounded = mtkFormats(mtkOk(Map.of("values", List.of(449, -449), "formats", List.of("fp8_e4m3"), "power_of_two_scales", true))).get(0);
        assertEquals(2.0, ((Map<?, ?>) tkRounded.get("scales")).get("max"));
        assertEquals(0, tkRounded.get("overflow_count"));
        assertEquals(1.0, tkRounded.get("max_abs_error"));
    }

    @Test
    void mtkHandlesAllZeroTensorsAndDuplicateFormats() {
        Map<String, Object> tkResult = mtkOk(Map.of("values", List.of(0, 0), "formats", List.of("int8", "int8")));
        List<Map<String, Object>> tkFormats = mtkFormats(tkResult);
        assertEquals(1, tkFormats.size());
        assertEquals(0.0, tkFormats.get(0).get("mse"));
        assertNull(tkFormats.get(0).get("snr_db"));
        assertEquals(1.0, ((Map<?, ?>) tkFormats.get(0).get("scales")).get("min"));
    }

    @Test
    void mtkSaturatesAndPropagatesOverflowInRawCasts() {
        assertEquals(448f, TkFormats.mtkRoundTrip("fp8_e4m3", 464f));
        assertTrue(Float.isNaN(TkFormats.mtkRoundTrip("fp8_e4m3", 465f)));
        assertEquals(Float.NEGATIVE_INFINITY, TkFormats.mtkRoundTrip("fp8_e5m2", -61440f));
        assertEquals(Float.POSITIVE_INFINITY, TkFormats.mtkRoundTrip("bf16", Float.MAX_VALUE));
        assertEquals(Float.floatToIntBits(-0f), Float.floatToIntBits(TkFormats.mtkRoundTrip("fp8_e4m3", -1e-4f)));
    }

    @Test
    void mtkRejectsInvalidInputs() {
        assertEquals("values must not be empty", mtkError(Map.of("values", List.of())));
        assertEquals("missing argument: values", mtkError(Map.of()));
        assertEquals("values must be number", mtkError(Map.of("values", List.of("1"))));
        assertEquals("values must be finite and within float32 range", mtkError(Map.of("values", List.of(1, 1e39))));
        assertEquals("formats must not be empty", mtkError(Map.of("values", List.of(1), "formats", List.of())));
        assertEquals("unknown format: fp4, expected one of [fp16, bf16, fp8_e4m3, fp8_e5m2, int8]", mtkError(Map.of("values", List.of(1), "formats", List.of("fp4"))));
        assertEquals("block_size must be between 1 and 1000000", mtkError(Map.of("values", List.of(1), "block_size", 0)));
        assertEquals("block_size must be an integer", mtkError(Map.of("values", List.of(1), "block_size", 1.5)));
        assertEquals("power_of_two_scales must be boolean", mtkError(Map.of("values", List.of(1), "power_of_two_scales", "yes")));
    }

    @Test
    void mtkCapsTensorSize() {
        assertEquals("too many values, limit is " + TkQuantizationServer._TKMAX_VALUES,
                mtkError(Map.of("values", Collections.nCopies(TkQuantizationServer._TKMAX_VALUES + 1, 1))));
        assertEquals("block_size must be between 1 and 1000000", mtkError(Map.of("values", List.of(1), "block_size", 1_000_001)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mtkFormats(Map<String, Object> tkResult) {
        return (List<Map<String, Object>>) tkResult.get("formats");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mtkOk(Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkAnalyze.mtkCall(tkArgs);
        assertEquals(false, tkResult.isError(), () -> ((TextContent) tkResult.content().get(0)).text());
        return (Map<String, Object>) tkResult.structuredContent();
    }

    private String mtkError(Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkAnalyze.mtkCall(tkArgs);
        assertEquals(true, tkResult.isError());
        return ((TextContent) tkResult.content().get(0)).text();
    }
}
