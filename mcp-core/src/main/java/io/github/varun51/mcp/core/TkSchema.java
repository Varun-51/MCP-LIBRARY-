package io.github.varun51.mcp.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TkSchema {

    private TkSchema() {
    }

    public static Map<String, Object> mtkObject(Map<String, Object> tkProperties, String... tkRequired) {
        return Map.of("type", "object", "properties", new LinkedHashMap<>(tkProperties), "required", List.of(tkRequired));
    }

    public static Map<String, Object> mtkField(String tkType, String tkDescription) {
        return Map.of("type", tkType, "description", tkDescription);
    }

    public static Map<String, Object> mtkArray(String tkItemType, String tkDescription) {
        return Map.of("type", "array", "items", Map.of("type", tkItemType), "description", tkDescription);
    }

    public static Map<String, Object> mtkEnum(String tkDescription, String... tkValues) {
        return Map.of("type", "string", "enum", List.of(tkValues), "description", tkDescription);
    }
}
