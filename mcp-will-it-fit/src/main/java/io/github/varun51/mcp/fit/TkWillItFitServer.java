package io.github.varun51.mcp.fit;

import static io.github.varun51.mcp.core.TkSchema.mtkEnum;
import static io.github.varun51.mcp.core.TkSchema.mtkField;
import static io.github.varun51.mcp.core.TkSchema.mtkObject;
import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkServerLauncher;
import io.github.varun51.mcp.core.TkTool;
import io.github.varun51.mcp.core.TkToolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class TkWillItFitServer {

    private TkWillItFitServer() {
    }

    public static void main(String[] tkArgs) {
        TkServerLauncher.mtkStartStdio("mcp-will-it-fit", "0.1.0", mtkTools(new TkFitPlanner(TkHubClient.mtkFromEnvironment()), TkWillItFitServer::mtkDetectGpu));
    }

    public static List<TkTool> mtkTools(TkFitPlanner tkPlanner, Supplier<Optional<TkGpu>> tkDetector) {
        return List.of(
                new TkTool("will_it_fit",
                        "Check whether a Hugging Face model fits in GPU memory, for every available precision and GGUF quantization, using the real tensor headers without downloading weights",
                        mtkObject(Map.of(
                                "model", mtkField("string", "Hugging Face repo id, e.g. Qwen/Qwen2.5-7B-Instruct or bartowski/Qwen2.5-7B-Instruct-GGUF"),
                                "vram_gb", mtkField("number", "GPU memory in GiB; detected automatically when omitted"),
                                "context_length", mtkField("integer", "Tokens per sequence, default min(model maximum, 8192)"),
                                "batch_size", mtkField("integer", "Concurrent sequences, default 1"),
                                "kv_cache_dtype", mtkEnum("KV cache precision, default fp16", "fp16", "q8_0", "q4_0"),
                                "overhead_gb", mtkField("number", "Runtime overhead in GiB (CUDA context, activations), default 1.0"),
                                "revision", mtkField("string", "Branch, tag or commit, default main")), "model"),
                        tkCall -> {
                            double tkVram = tkCall.mtkHas("vram_gb") ? tkCall.mtkDouble("vram_gb")
                                    : tkDetector.get().map(TkGpu::tkVramGib)
                                    .orElseThrow(() -> new TkToolException("no GPU detected: pass vram_gb, for example 24 for an RTX 4090"));
                            return tkPlanner.mtkPlan(tkCall.mtkString("model"), tkCall.mtkString("revision", "main"), tkVram,
                                    tkCall.mtkHas("context_length") ? tkCall.mtkInt("context_length") : null, tkCall.mtkInt("batch_size", 1),
                                    tkCall.mtkString("kv_cache_dtype", "fp16"), tkCall.mtkDouble("overhead_gb", 1.0));
                        }),
                new TkTool("detect_gpu", "Detect local GPU memory through nvidia-smi, or unified memory on Apple Silicon",
                        mtkObject(Map.of()),
                        tkCall -> tkDetector.get()
                                .map(tkGpu -> mtkResult("detected", true, "name", tkGpu.tkName(), "vram_gib", tkGpu.tkVramGib(), "source", tkGpu.tkSource()))
                                .orElse(mtkResult("detected", false, "name", null, "vram_gib", null, "source", null))));
    }

    static Optional<TkGpu> mtkDetectGpu() {
        Optional<String> tkNvidia = mtkRun("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits");
        if (tkNvidia.isPresent()) {
            return mtkParseNvidia(tkNvidia.get());
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return mtkRun("sysctl", "-n", "hw.memsize").map(tkBytes -> new TkGpu("Apple unified memory",
                    Math.round(Long.parseLong(tkBytes.strip()) * 0.75 / TkFitPlanner._TKGIB * 100) / 100.0, "sysctl hw.memsize x 0.75"));
        }
        return Optional.empty();
    }

    static Optional<TkGpu> mtkParseNvidia(String tkOutput) {
        List<String[]> tkGpus = tkOutput.lines().filter(tkLine -> tkLine.contains(",")).map(tkLine -> tkLine.split(",")).toList();
        if (tkGpus.isEmpty()) {
            return Optional.empty();
        }
        double tkMib = tkGpus.stream().mapToDouble(tkGpu -> Double.parseDouble(tkGpu[1].strip())).sum();
        String tkName = tkGpus.size() == 1 ? tkGpus.get(0)[0].strip() : tkGpus.size() + " x " + tkGpus.get(0)[0].strip();
        return Optional.of(new TkGpu(tkName, Math.round(tkMib / 1024 * 100) / 100.0, "nvidia-smi"));
    }

    private static Optional<String> mtkRun(String... tkCommand) {
        try {
            Process tkProcess = new ProcessBuilder(tkCommand).redirectErrorStream(true).start();
            String tkOutput = new String(tkProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return tkProcess.waitFor(5, TimeUnit.SECONDS) && tkProcess.exitValue() == 0 ? Optional.of(tkOutput) : Optional.empty();
        } catch (IOException tkNotInstalled) {
            return Optional.empty();
        } catch (InterruptedException tkInterrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    record TkGpu(String tkName, double tkVramGib, String tkSource) {
    }
}
