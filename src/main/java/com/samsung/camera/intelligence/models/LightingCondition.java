package com.samsung.camera.intelligence.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Lighting conditions for exposure optimization.
 * Matches the Python LightingCondition enum in models/enums.py.
 */
public enum LightingCondition {
    VERY_LOW_LIGHT("very_low_light"),
    LOW_LIGHT("low_light"),
    INDOOR("indoor"),
    CLOUDY("cloudy"),
    NORMAL("normal"),
    BRIGHT("bright"),
    VERY_BRIGHT("very_bright"),
    BACKLIT("backlit"),
    MIXED("mixed"),
    ARTIFICIAL("artificial"),
    GOLDEN_HOUR("golden_hour"),
    BLUE_HOUR("blue_hour");

    private final String value;
    private static final Map<String, LightingCondition> VALUE_MAP = new HashMap<>();

    static {
        for (LightingCondition lc : values()) {
            VALUE_MAP.put(lc.value, lc);
        }
    }

    LightingCondition(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static LightingCondition fromValue(String value) {
        LightingCondition result = VALUE_MAP.get(value);
        return result != null ? result : NORMAL;
    }
}
