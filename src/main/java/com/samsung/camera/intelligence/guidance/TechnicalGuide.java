package com.samsung.camera.intelligence.guidance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 3 — Technical Parameter Guidance.
 *
 * Stateless rule engine that evaluates a decoded {@link FrameAnalysis} and
 * produces real-time technical-parameter alerts/suggestions.
 *
 * Rules:
 *   - Camera-shake / tripod warning (fast motion + low light)
 *   - Exposure / spot-metering suggestion (backlit + human subject)
 *   - HDR mode suggestion (high contrast + back/mixed lighting)
 *   - Noise warning (high ISO indicator, low light)
 *   - Sharpness warning (soft/blurry frame)
 *   - Low-light mode / Night mode suggestion
 *
 * Ported from Python technical_guide.py
 */
public class TechnicalGuide {

    // ---- Config ----

    private float noiseWarningThreshold = 0.50f;
    private float sharpnessWarningThreshold = 0.35f;
    private float contrastHdrThreshold = 0.70f;

    private final Set<String> shakeMotionTypes = new HashSet<>(
            Arrays.asList("fast", "very_fast", "chaotic"));
    private final Set<String> shakeLightingTypes = new HashSet<>(
            Arrays.asList("low_light", "very_low_light"));
    private final Set<String> hdrLightingTypes = new HashSet<>(
            Arrays.asList("backlit", "mixed"));
    private final Set<String> nightLightingTypes = new HashSet<>(
            Arrays.asList("very_low_light"));

    public TechnicalGuide() {}

    // Config setters
    public void setNoiseWarningThreshold(float v) { this.noiseWarningThreshold = v; }
    public void setSharpnessWarningThreshold(float v) { this.sharpnessWarningThreshold = v; }
    public void setContrastHdrThreshold(float v) { this.contrastHdrThreshold = v; }

    // ---- Public API ----

    /**
     * Run all technical rules and return overlay directives.
     */
    public List<GuidanceOverlay> evaluate(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();

        // 1 — Camera shake / tripod warning
        AlertBadge shake = checkShake(analysis);
        if (shake != null) overlays.add(shake);

        // 2 — Spot-metering / exposure suggestion
        AlertBadge exposure = checkExposure(analysis);
        if (exposure != null) overlays.add(exposure);

        // 3 — HDR suggestion
        AlertBadge hdr = checkHdr(analysis);
        if (hdr != null) overlays.add(hdr);

        // 4 — Noise warning
        AlertBadge noise = checkNoise(analysis);
        if (noise != null) overlays.add(noise);

        // 5 — Sharpness warning
        AlertBadge sharpness = checkSharpness(analysis);
        if (sharpness != null) overlays.add(sharpness);

        // 6 — Night mode suggestion
        AlertBadge night = checkNightMode(analysis);
        if (night != null) overlays.add(night);

        // 7 — Motion capture limit / uncapturable motion warning
        AlertBadge captureLimit = checkMotionCaptureLimit(analysis);
        if (captureLimit != null) overlays.add(captureLimit);

        // 8 — Shutter speed recommendation for fast motion
        AlertBadge shutterRec = checkShutterRecommendation(analysis);
        if (shutterRec != null) overlays.add(shutterRec);

        // 9 — Video mode suggestion for continuous motion
        AlertBadge videoSwitch = checkVideoSwitch(analysis);
        if (videoSwitch != null) overlays.add(videoSwitch);

        return overlays;
    }

    // ---- Individual rule implementations ----

    private AlertBadge checkShake(FrameAnalysis analysis) {
        if (shakeMotionTypes.contains(analysis.getMotionType())
                && shakeLightingTypes.contains(analysis.getLightingCondition())) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.WARNING,
                    "Camera shake risk — hold steady or use a tripod",
                    "shake_warning",
                    "shake"
            );
        }
        return null;
    }

    private AlertBadge checkExposure(FrameAnalysis analysis) {
        if ("backlit".equals(analysis.getLightingCondition())) {
            String scene = analysis.getSceneType();
            if ("portrait".equals(scene) || "group_portrait".equals(scene)
                    || "selfie".equals(scene) || "event".equals(scene)) {
                return new AlertBadge(
                        GuidanceCategory.TECHNICAL,
                        GuidanceUrgency.SUGGESTION,
                        "Backlit subject — tap to set focus & exposure",
                        "spot_metering",
                        "exposure"
                );
            }
        }
        return null;
    }

    private AlertBadge checkHdr(FrameAnalysis analysis) {
        if (analysis.getContrastValue() > contrastHdrThreshold
                && hdrLightingTypes.contains(analysis.getLightingCondition())) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.SUGGESTION,
                    "High dynamic range — consider enabling HDR",
                    "hdr_suggestion",
                    "hdr"
            );
        }
        return null;
    }

    private AlertBadge checkNoise(FrameAnalysis analysis) {
        if (analysis.getNoiseLevel() > noiseWarningThreshold) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.WARNING,
                    "High noise level — more light or lower ISO would help",
                    "noise_warning",
                    "noise"
            );
        }
        return null;
    }

    private AlertBadge checkSharpness(FrameAnalysis analysis) {
        if (analysis.getSharpnessValue() < sharpnessWarningThreshold) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.WARNING,
                    "Image appears soft — clean the lens or hold steady",
                    "sharpness_warning",
                    "sharpness"
            );
        }
        return null;
    }

    private AlertBadge checkNightMode(FrameAnalysis analysis) {
        if (nightLightingTypes.contains(analysis.getLightingCondition())) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.SUGGESTION,
                    "Very low light — try Night mode for better results",
                    "night_mode",
                    "night"
            );
        }
        return null;
    }

    // ---- Motion-capture-advisory rules (flow_magnitude-driven) ----

    /**
     * CRITICAL alert when subject motion exceeds sensor capture limits.
     * WARNING when motion is very fast and a higher FPS is needed.
     */
    private AlertBadge checkMotionCaptureLimit(FrameAnalysis analysis) {
        float mag = analysis.getFlowMagnitude();
        if (mag > 200) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.CRITICAL,
                    "Subject motion exceeds capture limits — "
                        + "try burst mode, panning, or Slow-Mo (240 fps)",
                    "motion_uncapturable",
                    "motion_limit"
            );
        }
        if (mag > 100) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.WARNING,
                    "Very fast motion — switch to Slow Motion (240 fps) "
                        + "or Super Slow-Mo for best results",
                    "motion_extreme",
                    "motion_fast"
            );
        }
        if (mag > 50) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.SUGGESTION,
                    "Fast motion detected — 120 fps capture recommended "
                        + "for sharper results",
                    "motion_very_fast",
                    "motion_fast"
            );
        }
        return null;
    }

    /**
     * Suggest a faster shutter speed based on flow magnitude to
     * freeze subject motion.
     */
    private AlertBadge checkShutterRecommendation(FrameAnalysis analysis) {
        float mag = analysis.getFlowMagnitude();
        if (mag <= 8) {
            return null; // gentle or static — no shutter advice needed
        }

        // Map flow magnitude to recommended shutter denominator
        int denom;
        if (mag > 200) {
            denom = 4000;
        } else if (mag > 100) {
            denom = 2000;
        } else if (mag > 50) {
            denom = 1000;
        } else if (mag > 20) {
            denom = 500;
        } else {
            denom = 250;
        }

        return new AlertBadge(
                GuidanceCategory.TECHNICAL,
                GuidanceUrgency.SUGGESTION,
                "Use shutter speed 1/" + denom + " or faster to freeze motion",
                "shutter_recommendation",
                "shutter"
        );
    }

    /**
     * Suggest switching from photo to video when motion is continuous
     * and faster than what a single photo can cleanly capture.
     */
    private AlertBadge checkVideoSwitch(FrameAnalysis analysis) {
        float mag = analysis.getFlowMagnitude();
        if (mag > 15) {
            return new AlertBadge(
                    GuidanceCategory.TECHNICAL,
                    GuidanceUrgency.SUGGESTION,
                    "Continuous motion detected — consider switching to "
                        + "Video mode for better results",
                    "video_switch",
                    "video"
            );
        }
        return null;
    }
}
