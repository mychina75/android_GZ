package com.samsung.camera.intelligence.recommendation;

import com.samsung.camera.intelligence.guidance.FrameAnalysis;

import java.util.Objects;

/**
 * Computes optimal tone curve parameters (contrast, highlights, shadows, saturation)
 * based on real-time scene analysis for Pro mode auto-enhancement.
 *
 * <p>Uses scene type, lighting, brightness, contrast, and feature flags from
 * FrameAnalysis to generate tone adjustments in the -100..+100 range.
 *
 * <p><b>Feedback-loop prevention:</b> The camera preview that FrameAnalyzer
 * sees already has our TONEMAP_MODE_CONTRAST_CURVE applied.  If we keep
 * re-computing tone from the brightened preview, the parameters drift upward
 * indefinitely (gradual brightening).  To break this loop, we compute tone
 * parameters <b>once</b> per scene/lighting combination and <b>lock</b> them.
 * Re-computation only happens when the model's scene-type or lighting
 * classification changes (these classification heads are robust to the mild
 * brightness shift our curve introduces) or when brightness drifts by a large
 * amount (≥0.20) indicating a genuinely different scene.
 */
public final class SceneToneOptimizer {

    /** Immutable tone parameter set. */
    public static class ToneParams {
        public final float contrast;
        public final float highlights;
        public final float shadows;
        public final float saturation;

        public ToneParams(float contrast, float highlights, float shadows, float saturation) {
            this.contrast = clamp(contrast);
            this.highlights = clamp(highlights);
            this.shadows = clamp(shadows);
            this.saturation = clamp(saturation);
        }

        /** All-zero (neutral, identity curve). */
        public static ToneParams neutral() {
            return new ToneParams(0, 0, 0, 0);
        }

        /** Format for display in the parameter bar. */
        public String toDisplayString() {
            return "C:" + fmt(contrast) + "  H:" + fmt(highlights)
                    + "  Sh:" + fmt(shadows) + "  Sat:" + fmt(saturation);
        }

        /** True if any parameter differs from zero by more than threshold. */
        public boolean isNonTrivial() {
            return Math.abs(contrast) > 1f || Math.abs(highlights) > 1f
                    || Math.abs(shadows) > 1f || Math.abs(saturation) > 1f;
        }

        /** True if the difference from another ToneParams exceeds the threshold. */
        public boolean significantlyDifferentFrom(ToneParams other, float threshold) {
            if (other == null) return isNonTrivial();
            return Math.abs(contrast - other.contrast) > threshold
                    || Math.abs(highlights - other.highlights) > threshold
                    || Math.abs(shadows - other.shadows) > threshold
                    || Math.abs(saturation - other.saturation) > threshold;
        }

        private static float clamp(float v) {
            return Math.max(-100f, Math.min(100f, v));
        }

        private static String fmt(float v) {
            int iv = Math.round(v);
            return iv >= 0 ? "+" + iv : String.valueOf(iv);
        }
    }

    // Minimum delta to consider two ToneParams meaningfully different.
    private static final float SIGNIFICANCE_THRESHOLD = 3f;

    // After computing, lock for at least this duration before allowing
    // a scene-change re-computation.  This prevents the very first
    // post-tone analysis frame (which sees a slightly brighter preview)
    // from triggering a spurious "scene changed" re-computation.
    private static final long MIN_LOCK_MS = 5000;

    // If the model-predicted brightness drifts by more than this from the
    // value that was used for the locked computation, treat it as a genuine
    // scene change (e.g. user pointed the camera elsewhere) and re-compute.
    // Our tone curve typically shifts model-predicted brightness by <0.10,
    // so 0.20 safely exceeds feedback-induced drift.
    private static final float BRIGHTNESS_DRIFT_THRESHOLD = 0.20f;

    // ── Locked state ──
    // Once we compute tone for a scene, these fields record the analysis
    // context.  We will NOT re-compute until the context changes.
    private ToneParams current = ToneParams.neutral();
    private String lockedScene = null;
    private String lockedLighting = null;
    private float lockedBrightness = -1f;
    private long lockTimeMs = 0;
    private boolean hasComputed = false;

    /**
     * Compute raw (unsmoothed) tone parameters from the current frame analysis.
     */
    public static ToneParams computeRaw(FrameAnalysis analysis) {
        if (analysis == null) return ToneParams.neutral();

        float contrast = 0f, highlights = 0f, shadows = 0f, saturation = 0f;
        String scene = analysis.getSceneType();
        String lighting = analysis.getLightingCondition();

        // ── 1. Scene-type baseline ──────────────────────────────────────
        if (scene != null) {
            switch (scene) {
                case "portrait":
                case "group_portrait":
                case "selfie":
                    contrast   += 8;
                    shadows    += 15;   // open up shadows on faces
                    saturation += 10;   // slightly vivid skin
                    break;
                case "landscape":
                case "panoramic":
                    contrast   += 20;
                    highlights -= 10;   // recover sky detail
                    saturation += 25;
                    break;
                case "cityscape":
                case "night_cityscape":
                    contrast   += 15;
                    highlights -= 10;
                    saturation += 15;
                    break;
                case "architecture":
                    contrast   += 25;
                    shadows    += 5;
                    saturation += 5;
                    break;
                case "food":
                case "product":
                    contrast   += 15;
                    shadows    += 10;
                    saturation += 30;
                    break;
                case "macro":
                case "flower":
                    contrast   += 10;
                    saturation += 20;
                    break;
                case "night":
                case "night_sky":
                    shadows    += 20;
                    highlights -= 15;
                    saturation += 15;
                    break;
                case "night_portrait":
                    shadows    += 20;
                    highlights -= 10;
                    saturation += 10;
                    break;
                case "sunset_sunrise":
                    highlights -= 10;
                    saturation += 35;
                    break;
                case "backlit_portrait":
                    shadows    += 25;
                    highlights -= 20;
                    saturation += 5;
                    break;
                case "document":
                    contrast   += 30;   // maximize text readability
                    saturation -= 20;   // desaturate for clarity
                    break;
                case "pet":
                case "wildlife":
                    contrast   += 15;
                    saturation += 20;
                    break;
                // fast/slow/sports: no tone adjustment
            }
        }

        // ── 2. Dynamic compensation from real-time analysis ──────────────
        float contrastVal = analysis.getContrastValue();      // 0~1
        float brightness  = analysis.getBrightnessValue();    // 0~1
        float noise       = analysis.getNoiseLevel();         // 0~1

        // Low contrast scene → boost contrast
        if (contrastVal < 0.35f) {
            contrast += 15;
        } else if (contrastVal < 0.45f) {
            contrast += 8;
        } else if (contrastVal > 0.75f) {
            // Very high contrast → reduce slightly to avoid clipping
            contrast -= 10;
        }

        // Brightness compensation
        if (brightness > 0.8f) {
            highlights -= 20;   // prevent blown highlights
        } else if (brightness > 0.65f) {
            highlights -= 10;
        }
        if (brightness < 0.2f) {
            shadows += 20;      // lift deep shadows
        } else if (brightness < 0.35f) {
            shadows += 10;
        }

        // Noise level → reduce saturation boost in noisy conditions
        if (noise > 0.5f) {
            saturation = Math.max(saturation - 15, -30);
        }

        // Feature flags
        if (analysis.isHasShadow()) {
            shadows += 10;
        }
        if (analysis.isHasFlare()) {
            highlights -= 15;
        }

        // ── 3. Lighting condition refinements ────────────────────────────
        if (lighting != null) {
            switch (lighting) {
                case "golden_hour":
                    saturation += 15;
                    highlights -= 5;
                    break;
                case "blue_hour":
                    saturation += 10;
                    shadows    += 5;
                    break;
                case "backlit":
                    shadows    += 25;
                    highlights -= 20;
                    break;
                case "very_low_light":
                case "low_light":
                    shadows    += 15;
                    contrast   -= 5;    // reduce harshness in noisy low-light
                    break;
                case "very_bright":
                    highlights -= 15;
                    break;
                case "artificial":
                case "mixed":
                    saturation -= 5;    // temper artificial color casts
                    break;
            }
        }

        // ── 4. Brightness-adaptive scaling ──────────────────────────────
        // Scene baselines, lighting adjustments, brightness compensation and
        // feature flags all stack cumulatively.  Without attenuation the
        // cumulative shadow lifting can reach +55 (night portrait + low-light
        // + hasShadow + dark brightness) and the contrast boost can push
        // midtones up in already-bright scenes.
        //
        // Scale positive shadows inversely with brightness so bright scenes
        // are barely affected and dark scenes get moderate lifting.

        // (a) Shadow attenuation: ramp from full (brightness <= 0.20) to
        //     zero (brightness >= 0.75).
        if (shadows > 0f) {
            float shadowScale = Math.max(0f, Math.min(1f,
                    1f - (brightness - 0.20f) / 0.55f));
            shadows *= shadowScale;
        }

        // (b) Contrast attenuation for bright scenes: positive contrast
        //     steepens the gamma midtone → perceptual brightening.
        if (contrast > 0f && brightness > 0.50f) {
            float contrastScale = Math.max(0.15f,
                    1f - (brightness - 0.50f) / 0.50f * 0.70f);
            contrast *= contrastScale;
        }

        // (c) Forced highlight recovery for bright scenes.
        //     Using Math.min ensures we keep any existing negative value.
        if (brightness > 0.50f) {
            float hlFloor = -((brightness - 0.50f) / 0.50f) * 30f; // up to -30
            highlights = Math.min(highlights, hlFloor);
        }

        // (d) Hard cap on cumulative shadow lifting.
        shadows = Math.min(shadows, 30f);

        // ── 5. Net brightness guard ─────────────────────────────────────
        // Approximate the net output brightness shift:
        //   shadows  → lift lower half  → positive shift
        //   highlights → pull upper half → negative shift
        //   contrast  → steepen midtones → slight positive for bright imgs
        float netBrightShift = shadows * 0.20f
                             + highlights * 0.15f
                             + contrast * 0.05f;

        // Maximum allowed net positive shift depends on scene brightness.
        float maxNetShift;
        if (brightness > 0.60f) {
            maxNetShift = 0f;   // bright scenes must not become brighter
        } else if (brightness > 0.40f) {
            maxNetShift = 2f;   // mid-range: very mild lifting OK
        } else {
            maxNetShift = 5f;   // dark scenes: modest lifting permitted
        }

        if (netBrightShift > maxNetShift) {
            float excess = netBrightShift - maxNetShift;
            // Reduce shadows first (the primary brightening source).
            if (shadows > 0f) {
                float shadowReduce = Math.min(shadows, excess / 0.20f);
                shadows -= shadowReduce;
                excess  -= shadowReduce * 0.20f;
            }
            // If still over budget, deepen highlight recovery.
            if (excess > 0.5f) {
                highlights -= excess / 0.15f;
            }
        }

        return new ToneParams(contrast, highlights, shadows, saturation);
    }

    /**
     * Compute tone parameters for the current frame.
     *
     * <p><b>Lock semantics:</b> On the first call (or after a scene change),
     * we compute raw tone parameters from the analysis and <b>lock</b> them.
     * Subsequent calls with the same scene type and lighting condition return
     * {@code null} (no Camera2 update needed).  The lock is released when:
     * <ul>
     *   <li>The scene-type or lighting-condition classification changes</li>
     *   <li>The predicted brightness drifts by ≥0.20 (genuine scene change,
     *       not feedback from our own tone curve)</li>
     * </ul>
     *
     * <p>This breaks the feedback loop: the preview bitmap that
     * FrameAnalyzer sees already has our tone curve applied, so its
     * brightness prediction is affected by our own adjustments.  Re-computing
     * from that would cause the preview to brighten progressively.
     *
     * @return non-null ToneParams when the camera should update, null otherwise
     */
    public ToneParams computeSmoothed(FrameAnalysis analysis) {
        if (analysis == null) return null;

        String scene = analysis.getSceneType();
        String lighting = analysis.getLightingCondition();
        float brightness = analysis.getBrightnessValue();

        if (hasComputed) {
            // Within the minimum lock period, never re-compute — the first
            // post-tone frame's slightly-shifted predictions are not reliable.
            long elapsed = System.currentTimeMillis() - lockTimeMs;
            if (elapsed < MIN_LOCK_MS) {
                return null;
            }

            // After the lock period, only re-compute if the scene genuinely
            // changed.  Classification heads (scene type, lighting) are
            // robust to the ±5-10% brightness shift our tone curve introduces.
            boolean sceneChanged = !Objects.equals(scene, lockedScene)
                                || !Objects.equals(lighting, lockedLighting);
            boolean brightnessDrifted =
                    Math.abs(brightness - lockedBrightness) > BRIGHTNESS_DRIFT_THRESHOLD;

            if (!sceneChanged && !brightnessDrifted) {
                return null;  // same scene → keep locked tone
            }
        }

        // First computation, or scene genuinely changed → compute fresh params
        ToneParams raw = computeRaw(analysis);

        // Lock the analysis context that this computation was based on
        lockedScene = scene;
        lockedLighting = lighting;
        lockedBrightness = brightness;
        lockTimeMs = System.currentTimeMillis();
        hasComputed = true;

        // If the new params are effectively the same as current, update
        // internally but don't signal a Camera2 update.
        if (!raw.significantlyDifferentFrom(current, SIGNIFICANCE_THRESHOLD)) {
            current = raw;
            return null;
        }

        current = raw;
        return current;
    }

    /**
     * Get the current locked tone parameters (even if last computeSmoothed
     * returned null because no update was needed).
     */
    public ToneParams getCurrent() {
        return current;
    }

    /** Reset to neutral (e.g. when leaving Pro mode or disabling auto-tone). */
    public void reset() {
        current = ToneParams.neutral();
        lockedScene = null;
        lockedLighting = null;
        lockedBrightness = -1f;
        lockTimeMs = 0;
        hasComputed = false;
    }
}
