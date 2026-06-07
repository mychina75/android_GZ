package com.samsung.camera.intelligence.guidance;

import androidx.annotation.Nullable;

/**
 * Structured output of {@link CompositionAdvisor}. Carries at most ONE
 * primary technique (drives the bottom chip + top coach pill) and
 * optionally one secondary tip (only when stable + no primary). Designed
 * so the consumer UI says exactly one thing at a time.
 *
 * Phase 1 of plan-compositionGuidanceV2.
 */
public final class CompositionAdvice {

    public enum Severity { INFO, COACH, WARN, CRITICAL }

    public enum Source { MODEL_HEAD, HEURISTIC, SCENE_RULE, EXTERNAL_MODEL }

    /** Stable identifier of the firing technique (e.g. "rule_of_thirds"). */
    public final String techniqueId;
    /** Short label shown on the bottom chip. May be a string-resource key. */
    public final String chipLabel;
    /** Coach pill text shown at the top. May be null if only a chip applies. */
    @Nullable public final String coachHint;
    /** Optional secondary tip; only populated when no primary fires. */
    @Nullable public final String secondaryTip;
    public final Severity severity;
    public final Source source;
    /** Confidence in [0, 1] of the underlying signal. */
    public final float confidence;
    /** UI time-to-live in ms before the advice should fade. */
    public final long ttlMs;
    /** Wall-clock timestamp the advice was created (ms since epoch). */
    public final long timestampMs;

    private CompositionAdvice(Builder b) {
        this.techniqueId = b.techniqueId;
        this.chipLabel = b.chipLabel;
        this.coachHint = b.coachHint;
        this.secondaryTip = b.secondaryTip;
        this.severity = b.severity;
        this.source = b.source;
        this.confidence = b.confidence;
        this.ttlMs = b.ttlMs;
        this.timestampMs = b.timestampMs > 0 ? b.timestampMs : System.currentTimeMillis();
    }

    public boolean hasChip() { return chipLabel != null && !chipLabel.isEmpty(); }
    public boolean hasCoach() { return coachHint != null && !coachHint.isEmpty(); }
    public boolean hasSecondary() { return secondaryTip != null && !secondaryTip.isEmpty(); }

    public static Builder builder(String techniqueId) {
        return new Builder(techniqueId);
    }

    public static final class Builder {
        private final String techniqueId;
        private String chipLabel;
        private String coachHint;
        private String secondaryTip;
        private Severity severity = Severity.INFO;
        private Source source = Source.HEURISTIC;
        private float confidence = 1f;
        private long ttlMs = 4000L;
        private long timestampMs = 0L;

        private Builder(String techniqueId) {
            this.techniqueId = techniqueId;
        }

        public Builder chip(String s) { this.chipLabel = s; return this; }
        public Builder coach(String s) { this.coachHint = s; return this; }
        public Builder secondary(String s) { this.secondaryTip = s; return this; }
        public Builder severity(Severity s) { this.severity = s; return this; }
        public Builder source(Source s) { this.source = s; return this; }
        public Builder confidence(float c) { this.confidence = Math.max(0f, Math.min(1f, c)); return this; }
        public Builder ttlMs(long ms) { this.ttlMs = Math.max(500L, ms); return this; }
        public Builder timestampMs(long ms) { this.timestampMs = ms; return this; }

        public CompositionAdvice build() { return new CompositionAdvice(this); }
    }
}
