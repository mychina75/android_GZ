package com.samsung.camera.intelligence.guidance;

/**
 * Holds a screen-normalized framing target once the preview has been steady
 * long enough. After acquisition the target remains "locked" for UX state,
 * but its geometry follows fresh candidates with a low-pass filter so the
 * crop frame and aim ring keep matching the live scene instead of freezing at
 * the first acquired position.
 */
public final class CompositionTargetLockController {

    public enum State { SEARCHING, ARMING, TARGET_LOCKED }

    private boolean enabled = true;
    private long acquireMs = 1200L;
    private int minStableFrames = 6;
    private float candidateIouThreshold = 0.80f;
    private float anchorTolerance = 0.04f;
    private float lockedFollowAlpha = 0.28f;

    private Candidate baseCandidate = null;
    private Candidate latestCandidate = null;
    private Candidate lockedTarget = null;
    private long stableStartMs = 0L;
    private int stableFrames = 0;
    private int targetRevision = 0;
    private State state = State.SEARCHING;

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setAcquireMs(long acquireMs) { this.acquireMs = Math.max(100L, acquireMs); }
    public void setMinStableFrames(int minStableFrames) {
        this.minStableFrames = Math.max(1, minStableFrames);
    }
    public void setCandidateIouThreshold(float candidateIouThreshold) {
        this.candidateIouThreshold = clamp(candidateIouThreshold, 0.05f, 0.99f);
    }
    public void setAnchorTolerance(float anchorTolerance) {
        this.anchorTolerance = Math.max(0.005f, anchorTolerance);
    }
    public void setLockedFollowAlpha(float lockedFollowAlpha) {
        this.lockedFollowAlpha = clamp(lockedFollowAlpha, 0.02f, 1.0f);
    }

    public boolean isTargetLocked() { return lockedTarget != null; }
    public int getTargetRevision() { return targetRevision; }

    public void reset() {
        baseCandidate = null;
        latestCandidate = null;
        lockedTarget = null;
        stableStartMs = 0L;
        stableFrames = 0;
        state = State.SEARCHING;
    }

    public Result update(float[] cropNorm,
                         float[] anchorNorm,
                         CompositionTemplate.Type templateType,
                         float[] sketchParams,
                         String shortName,
                         float[] ghostSizeNorm,
                         String source,
                         boolean usable,
                         long nowMs) {
        Candidate incoming = usable
                ? Candidate.create(cropNorm, anchorNorm, templateType, sketchParams,
                        shortName, ghostSizeNorm, source)
                : null;

        if (!enabled) {
            state = incoming == null ? State.SEARCHING : State.ARMING;
            return Result.from(incoming, false, 0f, 0, state);
        }

        if (incoming == null) {
            if (lockedTarget != null) {
                state = State.TARGET_LOCKED;
                return Result.from(lockedTarget, true, 1f, targetRevision, state);
            }
            baseCandidate = null;
            latestCandidate = null;
            stableFrames = 0;
            stableStartMs = 0L;
            state = State.SEARCHING;
            return Result.from(null, false, 0f, targetRevision, state);
        }

        if (lockedTarget != null) {
            if (lockedTarget.templateType != incoming.templateType) {
                lockedTarget = null;
                baseCandidate = incoming;
                latestCandidate = incoming;
                stableStartMs = nowMs;
                stableFrames = 1;
                state = State.ARMING;
                return Result.from(latestCandidate, false, 0f, targetRevision, state);
            }
            lockedTarget = lockedTarget.follow(incoming, lockedFollowAlpha);
            latestCandidate = incoming;
            state = State.TARGET_LOCKED;
            return Result.from(lockedTarget, true, 1f, targetRevision, state);
        }

        if (baseCandidate == null || !isSameCandidate(baseCandidate, incoming)) {
            baseCandidate = incoming;
            latestCandidate = incoming;
            stableStartMs = nowMs;
            stableFrames = 1;
            state = State.ARMING;
            return Result.from(latestCandidate, false, 0f, targetRevision, state);
        }

        latestCandidate = incoming;
        stableFrames++;
        long elapsedMs = Math.max(0L, nowMs - stableStartMs);
        float progress = Math.min(1f, elapsedMs / (float) acquireMs);
        if (stableFrames >= minStableFrames && elapsedMs >= acquireMs) {
            lockedTarget = latestCandidate;
            targetRevision++;
            state = State.TARGET_LOCKED;
            return Result.from(lockedTarget, true, 1f, targetRevision, state);
        }

        state = State.ARMING;
        return Result.from(latestCandidate, false, progress, targetRevision, state);
    }

    private boolean isSameCandidate(Candidate base, Candidate incoming) {
        if (base.templateType != incoming.templateType) return false;
        if (SuggestedCropStabilizer.iou(base.cropNorm, incoming.cropNorm) < candidateIouThreshold) {
            return false;
        }
        float dx = base.anchorNorm[0] - incoming.anchorNorm[0];
        float dy = base.anchorNorm[1] - incoming.anchorNorm[1];
        return (dx * dx + dy * dy) <= (anchorTolerance * anchorTolerance);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float[] cloneOrNull(float[] v) {
        return v == null ? null : v.clone();
    }

    private static boolean validCrop(float[] c) {
        return c != null && c.length >= 4
                && isFinite(c[0]) && isFinite(c[1]) && isFinite(c[2]) && isFinite(c[3])
                && c[2] > 0.03f && c[3] > 0.03f;
    }

    private static boolean validAnchor(float[] a) {
        return a != null && a.length >= 2
                && isFinite(a[0]) && isFinite(a[1])
                && a[0] >= -0.25f && a[0] <= 1.25f
                && a[1] >= -0.25f && a[1] <= 1.25f;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static final class Candidate {
        final float[] cropNorm;
        final float[] anchorNorm;
        final CompositionTemplate.Type templateType;
        final float[] sketchParams;
        final String shortName;
        final float[] ghostSizeNorm;
        final String source;

        private Candidate(float[] cropNorm,
                          float[] anchorNorm,
                          CompositionTemplate.Type templateType,
                          float[] sketchParams,
                          String shortName,
                          float[] ghostSizeNorm,
                          String source) {
            this.cropNorm = cropNorm;
            this.anchorNorm = anchorNorm;
            this.templateType = templateType;
            this.sketchParams = sketchParams;
            this.shortName = shortName;
            this.ghostSizeNorm = ghostSizeNorm;
            this.source = source == null ? "candidate" : source;
        }

        static Candidate create(float[] cropNorm,
                                float[] anchorNorm,
                                CompositionTemplate.Type templateType,
                                float[] sketchParams,
                                String shortName,
                                float[] ghostSizeNorm,
                                String source) {
            if (!validCrop(cropNorm) || !validAnchor(anchorNorm)) return null;
            return new Candidate(
                    cropNorm.clone(),
                    new float[]{anchorNorm[0], anchorNorm[1]},
                    templateType == null ? CompositionTemplate.Type.RULE_OF_THIRDS : templateType,
                    sketchParams == null ? new float[0] : sketchParams.clone(),
                    shortName == null ? "" : shortName,
                    cloneOrNull(ghostSizeNorm),
                    source
            );
        }

        Candidate follow(Candidate incoming, float alpha) {
            return new Candidate(
                    blendArray(cropNorm, incoming.cropNorm, alpha),
                    blendArray(anchorNorm, incoming.anchorNorm, alpha),
                    incoming.templateType,
                    blendArrayOrIncoming(sketchParams, incoming.sketchParams, alpha),
                    incoming.shortName,
                    blendArrayOrIncoming(ghostSizeNorm, incoming.ghostSizeNorm, alpha),
                    incoming.source
            );
        }
    }

    private static float[] blendArray(float[] current, float[] incoming, float alpha) {
        float[] out = current.clone();
        int n = Math.min(out.length, incoming.length);
        for (int i = 0; i < n; i++) {
            out[i] += alpha * (incoming[i] - out[i]);
        }
        return out;
    }

    private static float[] blendArrayOrIncoming(float[] current, float[] incoming, float alpha) {
        if (incoming == null) return cloneOrNull(current);
        if (current == null || current.length != incoming.length) return incoming.clone();
        return blendArray(current, incoming, alpha);
    }

    public static final class Result {
        private final float[] targetCropNorm;
        private final float[] anchorNorm;
        private final CompositionTemplate.Type templateType;
        private final float[] sketchParams;
        private final String shortName;
        private final float[] ghostSizeNorm;
        private final String source;
        private final boolean targetLocked;
        private final float progress;
        private final int targetRevision;
        private final State state;

        private Result(Candidate candidate,
                       boolean targetLocked,
                       float progress,
                       int targetRevision,
                       State state) {
            this.targetCropNorm = candidate == null ? null : candidate.cropNorm.clone();
            this.anchorNorm = candidate == null ? null : candidate.anchorNorm.clone();
            this.templateType = candidate == null ? CompositionTemplate.Type.RULE_OF_THIRDS
                    : candidate.templateType;
            this.sketchParams = candidate == null ? new float[0] : candidate.sketchParams.clone();
            this.shortName = candidate == null ? "" : candidate.shortName;
            this.ghostSizeNorm = candidate == null ? null : cloneOrNull(candidate.ghostSizeNorm);
            this.source = candidate == null ? "none" : candidate.source;
            this.targetLocked = targetLocked;
            this.progress = clamp(progress, 0f, 1f);
            this.targetRevision = targetRevision;
            this.state = state == null ? State.SEARCHING : state;
        }

        static Result from(Candidate candidate,
                           boolean targetLocked,
                           float progress,
                           int targetRevision,
                           State state) {
            return new Result(candidate, targetLocked, progress, targetRevision, state);
        }

        public float[] getTargetCropNorm() { return cloneOrNull(targetCropNorm); }
        public float[] getAnchorNorm() { return cloneOrNull(anchorNorm); }
        public CompositionTemplate.Type getTemplateType() { return templateType; }
        public float[] getSketchParams() { return cloneOrNull(sketchParams); }
        public String getShortName() { return shortName; }
        public float[] getGhostSizeNorm() { return cloneOrNull(ghostSizeNorm); }
        public String getSource() { return source; }
        public boolean isTargetLocked() { return targetLocked; }
        public float getProgress() { return progress; }
        public int getTargetRevision() { return targetRevision; }
        public State getState() { return state; }
    }
}
