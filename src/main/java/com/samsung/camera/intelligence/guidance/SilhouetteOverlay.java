package com.samsung.camera.intelligence.guidance;

/**
 * Phase 8 (Composition v2 — Plan A) — Silhouette overlay payload.
 *
 * Carries the GAIC v2 recommended crop bounding box together with the
 * SilhouetteController state machine output so the renderer can draw the
 * "ghost frame" outline at the right intensity. Subject silhouette is
 * intentionally OFF in Plan A — the U²-Netp gate failed (median IoU 0.36,
 * p10 0.04). See {@code /memories/repo/silhouette_ui_phase8_10_decisions.md}.
 *
 * Coordinates are normalized [0,1] in {@code (x, y, w, h)} order, same
 * convention as {@link FramingTemplateOverlay#getTargetCropNorm()}.
 */
public class SilhouetteOverlay extends GuidanceOverlay {

    /** Silhouette state machine — see {@link SilhouetteController}. */
    public enum State { OFF, ARMING, ACTIVE, LOCKED }

    private final float[] cropNorm;
    private final State state;
    /** 0..1 — used by ARMING to fade the outline in. */
    private final float progress;
    /** Set true on the single frame the controller transitioned to LOCKED so the renderer can fire haptic feedback once. */
    private final boolean lockTransition;

    public SilhouetteOverlay(GuidanceCategory category,
                             GuidanceUrgency urgency,
                             String message,
                             float[] cropNorm,
                             State state,
                             float progress,
                             boolean lockTransition) {
        super(category, urgency, message);
        this.cropNorm = cropNorm;
        this.state = state;
        this.progress = progress;
        this.lockTransition = lockTransition;
    }

    public float[] getCropNorm() { return cropNorm; }
    public State getState() { return state; }
    public float getProgress() { return progress; }
    public boolean isLockTransition() { return lockTransition; }
}
