package io.github.varun51.mcp.eval;

import static io.github.varun51.mcp.core.TkSchema.mtkArray;
import static io.github.varun51.mcp.core.TkSchema.mtkEnum;
import static io.github.varun51.mcp.core.TkSchema.mtkField;
import static io.github.varun51.mcp.core.TkSchema.mtkObject;
import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkArgs;
import io.github.varun51.mcp.core.TkServerLauncher;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.core.TkToolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class TkEvalServer {

    static final int _TKMAX_ITEMS = 100_000;
    static final long _TKMAX_WORK = 200_000_000L;

    private TkEvalServer() {
    }

    public static void main(String[] tkArgs) {
        TkServerLauncher.mtkStartStdio("mcp-eval", "0.1.0", mtkTools());
    }

    static List<TkTool> mtkTools() {
        Map<String, Object> tkReferences = Map.of("type", "array", "description", "One reference per prediction; a list of acceptable references scores the best match",
                "items", Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "array", "items", Map.of("type", "string")))));
        Map<String, Object> tkResults = Map.of("type", "array", "description", "Per problem: n samples generated, c of them correct",
                "items", mtkObject(Map.of("n", mtkField("integer", "Samples"), "c", mtkField("integer", "Correct samples")), "n", "c"));
        return List.of(
                new TkTool("score", "Score predictions against references with exact_match, token_f1, rouge_l, wer or cer",
                        mtkObject(Map.of("metric", mtkEnum("Metric", "exact_match", "token_f1", "rouge_l", "wer", "cer"),
                                "predictions", mtkArray("string", "Model outputs"), "references", tkReferences), "metric", "predictions", "references"),
                        TkEvalServer::mtkScore),
                new TkTool("pass_at_k", "Unbiased pass@k estimator for code generation (Chen et al., 2021), averaged over problems",
                        mtkObject(Map.of("results", tkResults, "k", mtkField("integer", "k")), "results", "k"),
                        TkEvalServer::mtkPassAtK),
                new TkTool("bootstrap_ci", "Percentile bootstrap confidence interval of the mean of per-item scores",
                        mtkObject(Map.of("scores", mtkArray("number", "Per-item scores"), "confidence", mtkField("number", "Confidence level, default 0.95"),
                                "iterations", mtkField("integer", "Bootstrap resamples, default 1000"), "seed", mtkField("integer", "Random seed, default 0")), "scores"),
                        TkEvalServer::mtkBootstrap));
    }

    private static Object mtkScore(TkArgs tkCall) {
        String tkMetric = tkCall.mtkString("metric");
        List<String> tkPredictions = tkCall.mtkStringList("predictions");
        List<List<String>> tkReferences = tkCall.mtkList("references").stream().map(TkEvalServer::mtkReferenceSet).toList();
        TkToolException.mtkCheck(!tkPredictions.isEmpty(), "predictions must not be empty");
        TkToolException.mtkCheck(tkPredictions.size() <= _TKMAX_ITEMS, "too many items, limit is " + _TKMAX_ITEMS);
        TkToolException.mtkCheck(tkPredictions.size() == tkReferences.size(), "predictions and references must have the same length");
        return switch (tkMetric) {
            case "exact_match" -> mtkAverage(tkMetric, tkPredictions, tkReferences, TkMetrics::mtkExactMatch);
            case "token_f1" -> mtkAverage(tkMetric, tkPredictions, tkReferences, TkMetrics::mtkTokenF1);
            case "rouge_l" -> {
                mtkChargeWork(tkPredictions, tkReferences, TkMetrics::mtkRougeTokens);
                yield mtkAverage(tkMetric, tkPredictions, tkReferences, TkMetrics::mtkRougeL);
            }
            case "wer" -> mtkErrorRate(tkMetric, tkPredictions, tkReferences, TkMetrics::mtkWords);
            case "cer" -> mtkErrorRate(tkMetric, tkPredictions, tkReferences, TkMetrics::mtkCharacters);
            default -> throw new TkToolException("unknown metric: " + tkMetric);
        };
    }

    private static Map<String, Object> mtkAverage(String tkMetric, List<String> tkPredictions, List<List<String>> tkReferences, BiFunction<String, String, Double> tkScorer) {
        List<Double> tkPerItem = new ArrayList<>();
        for (int tkIndex = 0; tkIndex < tkPredictions.size(); tkIndex++) {
            String tkPrediction = tkPredictions.get(tkIndex);
            tkPerItem.add(tkReferences.get(tkIndex).stream().mapToDouble(tkReference -> tkScorer.apply(tkPrediction, tkReference)).max().orElseThrow());
        }
        return mtkResult("metric", tkMetric, "score", tkPerItem.stream().mapToDouble(Double::doubleValue).average().orElseThrow(), "count", tkPerItem.size(), "per_item", tkPerItem);
    }

    private static <T> Map<String, Object> mtkErrorRate(String tkMetric, List<String> tkHypotheses, List<List<String>> tkReferences, Function<String, List<T>> tkUnits) {
        TkToolException.mtkCheck(tkReferences.stream().allMatch(tkSet -> tkSet.size() == 1), tkMetric + " takes exactly one reference per prediction");
        mtkChargeWork(tkHypotheses, tkReferences, tkUnits);
        long tkErrors = 0;
        long tkReferenceUnits = 0;
        List<Double> tkPerItem = new ArrayList<>();
        for (int tkIndex = 0; tkIndex < tkHypotheses.size(); tkIndex++) {
            List<T> tkReference = tkUnits.apply(tkReferences.get(tkIndex).get(0));
            TkToolException.mtkCheck(!tkReference.isEmpty(), tkMetric + " reference " + tkIndex + " is empty");
            int tkDistance = TkMetrics.mtkEditDistance(tkUnits.apply(tkHypotheses.get(tkIndex)), tkReference);
            tkErrors += tkDistance;
            tkReferenceUnits += tkReference.size();
            tkPerItem.add((double) tkDistance / tkReference.size());
        }
        return mtkResult("metric", tkMetric, "score", (double) tkErrors / tkReferenceUnits, "count", tkPerItem.size(), "per_item", tkPerItem);
    }

    private static <T> void mtkChargeWork(List<String> tkPredictions, List<List<String>> tkReferences, Function<String, List<T>> tkUnits) {
        long tkWork = 0;
        for (int tkIndex = 0; tkIndex < tkPredictions.size(); tkIndex++) {
            long tkPredicted = tkUnits.apply(tkPredictions.get(tkIndex)).size();
            for (String tkReference : tkReferences.get(tkIndex)) {
                tkWork += tkPredicted * tkUnits.apply(tkReference).size();
            }
        }
        TkToolException.mtkCheck(tkWork <= _TKMAX_WORK, "input too large: " + tkWork + " alignment cells exceeds the limit of " + _TKMAX_WORK);
    }

    private static List<String> mtkReferenceSet(Object tkReference) {
        if (tkReference instanceof String tkSingle) {
            return List.of(tkSingle);
        }
        TkToolException.mtkCheck(tkReference instanceof List<?> tkList && !tkList.isEmpty() && tkList.stream().allMatch(String.class::isInstance),
                "each reference must be a string or a non-empty list of strings");
        return ((List<?>) tkReference).stream().map(String.class::cast).toList();
    }

    private static Object mtkPassAtK(TkArgs tkCall) {
        int tkK = tkCall.mtkInt("k");
        List<Object> tkResults = tkCall.mtkList("results");
        TkToolException.mtkCheck(!tkResults.isEmpty() && tkResults.size() <= _TKMAX_ITEMS, "results must contain 1 to " + _TKMAX_ITEMS + " problems");
        List<Double> tkPerProblem = tkResults.stream().map(tkResult -> {
            TkToolException.mtkCheck(tkResult instanceof Map<?, ?>, "each result must be an object with n and c");
            @SuppressWarnings("unchecked")
            TkArgs tkProblem = new TkArgs((Map<String, Object>) tkResult);
            return TkMetrics.mtkPassAtK(tkProblem.mtkInt("n"), tkProblem.mtkInt("c"), tkK);
        }).toList();
        return mtkResult("k", tkK, "pass_at_k", tkPerProblem.stream().mapToDouble(Double::doubleValue).average().orElseThrow(), "per_problem", tkPerProblem);
    }

    private static Object mtkBootstrap(TkArgs tkCall) {
        double[] tkScores = tkCall.mtkDoubleArray("scores");
        int tkIterations = tkCall.mtkInt("iterations", 1000);
        TkToolException.mtkCheck(tkScores.length <= _TKMAX_ITEMS, "too many scores, limit is " + _TKMAX_ITEMS);
        TkToolException.mtkCheck((long) tkScores.length * tkIterations <= _TKMAX_WORK, "scores times iterations exceeds the limit of " + _TKMAX_WORK);
        double tkConfidence = tkCall.mtkDouble("confidence", 0.95);
        TkMetrics.TkInterval tkInterval = TkMetrics.mtkBootstrap(tkScores, tkConfidence, tkIterations, tkCall.mtkInt("seed", 0));
        return mtkResult("mean", tkInterval.tkMean(), "lower", tkInterval.tkLower(), "upper", tkInterval.tkUpper(),
                "standard_error", tkInterval.tkStandardError(), "confidence", tkConfidence, "iterations", tkIterations);
    }
}
