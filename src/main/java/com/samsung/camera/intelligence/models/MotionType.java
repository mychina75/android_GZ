package com.samsung.camera.intelligence.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Motion detection classification.
 * Matches the Python MotionType enum in models/enums.py.
 */
public enum MotionType {
    STATIC("static"),
    SLOW("slow"),
    NORMAL("normal"),
    FAST("fast"),
    VERY_FAST("very_fast"),
    REPEATING("repeating"),
    CHAOTIC("chaotic");

    private final String value;
    private static final Map<String, MotionType> VALUE_MAP = new HashMap<>();

    static {
        for (MotionType mt : values()) {
            VALUE_MAP.put(mt.value, mt);
        }
    }

    MotionType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MotionType fromValue(String value) {
        MotionType result = VALUE_MAP.get(value);
        return result != null ? result : STATIC;
    }
}
