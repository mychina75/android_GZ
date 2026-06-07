package com.samsung.camera.intelligence.guidance;

/**
 * How important the guidance message is.
 */
public enum GuidanceUrgency {
    INFO("info"),
    SUGGESTION("suggestion"),
    WARNING("warning"),
    CRITICAL("critical");

    private final String value;

    GuidanceUrgency(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
