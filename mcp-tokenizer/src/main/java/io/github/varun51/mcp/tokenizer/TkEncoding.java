package io.github.varun51.mcp.tokenizer;

import io.github.varun51.mcp.core.TkToolException;
import java.net.URI;
import java.util.List;

public record TkEncoding(String tkName, String tkSha256, String tkPattern) {

    public static final List<TkEncoding> _TKALL = List.of(
            new TkEncoding(
                    "cl100k_base",
                    "223921b76ee99bde995b7ff738513eef100fb51d18c93597a113bcffe865b2a7",
                    "'(?i:[sdmt]|ll|ve|re)|[^\\r\\n\\p{L}\\p{N}]?+\\p{L}++|\\p{N}{1,3}+| ?[^\\s\\p{L}\\p{N}]++[\\r\\n]*+|\\s++\\z|\\s*[\\r\\n]|\\s+(?!\\S)|\\s"),
            new TkEncoding(
                    "o200k_base",
                    "446a9538cb6c348e3516120d7c08b09f57c36495e2acfffe59a5bf8b0cfb1a2d",
                    String.join("|",
                            "[^\\r\\n\\p{L}\\p{N}]?[\\p{Lu}\\p{Lt}\\p{Lm}\\p{Lo}\\p{M}]*[\\p{Ll}\\p{Lm}\\p{Lo}\\p{M}]+(?i:'s|'t|'re|'ve|'m|'ll|'d)?",
                            "[^\\r\\n\\p{L}\\p{N}]?[\\p{Lu}\\p{Lt}\\p{Lm}\\p{Lo}\\p{M}]+[\\p{Ll}\\p{Lm}\\p{Lo}\\p{M}]*(?i:'s|'t|'re|'ve|'m|'ll|'d)?",
                            "\\p{N}{1,3}",
                            " ?[^\\s\\p{L}\\p{N}]+[\\r\\n/]*",
                            "\\s*[\\r\\n]+",
                            "\\s+(?!\\S)",
                            "\\s+")));

    private static final String _TKBASE_URI = "https://openaipublic.blob.core.windows.net/encodings/";

    public static TkEncoding mtkNamed(String tkName) {
        return _TKALL.stream()
                .filter(tkEncoding -> tkEncoding.tkName.equals(tkName))
                .findFirst()
                .orElseThrow(() -> new TkToolException("unknown encoding: " + tkName + ", expected one of " + _TKALL.stream().map(TkEncoding::tkName).toList()));
    }

    public String mtkFileName() {
        return tkName + ".tiktoken";
    }

    public URI mtkDefaultUri() {
        return URI.create(_TKBASE_URI + mtkFileName());
    }
}
