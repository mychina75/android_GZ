package com.samsung.camera.intelligence.guidance;

import java.util.Map;

/**
 * Text/icon alert shown on the viewfinder (e.g. 'Use tripod').
 */
public class AlertBadge extends GuidanceOverlay {

    private String alertId;
    private String icon;

    public AlertBadge() {
        super();
        this.overlayType = "alert";
        this.alertId = "";
        this.icon = "";
    }

    public AlertBadge(GuidanceCategory category, GuidanceUrgency urgency, String message,
                      String alertId, String icon) {
        super(category, urgency, message);
        this.overlayType = "alert";
        this.alertId = alertId != null ? alertId : message.toLowerCase().replace(" ", "_");
        if (this.alertId.length() > 32) {
            this.alertId = this.alertId.substring(0, 32);
        }
        this.icon = icon != null ? icon : "";
    }

    public AlertBadge(GuidanceCategory category, GuidanceUrgency urgency, String message,
                      String alertId, String icon, float displayDurationS) {
        this(category, urgency, message, alertId, icon);
        this.displayDurationS = displayDurationS;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("alert_id", alertId);
        map.put("icon", icon);
        return map;
    }

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
}
