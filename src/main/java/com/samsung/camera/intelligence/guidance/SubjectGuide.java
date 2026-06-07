package com.samsung.camera.intelligence.guidance;

import java.util.Map;

/**
 * Dot / crosshair showing the ideal subject placement.
 */
public class SubjectGuide extends GuidanceOverlay {

    private float currentX;
    private float currentY;
    private float targetX;
    private float targetY;

    public SubjectGuide() {
        super(GuidanceCategory.COMPOSITION, GuidanceUrgency.SUGGESTION, "");
        this.overlayType = "subject_guide";
        this.currentX = 0.5f;
        this.currentY = 0.5f;
        this.targetX = 1.0f / 3;
        this.targetY = 1.0f / 3;
    }

    public SubjectGuide(GuidanceCategory category, GuidanceUrgency urgency, String message,
                        float currentX, float currentY, float targetX, float targetY) {
        super(category, urgency, message);
        this.overlayType = "subject_guide";
        this.currentX = currentX;
        this.currentY = currentY;
        this.targetX = targetX;
        this.targetY = targetY;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("current_x", Math.round(currentX * 1000.0f) / 1000.0f);
        map.put("current_y", Math.round(currentY * 1000.0f) / 1000.0f);
        map.put("target_x", Math.round(targetX * 1000.0f) / 1000.0f);
        map.put("target_y", Math.round(targetY * 1000.0f) / 1000.0f);
        return map;
    }

    public float getCurrentX() { return currentX; }
    public void setCurrentX(float currentX) { this.currentX = currentX; }

    public float getCurrentY() { return currentY; }
    public void setCurrentY(float currentY) { this.currentY = currentY; }

    public float getTargetX() { return targetX; }
    public void setTargetX(float targetX) { this.targetX = targetX; }

    public float getTargetY() { return targetY; }
    public void setTargetY(float targetY) { this.targetY = targetY; }
}
