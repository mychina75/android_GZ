package com.samsung.camera.intelligence.guidance;

/**
 * Which guidance layer produced this overlay.
 */
public enum GuidanceCategory {
    COMPOSITION("composition"),
    TECHNICAL("technical"),
    ANGLE("angle"),
    OBSTRUCTION("obstruction");

    private final String value;

    GuidanceCategory(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
