package com.samsung.camera.intelligence.models;

/**
 * Available Expert Raw modes.
 */
public enum ExpertRawMode {
    ASTRO("Astro"),
    ASTRO_PORTRAIT("AstroPortrait"),
    MULTI_EXPOSURE("MultiExposure"),
    ND_FILTER("NdFilter"),
    VIRTUAL_APERTURE("VirtualAperture");

    private final String value;

    ExpertRawMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
