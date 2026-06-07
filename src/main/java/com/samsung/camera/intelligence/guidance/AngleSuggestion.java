package com.samsung.camera.intelligence.guidance;

import java.util.Map;

/**
 * Recommends a change in camera pitch (high/low angle) or physical position.
 */
public class AngleSuggestion extends GuidanceOverlay {

    private String suggestedPitch;        // "low", "eye_level", "high", "overhead"
    private String currentPitchEstimate;  // "low", "eye_level", "high", "overhead"
    private String reason;

    public AngleSuggestion() {
        super();
        this.overlayType = "angle_suggestion";
        this.category = GuidanceCategory.ANGLE;
        this.suggestedPitch = "eye_level";
        this.currentPitchEstimate = "eye_level";
        this.reason = "";
    }

    public AngleSuggestion(GuidanceCategory category, GuidanceUrgency urgency, String message,
                           String suggestedPitch, String currentPitchEstimate, String reason) {
        super(category, urgency, message);
        this.overlayType = "angle_suggestion";
        this.suggestedPitch = suggestedPitch;
        this.currentPitchEstimate = currentPitchEstimate;
        this.reason = reason != null ? reason : "";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("suggested_pitch", suggestedPitch);
        map.put("current_pitch_estimate", currentPitchEstimate);
        map.put("reason", reason);
        return map;
    }

    public String getSuggestedPitch() { return suggestedPitch; }
    public void setSuggestedPitch(String suggestedPitch) { this.suggestedPitch = suggestedPitch; }

    public String getCurrentPitchEstimate() { return currentPitchEstimate; }
    public void setCurrentPitchEstimate(String currentPitchEstimate) { this.currentPitchEstimate = currentPitchEstimate; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
