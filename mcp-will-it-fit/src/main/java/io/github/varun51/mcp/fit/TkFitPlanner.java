package io.github.varun51.mcp.fit;

import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkToolException;
import io.github.varun51.mcp.fit.TkHubClient.TkHubFile;
import io.github.varun51.mcp.inspector.TkModelFiles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TkFitPlanner {

    static final double _TKGIB = 1024.0 * 1024 * 1024;
    static final int _TKMAX_SHARDS = 200;
    static final Map<String, Double> _TKKV_BYTES = Map.of("fp16", 2.0, "q8_0", 34.0 / 32, "q4_0", 18.0 / 32);
    private static final Pattern _TKSHARD = Pattern.compile("-\\d{5}-of-\\d{5}(?=\\.(gguf|safetensors)$)");
    private static final Pattern _TKQUANT = Pattern.compile("(?i)(IQ\\d_[A-Z]+|Q\\d_K_[SML]|Q\\d_K|Q\\d_[01]|BF16|F16|F32)");

    private final TkHubClient tkHub;

    public TkFitPlanner(TkHubClient tkHub) {
        this.tkHub = tkHub;
    }

    public Map<String, Object> mtkPlan(String tkRepo, String tkRevision, double tkVramGib, Integer tkContext, int tkBatch, String tkKvDtype, double tkOverheadGib) {
        TkHubClient.mtkValidate(tkRepo, tkRevision);
        Double tkKvBytes = _TKKV_BYTES.get(tkKvDtype);
        TkToolException.mtkCheck(tkKvBytes != null, "kv_cache_dtype must be one of " + _TKKV_BYTES.keySet().stream().sorted().toList());
        TkToolException.mtkCheck(tkVramGib > 0 && tkBatch >= 1 && tkOverheadGib >= 0, "vram_gb and batch_size must be positive and overhead_gb non-negative");
        List<TkHubFile> tkFiles = tkHub.mtkFiles(tkRepo, tkRevision);
        List<TkVariant> tkVariants = new ArrayList<>(mtkSafetensorsVariant(tkRepo, tkRevision, tkFiles).stream().toList());
        tkVariants.addAll(mtkGgufVariants(tkFiles));
        TkToolException.mtkCheck(!tkVariants.isEmpty(), "no safetensors or GGUF weights found in " + tkRepo);
        TkArchitecture tkArchitecture = mtkArchitecture(tkRepo, tkRevision, tkFiles);
        long tkUsedContext = tkContext != null ? tkContext : Math.min(tkArchitecture.tkMaxContext(), 8192);
        TkToolException.mtkCheck(tkUsedContext >= 1, "context_length must be positive");
        double tkKvPerToken = 2.0 * tkArchitecture.tkLayers() * tkArchitecture.tkKvHeads() * tkArchitecture.tkHeadDim() * tkKvBytes;
        double tkVramBytes = tkVramGib * _TKGIB;
        double tkOverheadBytes = tkOverheadGib * _TKGIB;
        tkVariants.sort(Comparator.comparingDouble(TkVariant::tkWeightBytes).reversed());
        List<Map<String, Object>> tkRows = new ArrayList<>();
        Map<String, Object> tkBest = null;
        for (TkVariant tkVariant : tkVariants) {
            double tkKv = tkKvPerToken * tkUsedContext * tkBatch;
            double tkTotal = tkVariant.tkWeightBytes() + tkKv + tkOverheadBytes;
            long tkMaxContext = (long) Math.max(0, Math.min(tkArchitecture.tkMaxContext(), Math.floor((tkVramBytes - tkVariant.tkWeightBytes() - tkOverheadBytes) / (tkKvPerToken * tkBatch))));
            Map<String, Object> tkRow = mtkResult("variant", tkVariant.tkName(), "format", tkVariant.tkFormat(), "weights_gib", mtkRound(tkVariant.tkWeightBytes() / _TKGIB),
                    "kv_cache_gib", mtkRound(tkKv / _TKGIB), "total_gib", mtkRound(tkTotal / _TKGIB), "fits", tkTotal <= tkVramBytes, "max_context", tkMaxContext);
            tkRows.add(tkRow);
            if (tkBest == null && tkTotal <= tkVramBytes) {
                tkBest = tkRow;
            }
        }
        String tkVerdict = tkBest != null
                ? "Best fit: " + tkBest.get("variant") + " uses " + tkBest.get("total_gib") + " of " + mtkRound(tkVramGib) + " GiB at " + tkUsedContext + " tokens"
                : "Nothing fits in " + mtkRound(tkVramGib) + " GiB at " + tkUsedContext + " tokens; the smallest variant needs " + tkRows.get(tkRows.size() - 1).get("total_gib") + " GiB";
        return mtkResult("model", tkRepo, "revision", tkRevision, "verdict", tkVerdict, "best_fit", tkBest == null ? null : tkBest.get("variant"),
                "vram_gib", mtkRound(tkVramGib), "context_length", tkUsedContext, "batch_size", tkBatch, "kv_cache_dtype", tkKvDtype, "overhead_gib", tkOverheadGib,
                "architecture", mtkResult("layers", tkArchitecture.tkLayers(), "kv_heads", tkArchitecture.tkKvHeads(), "head_dim", tkArchitecture.tkHeadDim(),
                        "max_context", tkArchitecture.tkMaxContext(), "source", tkArchitecture.tkSource()),
                "variants", tkRows,
                "assumptions", "full-attention KV cache (sliding-window and latent-attention models need less), activations folded into overhead");
    }

    private Optional<TkVariant> mtkSafetensorsVariant(String tkRepo, String tkRevision, List<TkHubFile> tkFiles) {
        List<TkHubFile> tkRoot = tkFiles.stream().filter(tkFile -> !tkFile.tkPath().contains("/") && tkFile.tkPath().endsWith(".safetensors")).toList();
        List<TkHubFile> tkShards = tkRoot.stream().anyMatch(tkFile -> tkFile.tkPath().startsWith("model"))
                ? tkRoot.stream().filter(tkFile -> tkFile.tkPath().startsWith("model")).toList()
                : tkRoot;
        if (tkShards.isEmpty()) {
            return Optional.empty();
        }
        TkToolException.mtkCheck(tkShards.size() <= _TKMAX_SHARDS, "too many safetensors shards: " + tkShards.size());
        Map<String, Long> tkByDtype = new LinkedHashMap<>();
        for (TkHubFile tkShard : tkShards) {
            Map<?, ?> tkCounts = (Map<?, ?>) tkHub.mtkHeader(tkRepo, tkRevision, tkShard).get("parameters_by_dtype");
            tkCounts.forEach((tkDtype, tkCount) -> tkByDtype.merge((String) tkDtype, ((Number) tkCount).longValue(), Long::sum));
        }
        double tkBytes = tkByDtype.entrySet().stream().mapToDouble(tkEntry -> (double) tkEntry.getValue() * TkModelFiles._TKSAFETENSORS_BYTES.get(tkEntry.getKey())).sum();
        String tkDominant = tkByDtype.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return Optional.of(new TkVariant("safetensors " + tkDominant, "safetensors", tkBytes));
    }

    private static List<TkVariant> mtkGgufVariants(List<TkHubFile> tkFiles) {
        Map<String, Double> tkGroups = new LinkedHashMap<>();
        for (TkHubFile tkFile : tkFiles) {
            String tkName = tkFile.tkPath().substring(tkFile.tkPath().lastIndexOf('/') + 1);
            if (tkName.endsWith(".gguf") && !tkName.toLowerCase(Locale.ROOT).contains("mmproj")) {
                Matcher tkQuant = _TKQUANT.matcher(tkName);
                String tkLabel = tkQuant.find() ? tkQuant.group(1).toUpperCase(Locale.ROOT) : _TKSHARD.matcher(tkName).replaceAll("");
                tkGroups.merge(tkLabel, (double) tkFile.tkSize(), Double::sum);
            }
        }
        return tkGroups.entrySet().stream().map(tkGroup -> new TkVariant(tkGroup.getKey(), "gguf", tkGroup.getValue())).toList();
    }

    private TkArchitecture mtkArchitecture(String tkRepo, String tkRevision, List<TkHubFile> tkFiles) {
        Optional<Map<String, Object>> tkConfig = tkHub.mtkConfig(tkRepo, tkRevision);
        if (tkConfig.isPresent()) {
            Map<?, ?> tkText = tkConfig.get().get("text_config") instanceof Map<?, ?> tkNested && tkNested.containsKey("num_hidden_layers") ? tkNested : tkConfig.get();
            long tkHeads = mtkLong(tkText, "num_attention_heads", "n_head");
            long tkHeadDim = tkText.get("head_dim") instanceof Number tkExplicit ? tkExplicit.longValue() : mtkLong(tkText, "hidden_size", "n_embd") / tkHeads;
            long tkKvHeads = tkText.get("num_key_value_heads") instanceof Number tkKv ? tkKv.longValue() : tkHeads;
            long tkMax = tkText.get("max_position_embeddings") instanceof Number tkPositions ? tkPositions.longValue() : 4096;
            return new TkArchitecture(mtkLong(tkText, "num_hidden_layers", "n_layer"), tkKvHeads, tkHeadDim, tkMax, "config.json");
        }
        TkHubFile tkGguf = tkFiles.stream().filter(tkFile -> tkFile.tkPath().endsWith(".gguf") && !tkFile.tkPath().toLowerCase(Locale.ROOT).contains("mmproj"))
                .min(Comparator.comparingLong(TkHubFile::tkSize))
                .orElseThrow(() -> new TkToolException("no config.json or GGUF metadata to read the architecture from"));
        Map<?, ?> tkMetadata = (Map<?, ?>) tkHub.mtkHeader(tkRepo, tkRevision, tkGguf).get("metadata");
        String tkArch = String.valueOf(tkMetadata.get("general.architecture"));
        long tkHeads = mtkLong(tkMetadata, tkArch + ".attention.head_count");
        long tkHeadDim = tkMetadata.get(tkArch + ".attention.key_length") instanceof Number tkKey ? tkKey.longValue() : mtkLong(tkMetadata, tkArch + ".embedding_length") / tkHeads;
        long tkKvHeads = tkMetadata.get(tkArch + ".attention.head_count_kv") instanceof Number tkKv ? tkKv.longValue() : tkHeads;
        return new TkArchitecture(mtkLong(tkMetadata, tkArch + ".block_count"), tkKvHeads, tkHeadDim, mtkLong(tkMetadata, tkArch + ".context_length"), "gguf:" + tkGguf.tkPath());
    }

    private static long mtkLong(Map<?, ?> tkValues, String... tkKeys) {
        for (String tkKey : tkKeys) {
            if (tkValues.get(tkKey) instanceof Number tkNumber) {
                TkToolException.mtkCheck(tkNumber.longValue() > 0, tkKey + " must be positive");
                return tkNumber.longValue();
            }
        }
        throw new TkToolException("model architecture is missing " + String.join(" or ", tkKeys));
    }

    private static double mtkRound(double tkValue) {
        return Math.round(tkValue * 100) / 100.0;
    }

    record TkVariant(String tkName, String tkFormat, double tkWeightBytes) {
    }

    record TkArchitecture(long tkLayers, long tkKvHeads, long tkHeadDim, long tkMaxContext, String tkSource) {
    }
}
