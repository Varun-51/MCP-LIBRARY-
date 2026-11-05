package io.github.varun51.mcp.inspector;

import static io.github.varun51.mcp.core.TkSchema.mtkEnum;
import static io.github.varun51.mcp.core.TkSchema.mtkField;
import static io.github.varun51.mcp.core.TkSchema.mtkObject;
import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkArgs;
import io.github.varun51.mcp.core.TkServerLauncher;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.core.TkToolException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class TkInspectorServer {

    private static final double _TKGIB = 1024.0 * 1024 * 1024;
    private static final Map<String, Double> _TKDTYPE_BYTES = Map.of("fp32", 4.0, "fp16", 2.0, "bf16", 2.0, "fp8", 1.0, "int8", 1.0, "int4", 0.5);
    private static final Map<String, Integer> _TKOPTIMIZER_BYTES = Map.of("adamw", 12, "sgd_momentum", 8);

    private TkInspectorServer() {
    }

    public static void main(String[] tkArgs) {
        String tkConfigured = System.getenv("TK_MODEL_ROOTS");
        List<Path> tkRoots = tkConfigured != null
                ? Arrays.stream(tkConfigured.split(File.pathSeparator)).map(Path::of).toList()
                : List.of(Path.of(System.getProperty("user.home")));
        TkServerLauncher.mtkStartStdio("mcp-model-inspector", "0.1.0", mtkTools(tkRoots));
    }

    public static List<TkTool> mtkTools(List<Path> tkRoots) {
        Map<String, Object> tkDtype = mtkEnum("Numeric format", _TKDTYPE_BYTES.keySet().stream().sorted().toArray(String[]::new));
        return List.of(
                new TkTool("inspect_model_file", "Read a safetensors or GGUF header without loading weights: tensors, dtypes, parameter count, metadata",
                        mtkObject(Map.of("path", mtkField("string", "Model file inside an allowed root")), "path"),
                        tkCall -> mtkInspect(tkRoots, tkCall.mtkString("path"))),
                new TkTool("estimate_inference_memory", "Estimate inference memory: weights plus KV cache",
                        mtkObject(Map.of("parameters_billions", mtkField("number", "Model size in billions of parameters"), "weight_dtype", tkDtype,
                                "layers", mtkField("integer", "Transformer layers"), "kv_heads", mtkField("integer", "Key/value heads (GQA aware)"),
                                "head_dim", mtkField("integer", "Head dimension"), "context_length", mtkField("integer", "Tokens per sequence"),
                                "batch_size", mtkField("integer", "Concurrent sequences, default 1"), "kv_dtype", tkDtype),
                                "parameters_billions", "weight_dtype", "layers", "kv_heads", "head_dim", "context_length"),
                        TkInspectorServer::mtkInference),
                new TkTool("estimate_training_memory", "Estimate per-GPU training memory for mixed precision with ZeRO partitioning, excluding activations",
                        mtkObject(Map.of("parameters_billions", mtkField("number", "Model size in billions of parameters"),
                                "optimizer", mtkEnum("Optimizer", "adamw", "sgd_momentum"), "zero_stage", mtkField("integer", "ZeRO stage 0-3"),
                                "gpus", mtkField("integer", "Data parallel GPUs, default 1")), "parameters_billions", "optimizer", "zero_stage"),
                        TkInspectorServer::mtkTraining));
    }

    private static Object mtkInspect(List<Path> tkRoots, String tkPath) {
        try {
            Path tkFile = Path.of(tkPath).toAbsolutePath().normalize();
            TkToolException.mtkCheck(Files.isRegularFile(tkFile), "model file not found: " + tkPath);
            Path tkReal = tkFile.toRealPath();
            boolean tkAllowed = tkRoots.stream().anyMatch(tkRoot -> {
                try {
                    return tkReal.startsWith(tkRoot.toRealPath());
                } catch (IOException tkMissingRoot) {
                    return false;
                }
            });
            TkToolException.mtkCheck(tkAllowed, "path is outside the allowed model roots");
            return TkModelFiles.mtkInspect(tkReal);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | ArithmeticException tkMalformed) {
            throw new TkToolException("model header is malformed");
        } catch (IOException tkError) {
            throw new UncheckedIOException(tkError);
        }
    }

    private static Object mtkInference(TkArgs tkCall) {
        double tkParameters = mtkPositive(tkCall.mtkDouble("parameters_billions"), "parameters_billions") * 1e9;
        double tkWeights = tkParameters * mtkDtypeBytes(tkCall.mtkString("weight_dtype"));
        double tkKvCache = 2.0 * mtkPositive(tkCall.mtkInt("layers"), "layers") * mtkPositive(tkCall.mtkInt("kv_heads"), "kv_heads")
                * mtkPositive(tkCall.mtkInt("head_dim"), "head_dim") * mtkPositive(tkCall.mtkInt("context_length"), "context_length")
                * mtkPositive(tkCall.mtkInt("batch_size", 1), "batch_size") * mtkDtypeBytes(tkCall.mtkString("kv_dtype", "fp16"));
        return mtkResult("weights_gib", tkWeights / _TKGIB, "kv_cache_gib", tkKvCache / _TKGIB, "total_gib", (tkWeights + tkKvCache) / _TKGIB,
                "excludes", "activations, framework overhead and fragmentation");
    }

    private static Object mtkTraining(TkArgs tkCall) {
        double tkParameters = mtkPositive(tkCall.mtkDouble("parameters_billions"), "parameters_billions") * 1e9;
        Integer tkOptimizerBytes = _TKOPTIMIZER_BYTES.get(tkCall.mtkString("optimizer"));
        TkToolException.mtkCheck(tkOptimizerBytes != null, "optimizer must be one of " + _TKOPTIMIZER_BYTES.keySet());
        int tkStage = tkCall.mtkInt("zero_stage");
        TkToolException.mtkCheck(tkStage >= 0 && tkStage <= 3, "zero_stage must be between 0 and 3");
        double tkGpus = mtkPositive(tkCall.mtkInt("gpus", 1), "gpus");
        double tkWeights = 2 * tkParameters / (tkStage >= 3 ? tkGpus : 1);
        double tkGradients = 2 * tkParameters / (tkStage >= 2 ? tkGpus : 1);
        double tkOptimizer = tkOptimizerBytes * tkParameters / (tkStage >= 1 ? tkGpus : 1);
        return mtkResult("weights_gib", tkWeights / _TKGIB, "gradients_gib", tkGradients / _TKGIB, "optimizer_gib", tkOptimizer / _TKGIB,
                "per_gpu_total_gib", (tkWeights + tkGradients + tkOptimizer) / _TKGIB, "excludes", "activations, communication buffers and framework overhead");
    }

    private static double mtkDtypeBytes(String tkDtype) {
        Double tkBytes = _TKDTYPE_BYTES.get(tkDtype);
        TkToolException.mtkCheck(tkBytes != null, "dtype must be one of " + _TKDTYPE_BYTES.keySet().stream().sorted().toList());
        return tkBytes;
    }

    private static double mtkPositive(double tkValue, String tkName) {
        TkToolException.mtkCheck(tkValue > 0 && Double.isFinite(tkValue), tkName + " must be positive");
        return tkValue;
    }
}
