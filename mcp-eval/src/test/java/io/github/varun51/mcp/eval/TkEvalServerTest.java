package io.github.varun51.mcp.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.varun51.mcp.core.TkTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TkEvalServerTest {

    private static final double _TKTOLERANCE = 1e-12;
    private static Map<String, Object> tkReference;
    private final Map<String, TkTool> tkTools = TkEvalServer.mtkTools().stream().collect(Collectors.toMap(TkTool::tkName, tkTool -> tkTool));

    @BeforeAll
    static void mtkLoadReference() throws Exception {
        try (InputStream tkIn = TkEvalServerTest.class.getResourceAsStream("/eval-reference.json")) {
            tkReference = McpJsonDefaults.getMapper().readValue(tkIn.readAllBytes(), new TypeRef<>() {
            });
        }
    }

    @Test
    void mtkMatchesSquadAndRougeReferenceImplementations() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tkPairs = (List<Map<String, Object>>) tkReference.get("pairs");
        for (Map<String, Object> tkPair : tkPairs) {
            String tkPrediction = (String) tkPair.get("prediction");
            String tkGold = (String) tkPair.get("reference");
            String tkCase = tkPrediction + " | " + tkGold;
            assertEquals(((Number) tkPair.get("exact_match")).doubleValue(), TkMetrics.mtkExactMatch(tkPrediction, tkGold), _TKTOLERANCE, tkCase);
            assertEquals(((Number) tkPair.get("token_f1")).doubleValue(), TkMetrics.mtkTokenF1(tkPrediction, tkGold), _TKTOLERANCE, tkCase);
            assertEquals(((Number) tkPair.get("rouge_l")).doubleValue(), TkMetrics.mtkRougeL(tkPrediction, tkGold), _TKTOLERANCE, tkCase);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mtkMatchesJiwerCorpusAndPerItemErrorRates() {
        Map<String, Object> tkAsr = (Map<String, Object>) tkReference.get("wer");
        for (String tkMetric : List.of("wer", "cer")) {
            Map<String, Object> tkResult = mtkOk("score", Map.of("metric", tkMetric, "predictions", tkAsr.get("hypotheses"), "references", tkAsr.get("references")));
            assertEquals(((Number) tkAsr.get(tkMetric)).doubleValue(), (double) tkResult.get("score"), _TKTOLERANCE);
            List<Double> tkExpected = ((List<Number>) tkAsr.get("per_item_" + tkMetric)).stream().map(Number::doubleValue).toList();
            List<Double> tkActual = (List<Double>) tkResult.get("per_item");
            for (int tkIndex = 0; tkIndex < tkExpected.size(); tkIndex++) {
                assertEquals(tkExpected.get(tkIndex), tkActual.get(tkIndex), _TKTOLERANCE);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mtkMatchesCodexPassAtKEstimator() {
        for (Map<String, Object> tkCase : (List<Map<String, Object>>) tkReference.get("pass_at_k")) {
            double tkActual = TkMetrics.mtkPassAtK(((Number) tkCase.get("n")).intValue(), ((Number) tkCase.get("c")).intValue(), ((Number) tkCase.get("k")).intValue());
            assertEquals(((Number) tkCase.get("value")).doubleValue(), tkActual, 1e-12, tkCase.toString());
        }
    }

    @Test
    void mtkScoresMultipleReferencesByBestMatch() {
        Map<String, Object> tkResult = mtkOk("score", Map.of("metric", "exact_match", "predictions", List.of("Paris", "Rome"),
                "references", List.of(List.of("London", "paris"), "Madrid")));
        assertEquals(0.5, tkResult.get("score"));
        assertEquals(List.of(1.0, 0.0), tkResult.get("per_item"));
    }

    @Test
    void mtkAveragesPassAtKOverProblems() {
        Map<String, Object> tkResult = mtkOk("pass_at_k", Map.of("k", 1, "results", List.of(Map.of("n", 10, "c", 0), Map.of("n", 10, "c", 10))));
        assertEquals(0.5, tkResult.get("pass_at_k"));
    }

    @Test
    void mtkBootstrapIsDeterministicAndHonoursIterations() {
        Map<String, Object> tkArgs = Map.of("scores", List.of(1, 0, 1, 1, 0, 1, 0, 1, 1, 1), "iterations", 1500, "seed", 7);
        Map<String, Object> tkFirst = mtkOk("bootstrap_ci", tkArgs);
        assertEquals(tkFirst, mtkOk("bootstrap_ci", tkArgs));
        assertEquals(1500, tkFirst.get("iterations"));
        assertEquals(0.7, (double) tkFirst.get("mean"), 1e-12);
        assertTrue((double) tkFirst.get("lower") <= 0.7 && 0.7 <= (double) tkFirst.get("upper"));
    }

    @Test
    void mtkBootstrapOfConstantScoresHasZeroWidth() {
        Map<String, Object> tkResult = mtkOk("bootstrap_ci", Map.of("scores", List.of(0.5, 0.5, 0.5)));
        assertEquals(0.5, (double) tkResult.get("lower"), 1e-12);
        assertEquals(0.5, (double) tkResult.get("upper"), 1e-12);
        assertEquals(0.0, (double) tkResult.get("standard_error"), 1e-12);
    }

    @Test
    void mtkRejectsInvalidInputs() {
        assertEquals("predictions and references must have the same length",
                mtkError("score", Map.of("metric", "token_f1", "predictions", List.of("a"), "references", List.of())));
        assertEquals("predictions must not be empty", mtkError("score", Map.of("metric", "token_f1", "predictions", List.of(), "references", List.of())));
        assertEquals("wer takes exactly one reference per prediction",
                mtkError("score", Map.of("metric", "wer", "predictions", List.of("a"), "references", List.of(List.of("a", "b")))));
        assertEquals("wer reference 0 is empty", mtkError("score", Map.of("metric", "wer", "predictions", List.of("a"), "references", List.of(" "))));
        assertEquals("each reference must be a string or a non-empty list of strings",
                mtkError("score", Map.of("metric", "exact_match", "predictions", List.of("a"), "references", List.of(List.of()))));
        assertEquals("pass@k requires n >= 1, 0 <= c <= n and 1 <= k <= n",
                mtkError("pass_at_k", Map.of("k", 5, "results", List.of(Map.of("n", 3, "c", 1)))));
        assertEquals("confidence must be between 0 and 1", mtkError("bootstrap_ci", Map.of("scores", List.of(1), "confidence", 1.5)));
    }

    @Test
    void mtkRejectsWorkloadsBeyondTheComputeBudget() {
        String tkLong = "word ".repeat(20_000);
        assertTrue(mtkError("score", Map.of("metric", "rouge_l", "predictions", List.of(tkLong), "references", List.of(tkLong))).startsWith("input too large"));
        assertTrue(mtkError("bootstrap_ci", Map.of("scores", Collections.nCopies(100_000, 1), "iterations", 100_000)).startsWith("scores times iterations"));
        assertTrue(mtkError("score", Map.of("metric", "exact_match", "predictions", Collections.nCopies(TkEvalServer._TKMAX_ITEMS + 1, "a"),
                "references", Collections.nCopies(TkEvalServer._TKMAX_ITEMS + 1, "a"))).startsWith("too many items"));
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
        return ((TextContent) tkResult.content().get(0)).text();
    }
}
