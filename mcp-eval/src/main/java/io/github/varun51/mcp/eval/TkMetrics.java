package io.github.varun51.mcp.eval;

import io.github.varun51.mcp.core.TkToolException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

public final class TkMetrics {

    private static final Pattern _TKARTICLES = Pattern.compile("\\b(a|an|the)\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern _TKWHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern _TKNON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final String _TKPUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private TkMetrics() {
    }

    public static String mtkNormalize(String tkText) {
        StringBuilder tkKept = new StringBuilder();
        tkText.toLowerCase(Locale.ROOT).codePoints()
                .filter(tkCodePoint -> _TKPUNCTUATION.indexOf(tkCodePoint) < 0)
                .forEach(tkKept::appendCodePoint);
        return String.join(" ", mtkSplit(_TKARTICLES.matcher(tkKept).replaceAll(" ")));
    }

    public static double mtkExactMatch(String tkPrediction, String tkReference) {
        return mtkNormalize(tkPrediction).equals(mtkNormalize(tkReference)) ? 1 : 0;
    }

    public static double mtkTokenF1(String tkPrediction, String tkReference) {
        List<String> tkPredicted = mtkSplit(mtkNormalize(tkPrediction));
        List<String> tkGold = mtkSplit(mtkNormalize(tkReference));
        if (tkPredicted.isEmpty() || tkGold.isEmpty()) {
            return tkPredicted.equals(tkGold) ? 1 : 0;
        }
        Map<String, Integer> tkGoldCounts = new HashMap<>();
        tkGold.forEach(tkToken -> tkGoldCounts.merge(tkToken, 1, Integer::sum));
        int tkShared = 0;
        for (String tkToken : tkPredicted) {
            if (tkGoldCounts.getOrDefault(tkToken, 0) > 0) {
                tkGoldCounts.merge(tkToken, -1, Integer::sum);
                tkShared++;
            }
        }
        return mtkF1(tkShared, tkPredicted.size(), tkGold.size());
    }

    public static double mtkRougeL(String tkPrediction, String tkReference) {
        List<String> tkPredicted = mtkRougeTokens(tkPrediction);
        List<String> tkGold = mtkRougeTokens(tkReference);
        if (tkPredicted.isEmpty() || tkGold.isEmpty()) {
            return 0;
        }
        return mtkF1(mtkLongestCommonSubsequence(tkPredicted, tkGold), tkPredicted.size(), tkGold.size());
    }

    public static List<String> mtkRougeTokens(String tkText) {
        return mtkSplit(_TKNON_ALPHANUMERIC.matcher(tkText.toLowerCase(Locale.ROOT)).replaceAll(" "));
    }

    public static List<String> mtkWords(String tkText) {
        return mtkSplit(tkText);
    }

    public static List<Integer> mtkCharacters(String tkText) {
        return tkText.strip().codePoints().boxed().toList();
    }

    public static <T> int mtkEditDistance(List<T> tkHypothesis, List<T> tkReference) {
        int[] tkPrevious = new int[tkReference.size() + 1];
        int[] tkCurrent = new int[tkReference.size() + 1];
        Arrays.setAll(tkPrevious, tkIndex -> tkIndex);
        for (int tkRow = 1; tkRow <= tkHypothesis.size(); tkRow++) {
            tkCurrent[0] = tkRow;
            for (int tkColumn = 1; tkColumn <= tkReference.size(); tkColumn++) {
                int tkSubstitution = tkPrevious[tkColumn - 1] + (tkHypothesis.get(tkRow - 1).equals(tkReference.get(tkColumn - 1)) ? 0 : 1);
                tkCurrent[tkColumn] = Math.min(tkSubstitution, Math.min(tkPrevious[tkColumn], tkCurrent[tkColumn - 1]) + 1);
            }
            int[] tkSwap = tkPrevious;
            tkPrevious = tkCurrent;
            tkCurrent = tkSwap;
        }
        return tkPrevious[tkReference.size()];
    }

    public static double mtkPassAtK(int tkSamples, int tkCorrect, int tkK) {
        TkToolException.mtkCheck(tkSamples >= 1 && tkCorrect >= 0 && tkCorrect <= tkSamples && tkK >= 1 && tkK <= tkSamples,
                "pass@k requires n >= 1, 0 <= c <= n and 1 <= k <= n");
        if (tkSamples - tkCorrect < tkK) {
            return 1;
        }
        double tkAllWrong = 1;
        for (int tkIndex = tkSamples - tkCorrect + 1; tkIndex <= tkSamples; tkIndex++) {
            tkAllWrong *= 1 - (double) tkK / tkIndex;
        }
        return 1 - tkAllWrong;
    }

    public static TkInterval mtkBootstrap(double[] tkScores, double tkConfidence, int tkIterations, long tkSeed) {
        TkToolException.mtkCheck(tkScores.length > 0, "scores must not be empty");
        TkToolException.mtkCheck(tkConfidence > 0 && tkConfidence < 1, "confidence must be between 0 and 1");
        TkToolException.mtkCheck(tkIterations >= 2, "iterations must be >= 2");
        SplittableRandom tkRandom = new SplittableRandom(tkSeed);
        double[] tkMeans = new double[tkIterations];
        for (int tkIteration = 0; tkIteration < tkIterations; tkIteration++) {
            double tkSum = 0;
            for (int tkDraw = 0; tkDraw < tkScores.length; tkDraw++) {
                tkSum += tkScores[tkRandom.nextInt(tkScores.length)];
            }
            tkMeans[tkIteration] = tkSum / tkScores.length;
        }
        Arrays.sort(tkMeans);
        double tkMeanOfMeans = Arrays.stream(tkMeans).average().orElseThrow();
        double tkVariance = Arrays.stream(tkMeans).map(tkMean -> (tkMean - tkMeanOfMeans) * (tkMean - tkMeanOfMeans)).sum() / (tkIterations - 1);
        double tkTail = (1 - tkConfidence) / 2;
        return new TkInterval(Arrays.stream(tkScores).average().orElseThrow(), mtkQuantile(tkMeans, tkTail), mtkQuantile(tkMeans, 1 - tkTail), Math.sqrt(tkVariance));
    }

    private static double mtkQuantile(double[] tkSorted, double tkProbability) {
        double tkPosition = tkProbability * (tkSorted.length - 1);
        int tkLower = (int) Math.floor(tkPosition);
        int tkUpper = Math.min(tkLower + 1, tkSorted.length - 1);
        return tkSorted[tkLower] + (tkPosition - tkLower) * (tkSorted[tkUpper] - tkSorted[tkLower]);
    }

    private static int mtkLongestCommonSubsequence(List<String> tkFirst, List<String> tkSecond) {
        int[] tkPrevious = new int[tkSecond.size() + 1];
        for (String tkToken : tkFirst) {
            int[] tkCurrent = new int[tkSecond.size() + 1];
            for (int tkColumn = 1; tkColumn <= tkSecond.size(); tkColumn++) {
                tkCurrent[tkColumn] = tkToken.equals(tkSecond.get(tkColumn - 1)) ? tkPrevious[tkColumn - 1] + 1 : Math.max(tkPrevious[tkColumn], tkCurrent[tkColumn - 1]);
            }
            tkPrevious = tkCurrent;
        }
        return tkPrevious[tkSecond.size()];
    }

    private static double mtkF1(int tkShared, int tkPredicted, int tkGold) {
        if (tkShared == 0) {
            return 0;
        }
        double tkPrecision = (double) tkShared / tkPredicted;
        double tkRecall = (double) tkShared / tkGold;
        return 2 * tkPrecision * tkRecall / (tkPrecision + tkRecall);
    }

    private static List<String> mtkSplit(CharSequence tkText) {
        String tkStripped = tkText.toString().strip();
        return tkStripped.isEmpty() ? List.of() : List.of(_TKWHITESPACE.split(tkStripped));
    }

    public record TkInterval(double tkMean, double tkLower, double tkUpper, double tkStandardError) {
    }
}
