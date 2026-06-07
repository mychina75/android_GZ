package com.samsung.camera.intelligence.guidance;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for all visual guidance overlays.
 * Ported from Python GuidanceOverlay dataclass in guidance/enums.py.
 */
public class GuidanceOverlay {

    protected GuidanceCategory category;
    protected GuidanceUrgency urgency;
    protected String message;
    protected float displayDurationS;
    protected String overlayType;

    public GuidanceOverlay() {
        this.category = GuidanceCategory.COMPOSITION;
        this.urgency = GuidanceUrgency.INFO;
        this.message = "";
        this.displayDurationS = 2.0f;
        this.overlayType = "generic";
    }

    public GuidanceOverlay(GuidanceCategory category, GuidanceUrgency urgency, String message) {
        this.category = category;
        this.urgency = urgency;
        this.message = message != null ? message : "";
        this.displayDurationS = 2.0f;
        this.overlayType = "generic";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("overlay_type", overlayType);
        map.put("category", category.getValue());
        map.put("urgency", urgency.getValue());
        map.put("message", message);
        map.put("display_duration_s", displayDurationS);
        return map;
    }

    // Getters and Setters
    public GuidanceCategory getCategory() { return category; }
    public void setCategory(GuidanceCategory category) { this.category = category; }

    public GuidanceUrgency getUrgency() { return urgency; }
    public void setUrgency(GuidanceUrgency urgency) { this.urgency = urgency; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public float getDisplayDurationS() { return displayDurationS; }
    public void setDisplayDurationS(float displayDurationS) { this.displayDurationS = displayDurationS; }

    public String getOverlayType() { return overlayType; }
    public void setOverlayType(String overlayType) { this.overlayType = overlayType; }
}
