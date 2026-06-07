package com.samsung.camera.intelligence.config;

import java.util.Arrays;
import java.util.List;

/**
 * Definition of a tool parameter.
 * Ported from Python ToolParameter dataclass in config/tool_mappings.py.
 */
public class ToolParameter {

    private final String name;
    private final String paramType; // "int", "float", "str", "bool", "enum"
    private final boolean required;
    private final Object defaultValue;
    private final Float minValue;
    private final Float maxValue;
    private final List<String> enumValues;
    private final String description;

    public ToolParameter(String name, String paramType, boolean required,
                         Object defaultValue, Float minValue, Float maxValue,
                         List<String> enumValues, String description) {
        this.name = name;
        this.paramType = paramType;
        this.required = required;
        this.defaultValue = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.enumValues = enumValues != null ? enumValues : Arrays.asList();
        this.description = description != null ? description : "";
    }

    /** Convenience: required parameter with enum values. */
    public static ToolParameter enumParam(String name, List<String> enumValues, String description) {
        return new ToolParameter(name, "enum", true, null, null, null, enumValues, description);
    }

    /** Convenience: required int parameter with range. */
    public static ToolParameter intParam(String name, float min, float max, String description) {
        return new ToolParameter(name, "int", true, null, min, max, null, description);
    }

    /** Convenience: optional int parameter with default. */
    public static ToolParameter optionalIntParam(String name, int defaultVal, float min, float max, String description) {
        return new ToolParameter(name, "int", false, defaultVal, min, max, null, description);
    }

    /** Convenience: required float parameter with range. */
    public static ToolParameter floatParam(String name, float min, float max, float defaultVal, String description) {
        return new ToolParameter(name, "float", true, defaultVal, min, max, null, description);
    }

    /** Convenience: optional float parameter. */
    public static ToolParameter optionalFloatParam(String name, float defaultVal, float min, float max, String description) {
        return new ToolParameter(name, "float", false, defaultVal, min, max, null, description);
    }

    /** Convenience: required bool parameter. */
    public static ToolParameter boolParam(String name, boolean defaultVal, String description) {
        return new ToolParameter(name, "bool", true, defaultVal, null, null, null, description);
    }

    /** Convenience: required str parameter. */
    public static ToolParameter strParam(String name, String description) {
        return new ToolParameter(name, "str", true, null, null, null, null, description);
    }

    // Getters
    public String getName() { return name; }
    public String getParamType() { return paramType; }
    public boolean isRequired() { return required; }
    public Object getDefaultValue() { return defaultValue; }
    public Float getMinValue() { return minValue; }
    public Float getMaxValue() { return maxValue; }
    public List<String> getEnumValues() { return enumValues; }
    public String getDescription() { return description; }
}
