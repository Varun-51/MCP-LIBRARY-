package io.github.varun51.mcp.quantization;

import io.github.varun51.mcp.core.TkToolException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TkFormats {

    public static final Map<String, Double> _TKMAX_FINITE = mtkMaxFinite();

    private TkFormats() {
    }

    public static float mtkRoundTrip(String tkFormat, float tkValue) {
        return switch (tkFormat) {
            case "fp16" -> Float.float16ToFloat(Float.floatToFloat16(tkValue));
            case "bf16" -> mtkBfloat16(tkValue);
            case "fp8_e4m3" -> mtkMinifloat(tkValue, 3, -6, 448, Float.NaN);
            case "fp8_e5m2" -> mtkMinifloat(tkValue, 2, -14, 57344, Float.POSITIVE_INFINITY);
            case "int8" -> (float) Math.rint(tkValue);
            default -> throw new TkToolException("unknown format: " + tkFormat);
        };
    }

    public static double mtkMaxFinite(String tkFormat) {
        Double tkMax = _TKMAX_FINITE.get(tkFormat);
        TkToolException.mtkCheck(tkMax != null, "unknown format: " + tkFormat + ", expected one of " + _TKMAX_FINITE.keySet());
        return tkMax;
    }

    static float mtkBfloat16(float tkValue) {
        if (Float.isNaN(tkValue)) {
            return tkValue;
        }
        int tkBits = Float.floatToRawIntBits(tkValue);
        return Float.intBitsToFloat((tkBits + 0x7FFF + ((tkBits >>> 16) & 1)) & 0xFFFF0000);
    }

    static float mtkMinifloat(float tkValue, int tkMantissaBits, int tkMinExponent, double tkMax, float tkOverflow) {
        if (Float.isNaN(tkValue)) {
            return tkValue;
        }
        double tkMagnitude = Math.abs((double) tkValue);
        double tkQuantum = Math.scalb(1.0, Math.max(Math.getExponent(tkMagnitude), tkMinExponent) - tkMantissaBits);
        double tkRounded = Math.rint(tkMagnitude / tkQuantum) * tkQuantum;
        return Math.copySign(tkRounded > tkMax ? tkOverflow : (float) tkRounded, tkValue);
    }

    private static Map<String, Double> mtkMaxFinite() {
        Map<String, Double> tkMax = new LinkedHashMap<>();
        tkMax.put("fp16", 65504.0);
        tkMax.put("bf16", (double) Float.intBitsToFloat(0x7F7F0000));
        tkMax.put("fp8_e4m3", 448.0);
        tkMax.put("fp8_e5m2", 57344.0);
        tkMax.put("int8", 127.0);
        return Collections.unmodifiableMap(tkMax);
    }
}
