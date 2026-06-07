package com.samsung.camera.intelligence.models;

/**
 * Available focus modes for Pro mode.
 */
public enum FocusMode {
    CENTER("Center"),
    MULTI_POINT("Multi-point"),
    MANUAL("Manual");

    private final String value;

    FocusMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
