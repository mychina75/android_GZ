package com.samsung.camera.intelligence.guidance;

import androidx.annotation.Nullable;

/**
 * A single composition technique rule. Each rule maps a PetaPixel-style
 * principle (e.g. #1 Rule of Thirds, #5 Leading Lines) onto a structured
 * trigger predicate over {@link FrameAnalysis} plus the canned UI payload
 * to emit when it fires.
 *
 * Phase 1 of plan-compositionGuidanceV2.
 */
public final class TechniqueRule {

    /** Lower number = higher priority (1 wins over 2 when both fire). */
    public final int priority;
    public final String techniqueId;
    public final boolean requiresTargetLock;
    public final long cooldownMs;
    public final Trigger trigger;

    public TechniqueRule(int priority,
                         String techniqueId,
                         boolean requiresTargetLock,
                         long cooldownMs,
                         Trigger trigger) {
        this.priority = priority;
        this.techniqueId = techniqueId;
        this.requiresTargetLock = requiresTargetLock;
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.trigger = trigger;
    }

    /**
     * Predicate + advice factory. Returns a fully populated
     * {@link CompositionAdvice} when the rule fires, or null when it does
     * not. Implementations must be pure and cheap (no I/O, no allocation
     * of large buffers); they are evaluated on every guidance frame.
     */
    public interface Trigger {
        @Nullable CompositionAdvice tryFire(Context ctx);
    }

    /** Per-frame evaluation context passed to every trigger. */
    public static final class Context {
        public final FrameAnalysis analysis;
        /** True after CompositionTargetLockController has TARGET_LOCKED. */
        public final boolean targetLocked;
        /** Latest alignment score in [0, 1] from the framing template. */
        public final float alignmentScore;
        /** Wall-clock now, threaded through so unit tests can fake time. */
        public final long nowMs;

        public Context(FrameAnalysis analysis,
                       boolean targetLocked,
                       float alignmentScore,
                       long nowMs) {
            this.analysis = analysis;
            this.targetLocked = targetLocked;
            this.alignmentScore = alignmentScore;
            this.nowMs = nowMs;
        }
    }
}
