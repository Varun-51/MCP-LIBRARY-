package io.github.varun51.mcp.quantization;

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

public final class TkQuantizationServer {

    static final int _TKMAX_VALUES = 1_000_000;

    private TkQuantizationServer() {
    }

    public static void main(String[] tkArgs) {
        TkServerLauncher.mtkStartStdio("mcp-quantization", "0.1.0", mtkTools());
    }

    public static List<TkTool> mtkTools() {
        String[] tkFormats = TkFormats._TKMAX_FINITE.keySet().toArray(String[]::new);
        return List.of(new TkTool("analyze_quantization",
                "Simulate casting a float32 tensor to fp16, bf16, fp8 (OCP E4M3/E5M2) or symmetric int8 and report the error: max_abs_error, mse, snr_db, "
                        + "overflow and underflow-to-zero counts. fp8 and int8 use amax scaling per tensor or per block and saturate; fp16 and bf16 are cast unscaled. "
                        + "Non-finite metrics are null: snr_db for an exact reconstruction, errors when fp16 or bf16 overflow to infinity",
                mtkObject(Map.of("values", mtkArray("number", "Tensor values, rounded to float32 first, at most " + _TKMAX_VALUES),
                        "formats", Map.of("type", "array", "items", mtkEnum("Format", tkFormats), "description", "Formats to simulate, default all"),
                        "block_size", mtkField("integer", "Elements per scale for fp8 and int8, default one scale for the whole tensor"),
                        "power_of_two_scales", mtkField("boolean", "Round each scale amax/format_max up to a power of two, i.e. the multiplier format_max/amax down, so nothing saturates; default false")),
                        "values"),
                TkQuantizationServer::mtkAnalyze));
    }

    private static Object mtkAnalyze(TkArgs tkCall) {
        double[] tkInput = tkCall.mtkDoubleArray("values");
        TkToolException.mtkCheck(tkInput.length > 0, "values must not be empty");
        TkToolException.mtkCheck(tkInput.length <= _TKMAX_VALUES, "too many values, limit is " + _TKMAX_VALUES);
        float[] tkValues = new float[tkInput.length];
        for (int tkIndex = 0; tkIndex < tkInput.length; tkIndex++) {
            tkValues[tkIndex] = (float) tkInput[tkIndex];
            TkToolException.mtkCheck(Float.isFinite(tkValues[tkIndex]), "values must be finite and within float32 range");
        }
        List<String> tkFormats = tkCall.mtkHas("formats") ? tkCall.mtkStringList("formats").stream().distinct().toList() : List.copyOf(TkFormats._TKMAX_FINITE.keySet());
        TkToolException.mtkCheck(!tkFormats.isEmpty(), "formats must not be empty");
        tkFormats.forEach(TkFormats::mtkMaxFinite);
        int tkBlockSize = tkCall.mtkInt("block_size", tkValues.length);
        TkToolException.mtkCheck(tkBlockSize >= 1 && tkBlockSize <= _TKMAX_VALUES, "block_size must be between 1 and " + _TKMAX_VALUES);
        boolean tkPowerOfTwo = tkCall.mtkBoolean("power_of_two_scales", false);
        return mtkResult("count", tkValues.length, "block_size", tkCall.mtkHas("block_size") ? tkBlockSize : null, "power_of_two_scales", tkPowerOfTwo,
                "formats", tkFormats.stream().map(tkFormat -> mtkSimulate(tkValues, tkFormat, tkBlockSize, tkPowerOfTwo)).toList());
    }

    static Map<String, Object> mtkSimulate(float[] tkValues, String tkFormat, int tkBlockSize, boolean tkPowerOfTwo) {
        double tkMax = TkFormats.mtkMaxFinite(tkFormat);
        boolean tkScaled = tkFormat.startsWith("fp8") || tkFormat.equals("int8");
        int tkBlock = tkScaled ? tkBlockSize : tkValues.length;
        double tkSignal = 0;
        double tkNoise = 0;
        double tkMaxError = 0;
        double tkScaleMin = Double.POSITIVE_INFINITY;
        double tkScaleMax = 0;
        int tkOverflows = 0;
        int tkUnderflows = 0;
        for (int tkStart = 0; tkStart < tkValues.length; tkStart += tkBlock) {
            int tkEnd = Math.min(tkValues.length, tkStart + tkBlock);
            double tkScale = tkScaled ? mtkScale(tkValues, tkStart, tkEnd, tkMax, tkPowerOfTwo) : 1;
            tkScaleMin = Math.min(tkScaleMin, tkScale);
            tkScaleMax = Math.max(tkScaleMax, tkScale);
            for (int tkIndex = tkStart; tkIndex < tkEnd; tkIndex++) {
                double tkValue = tkValues[tkIndex];
                float tkScaledValue = (float) (tkValue / tkScale);
                double tkQuantized = TkFormats.mtkRoundTrip(tkFormat, tkScaledValue);
                if (!(Math.abs(tkQuantized) <= tkMax)) {
                    tkOverflows++;
                    tkQuantized = tkScaled ? Math.copySign(tkMax, tkScaledValue) : tkQuantized;
                }
                tkUnderflows += tkQuantized == 0 && tkValue != 0 ? 1 : 0;
                double tkError = tkQuantized * tkScale - tkValue;
                tkSignal += tkValue * tkValue;
                tkNoise += tkError * tkError;
                tkMaxError = Math.max(tkMaxError, Math.abs(tkError));
            }
        }
        int tkScaleCount = (tkValues.length + tkBlock - 1) / tkBlock;
        Map<String, Object> tkScales = tkScaled ? mtkResult("granularity", tkScaleCount > 1 ? "per_block" : "per_tensor",
                "count", tkScaleCount, "min", tkScaleMin, "max", tkScaleMax, "power_of_two", tkPowerOfTwo) : null;
        return mtkResult("format", tkFormat, "max_finite", tkMax, "max_abs_error", mtkFinite(tkMaxError), "mse", mtkFinite(tkNoise / tkValues.length),
                "snr_db", mtkFinite(10 * Math.log10(tkSignal / tkNoise)), "overflow_count", tkOverflows, "underflow_to_zero_count", tkUnderflows,
                "scales", tkScales);
    }

    private static double mtkScale(float[] tkValues, int tkStart, int tkEnd, double tkMax, boolean tkPowerOfTwo) {
        double tkAmax = 0;
        for (int tkIndex = tkStart; tkIndex < tkEnd; tkIndex++) {
            tkAmax = Math.max(tkAmax, Math.abs(tkValues[tkIndex]));
        }
        double tkScale = tkAmax > 0 ? tkAmax / tkMax : 1;
        if (!tkPowerOfTwo) {
            return tkScale;
        }
        double tkFloor = Math.scalb(1.0, Math.getExponent(tkScale));
        return tkFloor == tkScale ? tkScale : 2 * tkFloor;
    }

    private static Double mtkFinite(double tkValue) {
        return Double.isFinite(tkValue) ? tkValue : null;
    }
}
