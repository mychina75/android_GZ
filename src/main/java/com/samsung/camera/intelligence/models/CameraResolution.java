package com.samsung.camera.intelligence.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Available camera capture resolutions.
 * Maps to common Samsung Galaxy sensor outputs.
 */
public enum CameraResolution {
    MP_12("12MP"),
    MP_50("50MP"),
    MP_108("108MP"),
    MP_200("200MP");

    private final String value;
    private static final Map<String, CameraResolution> VALUE_MAP = new HashMap<>();

    static {
        for (CameraResolution cr : values()) {
            VALUE_MAP.put(cr.value, cr);
        }
    }

    CameraResolution(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static CameraResolution fromValue(String value) {
        CameraResolution result = VALUE_MAP.get(value);
        return result != null ? result : MP_50;
    }
}
