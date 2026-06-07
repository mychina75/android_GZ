package com.samsung.camera.intelligence.app.camera;

import com.samsung.camera.intelligence.models.ToolRecommendation;
import com.samsung.camera.intelligence.models.ToolRecommendationResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecommendationExecutor {

    public interface AppActionListener {
        void onActionMessage(String message);
    }

    private final CameraController cameraController;
    private final AppActionListener actionListener;

    public RecommendationExecutor(CameraController cameraController, AppActionListener actionListener) {
        this.cameraController = cameraController;
        this.actionListener = actionListener;
    }

    public void apply(ToolRecommendationResult result) {
        if (result == null) {
            return;
        }
        List<ToolRecommendation> tools = result.getOrderedTools();
        for (ToolRecommendation tool : tools) {
            applyTool(tool);
        }
    }

    private void applyTool(ToolRecommendation tool) {
        if (tool == null) {
            return;
        }
        String name = tool.getToolName();
        Map<String, Object> p = tool.getParameters();
        if (name == null) {
            return;
        }

        switch (name) {
            case "Camera_ChangeMode":
                cameraController.setLogicalMode(asString(p.get("ModeName"), "Photo"));
                break;
            case "Camera_ChangeIso":
                cameraController.applyIso(asInt(p.get("iso"), 200));
                break;
            case "Camera_ChangeShutterSpeed":
                cameraController.applyShutter(asString(p.get("shutter_speed"), "1/125"));
                break;
            case "Camera_ChangeEV":
                cameraController.applyEv(asFloat(p.get("ev"), 0f));
                break;
            case "Camera_ChangeWhiteBalance":
                cameraController.applyWhiteBalance(
                        asString(p.get("mode"), "auto"),
                        asNullableInt(p.get("kelvin"))
                );
                break;
            case "Camera_ChangeFocusMode":
                cameraController.applyFocus(
                        asString(p.get("mode"), "multi_point"),
                        asNullableFloat(p.get("distance"))
                );
                break;
            case "Camera_ChangeMeteringMode":
                cameraController.applyMetering(asString(p.get("mode"), "matrix"));
                break;
            case "Camera_ChangeZoom":
                cameraController.setZoom(asFloat(p.get("zoom_level"), 1f));
                break;
            case "Camera_Flash":
                cameraController.setFlashMode(asString(p.get("mode"), "off"));
                break;
            case "Camera_ChangeCamera":
                boolean wantFront = "front".equalsIgnoreCase(asString(p.get("direction"), "rear"));
                if (wantFront != cameraController.isFrontCamera()) {
                    cameraController.switchCamera();
                }
                break;
            default:
                if (name.startsWith("Gallery_") || name.startsWith("PhotoEditor_")) {
                    actionListener.onActionMessage("Post action queued: " + name);
                }
                break;
        }
    }

    private static String asString(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    private static int asInt(Object v, int fallback) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private static Integer asNullableInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static float asFloat(Object v, float fallback) {
        if (v instanceof Number) {
            return ((Number) v).floatValue();
        }
        try {
            return v == null ? fallback : Float.parseFloat(String.valueOf(v));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private static Float asNullableFloat(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(v));
        } catch (Exception ignore) {
            return null;
        }
    }
}
