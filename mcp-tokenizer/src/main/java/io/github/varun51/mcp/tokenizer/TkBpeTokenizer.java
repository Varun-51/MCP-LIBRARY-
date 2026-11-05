package io.github.varun51.mcp.tokenizer;

import io.github.varun51.mcp.core.TkToolException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TkBpeTokenizer {

    private final Map<String, Integer> tkRanks;
    private final byte[][] tkTokenBytes;
    private final Pattern tkPattern;

    private TkBpeTokenizer(Map<String, Integer> tkRanks, byte[][] tkTokenBytes, Pattern tkPattern) {
        this.tkRanks = tkRanks;
        this.tkTokenBytes = tkTokenBytes;
        this.tkPattern = tkPattern;
    }

    public static TkBpeTokenizer mtkParse(byte[] tkVocabulary, String tkPattern) {
        Map<String, Integer> tkRanks = new HashMap<>();
        for (String tkLine : new String(tkVocabulary, StandardCharsets.US_ASCII).split("\n")) {
            if (!tkLine.isBlank()) {
                String[] tkParts = tkLine.trim().split(" ");
                TkToolException.mtkCheck(tkParts.length == 2, "invalid vocabulary line: " + tkLine);
                tkRanks.put(mtkKey(Base64.getDecoder().decode(tkParts[0])), Integer.parseInt(tkParts[1]));
            }
        }
        for (int tkByte = 0; tkByte < 256; tkByte++) {
            TkToolException.mtkCheck(tkRanks.containsKey(mtkKey(new byte[] {(byte) tkByte})), "vocabulary is missing single byte " + tkByte);
        }
        byte[][] tkTokenBytes = new byte[tkRanks.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1][];
        tkRanks.forEach((tkKey, tkRank) -> tkTokenBytes[tkRank] = tkKey.getBytes(StandardCharsets.ISO_8859_1));
        return new TkBpeTokenizer(tkRanks, tkTokenBytes, Pattern.compile(tkPattern, Pattern.UNICODE_CHARACTER_CLASS));
    }

    public List<Integer> mtkEncode(String tkText) {
        List<Integer> tkTokens = new ArrayList<>();
        Matcher tkMatcher = tkPattern.matcher(tkText);
        while (tkMatcher.find()) {
            String tkPiece = mtkKey(tkMatcher.group().getBytes(StandardCharsets.UTF_8));
            Integer tkDirect = tkRanks.get(tkPiece);
            if (tkDirect != null) {
                tkTokens.add(tkDirect);
            } else {
                mtkMerge(tkPiece, tkTokens);
            }
        }
        return tkTokens;
    }

    public byte[] mtkDecodeBytes(List<Integer> tkTokens) {
        ByteArrayOutputStream tkOut = new ByteArrayOutputStream();
        for (int tkToken : tkTokens) {
            TkToolException.mtkCheck(tkToken >= 0 && tkToken < tkTokenBytes.length && tkTokenBytes[tkToken] != null, "unknown token id: " + tkToken);
            tkOut.writeBytes(tkTokenBytes[tkToken]);
        }
        return tkOut.toByteArray();
    }

    public String mtkDecode(List<Integer> tkTokens) {
        return new String(mtkDecodeBytes(tkTokens), StandardCharsets.UTF_8);
    }

    public TkTruncation mtkTruncate(String tkText, int tkMaxTokens) {
        TkToolException.mtkCheck(tkMaxTokens >= 0, "max_tokens must be >= 0");
        List<Integer> tkTokens = mtkEncode(tkText);
        if (tkTokens.size() <= tkMaxTokens) {
            return new TkTruncation(tkText, tkTokens.size(), false);
        }
        for (int tkKeep = tkMaxTokens; ; tkKeep--) {
            try {
                String tkPrefix = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(mtkDecodeBytes(tkTokens.subList(0, tkKeep)))).toString();
                return new TkTruncation(tkPrefix, tkKeep, true);
            } catch (CharacterCodingException tkSplitCharacter) {
                continue;
            }
        }
    }

    private void mtkMerge(String tkPiece, List<Integer> tkTokens) {
        List<Integer> tkBounds = new ArrayList<>();
        for (int tkIndex = 0; tkIndex <= tkPiece.length(); tkIndex++) {
            tkBounds.add(tkIndex);
        }
        while (true) {
            int tkBest = -1;
            int tkBestRank = Integer.MAX_VALUE;
            for (int tkIndex = 0; tkIndex + 2 < tkBounds.size(); tkIndex++) {
                Integer tkRank = tkRanks.get(tkPiece.substring(tkBounds.get(tkIndex), tkBounds.get(tkIndex + 2)));
                if (tkRank != null && tkRank < tkBestRank) {
                    tkBest = tkIndex;
                    tkBestRank = tkRank;
                }
            }
            if (tkBest < 0) {
                break;
            }
            tkBounds.remove(tkBest + 1);
        }
        for (int tkIndex = 0; tkIndex + 1 < tkBounds.size(); tkIndex++) {
            tkTokens.add(tkRanks.get(tkPiece.substring(tkBounds.get(tkIndex), tkBounds.get(tkIndex + 1))));
        }
    }

    private static String mtkKey(byte[] tkBytes) {
        return new String(tkBytes, StandardCharsets.ISO_8859_1);
    }

    public record TkTruncation(String tkText, int tkTokens, boolean tkTruncated) {
    }
}
