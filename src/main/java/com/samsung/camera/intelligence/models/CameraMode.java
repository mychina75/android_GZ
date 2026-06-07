package com.samsung.camera.intelligence.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Available camera modes.
 * Matches the Python CameraMode enum in models/enums.py.
 */
public enum CameraMode {
    PORTRAIT("Portrait"),
    PHOTO("Photo"),
    VIDEO("Video"),
    PRO("Pro"),
    NIGHT("Night"),
    SINGLE_TAKE("Single_take"),
    HYPERLAPSE("Hyperlapse"),
    SLOW_MOTION("Slow_motion"),
    DUAL_RECORDING("Dual_recording"),
    PRO_VIDEO("Pro_video"),
    PORTRAIT_VIDEO("Portrait_video"),
    FOOD("Food"),
    PANORAMA("Panorama");

    private final String value;
    private static final Map<String, CameraMode> VALUE_MAP = new HashMap<>();

    static {
        for (CameraMode cm : values()) {
            VALUE_MAP.put(cm.value, cm);
        }
    }

    CameraMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static CameraMode fromValue(String value) {
        CameraMode result = VALUE_MAP.get(value);
        return result != null ? result : PHOTO;
    }
}
