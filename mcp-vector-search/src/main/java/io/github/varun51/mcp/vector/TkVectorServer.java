package io.github.varun51.mcp.vector;

import static io.github.varun51.mcp.core.TkSchema.mtkArray;
import static io.github.varun51.mcp.core.TkSchema.mtkEnum;
import static io.github.varun51.mcp.core.TkSchema.mtkField;
import static io.github.varun51.mcp.core.TkSchema.mtkObject;
import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkArgs;
import io.github.varun51.mcp.core.TkServerLauncher;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.core.TkToolException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class TkVectorServer {

    static final int _TKMAX_COLLECTIONS = 100;
    static final int _TKMAX_BATCH = 10_000;
    static final int _TKMAX_K = 1_000;
    private static final Pattern _TKNAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Map<String, TkCollection> tkCollections = new ConcurrentHashMap<>();
    private final Path tkSnapshotDirectory;

    TkVectorServer(Path tkSnapshotDirectory) {
        this.tkSnapshotDirectory = tkSnapshotDirectory;
    }

    public static void main(String[] tkArgs) {
        String tkConfigured = System.getenv("TK_VECTOR_DIR");
        Path tkDirectory = tkConfigured != null ? Path.of(tkConfigured) : Path.of(System.getProperty("user.home"), ".mcp-library", "vectors");
        TkServerLauncher.mtkStartStdio("mcp-vector-search", "0.1.0", new TkVectorServer(tkDirectory).mtkTools());
    }

    List<TkTool> mtkTools() {
        Map<String, Object> tkName = mtkField("string", "Collection name: letters, digits, '_' or '-', at most 64 characters");
        Map<String, Object> tkItems = Map.of("type", "array", "description", "Items to insert or replace",
                "items", mtkObject(Map.of("id", mtkField("string", "Unique id"), "vector", mtkArray("number", "Embedding"),
                        "metadata", mtkField("object", "Flat key/value metadata")), "id", "vector"));
        return List.of(
                new TkTool("create_collection", "Create an empty vector collection",
                        mtkObject(Map.of("name", tkName, "dimension", mtkField("integer", "Vector dimension"), "metric", mtkEnum("Similarity metric", "cosine", "dot", "l2")),
                                "name", "dimension", "metric"),
                        this::mtkCreate),
                new TkTool("upsert", "Insert or replace vectors with optional metadata",
                        mtkObject(Map.of("collection", tkName, "items", tkItems), "collection", "items"),
                        this::mtkUpsert),
                new TkTool("search", "Exact top-k nearest neighbour search with optional metadata equality filter",
                        mtkObject(Map.of("collection", tkName, "vector", mtkArray("number", "Query embedding"), "k", mtkField("integer", "Results, default 10"),
                                "filter", mtkField("object", "Metadata values that must match exactly")), "collection", "vector"),
                        this::mtkSearch),
                new TkTool("delete", "Delete vectors by id",
                        mtkObject(Map.of("collection", tkName, "ids", mtkArray("string", "Ids to delete")), "collection", "ids"),
                        tkCall -> mtkResult("deleted", mtkCollection(tkCall.mtkString("collection")).mtkDelete(tkCall.mtkStringList("ids")))),
                new TkTool("drop_collection", "Remove a collection from memory",
                        mtkObject(Map.of("name", tkName), "name"),
                        tkCall -> mtkResult("dropped", tkCollections.remove(tkCall.mtkString("name")) != null)),
                new TkTool("list_collections", "List collections with dimension, metric and size",
                        mtkObject(Map.of()),
                        tkCall -> mtkResult("collections", tkCollections.entrySet().stream().sorted(Map.Entry.comparingByKey())
                                .map(tkEntry -> mtkResult("name", tkEntry.getKey(), "dimension", tkEntry.getValue().mtkDimension(),
                                        "metric", tkEntry.getValue().mtkMetric(), "count", tkEntry.getValue().mtkSize())).toList())),
                new TkTool("save_collection", "Persist a collection to the snapshot directory",
                        mtkObject(Map.of("name", tkName), "name"),
                        this::mtkSave),
                new TkTool("load_collection", "Load a collection from the snapshot directory, replacing any in memory",
                        mtkObject(Map.of("name", tkName), "name"),
                        this::mtkLoad));
    }

    private Object mtkCreate(TkArgs tkCall) {
        String tkName = mtkValidName(tkCall.mtkString("name"));
        TkToolException.mtkCheck(tkCollections.size() < _TKMAX_COLLECTIONS, "collection limit of " + _TKMAX_COLLECTIONS + " reached");
        TkCollection tkCreated = new TkCollection(tkCall.mtkInt("dimension"), tkCall.mtkString("metric"));
        TkToolException.mtkCheck(tkCollections.putIfAbsent(tkName, tkCreated) == null, "collection already exists: " + tkName);
        return mtkResult("name", tkName, "dimension", tkCreated.mtkDimension(), "metric", tkCreated.mtkMetric());
    }

    private Object mtkUpsert(TkArgs tkCall) {
        TkCollection tkCollection = mtkCollection(tkCall.mtkString("collection"));
        List<Object> tkItems = tkCall.mtkList("items");
        TkToolException.mtkCheck(tkItems.size() <= _TKMAX_BATCH, "batch too large, limit is " + _TKMAX_BATCH);
        List<TkCollection.TkEntry> tkEntries = tkItems.stream().map(tkItem -> {
            TkToolException.mtkCheck(tkItem instanceof Map<?, ?>, "each item must be an object");
            @SuppressWarnings("unchecked")
            TkArgs tkFields = new TkArgs((Map<String, Object>) tkItem);
            return new TkCollection.TkEntry(tkFields.mtkString("id"), mtkFloats(tkFields.mtkDoubleArray("vector")), tkFields.mtkMap("metadata", Map.of()));
        }).toList();
        tkCollection.mtkUpsert(tkEntries);
        return mtkResult("upserted", tkEntries.size(), "count", tkCollection.mtkSize());
    }

    private Object mtkSearch(TkArgs tkCall) {
        int tkK = tkCall.mtkInt("k", 10);
        TkToolException.mtkCheck(tkK >= 1 && tkK <= _TKMAX_K, "k must be between 1 and " + _TKMAX_K);
        TkCollection tkCollection = mtkCollection(tkCall.mtkString("collection"));
        List<Map<String, Object>> tkMatches = tkCollection.mtkSearch(mtkFloats(tkCall.mtkDoubleArray("vector")), tkK, tkCall.mtkMap("filter", Map.of())).stream()
                .map(tkMatch -> mtkResult("id", tkMatch.tkId(), "score", tkMatch.tkScore(), "metadata", tkMatch.tkMetadata()))
                .toList();
        return mtkResult("metric", tkCollection.mtkMetric(), "matches", tkMatches);
    }

    private Object mtkSave(TkArgs tkCall) {
        String tkName = mtkValidName(tkCall.mtkString("name"));
        TkCollection tkCollection = mtkCollection(tkName);
        List<Map<String, Object>> tkItems = tkCollection.mtkEntries().stream()
                .map(tkEntry -> mtkResult("id", tkEntry.tkId(), "vector", tkEntry.tkVector(), "metadata", tkEntry.tkMetadata()))
                .toList();
        try {
            Files.createDirectories(tkSnapshotDirectory);
            Files.write(mtkSnapshot(tkName), McpJsonDefaults.getMapper().writeValueAsBytes(
                    mtkResult("dimension", tkCollection.mtkDimension(), "metric", tkCollection.mtkMetric(), "items", tkItems)));
        } catch (IOException tkError) {
            throw new UncheckedIOException("cannot save collection " + tkName, tkError);
        }
        return mtkResult("name", tkName, "count", tkItems.size());
    }

    private Object mtkLoad(TkArgs tkCall) {
        String tkName = mtkValidName(tkCall.mtkString("name"));
        Path tkFile = mtkSnapshot(tkName);
        TkToolException.mtkCheck(Files.isRegularFile(tkFile), "no snapshot for collection " + tkName);
        Map<String, Object> tkSnapshot;
        try {
            tkSnapshot = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(tkFile), new TypeRef<>() {
            });
        } catch (IOException tkError) {
            throw new TkToolException("snapshot for " + tkName + " is not valid JSON", tkError);
        }
        TkArgs tkFields = new TkArgs(tkSnapshot);
        TkCollection tkLoaded = new TkCollection(tkFields.mtkInt("dimension"), tkFields.mtkString("metric"));
        tkCollections.put(tkName, tkLoaded);
        return mtkUpsert(new TkArgs(Map.of("collection", tkName, "items", tkFields.mtkList("items"))));
    }

    private TkCollection mtkCollection(String tkName) {
        TkCollection tkCollection = tkCollections.get(tkName);
        TkToolException.mtkCheck(tkCollection != null, "unknown collection: " + tkName);
        return tkCollection;
    }

    private Path mtkSnapshot(String tkName) {
        return tkSnapshotDirectory.resolve(tkName + ".json");
    }

    private static String mtkValidName(String tkName) {
        TkToolException.mtkCheck(_TKNAME.matcher(tkName).matches(), "invalid collection name: " + tkName);
        return tkName;
    }

    private static float[] mtkFloats(double[] tkValues) {
        float[] tkFloats = new float[tkValues.length];
        for (int tkIndex = 0; tkIndex < tkValues.length; tkIndex++) {
            tkFloats[tkIndex] = (float) tkValues[tkIndex];
        }
        return tkFloats;
    }
}
