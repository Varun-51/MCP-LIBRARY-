package io.github.varun51.mcp.guardrails;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TkDetectors {

    public static final List<TkDetector> _TKALL = List.of(
            new TkDetector("email", "pii", "[A-Za-z0-9._%+-]{1,64}@(?:[A-Za-z0-9-]{1,63}\\.){1,8}[A-Za-z]{2,24}", tkValue -> true),
            new TkDetector("credit_card", "pii", "(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)", TkDetectors::mtkLuhn),
            new TkDetector("iban", "pii", "\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b", TkDetectors::mtkIban),
            new TkDetector("phone", "pii", "(?<![\\w+])(?:\\+\\d{1,3}[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?!\\w)", tkValue -> true),
            new TkDetector("ip_address", "pii", "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b", tkValue -> true),
            new TkDetector("aws_access_key", "secret", "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b", tkValue -> true),
            new TkDetector("github_token", "secret", "\\bgh[pousr]_[A-Za-z0-9]{36,255}\\b", tkValue -> true),
            new TkDetector("api_key", "secret", "\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,200}", tkValue -> true),
            new TkDetector("slack_token", "secret", "\\bxox[abprs]-[A-Za-z0-9-]{10,200}", tkValue -> true),
            new TkDetector("jwt", "secret", "\\beyJ[A-Za-z0-9_-]{10,2000}\\.[A-Za-z0-9_-]{10,2000}\\.[A-Za-z0-9_-]{10,2000}", tkValue -> true),
            new TkDetector("private_key", "secret", "-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----", tkValue -> true),
            new TkDetector("high_entropy", "secret", "[A-Za-z0-9+/=_-]{32,512}", tkValue -> mtkEntropy(tkValue) >= 4.5));

    private TkDetectors() {
    }

    public static List<TkFinding> mtkFind(String tkText, Set<String> tkTypes) {
        List<TkFinding> tkFindings = new ArrayList<>();
        for (TkDetector tkDetector : _TKALL) {
            if (tkTypes.contains(tkDetector.tkType())) {
                Matcher tkMatcher = tkDetector.tkPattern().matcher(tkText);
                while (tkMatcher.find()) {
                    if (tkDetector.tkVerifier().test(tkMatcher.group())) {
                        tkFindings.add(new TkFinding(tkDetector.tkType(), tkDetector.tkCategory(), tkMatcher.start(), tkMatcher.end(), mtkMask(tkMatcher.group())));
                    }
                }
            }
        }
        TreeMap<Integer, TkFinding> tkAccepted = new TreeMap<>();
        for (TkFinding tkFinding : tkFindings) {
            Map.Entry<Integer, TkFinding> tkNearest = tkAccepted.floorEntry(tkFinding.tkEnd() - 1);
            if (tkNearest == null || tkNearest.getValue().tkEnd() <= tkFinding.tkStart()) {
                tkAccepted.put(tkFinding.tkStart(), tkFinding);
            }
        }
        return List.copyOf(tkAccepted.values());
    }

    public static String mtkRedact(String tkText, List<TkFinding> tkFindings) {
        StringBuilder tkRedacted = new StringBuilder();
        int tkCursor = 0;
        for (TkFinding tkFinding : tkFindings) {
            tkRedacted.append(tkText, tkCursor, tkFinding.tkStart()).append('[').append(tkFinding.tkType().toUpperCase(Locale.ROOT)).append(']');
            tkCursor = tkFinding.tkEnd();
        }
        return tkRedacted.append(tkText.substring(tkCursor)).toString();
    }

    static String mtkMask(String tkValue) {
        if (tkValue.length() <= 4) {
            return "*".repeat(tkValue.length());
        }
        return tkValue.charAt(0) + "*".repeat(tkValue.length() - 2) + tkValue.charAt(tkValue.length() - 1);
    }

    static boolean mtkLuhn(String tkValue) {
        String tkDigits = tkValue.replaceAll("[ -]", "");
        int tkSum = 0;
        for (int tkIndex = 0; tkIndex < tkDigits.length(); tkIndex++) {
            int tkDigit = tkDigits.charAt(tkDigits.length() - 1 - tkIndex) - '0';
            if (tkIndex % 2 == 1) {
                tkDigit = tkDigit * 2 > 9 ? tkDigit * 2 - 9 : tkDigit * 2;
            }
            tkSum += tkDigit;
        }
        return tkDigits.length() >= 13 && tkSum % 10 == 0;
    }

    static boolean mtkIban(String tkValue) {
        String tkCompact = tkValue.replace(" ", "");
        StringBuilder tkNumeric = new StringBuilder();
        (tkCompact.substring(4) + tkCompact.substring(0, 4)).chars()
                .forEach(tkChar -> tkNumeric.append(Character.isDigit(tkChar) ? String.valueOf((char) tkChar) : String.valueOf(tkChar - 'A' + 10)));
        return new BigInteger(tkNumeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    static double mtkEntropy(String tkValue) {
        int[] tkCounts = new int[128];
        tkValue.chars().forEach(tkChar -> tkCounts[tkChar]++);
        double tkEntropy = 0;
        for (int tkCount : tkCounts) {
            if (tkCount > 0) {
                double tkProbability = (double) tkCount / tkValue.length();
                tkEntropy -= tkProbability * Math.log(tkProbability) / Math.log(2);
            }
        }
        return tkEntropy;
    }

    public record TkDetector(String tkType, String tkCategory, Pattern tkPattern, Predicate<String> tkVerifier) {

        TkDetector(String tkType, String tkCategory, String tkRegex, Predicate<String> tkVerifier) {
            this(tkType, tkCategory, Pattern.compile(tkRegex), tkVerifier);
        }
    }

    public record TkFinding(String tkType, String tkCategory, int tkStart, int tkEnd, String tkMasked) {
    }
}
