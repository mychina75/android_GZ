package com.samsung.camera.intelligence.guidance;

import java.util.Map;

/**
 * Arrow telling user to shift camera position.
 */
public class DirectionArrow extends GuidanceOverlay {

    private String direction; // "left", "right", "up", "down"
    private float magnitude;  // 0-1

    public DirectionArrow() {
        super();
        this.overlayType = "direction_arrow";
        this.direction = "none";
        this.magnitude = 0.0f;
    }

    public DirectionArrow(GuidanceCategory category, GuidanceUrgency urgency, String message,
                          String direction, float magnitude) {
        super(category, urgency, message);
        this.overlayType = "direction_arrow";
        this.direction = direction;
        this.magnitude = magnitude;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("direction", direction);
        map.put("magnitude", Math.round(magnitude * 1000.0f) / 1000.0f);
        return map;
    }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public float getMagnitude() { return magnitude; }
    public void setMagnitude(float magnitude) { this.magnitude = magnitude; }
}
