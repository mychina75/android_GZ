package com.samsung.camera.intelligence.guidance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 2 — Angle & Perspective Guidance.
 *
 * Stateless rule engine that recommends physical camera positioning changes
 * based on scene type, subject, and lighting analysis.
 *
 * Two main capabilities:
 *   - High/Low Angle Recommendations (scene → ideal pitch)
 *   - Spatial Displacement Arrows (lighting / obstacle avoidance)
 *
 * Ported from Python angle_guide.py
 */
public class AngleGuide {

    // ---- Scene → ideal pitch mapping ----

    private static final Map<String, String> SCENE_PITCH_MAP = new HashMap<>();
    static {
        SCENE_PITCH_MAP.put("architecture", "low");
        SCENE_PITCH_MAP.put("cityscape", "low");
        SCENE_PITCH_MAP.put("night_cityscape", "low");
        SCENE_PITCH_MAP.put("portrait", "eye_level");
        SCENE_PITCH_MAP.put("group_portrait", "eye_level");
        SCENE_PITCH_MAP.put("selfie", "eye_level");
        SCENE_PITCH_MAP.put("night_portrait", "eye_level");
        SCENE_PITCH_MAP.put("backlit_portrait", "eye_level");
        SCENE_PITCH_MAP.put("pet", "low");
        SCENE_PITCH_MAP.put("food", "overhead");
        SCENE_PITCH_MAP.put("product", "high");
        SCENE_PITCH_MAP.put("macro", "eye_level");
        SCENE_PITCH_MAP.put("flower", "eye_level");
        SCENE_PITCH_MAP.put("sports", "low");
        SCENE_PITCH_MAP.put("fast_moving", "low");
        SCENE_PITCH_MAP.put("landscape", "eye_level");
        SCENE_PITCH_MAP.put("sunset_sunrise", "eye_level");
        SCENE_PITCH_MAP.put("panoramic", "eye_level");
        SCENE_PITCH_MAP.put("waterfall", "low");
        SCENE_PITCH_MAP.put("document", "overhead");
        SCENE_PITCH_MAP.put("night_sky", "low");
        SCENE_PITCH_MAP.put("night", "eye_level");
        SCENE_PITCH_MAP.put("vehicle", "low");
        SCENE_PITCH_MAP.put("wildlife", "eye_level");
    }

    // Pitch-message action map (current, ideal) → message template
    private static final Map<String, String> ACTION_MAP = new HashMap<>();
    static {
        ACTION_MAP.put("eye_level|low",     "Try shooting %s from a lower angle for more impact");
        ACTION_MAP.put("eye_level|high",    "Try a higher angle for this %s shot");
        ACTION_MAP.put("eye_level|overhead","Try an overhead / bird's-eye view for %s");
        ACTION_MAP.put("low|eye_level",     "Try eye-level for a more natural %s");
        ACTION_MAP.put("low|high",          "Try a higher angle for this %s");
        ACTION_MAP.put("low|overhead",      "Try overhead view for %s");
        ACTION_MAP.put("high|low",          "Try a low angle for dramatic %s");
        ACTION_MAP.put("high|eye_level",    "Try eye-level perspective for %s");
        ACTION_MAP.put("high|overhead",     "Try overhead for this %s");
        ACTION_MAP.put("overhead|low",      "Lower the camera angle for more drama");
        ACTION_MAP.put("overhead|eye_level","Eye-level would look more natural here");
        ACTION_MAP.put("overhead|high",     "A slight high angle would work for %s");
    }

    // Portrait-type scenes set (for background-people check)
    private static final Set<String> PORTRAIT_SCENES = new HashSet<>();
    static {
        PORTRAIT_SCENES.add("portrait");
        PORTRAIT_SCENES.add("group_portrait");
        PORTRAIT_SCENES.add("selfie");
        PORTRAIT_SCENES.add("backlit_portrait");
    }

    // ---- Config ----

    // Raised from 0.45 → 0.7 (May 2026) to suppress noisy pitch suggestions when
    // the scene classifier isn't confident enough. Combined with the
    // composition-score gate below this prevents the same "Try a higher angle
    // for this product shot" message from repeating on already-decent frames.
    private float minSceneConfidence = 0.7f;
    // Pitch suggestion is suppressed when the overall composition is already
    // strong enough; only nag when the frame can clearly be improved.
    private float pitchSuggestionScoreCeiling = 0.65f;
    private GuidanceUrgency displacementUrgency = GuidanceUrgency.SUGGESTION;
    private float displacementMagnitude = 0.5f;
    private boolean enablePitch = true;
    private boolean enableDisplacement = true;

    public AngleGuide() {}

    // Config setters
    public void setMinSceneConfidence(float v) { this.minSceneConfidence = v; }
    public void setPitchSuggestionScoreCeiling(float v) { this.pitchSuggestionScoreCeiling = v; }
    public void setDisplacementUrgency(GuidanceUrgency v) { this.displacementUrgency = v; }
    public void setDisplacementMagnitude(float v) { this.displacementMagnitude = v; }
    public void setEnablePitch(boolean v) { this.enablePitch = v; }
    public void setEnableDisplacement(boolean v) { this.enableDisplacement = v; }

    // ---- Public API ----

    /**
     * Evaluate angle / perspective rules.
     */
    public List<GuidanceOverlay> evaluate(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();

        // 1 — Pitch (high / low angle) recommendation
        if (enablePitch) {
            AngleSuggestion pitch = checkPitch(analysis);
            if (pitch != null) {
                overlays.add(pitch);
            }
        }

        // 2 — Spatial displacement (lighting / obstacle avoidance)
        if (enableDisplacement) {
            overlays.addAll(checkDisplacement(analysis));
        }

        return overlays;
    }

    // ---- Pitch recommendation ----

    private AngleSuggestion checkPitch(FrameAnalysis analysis) {
        if (analysis.getSceneConfidence() < minSceneConfidence) {
            return null;
        }
        // Don't nag about angle when overall composition is already strong.
        if (analysis.getCompositionScore() >= pitchSuggestionScoreCeiling) {
            return null;
        }
        String ideal = SCENE_PITCH_MAP.get(analysis.getSceneType());
        if (ideal == null) {
            return null;
        }
        String current = estimateCurrentPitch(analysis);
        if (current.equals(ideal)) {
            return null;
        }

        String msg = pitchMessage(analysis.getSceneType(), ideal, current);

        return new AngleSuggestion(
                GuidanceCategory.ANGLE,
                GuidanceUrgency.SUGGESTION,
                msg,
                ideal,
                current,
                "Scene: " + analysis.getSceneType()
        );
    }

    // ---- Spatial displacement ----

    private List<GuidanceOverlay> checkDisplacement(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();

        // Backlit displacement
        if ("backlit".equals(analysis.getLightingCondition())) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.ANGLE,
                    GuidanceUrgency.SUGGESTION,
                    "Move so the light source is to the side",
                    backlitEscapeDirection(analysis),
                    displacementMagnitude
            ));
        }

        // Flare avoidance
        if (analysis.isHasFlare()) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.ANGLE,
                    GuidanceUrgency.WARNING,
                    "Lens flare detected — shift position slightly",
                    flareEscapeDirection(analysis),
                    0.3f
            ));
        }

        // Shadow balancing — only when composition needs editing
        if (analysis.isHasShadow() && analysis.isNeedsCompositionEdit()) {
            String lighting = analysis.getLightingCondition();
            if ("bright".equals(lighting) || "very_bright".equals(lighting) || "normal".equals(lighting)) {
                overlays.add(new DirectionArrow(
                        GuidanceCategory.ANGLE,
                        GuidanceUrgency.SUGGESTION,
                        "Strong shadows — step to the side to soften them",
                        shadowEscapeDirection(analysis),
                        0.35f
                ));
            }
        }

        // Background people avoidance — only with sufficient subject confidence
        if (analysis.isHasBackgroundPeople()
                && PORTRAIT_SCENES.contains(analysis.getSceneType())
                && analysis.getSubjectConfidence() > 0.5f) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.ANGLE,
                    GuidanceUrgency.SUGGESTION,
                    "People in background — step aside to clear the frame",
                    "left",
                    0.4f
            ));
        }

        // Distracting elements / obstacle avoidance
        if (analysis.getCompositionIssues().contains("distracting_elements")) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.ANGLE,
                    GuidanceUrgency.SUGGESTION,
                    "Distracting objects — move a few steps to reframe",
                    obstacleEscapeDirection(analysis),
                    0.4f
            ));
        }

        // Reflection avoidance
        if (analysis.isHasReflection()) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.ANGLE,
                    GuidanceUrgency.SUGGESTION,
                    "Reflection detected — shift position to reduce it",
                    reflectionEscapeDirection(analysis),
                    0.3f
            ));
        }

        // Mixed / unbalanced lighting repositioning — only when composition score is low
        if ("mixed".equals(analysis.getLightingCondition())
                && analysis.getCompositionScore() < 0.6f) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.ANGLE,
                    GuidanceUrgency.INFO,
                    "Mixed lighting — reposition toward a single light source",
                    lightingRepositionDirection(analysis),
                    0.35f
            ));
        }

        return overlays;
    }

    // ---- Pitch estimation heuristic ----

    private static String estimateCurrentPitch(FrameAnalysis analysis) {
        Float cy = analysis.getSubjectCenterY();
        if (cy != null) {
            if (cy < 0.25f) return "low";   // Subject near top → camera looking up
            if (cy > 0.75f) return "high";   // Subject near bottom → looking down
        }
        String scene = analysis.getSceneType();
        if ("food".equals(scene) || "document".equals(scene)) {
            return "high";
        }
        return "eye_level";
    }

    // ---- Direction heuristics ----

    private static String backlitEscapeDirection(FrameAnalysis a) {
        Float cx = a.getSubjectCenterX();
        if (cx != null) return cx > 0.5f ? "left" : "right";
        return "left";
    }

    private static String flareEscapeDirection(FrameAnalysis a) {
        Float cx = a.getSubjectCenterX();
        if (cx != null) return cx < 0.5f ? "right" : "left";
        return "right";
    }

    private static String shadowEscapeDirection(FrameAnalysis a) {
        Float cx = a.getSubjectCenterX();
        if (cx != null) return cx < 0.5f ? "right" : "left";
        return "left";
    }

    private static String obstacleEscapeDirection(FrameAnalysis a) {
        Float cx = a.getSubjectCenterX();
        if (cx != null) return cx < 0.5f ? "left" : "right";
        return "right";
    }

    private static String reflectionEscapeDirection(FrameAnalysis a) {
        Float cx = a.getSubjectCenterX();
        if (cx != null) return cx > 0.5f ? "right" : "left";
        return "right";
    }

    private static String lightingRepositionDirection(FrameAnalysis a) {
        Float cx = a.getSubjectCenterX();
        if (cx != null) return cx > 0.5f ? "left" : "right";
        return "left";
    }

    // ---- Pitch message builder ----

    private static String pitchMessage(String sceneType, String ideal, String current) {
        String sceneLabel = sceneType.replace("_", " ");
        String key = current + "|" + ideal;
        String template = ACTION_MAP.get(key);
        if (template != null) {
            return String.format(template, sceneLabel);
        }
        return "Try a " + ideal.replace("_", " ") + " angle for " + sceneLabel;
    }
}
