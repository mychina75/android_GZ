package com.samsung.camera.intelligence.trigger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-trigger temporal hysteresis for the live preview.
 *
 * <p>Removes single-frame flicker by requiring a trigger to clear its
 * threshold for {@code enterFrames} consecutive frames before it is reported
 * active, and to fall below {@code threshold * releaseRatio} for
 * {@code exitFrames} consecutive frames before it is released.  While held
 * active the previous state is maintained.</p>
 *
 * <p>Stateful — hold one instance per analyzer.  Config from the top-level
 * {@code __hysteresis__} block of {@code trigger_rules.json}.  There is no
 * Python mirror because offline evaluation has no frame stream.</p>
 */
public final class TriggerHysteresis {

    private final int enterFrames;
    private final int exitFrames;
    private final float releaseRatio;

    private final Map<String, Boolean> active = new HashMap<>();
    private final Map<String, Integer> enterCount = new HashMap<>();
    private final Map<String, Integer> exitCount = new HashMap<>();

    public TriggerHysteresis(int enterFrames, int exitFrames, float releaseRatio) {
        this.enterFrames = Math.max(1, enterFrames);
        this.exitFrames = Math.max(1, exitFrames);
        this.releaseRatio = releaseRatio;
    }

    /** Build from the parsed {@code __hysteresis__} JSON object (may be null). */
    public static TriggerHysteresis fromConfig(JSONObject cfg) {
        if (cfg == null) {
            // Identity defaults: 1/1 frame, ratio 1.0 ⇒ no smoothing.
            return new TriggerHysteresis(1, 1, 1.0f);
        }
        return new TriggerHysteresis(
                cfg.optInt("enter_frames", 3),
                cfg.optInt("exit_frames", 5),
                (float) cfg.optDouble("release_ratio", 0.7));
    }

    /**
     * Stabilise raw scores against thresholds.  Returns a map where suppressed
     * triggers are forced just below their threshold and confirmed triggers
     * are kept at their raw score.  Does not mutate {@code scores}.
     */
    public Map<String, Float> stabilize(Map<String, Float> scores,
                                        Map<String, Float> thresholds) {
        final float eps = 1e-3f;
        Map<String, Float> out = new HashMap<>(scores);
        for (Map.Entry<String, Float> e : scores.entrySet()) {
            String name = e.getKey();
            float score = e.getValue();
            float thr = thresholds.getOrDefault(name, 0.5f);
            boolean wasActive = active.getOrDefault(name, false);

            boolean confirmed;
            if (!wasActive) {
                if (score >= thr) {
                    int c = enterCount.getOrDefault(name, 0) + 1;
                    enterCount.put(name, c);
                    confirmed = c >= enterFrames;
                } else {
                    enterCount.put(name, 0);
                    confirmed = false;
                }
                exitCount.put(name, 0);
            } else {
                if (score < thr * releaseRatio) {
                    int c = exitCount.getOrDefault(name, 0) + 1;
                    exitCount.put(name, c);
                    confirmed = c < exitFrames;  // stay active until exit confirmed
                } else {
                    exitCount.put(name, 0);
                    confirmed = true;
                }
                enterCount.put(name, 0);
            }

            active.put(name, confirmed);
            if (!confirmed && score >= thr) {
                // Not yet confirmed: hold just below threshold so the pill
                // does not surface.
                out.put(name, Math.min(score, thr - eps));
            } else if (confirmed && score < thr) {
                // Confirmed-active but dipping: hold at threshold so it stays lit.
                out.put(name, Math.max(score, thr));
            }
        }
        return out;
    }
}
