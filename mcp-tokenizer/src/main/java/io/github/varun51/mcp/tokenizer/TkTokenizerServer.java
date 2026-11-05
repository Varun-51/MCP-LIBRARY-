package io.github.varun51.mcp.tokenizer;

import static io.github.varun51.mcp.core.TkSchema.mtkArray;
import static io.github.varun51.mcp.core.TkSchema.mtkEnum;
import static io.github.varun51.mcp.core.TkSchema.mtkField;
import static io.github.varun51.mcp.core.TkSchema.mtkObject;
import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkArgs;
import io.github.varun51.mcp.core.TkServerLauncher;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.core.TkToolException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class TkTokenizerServer {

    static final int _TKMAX_TEXT_CHARS = 1_000_000;
    static final int _TKMAX_TOKENS = 1_000_000;
    private static final String _TKDEFAULT_ENCODING = "cl100k_base";

    private TkTokenizerServer() {
    }

    public static void main(String[] tkArgs) throws Exception {
        TkVocabStore tkStore = new TkVocabStore(TkVocabStore.mtkDefaultDirectory());
        if (tkArgs.length > 0 && tkArgs[0].equals("download")) {
            for (TkEncoding tkEncoding : TkEncoding._TKALL) {
                System.out.println("downloaded " + tkStore.mtkDownload(tkEncoding, tkEncoding.mtkDefaultUri()));
            }
            return;
        }
        TkServerLauncher.mtkStartStdio("mcp-tokenizer", "0.1.0", mtkTools(tkStore::mtkTokenizer));
    }

    static List<TkTool> mtkTools(Function<String, TkBpeTokenizer> tkTokenizers) {
        Map<String, Object> tkEncoding = mtkEnum("Tokenizer encoding, default " + _TKDEFAULT_ENCODING,
                TkEncoding._TKALL.stream().map(TkEncoding::tkName).toArray(String[]::new));
        Map<String, Object> tkText = mtkField("string", "Input text");
        return List.of(
                new TkTool("count_tokens", "Count the tokens a model tokenizer produces for a text",
                        mtkObject(Map.of("text", tkText, "encoding", tkEncoding), "text"),
                        tkCall -> mtkResult("encoding", mtkEncodingName(tkCall), "tokens", mtkEncode(tkTokenizers, tkCall).size())),
                new TkTool("encode", "Encode text into token ids",
                        mtkObject(Map.of("text", tkText, "encoding", tkEncoding), "text"),
                        tkCall -> {
                            List<Integer> tkTokens = mtkEncode(tkTokenizers, tkCall);
                            return mtkResult("encoding", mtkEncodingName(tkCall), "count", tkTokens.size(), "tokens", tkTokens);
                        }),
                new TkTool("decode", "Decode token ids back into text",
                        mtkObject(Map.of("tokens", mtkArray("integer", "Token ids"), "encoding", tkEncoding), "tokens"),
                        tkCall -> {
                            List<Integer> tkTokens = tkCall.mtkIntList("tokens");
                            TkToolException.mtkCheck(tkTokens.size() <= _TKMAX_TOKENS, "too many tokens, limit is " + _TKMAX_TOKENS);
                            return mtkResult("text", tkTokenizers.apply(mtkEncodingName(tkCall)).mtkDecode(tkTokens));
                        }),
                new TkTool("truncate_to_budget", "Truncate text to at most max_tokens tokens without splitting a character",
                        mtkObject(Map.of("text", tkText, "max_tokens", mtkField("integer", "Token budget"), "encoding", tkEncoding), "text", "max_tokens"),
                        tkCall -> {
                            TkBpeTokenizer.TkTruncation tkTruncation = tkTokenizers.apply(mtkEncodingName(tkCall)).mtkTruncate(mtkText(tkCall), tkCall.mtkInt("max_tokens"));
                            return mtkResult("text", tkTruncation.tkText(), "tokens", tkTruncation.tkTokens(), "truncated", tkTruncation.tkTruncated());
                        }));
    }

    private static List<Integer> mtkEncode(Function<String, TkBpeTokenizer> tkTokenizers, TkArgs tkCall) {
        return tkTokenizers.apply(mtkEncodingName(tkCall)).mtkEncode(mtkText(tkCall));
    }

    private static String mtkText(TkArgs tkCall) {
        String tkText = tkCall.mtkString("text");
        TkToolException.mtkCheck(tkText.length() <= _TKMAX_TEXT_CHARS, "text too long, limit is " + _TKMAX_TEXT_CHARS + " characters");
        return tkText;
    }

    private static String mtkEncodingName(TkArgs tkCall) {
        return tkCall.mtkString("encoding", _TKDEFAULT_ENCODING);
    }
}
