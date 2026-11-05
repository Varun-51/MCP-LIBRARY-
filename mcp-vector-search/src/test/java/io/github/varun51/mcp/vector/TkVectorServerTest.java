package io.github.varun51.mcp.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.varun51.mcp.core.TkTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TkVectorServerTest {

    @TempDir
    Path tkDirectory;

    private Map<String, TkTool> mtkTools() {
        return new TkVectorServer(tkDirectory).mtkTools().stream().collect(Collectors.toMap(TkTool::tkName, tkTool -> tkTool));
    }

    private Map<String, TkTool> mtkSeeded(String tkMetric) {
        Map<String, TkTool> tkTools = mtkTools();
        mtkOk(tkTools, "create_collection", Map.of("name", "docs", "dimension", 2, "metric", tkMetric));
        mtkOk(tkTools, "upsert", Map.of("collection", "docs", "items", List.of(
                Map.of("id", "east", "vector", List.of(1, 0), "metadata", Map.of("lang", "en", "year", 2024)),
                Map.of("id", "north", "vector", List.of(0, 1), "metadata", Map.of("lang", "fr", "year", 2023)),
                Map.of("id", "far-east", "vector", List.of(10, 2), "metadata", Map.of("lang", "en", "year", 2025)))));
        return tkTools;
    }

    @Test
    void mtkRanksByCosineIgnoringMagnitude() {
        assertEquals(List.of("east", "far-east", "north"), mtkIds(mtkSeeded("cosine"), Map.of("collection", "docs", "vector", List.of(1, 0))));
    }

    @Test
    void mtkRanksByDotProductRewardingMagnitude() {
        assertEquals(List.of("far-east", "east", "north"), mtkIds(mtkSeeded("dot"), Map.of("collection", "docs", "vector", List.of(1, 0.1))));
    }

    @Test
    void mtkRanksByEuclideanDistanceAscending() {
        Map<String, TkTool> tkTools = mtkSeeded("l2");
        assertEquals(List.of("east", "north", "far-east"), mtkIds(tkTools, Map.of("collection", "docs", "vector", List.of(0.5, 0.4))));
        Map<String, Object> tkTop = mtkMatches(tkTools, Map.of("collection", "docs", "vector", List.of(1, 0), "k", 1)).get(0);
        assertEquals(0.0, (double) tkTop.get("score"), 1e-9);
    }

    @Test
    void mtkLimitsToKAndAppliesFilters() {
        Map<String, TkTool> tkTools = mtkSeeded("cosine");
        assertEquals(List.of("east"), mtkIds(tkTools, Map.of("collection", "docs", "vector", List.of(1, 0), "k", 1)));
        assertEquals(List.of("far-east"), mtkIds(tkTools, Map.of("collection", "docs", "vector", List.of(1, 0), "filter", Map.of("lang", "en", "year", 2025.0))));
        assertEquals(List.of(), mtkIds(tkTools, Map.of("collection", "docs", "vector", List.of(1, 0), "filter", Map.of("missing", "x"))));
    }

    @Test
    void mtkUpsertReplacesAndDeleteRemoves() {
        Map<String, TkTool> tkTools = mtkSeeded("cosine");
        assertEquals(3, mtkOk(tkTools, "upsert", Map.of("collection", "docs", "items", List.of(Map.of("id", "north", "vector", List.of(1, 1))))).get("count"));
        assertEquals(2, mtkOk(tkTools, "delete", Map.of("collection", "docs", "ids", List.of("north", "far-east", "absent"))).get("deleted"));
        assertEquals(List.of(Map.of("name", "docs", "dimension", 2, "metric", "cosine", "count", 1)), mtkOk(tkTools, "list_collections", Map.of()).get("collections"));
        assertEquals(true, mtkOk(tkTools, "drop_collection", Map.of("name", "docs")).get("dropped"));
    }

    @Test
    void mtkPersistsAndRestoresSnapshots() {
        Map<String, TkTool> tkTools = mtkSeeded("dot");
        mtkOk(tkTools, "save_collection", Map.of("name", "docs"));
        Map<String, TkTool> tkRestarted = mtkTools();
        assertEquals(3, mtkOk(tkRestarted, "load_collection", Map.of("name", "docs")).get("count"));
        assertEquals(List.of("far-east", "east", "north"), mtkIds(tkRestarted, Map.of("collection", "docs", "vector", List.of(1, 0.1))));
    }

    @Test
    void mtkRejectsInvalidVectors() {
        Map<String, TkTool> tkTools = mtkSeeded("cosine");
        assertEquals("vector dimension 3 does not match collection dimension 2",
                mtkError(tkTools, "search", Map.of("collection", "docs", "vector", List.of(1, 0, 0))));
        assertEquals("cosine similarity is undefined for a zero vector",
                mtkError(tkTools, "upsert", Map.of("collection", "docs", "items", List.of(Map.of("id", "z", "vector", List.of(0, 0))))));
        assertEquals("vectors must contain only finite numbers",
                mtkError(tkTools, "search", Map.of("collection", "docs", "vector", List.of(Double.NaN, 1))));
        assertEquals("collection already exists: docs", mtkError(tkTools, "create_collection", Map.of("name", "docs", "dimension", 2, "metric", "dot")));
        assertEquals("unknown collection: nope", mtkError(tkTools, "search", Map.of("collection", "nope", "vector", List.of(1, 0))));
        assertEquals("k must be between 1 and 1000", mtkError(tkTools, "search", Map.of("collection", "docs", "vector", List.of(1, 0), "k", 0)));
    }

    @Test
    void mtkConfinesSnapshotsToTheirDirectory() throws Exception {
        Map<String, TkTool> tkTools = mtkTools();
        assertEquals("invalid collection name: ../escape", mtkError(tkTools, "load_collection", Map.of("name", "../escape")));
        assertEquals("invalid collection name: a/b", mtkError(tkTools, "create_collection", Map.of("name", "a/b", "dimension", 2, "metric", "dot")));
        Files.writeString(tkDirectory.resolve("broken.json"), "{not json");
        assertEquals("snapshot for broken is not valid JSON", mtkError(tkTools, "load_collection", Map.of("name", "broken")));
        try (var tkFiles = Files.list(tkDirectory.getParent())) {
            assertFalse(tkFiles.anyMatch(tkPath -> tkPath.getFileName().toString().equals("escape.json")));
        }
    }

    @Test
    void mtkEnforcesBatchAndCapacityLimits() {
        Map<String, TkTool> tkTools = mtkTools();
        mtkOk(tkTools, "create_collection", Map.of("name", "big", "dimension", 8192, "metric", "dot"));
        List<Object> tkTooMany = Collections.nCopies(TkVectorServer._TKMAX_BATCH + 1, Map.of("id", "x", "vector", List.of(1)));
        assertTrue(mtkError(tkTools, "upsert", Map.of("collection", "big", "items", tkTooMany)).startsWith("batch too large"));
        assertEquals("dimension must be between 1 and 8192", mtkError(tkTools, "create_collection", Map.of("name", "huge", "dimension", 8193, "metric", "dot")));
    }

    private List<String> mtkIds(Map<String, TkTool> tkTools, Map<String, Object> tkArgs) {
        return mtkMatches(tkTools, tkArgs).stream().map(tkMatch -> (String) tkMatch.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mtkMatches(Map<String, TkTool> tkTools, Map<String, Object> tkArgs) {
        return (List<Map<String, Object>>) mtkOk(tkTools, "search", tkArgs).get("matches");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mtkOk(Map<String, TkTool> tkTools, String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get(tkName).mtkCall(tkArgs);
        assertEquals(false, tkResult.isError(), () -> ((TextContent) tkResult.content().get(0)).text());
        return (Map<String, Object>) tkResult.structuredContent();
    }

    private String mtkError(Map<String, TkTool> tkTools, String tkName, Map<String, Object> tkArgs) {
        CallToolResult tkResult = tkTools.get(tkName).mtkCall(tkArgs);
        assertEquals(true, tkResult.isError());
        return ((TextContent) tkResult.content().get(0)).text();
    }
}
