package com.samsung.camera.intelligence.guidance;

import java.util.Map;

/**
 * Tilt correction indicator.
 */
public class HorizonLine extends GuidanceOverlay {

    private float tiltAngleDeg;
    private float correctionDeg;

    public HorizonLine() {
        super();
        this.overlayType = "horizon";
        this.category = GuidanceCategory.COMPOSITION;
        this.tiltAngleDeg = 0.0f;
        this.correctionDeg = 0.0f;
    }

    public HorizonLine(GuidanceCategory category, GuidanceUrgency urgency, String message,
                       float tiltAngleDeg) {
        super(category, urgency, message);
        this.overlayType = "horizon";
        this.tiltAngleDeg = tiltAngleDeg;
        this.correctionDeg = -tiltAngleDeg;
        if (Math.abs(tiltAngleDeg) > 5.0f) {
            this.urgency = GuidanceUrgency.WARNING;
        } else {
            this.urgency = GuidanceUrgency.SUGGESTION;
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("tilt_angle_deg", tiltAngleDeg);
        map.put("correction_deg", correctionDeg);
        return map;
    }

    public float getTiltAngleDeg() { return tiltAngleDeg; }
    public void setTiltAngleDeg(float tiltAngleDeg) {
        this.tiltAngleDeg = tiltAngleDeg;
        this.correctionDeg = -tiltAngleDeg;
    }

    public float getCorrectionDeg() { return correctionDeg; }
    public void setCorrectionDeg(float correctionDeg) { this.correctionDeg = correctionDeg; }
}
