package io.github.varun51.mcp.vector;

import io.github.varun51.mcp.core.TkToolException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

public final class TkCollection {

    static final Set<String> _TKMETRICS = Set.of("cosine", "dot", "l2");
    static final long _TKMAX_ELEMENTS = 50_000_000L;

    private final int tkDimension;
    private final String tkMetric;
    private final Map<String, TkEntry> tkEntries = new LinkedHashMap<>();

    public TkCollection(int tkDimension, String tkMetric) {
        TkToolException.mtkCheck(tkDimension >= 1 && tkDimension <= 8192, "dimension must be between 1 and 8192");
        TkToolException.mtkCheck(_TKMETRICS.contains(tkMetric), "metric must be one of " + _TKMETRICS);
        this.tkDimension = tkDimension;
        this.tkMetric = tkMetric;
    }

    public int mtkDimension() {
        return tkDimension;
    }

    public String mtkMetric() {
        return tkMetric;
    }

    public synchronized int mtkSize() {
        return tkEntries.size();
    }

    public synchronized List<TkEntry> mtkEntries() {
        return List.copyOf(tkEntries.values());
    }

    public synchronized void mtkUpsert(List<TkEntry> tkBatch) {
        tkBatch.forEach(tkEntry -> mtkValidate(tkEntry.tkVector()));
        long tkNewIds = tkBatch.stream().map(TkEntry::tkId).distinct().filter(tkId -> !tkEntries.containsKey(tkId)).count();
        TkToolException.mtkCheck((tkEntries.size() + tkNewIds) * tkDimension <= _TKMAX_ELEMENTS, "collection would exceed " + _TKMAX_ELEMENTS + " stored values");
        tkBatch.forEach(tkEntry -> tkEntries.put(tkEntry.tkId(), tkEntry));
    }

    public synchronized int mtkDelete(List<String> tkIds) {
        return (int) tkIds.stream().filter(tkId -> tkEntries.remove(tkId) != null).count();
    }

    public synchronized List<TkMatch> mtkSearch(float[] tkQuery, int tkK, Map<String, Object> tkFilter) {
        mtkValidate(tkQuery);
        double tkQueryNorm = mtkNorm(tkQuery);
        Comparator<TkMatch> tkBetter = "l2".equals(tkMetric)
                ? Comparator.comparingDouble(TkMatch::tkScore).reversed()
                : Comparator.comparingDouble(TkMatch::tkScore);
        PriorityQueue<TkMatch> tkTop = new PriorityQueue<>(tkBetter);
        for (TkEntry tkEntry : tkEntries.values()) {
            if (tkFilter.entrySet().stream().allMatch(tkCondition -> mtkSameValue(tkEntry.tkMetadata().get(tkCondition.getKey()), tkCondition.getValue()))) {
                tkTop.add(new TkMatch(tkEntry.tkId(), mtkScore(tkQuery, tkQueryNorm, tkEntry), tkEntry.tkMetadata()));
                if (tkTop.size() > tkK) {
                    tkTop.poll();
                }
            }
        }
        List<TkMatch> tkMatches = new ArrayList<>(tkTop);
        tkMatches.sort(tkBetter.reversed());
        return tkMatches;
    }

    private double mtkScore(float[] tkQuery, double tkQueryNorm, TkEntry tkEntry) {
        double tkAccumulator = 0;
        for (int tkIndex = 0; tkIndex < tkDimension; tkIndex++) {
            double tkValue = "l2".equals(tkMetric) ? tkQuery[tkIndex] - tkEntry.tkVector()[tkIndex] : (double) tkQuery[tkIndex] * tkEntry.tkVector()[tkIndex];
            tkAccumulator += "l2".equals(tkMetric) ? tkValue * tkValue : tkValue;
        }
        return switch (tkMetric) {
            case "l2" -> Math.sqrt(tkAccumulator);
            case "cosine" -> tkAccumulator / (tkQueryNorm * tkEntry.tkNorm());
            default -> tkAccumulator;
        };
    }

    private void mtkValidate(float[] tkVector) {
        TkToolException.mtkCheck(tkVector.length == tkDimension, "vector dimension " + tkVector.length + " does not match collection dimension " + tkDimension);
        for (float tkValue : tkVector) {
            TkToolException.mtkCheck(Float.isFinite(tkValue), "vectors must contain only finite numbers");
        }
        TkToolException.mtkCheck(!"cosine".equals(tkMetric) || mtkNorm(tkVector) > 0, "cosine similarity is undefined for a zero vector");
    }

    static double mtkNorm(float[] tkVector) {
        double tkSum = 0;
        for (float tkValue : tkVector) {
            tkSum += (double) tkValue * tkValue;
        }
        return Math.sqrt(tkSum);
    }

    private static boolean mtkSameValue(Object tkActual, Object tkExpected) {
        if (tkActual instanceof Number tkLeft && tkExpected instanceof Number tkRight) {
            return tkLeft.doubleValue() == tkRight.doubleValue();
        }
        return tkActual != null && Objects.equals(tkActual, tkExpected);
    }

    public record TkEntry(String tkId, float[] tkVector, Map<String, Object> tkMetadata, double tkNorm) {

        public TkEntry(String tkId, float[] tkVector, Map<String, Object> tkMetadata) {
            this(tkId, tkVector, tkMetadata, mtkNorm(tkVector));
        }
    }

    public record TkMatch(String tkId, double tkScore, Map<String, Object> tkMetadata) {
    }
}
