package com.samsung.camera.intelligence.models;

/**
 * Available metering modes for Pro mode.
 */
public enum MeteringMode {
    CENTER_WEIGHTED("Center-weighted"),
    MATRIX("Matrix"),
    SPOT("Spot");

    private final String value;

    MeteringMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
