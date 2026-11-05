package io.github.varun51.mcp.core;

import java.util.List;
import java.util.Map;

public record TkArgs(Map<String, Object> tkValues) {

    public TkArgs {
        tkValues = tkValues == null ? Map.of() : tkValues;
    }

    public boolean mtkHas(String tkName) {
        return tkValues.get(tkName) != null;
    }

    public String mtkString(String tkName) {
        return mtkTyped(tkName, String.class);
    }

    public String mtkString(String tkName, String tkDefault) {
        return mtkHas(tkName) ? mtkString(tkName) : tkDefault;
    }

    public boolean mtkBoolean(String tkName, boolean tkDefault) {
        return mtkHas(tkName) ? mtkTyped(tkName, Boolean.class) : tkDefault;
    }

    public double mtkDouble(String tkName) {
        return mtkTyped(tkName, Number.class).doubleValue();
    }

    public double mtkDouble(String tkName, double tkDefault) {
        return mtkHas(tkName) ? mtkDouble(tkName) : tkDefault;
    }

    public int mtkInt(String tkName) {
        return mtkToInt(tkName, mtkTyped(tkName, Number.class));
    }

    public int mtkInt(String tkName, int tkDefault) {
        return mtkHas(tkName) ? mtkInt(tkName) : tkDefault;
    }

    public Map<String, Object> mtkMap(String tkName) {
        Map<?, ?> tkMap = mtkTyped(tkName, Map.class);
        tkMap.keySet().forEach(tkKey -> TkToolException.mtkCheck(tkKey instanceof String, tkName + " must have string keys"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tkResult = (Map<String, Object>) tkMap;
        return tkResult;
    }

    public Map<String, Object> mtkMap(String tkName, Map<String, Object> tkDefault) {
        return mtkHas(tkName) ? mtkMap(tkName) : tkDefault;
    }

    public List<String> mtkStringList(String tkName) {
        return mtkList(tkName).stream().map(tkItem -> mtkCast(tkName, tkItem, String.class)).toList();
    }

    public List<Integer> mtkIntList(String tkName) {
        return mtkList(tkName).stream().map(tkItem -> mtkToInt(tkName, mtkCast(tkName, tkItem, Number.class))).toList();
    }

    public double[] mtkDoubleArray(String tkName) {
        return mtkList(tkName).stream().mapToDouble(tkItem -> mtkCast(tkName, tkItem, Number.class).doubleValue()).toArray();
    }

    public List<Object> mtkList(String tkName) {
        List<?> tkList = mtkTyped(tkName, List.class);
        return List.copyOf(tkList);
    }

    private <T> T mtkTyped(String tkName, Class<T> tkType) {
        Object tkValue = tkValues.get(tkName);
        TkToolException.mtkCheck(tkValue != null, "missing argument: " + tkName);
        return mtkCast(tkName, tkValue, tkType);
    }

    private static <T> T mtkCast(String tkName, Object tkValue, Class<T> tkType) {
        TkToolException.mtkCheck(tkType.isInstance(tkValue), tkName + " must be " + tkType.getSimpleName().toLowerCase());
        return tkType.cast(tkValue);
    }

    private static int mtkToInt(String tkName, Number tkNumber) {
        double tkDouble = tkNumber.doubleValue();
        TkToolException.mtkCheck(tkDouble == Math.rint(tkDouble) && Math.abs(tkDouble) <= Integer.MAX_VALUE, tkName + " must be an integer");
        return (int) tkDouble;
    }
}
