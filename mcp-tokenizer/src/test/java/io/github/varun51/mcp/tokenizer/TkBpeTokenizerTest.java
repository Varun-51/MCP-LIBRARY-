package io.github.varun51.mcp.tokenizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.varun51.mcp.core.TkToolException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class TkBpeTokenizerTest {

    static TkBpeTokenizer mtkSynthetic() {
        StringBuilder tkVocabulary = new StringBuilder();
        for (int tkByte = 0; tkByte < 256; tkByte++) {
            tkVocabulary.append(mtkLine(new byte[] {(byte) tkByte}, tkByte));
        }
        tkVocabulary.append(mtkLine("ab".getBytes(StandardCharsets.UTF_8), 256))
                .append(mtkLine("bc".getBytes(StandardCharsets.UTF_8), 257))
                .append(mtkLine("abc".getBytes(StandardCharsets.UTF_8), 258));
        return TkBpeTokenizer.mtkParse(tkVocabulary.toString().getBytes(StandardCharsets.US_ASCII), "\\S+|\\s+");
    }

    private static String mtkLine(byte[] tkBytes, int tkRank) {
        return Base64.getEncoder().encodeToString(tkBytes) + " " + tkRank + "\n";
    }

    @Test
    void mtkMergesLowestRankedPairFirst() {
        TkBpeTokenizer tkTokenizer = mtkSynthetic();
        assertEquals(List.of(258), tkTokenizer.mtkEncode("abc"));
        assertEquals(List.of(258, 256), tkTokenizer.mtkEncode("abcab"));
        assertEquals(List.of(258, 32, 120), tkTokenizer.mtkEncode("abc x"));
    }

    @Test
    void mtkFallsBackToRawBytesForUnmergedUnicode() {
        assertEquals(List.of(97, 0xC3, 0xA9), mtkSynthetic().mtkEncode("aé"));
    }

    @Test
    void mtkRoundTripsThroughDecode() {
        TkBpeTokenizer tkTokenizer = mtkSynthetic();
        String tkText = "abcab é\n\tzz";
        assertEquals(tkText, tkTokenizer.mtkDecode(tkTokenizer.mtkEncode(tkText)));
    }

    @Test
    void mtkEncodesEmptyTextToNoTokens() {
        assertEquals(List.of(), mtkSynthetic().mtkEncode(""));
    }

    @Test
    void mtkRejectsUnknownTokenIds() {
        TkBpeTokenizer tkTokenizer = mtkSynthetic();
        assertEquals("unknown token id: 999", assertThrows(TkToolException.class, () -> tkTokenizer.mtkDecode(List.of(999))).getMessage());
        assertEquals("unknown token id: -1", assertThrows(TkToolException.class, () -> tkTokenizer.mtkDecode(List.of(-1))).getMessage());
    }

    @Test
    void mtkTruncatesWithoutSplittingACharacter() {
        TkBpeTokenizer.TkTruncation tkTruncation = mtkSynthetic().mtkTruncate("aé", 2);
        assertEquals(new TkBpeTokenizer.TkTruncation("a", 1, true), tkTruncation);
    }

    @Test
    void mtkLeavesTextWithinBudgetUntouched() {
        assertEquals(new TkBpeTokenizer.TkTruncation("abc ab", 3, false), mtkSynthetic().mtkTruncate("abc ab", 3));
        assertEquals(new TkBpeTokenizer.TkTruncation("", 0, true), mtkSynthetic().mtkTruncate("abc", 0));
    }

    @Test
    void mtkRejectsNegativeBudget() {
        assertThrows(TkToolException.class, () -> mtkSynthetic().mtkTruncate("abc", -1));
    }

    @Test
    void mtkRejectsVocabularyMissingSingleBytes() {
        byte[] tkVocabulary = mtkLine("a".getBytes(StandardCharsets.UTF_8), 0).getBytes(StandardCharsets.US_ASCII);
        assertEquals("vocabulary is missing single byte 0", assertThrows(TkToolException.class, () -> TkBpeTokenizer.mtkParse(tkVocabulary, "\\S+")).getMessage());
    }

    @Test
    void mtkRejectsMalformedVocabularyLines() {
        byte[] tkVocabulary = "YQ== 0 extra\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(TkToolException.class, () -> TkBpeTokenizer.mtkParse(tkVocabulary, "\\S+"));
    }
}
