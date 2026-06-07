package com.samsung.camera.intelligence.models;

import java.util.HashMap;
import java.util.Map;

/**
 * All 27 scene categories for scene classification.
 * Matches the Python SceneType enum in models/enums.py.
 */
public enum SceneType {
    PORTRAIT("portrait"),
    GROUP_PORTRAIT("group_portrait"),
    SELFIE("selfie"),
    LANDSCAPE("landscape"),
    CITYSCAPE("cityscape"),
    ARCHITECTURE("architecture"),
    FOOD("food"),
    PRODUCT("product"),
    DOCUMENT("document"),
    PET("pet"),
    WILDLIFE("wildlife"),
    MACRO("macro"),
    FLOWER("flower"),
    NIGHT("night"),
    SUNSET_SUNRISE("sunset_sunrise"),
    NIGHT_SKY("night_sky"),
    NIGHT_PORTRAIT("night_portrait"),
    NIGHT_CITYSCAPE("night_cityscape"),
    BACKLIT_PORTRAIT("backlit_portrait"),
    FAST_MOVING("fast_moving"),
    SLOW_MOVING("slow_moving"),
    REPEATING_MOTION("repeating_motion"),
    SPORTS("sports"),
    VEHICLE("vehicle"),
    WATERFALL("waterfall"),
    PANORAMIC("panoramic"),
    GENERAL("general");

    private final String value;
    private static final Map<String, SceneType> VALUE_MAP = new HashMap<>();

    static {
        for (SceneType type : values()) {
            VALUE_MAP.put(type.value, type);
        }
    }

    SceneType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SceneType fromValue(String value) {
        SceneType result = VALUE_MAP.get(value);
        return result != null ? result : GENERAL;
    }
}
