package com.samsung.camera.intelligence.guidance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Temporal Smoother — smooths model predictions across consecutive camera
 * preview frames to prevent guidance overlays from flickering.
 *
 * Uses exponential moving averages for continuous values, hysteresis for
 * binary flags, and cooldown-based debouncing for alert badges.
 *
 * Ported from Python temporal_smoother.py
 */
public class TemporalSmoother {

    // ---- Config ----

    private float emaAlpha = 0.3f;
    private int hysteresisOnFrames = 3;
    private int hysteresisOffFrames = 3;
    private float alertMinDisplayMs = 2000f;  // 2 seconds in ms
    private float alertCooldownMs = 5000f;    // 5 seconds in ms
    private float arrowEmaAlpha = 0.25f;

    private static final float FAST_SUBJECT_DEAD_ZONE = 0.006f;
    private static final float FAST_SUBJECT_FAST_DISTANCE = 0.120f;
    private static final float FAST_SUBJECT_MIN_ALPHA = 0.18f;
    private static final float FAST_SUBJECT_MAX_ALPHA = 0.65f;

    // Embedding-based scene stability config
    private static final float SCENE_SAME_THRESHOLD = 0.92f;
    private static final float SCENE_CHANGE_THRESHOLD = 0.85f;
    private static final int SCENE_TYPE_WINDOW = 5;
    private static final float ARROW_DEAD_ZONE = 0.15f;

    // ---- State ----

    // EMA state for continuous values
    private final Map<String, Float> ema = new HashMap<>();

    // Binary hysteresis state
    private final Map<String, Boolean> binaryRaw = new HashMap<>();
    private final Map<String, Integer> binaryCount = new HashMap<>();
    private final Map<String, Boolean> binarySmoothed = new HashMap<>();

    // Alert dedup / cooldown (alert_id → timestamp ms)
    private final Map<String, Long> alertLastShown = new HashMap<>();
    private final Map<String, Long> alertActive = new HashMap<>();

    // Categorical smoothing: majority-vote over a sliding window
    private static final int CATEGORY_WINDOW = 3;
    private final List<String> mainSubjectHistory = new ArrayList<>();
    private final List<String> lightingHistory = new ArrayList<>();
    private final List<String> motionHistory = new ArrayList<>();
    private final List<String> sceneTypeHistory = new ArrayList<>();

    // Embedding-based scene stability state
    private float[] prevEmbedding = null;
    private String lockedSceneType = null;
    private boolean sceneStable = false;
    private float lastCosineSimilarity = 0.0f;

    // Arrow hysteresis state (per-direction on/off counters)
    private final Map<String, Integer> arrowOnCount = new HashMap<>();
    private final Map<String, Boolean> arrowActive = new HashMap<>();

    private Float fastSubjectCenterX = null;
    private Float fastSubjectCenterY = null;
    public TemporalSmoother() {}

    // Config setters
    public void setEmaAlpha(float v) { this.emaAlpha = v; }
    public void setHysteresisOnFrames(int v) { this.hysteresisOnFrames = v; }
    public void setHysteresisOffFrames(int v) { this.hysteresisOffFrames = v; }
    public void setAlertMinDisplayMs(float v) { this.alertMinDisplayMs = v; }
    public void setAlertCooldownMs(float v) { this.alertCooldownMs = v; }
    public void setArrowEmaAlpha(float v) { this.arrowEmaAlpha = v; }

    // ---- Public API ----

    /**
     * Apply temporal smoothing to a FrameAnalysis (modifies in-place).
     *
     * - Continuous fields: EMA-smoothed
     * - Binary flags: hysteresis
     * - Composition issues list: per-issue hysteresis
     */
    public FrameAnalysis smoothAnalysis(FrameAnalysis analysis) {
        float alpha = emaAlpha;

        // --- Embedding-based scene stability ---
        float[] currentEmbedding = analysis.getFeatureEmbedding();
        String rawSceneType = analysis.getSceneType();
        if (currentEmbedding != null && currentEmbedding.length > 0) {
            if (prevEmbedding != null && prevEmbedding.length == currentEmbedding.length) {
                float cosSim = dotProduct(prevEmbedding, currentEmbedding);
                lastCosineSimilarity = cosSim;
                if (cosSim > SCENE_SAME_THRESHOLD) {
                    // Same scene — lock scene type
                    sceneStable = true;
                    if (lockedSceneType != null) {
                        analysis.setSceneType(lockedSceneType);
                    } else {
                        lockedSceneType = rawSceneType;
                    }
                } else if (cosSim < SCENE_CHANGE_THRESHOLD) {
                    // Scene changed — accept new scene type immediately
                    sceneStable = false;
                    lockedSceneType = rawSceneType;
                    sceneTypeHistory.clear();
                } else {
                    // Transitional — use majority-vote
                    sceneStable = false;
                    String smoothed = categoricalSmooth(sceneTypeHistory, rawSceneType, SCENE_TYPE_WINDOW);
                    analysis.setSceneType(smoothed);
                    lockedSceneType = smoothed;
                }
            } else {
                // First frame with embedding
                lockedSceneType = rawSceneType;
                sceneStable = false;
            }
            prevEmbedding = currentEmbedding.clone();
        } else {
            // No embedding — fall back to majority-vote for scene type
            analysis.setSceneType(categoricalSmooth(sceneTypeHistory, rawSceneType, SCENE_TYPE_WINDOW));
        }

        // --- Categorical smoothing ---
        analysis.setMainSubject(categoricalSmooth(mainSubjectHistory, analysis.getMainSubject()));
        analysis.setLightingCondition(categoricalSmooth(lightingHistory, analysis.getLightingCondition()));
        analysis.setMotionType(categoricalSmooth(motionHistory, analysis.getMotionType()));

        // --- Continuous EMA ---
        analysis.setCompositionScore(emaUpdate("composition_score", analysis.getCompositionScore(), alpha));
        analysis.setContrastValue(emaUpdate("contrast_value", analysis.getContrastValue(), alpha));
        analysis.setSharpnessValue(emaUpdate("sharpness_value", analysis.getSharpnessValue(), alpha));
        analysis.setNoiseLevel(emaUpdate("noise_level", analysis.getNoiseLevel(), alpha));
        analysis.setTiltAngle(emaUpdate("tilt_angle", analysis.getTiltAngle(), alpha));
        analysis.setFlowMagnitude(emaUpdate("flow_magnitude", analysis.getFlowMagnitude(), alpha));

        // Confidence values with lighter smoothing
        float confAlpha = Math.min(alpha * 1.5f, 0.9f);
        analysis.setSceneConfidence(emaUpdate("scene_confidence", analysis.getSceneConfidence(), confAlpha));
        analysis.setLightingConfidence(emaUpdate("lighting_confidence", analysis.getLightingConfidence(), confAlpha));
        analysis.setMotionConfidence(emaUpdate("motion_confidence", analysis.getMotionConfidence(), confAlpha));
        analysis.setSubjectConfidence(emaUpdate("subject_confidence", analysis.getSubjectConfidence(), confAlpha));

        updateFastSubjectCenter(analysis, analysis.getSubjectCenterX(), analysis.getSubjectCenterY());

        // Stable subject center used by scoring and lock state machines.
        if (analysis.getSubjectCenterX() != null) {
            analysis.setSubjectCenterX(emaUpdate("subject_center_x", analysis.getSubjectCenterX(), alpha));
        }
        if (analysis.getSubjectCenterY() != null) {
            analysis.setSubjectCenterY(emaUpdate("subject_center_y", analysis.getSubjectCenterY(), alpha));
        }

        // --- Binary hysteresis ---
        analysis.setTilted(hysteresisUpdate("is_tilted", analysis.isTilted()));
        analysis.setNeedsCompositionEdit(hysteresisUpdate("needs_composition_edit", analysis.isNeedsCompositionEdit()));
        analysis.setHasFace(hysteresisUpdate("has_face", analysis.isHasFace()));
        analysis.setHasText(hysteresisUpdate("has_text", analysis.isHasText()));
        analysis.setHasShadow(hysteresisUpdate("has_shadow", analysis.isHasShadow()));
        analysis.setHasReflection(hysteresisUpdate("has_reflection", analysis.isHasReflection()));
        analysis.setHasBackgroundPeople(hysteresisUpdate("has_background_people", analysis.isHasBackgroundPeople()));
        analysis.setHasFlare(hysteresisUpdate("has_flare", analysis.isHasFlare()));
        analysis.setHasMoire(hysteresisUpdate("has_moire", analysis.isHasMoire()));
        // Finger obstruction uses stricter hysteresis (5 consecutive frames)
        // to avoid false positives from normal dark edges / vignetting.
        analysis.setFingerObstruction(
                hysteresisUpdateCustom("finger_obstruction", analysis.isFingerObstruction(), 5, 2));

        // --- Composition issues hysteresis (per-issue) ---
        Set<String> currentIssues = new HashSet<>(analysis.getCompositionIssues());
        Set<String> allIssueKeys = new HashSet<>(binarySmoothed.keySet());
        for (String issue : currentIssues) {
            allIssueKeys.add("comp_issue_" + issue);
        }

        List<String> smoothedIssues = new ArrayList<>();
        for (String key : allIssueKeys) {
            if (!key.startsWith("comp_issue_")) continue;
            String issue = key.substring("comp_issue_".length());
            boolean present = currentIssues.contains(issue);
            if (hysteresisUpdate(key, present)) {
                smoothedIssues.add(issue);
            }
        }
        analysis.setCompositionIssues(smoothedIssues);

        return analysis;
    }

    /**
     * Apply temporal smoothing to the overlay list.
     *
     * - AlertBadge: debounce (min display, cooldown)
     * - DirectionArrow: EMA smooth magnitude
     * - HorizonLine: EMA smooth angle
     */
    public List<GuidanceOverlay> smoothOverlays(List<GuidanceOverlay> overlays) {
        List<GuidanceOverlay> result = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (GuidanceOverlay ov : overlays) {
            if (ov instanceof AlertBadge) {
                AlertBadge smoothed = debounceAlert((AlertBadge) ov, now);
                if (smoothed != null) {
                    result.add(smoothed);
                }
            } else if (ov instanceof DirectionArrow) {
                DirectionArrow arrow = (DirectionArrow) ov;
                String dir = arrow.getDirection();
                float smoothedMag = emaUpdate(
                        "arrow_" + dir + "_mag",
                        arrow.getMagnitude(),
                        arrowEmaAlpha
                );
                // Dead zone: suppress very small arrows
                if (smoothedMag < ARROW_DEAD_ZONE) {
                    updateArrowHysteresis(dir, false);
                    continue;
                }
                // Arrow hysteresis: require sustained presence
                if (!updateArrowHysteresis(dir, true)) {
                    continue;
                }
                arrow.setMagnitude(smoothedMag);
                result.add(arrow);
            } else if (ov instanceof HorizonLine) {
                HorizonLine horizon = (HorizonLine) ov;
                float smoothedAngle = emaUpdate(
                        "horizon_tilt",
                        horizon.getTiltAngleDeg(),
                        emaAlpha
                );
                horizon.setTiltAngleDeg(smoothedAngle);
                horizon.setCorrectionDeg(-smoothedAngle);
                result.add(horizon);
            } else {
                result.add(ov);
            }
        }

        // Re-inject alerts still within min display window
        for (Map.Entry<String, Long> entry : new HashMap<>(alertActive).entrySet()) {
            String alertId = entry.getKey();
            long activationTime = entry.getValue();
            long elapsed = now - activationTime;
            if (elapsed < (long) alertMinDisplayMs) {
                boolean alreadyPresent = false;
                for (GuidanceOverlay o : result) {
                    if (o instanceof AlertBadge && alertId.equals(((AlertBadge) o).getAlertId())) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    result.add(new AlertBadge(
                            GuidanceCategory.COMPOSITION,
                            GuidanceUrgency.INFO,
                            alertId.replace("_", " "),
                            alertId,
                            ""
                    ));
                }
            }
        }

        return result;
    }

    /**
     * Clear all smoothing state (e.g. on camera switch).
     */
    /**
     * Returns true if the scene is considered stable (embedding similarity > SCENE_SAME_THRESHOLD).
     */
    public boolean isSceneStable() {
        return sceneStable;
    }

    /**
     * Returns the locked scene type, or null if not yet locked.
     */
    public String getLockedSceneType() {
        return lockedSceneType;
    }

    public void reset() {
        ema.clear();
        binaryRaw.clear();
        binaryCount.clear();
        binarySmoothed.clear();
        alertLastShown.clear();
        alertActive.clear();
        mainSubjectHistory.clear();
        lightingHistory.clear();
        motionHistory.clear();
        sceneTypeHistory.clear();
        prevEmbedding = null;
        lockedSceneType = null;
        sceneStable = false;
        lastCosineSimilarity = 0.0f;
        arrowOnCount.clear();
        arrowActive.clear();
        fastSubjectCenterX = null;
        fastSubjectCenterY = null;
    }

    // ---- Internal helpers ----

    /**
     * Majority-vote smoothing for categorical labels over a sliding window.
     * Prevents scene type / subject / lighting from flickering between frames.
     */
    private String categoricalSmooth(List<String> history, String rawValue) {
        return categoricalSmooth(history, rawValue, CATEGORY_WINDOW);
    }

    private String categoricalSmooth(List<String> history, String rawValue, int windowSize) {
        history.add(rawValue);
        if (history.size() > windowSize) {
            history.remove(0);
        }
        // Find the most frequent value in the window
        Map<String, Integer> freq = new HashMap<>();
        for (String v : history) {
            freq.put(v, freq.getOrDefault(v, 0) + 1);
        }
        String best = rawValue;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : freq.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    private float emaUpdate(String key, float raw, float alpha) {
        Float prev = ema.get(key);
        if (prev == null) {
            ema.put(key, raw);
            return raw;
        }
        float smoothed = alpha * raw + (1.0f - alpha) * prev;
        ema.put(key, smoothed);
        return smoothed;
    }

    private void updateFastSubjectCenter(FrameAnalysis analysis,
                                         Float rawCenterX,
                                         Float rawCenterY) {
        if (rawCenterX == null || rawCenterY == null) {
            analysis.setFastSubjectCenterX(null);
            analysis.setFastSubjectCenterY(null);
            fastSubjectCenterX = null;
            fastSubjectCenterY = null;
            return;
        }

        float clampedRawX = clamp01(rawCenterX);
        float clampedRawY = clamp01(rawCenterY);
        if (fastSubjectCenterX == null || fastSubjectCenterY == null) {
            fastSubjectCenterX = clampedRawX;
            fastSubjectCenterY = clampedRawY;
        } else {
            float distanceToFast = (float) Math.hypot(
                    clampedRawX - fastSubjectCenterX,
                    clampedRawY - fastSubjectCenterY);
            if (distanceToFast > FAST_SUBJECT_DEAD_ZONE) {
                float alpha = fastSubjectAlpha(distanceToFast);
                fastSubjectCenterX += alpha * (clampedRawX - fastSubjectCenterX);
                fastSubjectCenterY += alpha * (clampedRawY - fastSubjectCenterY);
            }
        }

        analysis.setFastSubjectCenterX(fastSubjectCenterX);
        analysis.setFastSubjectCenterY(fastSubjectCenterY);
    }

    private float fastSubjectAlpha(float distanceToFast) {
        if (distanceToFast <= FAST_SUBJECT_DEAD_ZONE) {
            return FAST_SUBJECT_MIN_ALPHA;
        }
        if (distanceToFast >= FAST_SUBJECT_FAST_DISTANCE) {
            return FAST_SUBJECT_MAX_ALPHA;
        }
        float progress = (distanceToFast - FAST_SUBJECT_DEAD_ZONE)
                / (FAST_SUBJECT_FAST_DISTANCE - FAST_SUBJECT_DEAD_ZONE);
        return FAST_SUBJECT_MIN_ALPHA
                + progress * (FAST_SUBJECT_MAX_ALPHA - FAST_SUBJECT_MIN_ALPHA);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private boolean hysteresisUpdate(String key, boolean rawValue) {
        Boolean prevRaw = binaryRaw.get(key);
        if (prevRaw == null) {
            // First frame — accept raw
            binaryRaw.put(key, rawValue);
            binaryCount.put(key, 1);
            binarySmoothed.put(key, rawValue);
            return rawValue;
        }

        if (rawValue == prevRaw) {
            binaryCount.put(key, binaryCount.getOrDefault(key, 0) + 1);
        } else {
            binaryRaw.put(key, rawValue);
            binaryCount.put(key, 1);
        }

        boolean currentSmoothed = binarySmoothed.getOrDefault(key, false);
        int count = binaryCount.getOrDefault(key, 0);

        if (rawValue && !currentSmoothed) {
            // Trying to activate
            if (count >= hysteresisOnFrames) {
                binarySmoothed.put(key, true);
            }
        } else if (!rawValue && currentSmoothed) {
            // Trying to deactivate
            if (count >= hysteresisOffFrames) {
                binarySmoothed.put(key, false);
            }
        }

        return binarySmoothed.getOrDefault(key, false);
    }

    /** Hysteresis with per-key on/off frame thresholds. */
    private boolean hysteresisUpdateCustom(String key, boolean rawValue, int onFrames, int offFrames) {
        Boolean prevRaw = binaryRaw.get(key);
        if (prevRaw == null) {
            binaryRaw.put(key, rawValue);
            binaryCount.put(key, 1);
            binarySmoothed.put(key, rawValue);
            return rawValue;
        }

        if (rawValue == prevRaw) {
            binaryCount.put(key, binaryCount.getOrDefault(key, 0) + 1);
        } else {
            binaryRaw.put(key, rawValue);
            binaryCount.put(key, 1);
        }

        boolean currentSmoothed = binarySmoothed.getOrDefault(key, false);
        int count = binaryCount.getOrDefault(key, 0);

        if (rawValue && !currentSmoothed) {
            if (count >= onFrames) {
                binarySmoothed.put(key, true);
            }
        } else if (!rawValue && currentSmoothed) {
            if (count >= offFrames) {
                binarySmoothed.put(key, false);
            }
        }

        return binarySmoothed.getOrDefault(key, false);
    }

    private static float dotProduct(float[] a, float[] b) {
        float sum = 0.0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Arrow hysteresis: track per-direction on/off with 3-frame threshold.
     * Returns true if the arrow should be shown.
     */
    private boolean updateArrowHysteresis(String direction, boolean wantShow) {
        Boolean currentlyActive = arrowActive.get(direction);
        if (currentlyActive == null) {
            currentlyActive = false;
        }
        int count = arrowOnCount.getOrDefault(direction, 0);

        if (wantShow) {
            count = Math.max(count, 0) + 1;
        } else {
            count = Math.min(count, 0) - 1;
        }
        arrowOnCount.put(direction, count);

        if (!currentlyActive && count >= hysteresisOnFrames) {
            arrowActive.put(direction, true);
            return true;
        } else if (currentlyActive && count <= -hysteresisOffFrames) {
            arrowActive.put(direction, false);
            return false;
        }
        return currentlyActive;
    }

    private AlertBadge debounceAlert(AlertBadge alert, long now) {
        String aid = alert.getAlertId();

        // Check cooldown
        long lastShown = alertLastShown.getOrDefault(aid, 0L);
        if (now - lastShown < (long) alertCooldownMs) {
            // Still in cooldown — but allow if already active (min display)
            if (alertActive.containsKey(aid)) {
                return alert;
            }
            return null;
        }

        // Activate
        if (!alertActive.containsKey(aid)) {
            alertActive.put(aid, now);
            alertLastShown.put(aid, now);
        }

        return alert;
    }
}
