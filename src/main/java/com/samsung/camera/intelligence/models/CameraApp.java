package com.samsung.camera.intelligence.models;

/**
 * Application context for tool selection.
 */
public enum CameraApp {
    CAMERA("camera"),
    EXPERT_RAW("expert_raw"),
    GALLERY("gallery"),
    PHOTO_EDITOR("photo_editor");

    private final String value;

    CameraApp(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
