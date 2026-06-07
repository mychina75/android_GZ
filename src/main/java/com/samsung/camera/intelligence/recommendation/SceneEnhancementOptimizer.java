package com.samsung.camera.intelligence.recommendation;

import com.samsung.camera.intelligence.guidance.FrameAnalysis;

import java.util.Objects;

/**
 * Scene-aware enhancement engine that matches 15 predefined scene patterns
 * against real-time FrameAnalysis and produces GPU enhancement parameters
 * across three tiers: T1 (LUT only), T2 (shader uniforms), T3 (FBO+USM).
 *
 * <p>Uses the same anti-feedback lock pattern as {@link SceneToneOptimizer}:
 * compute once per scene, lock for 5 seconds, re-compute only on scene change
 * or significant brightness drift.
 */
public final class SceneEnhancementOptimizer {

    /** Enhancement tier: determines which GPU pipeline is used. */
    public enum Tier { T1_LUT, T2_SHADER, T3_FBO_USM }

    /**
     * The 15 supported enhancement modes.
     * Each carries trigger conditions, display metadata, and tier classification.
     */
    public enum EnhancementMode {
        // T2: Backlit Face Lift + HDR (modes #1 + #22 merged)
        BACKLIT_FACE_LIFT(1, "Backlit Face", "\uD83D\uDCA1",
                "backlit_portrait", "backlit", "human_face", Tier.T2_SHADER),
        // T1: Golden Hour Glow (#4)
        GOLDEN_GLOW(4, "Golden Glow", "\uD83C\uDF1F",
                "selfie", "golden_hour", "human_face", Tier.T1_LUT),
        // T1: Blue Hour Contrast (#6)
        BLUE_HOUR(6, "Blue Hour", "\uD83C\uDF03",
                "cityscape", "blue_hour", "sky_night", Tier.T1_LUT),
        // T2: Digital GND (#7)
        DIGITAL_GND(7, "Digital GND", "\u26F0\uFE0F",
                "landscape", "bright", "sky_day", Tier.T2_SHADER),
        // T3: Architecture Clarity (#8)
        ARCHITECTURE_CLARITY(8, "Architecture", "\uD83C\uDFDB\uFE0F",
                "architecture", "normal", "architecture_exterior", Tier.T3_FBO_USM),
        // T1: Food Boost (#9)
        FOOD_BOOST(9, "Food Boost", "\uD83C\uDF7D\uFE0F",
                "food", "artificial", "food_dish", Tier.T1_LUT),
        // T3: Macro Detail (#12)
        MACRO_DETAIL(12, "Macro Detail", "\uD83D\uDD2C",
                "macro", "normal", "plant_flower", Tier.T3_FBO_USM),
        // T2: Starry Sky (#13)
        STARRY_SKY(13, "Starry Sky", "\u2B50",
                "night_sky", "very_low_light", "sky_night", Tier.T2_SHADER),
        // T1: Sunset Sensation (#15)
        SUNSET(15, "Sunset", "\uD83C\uDF05",
                "sunset_sunrise", "golden_hour", "sky_day", Tier.T1_LUT),
        // T1: Night Vision (#16)
        NIGHT_VISION(16, "Night Vision", "\uD83C\uDF11",
                "night", "very_low_light", "none", Tier.T1_LUT),
        // T3: Pet Fur Detail (#19)
        PET_FUR(19, "Pet Fur", "\uD83D\uDC3E",
                "pet", "indoor", "animal_pet", Tier.T3_FBO_USM),
        // T1: Film Simulation (#21) — artistic scenes fallback
        FILM_SIM(21, "Film Sim", "\uD83C\uDFAC",
                null, null, null, Tier.T1_LUT),
        // T1: Fine Art B&W (#23) — triggered by suggestBw
        FINE_ART_BW(23, "B&W Art", "\u26AB",
                null, null, null, Tier.T1_LUT),
        // T1: Consistency Lock (#24) — locks current params
        CONSISTENCY_LOCK(24, "Style Lock", "\uD83D\uDD12",
                null, null, null, Tier.T1_LUT);

        public final int id;
        public final String displayName;
        public final String emoji;
        public final String triggerScene;
        public final String triggerLighting;
        public final String triggerSubject;
        public final Tier tier;

        EnhancementMode(int id, String displayName, String emoji,
                        String triggerScene, String triggerLighting, String triggerSubject,
                        Tier tier) {
            this.id = id;
            this.displayName = displayName;
            this.emoji = emoji;
            this.triggerScene = triggerScene;
            this.triggerLighting = triggerLighting;
            this.triggerSubject = triggerSubject;
            this.tier = tier;
        }
    }

    /** Immutable result carrying all GPU parameters for the matched enhancement. */
    public static class EnhancementResult {
        public final EnhancementMode mode;
        // LUT params (6-param)
        public final float contrast;
        public final float highlights;
        public final float shadows;
        public final float saturation;
        public final float highlightWarmth;
        public final float shadowTint;
        // T2 shader mode: 0=none, 1=faceLift+HDR, 2=GND, 3=starSky
        public final int shaderMode;
        // Shader params: meaning depends on shaderMode
        //   mode 1: [faceCenterX, faceCenterY, radius, liftStrength, hdrStrength]
        //   mode 2: [gndPosition, gndStrength]
        //   mode 3: [blackFloor, gain, starThreshold, starGlow]
        public final float[] shaderParams;
        // T3 USM
        public final boolean usmEnabled;
        public final float usmRadius;
        public final float usmStrength;

        public EnhancementResult(EnhancementMode mode,
                                 float contrast, float highlights, float shadows,
                                 float saturation, float highlightWarmth, float shadowTint,
                                 int shaderMode, float[] shaderParams,
                                 boolean usmEnabled, float usmRadius, float usmStrength) {
            this.mode = mode;
            this.contrast = clamp(contrast);
            this.highlights = clamp(highlights);
            this.shadows = clamp(shadows);
            this.saturation = clamp(saturation);
            this.highlightWarmth = clamp(highlightWarmth);
            this.shadowTint = clamp(shadowTint);
            this.shaderMode = shaderMode;
            this.shaderParams = shaderParams;
            this.usmEnabled = usmEnabled;
            this.usmRadius = usmRadius;
            this.usmStrength = usmStrength;
        }

        private static float clamp(float v) {
            return Math.max(-100f, Math.min(100f, v));
        }

        /** Label for status display: emoji + name. */
        public String statusLabel() {
            return mode.emoji + " " + mode.displayName;
        }
    }

    // ── Anti-feedback lock (same pattern as SceneToneOptimizer) ──────

    private static final long MIN_LOCK_MS = 5000;
    private static final float BRIGHTNESS_DRIFT_THRESHOLD = 0.20f;

    private EnhancementResult current = null;
    private String lockedScene = null;
    private String lockedLighting = null;
    private String lockedSubject = null;
    private float lockedBrightness = -1f;
    private long lockTimeMs = 0;
    private boolean hasComputed = false;

    // ── Scene matching ──────────────────────────────────────────────

    /**
     * Match the current frame analysis against the 15 modes.
     * Uses scene-primary matching: the sceneType alone selects the candidate mode,
     * lighting/subject act as optional refinement (not required).
     * Returns the best matching mode, or null if no match.
     */
    public EnhancementMode matchScene(FrameAnalysis analysis) {
        if (analysis == null) return null;

        String scene = analysis.getSceneType();
        String lighting = analysis.getLightingCondition();

        // Special: B&W suggestion from model overrides other modes (#23)
        if (analysis.isSuggestBw()) {
            return EnhancementMode.FINE_ART_BW;
        }

        // Scene-primary matching: select mode based on sceneType,
        // with lighting used only to choose among overlapping scene types.
        if (scene == null) return null;

        switch (scene) {
            case "backlit_portrait":
                return EnhancementMode.BACKLIT_FACE_LIFT;

            case "selfie":
                // Golden Glow only in warm light; skip for other lighting
                if ("golden_hour".equals(lighting)) {
                    return EnhancementMode.GOLDEN_GLOW;
                }
                return null;

            case "cityscape":
            case "night_cityscape":
                if ("blue_hour".equals(lighting)) {
                    return EnhancementMode.BLUE_HOUR;
                }
                return null;

            case "landscape":
                return EnhancementMode.DIGITAL_GND;

            case "architecture":
                return EnhancementMode.ARCHITECTURE_CLARITY;

            case "food":
                return EnhancementMode.FOOD_BOOST;

            case "macro":
            case "flower":
                return EnhancementMode.MACRO_DETAIL;

            case "night_sky":
                return EnhancementMode.STARRY_SKY;

            case "sunset_sunrise":
                return EnhancementMode.SUNSET;

            case "night":
            case "night_portrait":
                if ("very_low_light".equals(lighting) || "low_light".equals(lighting)) {
                    return EnhancementMode.NIGHT_VISION;
                }
                return null;

            case "pet":
                return EnhancementMode.PET_FUR;

            default:
                return null;
        }
    }

    // ── Enhancement computation ─────────────────────────────────────

    /**
     * Compute full enhancement parameters for the matched mode.
     */
    public EnhancementResult computeEnhancement(FrameAnalysis analysis) {
        EnhancementMode mode = matchScene(analysis);
        if (mode == null) return null;
        return computeForMode(mode, analysis);
    }

    private EnhancementResult computeForMode(EnhancementMode mode, FrameAnalysis analysis) {
        float c = 0, h = 0, sh = 0, sat = 0, w = 0, t = 0;
        int shaderMode = 0;
        float[] shaderParams = null;
        boolean usm = false;
        float usmRadius = 0, usmStrength = 0;

        switch (mode) {
            case BACKLIT_FACE_LIFT: // #1+22: warm shadow lift + radial face shader + HDR
                c = 10; h = -10; sh = 30; sat = 5; w = 15; t = 5;
                shaderMode = 1;
                float cx = analysis.getSubjectCenterX() != null ? analysis.getSubjectCenterX() : 0.5f;
                float cy = analysis.getSubjectCenterY() != null ? analysis.getSubjectCenterY() : 0.4f;
                shaderParams = new float[]{cx, cy, 0.25f, 0.5f, 0.3f};
                break;

            case GOLDEN_GLOW: // #4: golden warmth
                c = 5; h = -5; sh = 10; sat = 15; w = 40; t = 10;
                break;

            case BLUE_HOUR: // #6: cool blue with warm highlights
                c = 15; h = -10; sh = 5; sat = 20; w = -20; t = -15;
                break;

            case DIGITAL_GND: // #7: landscape + graduated ND shader
                c = 10; h = -15; sh = 5; sat = 10; w = 5; t = 0;
                shaderMode = 2;
                shaderParams = new float[]{0.4f, 0.6f}; // gndPosition=0.4, gndStrength=0.6
                break;

            case ARCHITECTURE_CLARITY: // #8: sharp contrast + USM
                c = 15; h = -5; sh = 0; sat = 5; w = 0; t = 0;
                usm = true; usmRadius = 5f; usmStrength = 0.6f;
                break;

            case FOOD_BOOST: // #9: red/yellow saturation boost
                c = 5; h = 0; sh = 5; sat = 35; w = 20; t = 5;
                break;

            case MACRO_DETAIL: // #12: vivid macro + USM
                c = 10; h = -5; sh = 5; sat = 15; w = 5; t = 0;
                usm = true; usmRadius = 3f; usmStrength = 0.8f;
                break;

            case STARRY_SKY: // #13: shadow lift + star enhancement shader
                c = 20; h = -10; sh = 50; sat = -10; w = -10; t = -5;
                shaderMode = 3;
                shaderParams = new float[]{0.05f, 2.5f, 0.6f, 0.3f};
                break;

            case SUNSET: // #15: sunset orange-red warmth
                c = 10; h = 0; sh = 10; sat = 25; w = 45; t = 15;
                break;

            case NIGHT_VISION: // #16: extreme shadow lift + desaturate
                c = -5; h = 0; sh = 80; sat = -20; w = 0; t = 0;
                break;

            case PET_FUR: // #19: warm pet tones + USM
                c = 10; h = -5; sh = 5; sat = 10; w = 10; t = 0;
                usm = true; usmRadius = 3f; usmStrength = 0.7f;
                break;

            case FILM_SIM: // #21: film warmth
                c = 5; h = -5; sh = 10; sat = 10; w = 15; t = 5;
                break;

            case FINE_ART_BW: // #23: full desaturate + contrast
                c = 15; h = -10; sh = 10; sat = -100; w = 0; t = 0;
                break;

            case CONSISTENCY_LOCK: // #24: return current params if any
                if (current != null) {
                    return current;
                }
                return null;
        }

        // Dynamic refinement from frame analysis (same approach as SceneToneOptimizer)
        float brightness = analysis.getBrightnessValue();
        float contrastVal = analysis.getContrastValue();
        float noise = analysis.getNoiseLevel();

        // Low contrast → boost
        if (contrastVal < 0.35f) c += 10;
        else if (contrastVal < 0.45f) c += 5;

        // Brightness compensation
        if (brightness > 0.8f) h -= 15;
        else if (brightness > 0.65f) h -= 8;
        if (brightness < 0.2f) sh += 15;
        else if (brightness < 0.35f) sh += 8;

        // Noise → reduce saturation
        if (noise > 0.5f) sat = Math.max(sat - 10, -100);

        // Shadow attenuation for bright scenes
        if (sh > 0f) {
            float shadowScale = Math.max(0f, Math.min(1f,
                    1f - (brightness - 0.20f) / 0.55f));
            sh *= shadowScale;
        }

        // Hard cap on shadows
        sh = Math.min(sh, 40f);

        return new EnhancementResult(mode, c, h, sh, sat, w, t,
                shaderMode, shaderParams, usm, usmRadius, usmStrength);
    }

    // ── Smoothed computation with anti-feedback lock ────────────────

    /**
     * Compute enhancement for the current frame with anti-feedback protection.
     * Returns non-null only when enhancement should be applied/updated.
     * Returns null when locked (caller should keep current enhancement).
     */
    public EnhancementResult computeSmoothed(FrameAnalysis analysis) {
        if (analysis == null) return null;

        String scene = analysis.getSceneType();
        String lighting = analysis.getLightingCondition();
        String subject = analysis.getMainSubject();
        float brightness = analysis.getBrightnessValue();

        if (hasComputed) {
            long elapsed = System.currentTimeMillis() - lockTimeMs;
            if (elapsed < MIN_LOCK_MS) {
                return null;
            }

            boolean sceneChanged = !Objects.equals(scene, lockedScene)
                    || !Objects.equals(lighting, lockedLighting)
                    || !Objects.equals(subject, lockedSubject);
            boolean brightnessDrifted =
                    Math.abs(brightness - lockedBrightness) > BRIGHTNESS_DRIFT_THRESHOLD;

            if (!sceneChanged && !brightnessDrifted) {
                return null; // same scene → keep locked enhancement
            }
        }

        // Compute fresh enhancement
        EnhancementResult result = computeEnhancement(analysis);

        // Lock the context
        lockedScene = scene;
        lockedLighting = lighting;
        lockedSubject = subject;
        lockedBrightness = brightness;
        lockTimeMs = System.currentTimeMillis();
        hasComputed = true;

        current = result;
        return result; // may be null if no scene matched
    }

    /** Get the current locked enhancement result. */
    public EnhancementResult getCurrent() {
        return current;
    }

    /** Reset state (e.g. when toggling off or switching modes). */
    public void reset() {
        current = null;
        lockedScene = null;
        lockedLighting = null;
        lockedSubject = null;
        lockedBrightness = -1f;
        lockTimeMs = 0;
        hasComputed = false;
    }
}
