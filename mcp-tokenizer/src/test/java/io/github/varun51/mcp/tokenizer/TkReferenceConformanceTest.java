package io.github.varun51.mcp.tokenizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TkReferenceConformanceTest {

    @ParameterizedTest
    @ValueSource(strings = {"cl100k_base", "o200k_base"})
    void mtkMatchesTiktokenReferenceOutput(String tkEncodingName) throws Exception {
        TkEncoding tkEncoding = TkEncoding.mtkNamed(tkEncodingName);
        assumeTrue(Files.isRegularFile(TkVocabStore.mtkDefaultDirectory().resolve(tkEncoding.mtkFileName())), "vocabulary not downloaded");
        TkBpeTokenizer tkTokenizer = new TkVocabStore(TkVocabStore.mtkDefaultDirectory()).mtkTokenizer(tkEncodingName);
        Map<String, List<Map<String, Object>>> tkReference;
        try (InputStream tkIn = getClass().getResourceAsStream("/tiktoken-reference.json")) {
            tkReference = McpJsonDefaults.getMapper().readValue(tkIn.readAllBytes(), new TypeRef<>() {
            });
        }
        for (Map<String, Object> tkCase : tkReference.get(tkEncodingName)) {
            String tkText = (String) tkCase.get("text");
            List<Integer> tkTokens = tkTokenizer.mtkEncode(tkText);
            assertEquals(tkCase.get("tokens"), tkTokens, tkText);
            assertEquals(tkText, tkTokenizer.mtkDecode(tkTokens));
        }
    }
}
