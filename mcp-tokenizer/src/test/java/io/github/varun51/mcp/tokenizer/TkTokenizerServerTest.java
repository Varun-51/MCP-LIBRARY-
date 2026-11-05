package io.github.varun51.mcp.tokenizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.varun51.mcp.core.TkTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TkTokenizerServerTest {

    private final Map<String, TkTool> tkTools = TkTokenizerServer.mtkTools(tkName -> TkBpeTokenizerTest.mtkSynthetic())
            .stream().collect(Collectors.toMap(TkTool::tkName, tkTool -> tkTool));

    @Test
    void mtkExposesAllTokenizerTools() {
        assertEquals(Set.of("count_tokens", "encode", "decode", "truncate_to_budget"), tkTools.keySet());
    }

    @Test
    void mtkCountsEncodesAndDecodes() {
        assertEquals(Map.of("encoding", "cl100k_base", "tokens", 3), mtkOk("count_tokens", Map.of("text", "abc ab")));
        assertEquals(Map.of("encoding", "o200k_base", "count", 3, "tokens", List.of(258, 32, 256)),
                mtkOk("encode", Map.of("text", "abc ab", "encoding", "o200k_base")));
        assertEquals(Map.of("text", "abc ab"), mtkOk("decode", Map.of("tokens", List.of(258, 32, 256))));
    }

    @Test
    void mtkTruncatesToBudget() {
        assertEquals(Map.of("text", "abc", "tokens", 1, "truncated", true), mtkOk("truncate_to_budget", Map.of("text", "abc ab", "max_tokens", 1)));
    }

    @Test
    void mtkRejectsOversizedInputs() {
        assertTrue(mtkError("count_tokens", Map.of("text", "a".repeat(TkTokenizerServer._TKMAX_TEXT_CHARS + 1))).startsWith("text too long"));
        List<Integer> tkTooMany = Collections.nCopies(TkTokenizerServer._TKMAX_TOKENS + 1, 97);
        assertTrue(mtkError("decode", Map.of("tokens", tkTooMany)).startsWith("too many tokens"));
    }

    @Test
    void mtkRejectsInvalidArguments() {
        assertEquals("missing argument: text", mtkError("encode", Map.of()));
        assertEquals("max_tokens must be >= 0", mtkError("truncate_to_budget", Map.of("text", "abc", "max_tokens", -1)));
        assertEquals("unknown token id: 9999", mtkError("decode", Map.of("tokens", List.of(9999))));
    }

    private Object mtkOk(String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get(tkName).mtkCall(tkArgs);
        assertEquals(false, tkResult.isError(), () -> ((TextContent) tkResult.content().get(0)).text());
        return tkResult.structuredContent();
    }

    private String mtkError(String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get(tkName).mtkCall(tkArgs);
        assertEquals(true, tkResult.isError());
        return ((TextContent) tkResult.content().get(0)).text();
    }
}
