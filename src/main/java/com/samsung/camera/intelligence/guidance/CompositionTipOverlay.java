package com.samsung.camera.intelligence.guidance;

import java.util.Map;

/**
 * Pure-text composition technique tip card.
 * Ported from Python CompositionTipOverlay dataclass in guidance/enums.py.
 */
public class CompositionTipOverlay extends GuidanceOverlay {

    private String tipText;        // The tip body text
    private String techniqueName;  // e.g. "Rule of Odds", "Simplicity"

    public CompositionTipOverlay() {
        super(GuidanceCategory.COMPOSITION, GuidanceUrgency.INFO, "");
        this.overlayType = "composition_tip";
        this.tipText = "";
        this.techniqueName = "";
    }

    public CompositionTipOverlay(GuidanceCategory category, GuidanceUrgency urgency,
                                 String message, String tipText, String techniqueName) {
        super(category, urgency, message);
        this.overlayType = "composition_tip";
        this.tipText = tipText != null ? tipText : "";
        this.techniqueName = techniqueName != null ? techniqueName : "";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("tip_text", tipText);
        map.put("technique_name", techniqueName);
        return map;
    }

    public String getTipText() { return tipText; }
    public void setTipText(String tipText) { this.tipText = tipText; }

    public String getTechniqueName() { return techniqueName; }
    public void setTechniqueName(String techniqueName) { this.techniqueName = techniqueName; }
}
