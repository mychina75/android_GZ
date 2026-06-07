package com.samsung.camera.intelligence.models;

/**
 * Available white balance modes for Pro mode.
 */
public enum WhiteBalanceMode {
    AUTO("Auto"),
    DAYLIGHT("Daylight"),
    CLOUDY("Cloudy"),
    FLUORESCENT("Fluorescent"),
    INCANDESCENT("Incandescent"),
    CUSTOM("Custom");

    private final String value;

    WhiteBalanceMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
