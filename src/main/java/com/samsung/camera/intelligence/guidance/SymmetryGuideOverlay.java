package com.samsung.camera.intelligence.guidance;

import java.util.Map;

/**
 * Centre-line overlay for symmetrical compositions.
 * Ported from Python SymmetryGuideOverlay dataclass in guidance/enums.py.
 */
public class SymmetryGuideOverlay extends GuidanceOverlay {

    private String axis;          // "vertical" | "horizontal"
    private float axisPosition;   // Normalised position along perpendicular axis

    public SymmetryGuideOverlay() {
        super(GuidanceCategory.COMPOSITION, GuidanceUrgency.SUGGESTION, "");
        this.overlayType = "symmetry_guide";
        this.axis = "vertical";
        this.axisPosition = 0.5f;
    }

    public SymmetryGuideOverlay(GuidanceCategory category, GuidanceUrgency urgency,
                                String message, String axis, float axisPosition) {
        super(category, urgency, message);
        this.overlayType = "symmetry_guide";
        this.axis = axis != null ? axis : "vertical";
        this.axisPosition = axisPosition;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("axis", axis);
        map.put("axis_position", Math.round(axisPosition * 1000.0f) / 1000.0f);
        return map;
    }

    public String getAxis() { return axis; }
    public void setAxis(String axis) { this.axis = axis; }

    public float getAxisPosition() { return axisPosition; }
    public void setAxisPosition(float axisPosition) { this.axisPosition = axisPosition; }
}
