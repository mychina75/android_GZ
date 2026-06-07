package com.samsung.camera.intelligence.guidance;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 6 — "Aim & Capture" overlay payload. Carries everything the
 * renderer needs to draw the consumer-friendly framing UX:
 *
 *   - {@link #templateType} — composition style (RoT, symmetry, spiral …)
 *   - {@link #targetCropNorm} — recommended crop, used for snap-to-template
 *     capture and the auto-zoom controller
 *   - {@link #anchorNorm} — the bullseye centre (the green target ring)
 *   - {@link #liveSubjectCenterNorm} — stable subject centre for scoring
 *   - {@link #fastLiveSubjectCenterNorm} — responsive display-only live dot
 *   - {@link #alignmentScore} — 0..1 distance-only score driving the arc
 *     and the lock state machine
 *   - {@link #sketchParams} — template-specific guide-line geometry
 *   - {@link #state} — SEARCHING / GUIDING / NEAR / LOCKED / ZOOMING
 *   - {@link #coachHint} — short, friendly localized hint
 *
 * Comparison overlay (drawn in distinct colors when the user enables
 * the external sub-models so they can A/B them against the main heads):
 *   - {@link #externalSubjectBboxNorm}
 *   - {@link #externalSubjectCenterNorm}
 *   - {@link #externalCropNorm}
 *   - {@link #externalCropAestheticScore}
 *
 * Legacy fields ({@link #subjectGhostSizeNorm}, {@link #panHints},
 * {@link #matchScore}) are retained for the Pro HUD widgets.
 */
public class FramingTemplateOverlay extends GuidanceOverlay {

    public enum TemplateState { HIDDEN, SEARCHING, GUIDING, NEAR, LOCKED, ZOOMING }

    private final CompositionTemplate.Type templateType;
    private final float[] targetCropNorm;
    private final float[] anchorNorm;
    private final float[] liveSubjectCenterNorm;
    private final float[] fastLiveSubjectCenterNorm;
    private final float[] sketchParams;
    private final float alignmentScore;
    private final TemplateState state;
    private final String coachHint;
    private final String shortName;
    private final float targetZoomRatio;

    // Legacy / Pro-HUD fields.
    private final float[] subjectGhostSizeNorm;
    private final float[] panHints;
    private final float matchScore;

    // External sub-model comparison overlay.
    private final float[] externalSubjectBboxNorm;
    private final float[] externalSubjectCenterNorm;
    private final float[] externalCropNorm;
    private final float externalCropAestheticScore;

    // Raw multi-task model suggested_crop (debug visualization). May differ
    // from {@link #targetCropNorm}, which is the post-stabilizer / heuristic-
    // overridden version used for guidance.
    private final float[] rawSuggestedCropNorm;

    private final boolean targetLocked;
    private final float targetLockProgress;
    private final int targetRevision;
    private final String targetSource;

    public FramingTemplateOverlay(GuidanceCategory category,
                                  GuidanceUrgency urgency,
                                  String message,
                                  CompositionTemplate.Type templateType,
                                  float[] targetCropNorm,
                                  float[] anchorNorm,
                                  float[] liveSubjectCenterNorm,
                                  float[] sketchParams,
                                  float alignmentScore,
                                  TemplateState state,
                                  String coachHint,
                                  String shortName,
                                  float targetZoomRatio,
                                  float[] subjectGhostSizeNorm,
                                  float[] panHints,
                                  float matchScore,
                                  float[] externalSubjectBboxNorm,
                                  float[] externalSubjectCenterNorm,
                                  float[] externalCropNorm,
                                  float externalCropAestheticScore,
                                  float[] rawSuggestedCropNorm) {
        this(category, urgency, message, templateType, targetCropNorm, anchorNorm,
                liveSubjectCenterNorm, sketchParams, alignmentScore, state, coachHint,
                shortName, targetZoomRatio, subjectGhostSizeNorm, panHints, matchScore,
                externalSubjectBboxNorm, externalSubjectCenterNorm, externalCropNorm,
                externalCropAestheticScore, rawSuggestedCropNorm, false, 0f, 0, "live");
    }

    public FramingTemplateOverlay(GuidanceCategory category,
                                  GuidanceUrgency urgency,
                                  String message,
                                  CompositionTemplate.Type templateType,
                                  float[] targetCropNorm,
                                  float[] anchorNorm,
                                  float[] liveSubjectCenterNorm,
                                  float[] sketchParams,
                                  float alignmentScore,
                                  TemplateState state,
                                  String coachHint,
                                  String shortName,
                                  float targetZoomRatio,
                                  float[] subjectGhostSizeNorm,
                                  float[] panHints,
                                  float matchScore,
                                  float[] externalSubjectBboxNorm,
                                  float[] externalSubjectCenterNorm,
                                  float[] externalCropNorm,
                                  float externalCropAestheticScore,
                                  float[] rawSuggestedCropNorm,
                                  boolean targetLocked,
                                  float targetLockProgress,
                                  int targetRevision,
                                  String targetSource) {
                    this(category, urgency, message, templateType, targetCropNorm, anchorNorm,
                        liveSubjectCenterNorm, null, sketchParams, alignmentScore, state, coachHint,
                        shortName, targetZoomRatio, subjectGhostSizeNorm, panHints, matchScore,
                        externalSubjectBboxNorm, externalSubjectCenterNorm, externalCropNorm,
                        externalCropAestheticScore, rawSuggestedCropNorm, targetLocked,
                        targetLockProgress, targetRevision, targetSource);
                    }

                    public FramingTemplateOverlay(GuidanceCategory category,
                                  GuidanceUrgency urgency,
                                  String message,
                                  CompositionTemplate.Type templateType,
                                  float[] targetCropNorm,
                                  float[] anchorNorm,
                                  float[] liveSubjectCenterNorm,
                                  float[] fastLiveSubjectCenterNorm,
                                  float[] sketchParams,
                                  float alignmentScore,
                                  TemplateState state,
                                  String coachHint,
                                  String shortName,
                                  float targetZoomRatio,
                                  float[] subjectGhostSizeNorm,
                                  float[] panHints,
                                  float matchScore,
                                  float[] externalSubjectBboxNorm,
                                  float[] externalSubjectCenterNorm,
                                  float[] externalCropNorm,
                                  float externalCropAestheticScore,
                                  float[] rawSuggestedCropNorm,
                                  boolean targetLocked,
                                  float targetLockProgress,
                                  int targetRevision,
                                  String targetSource) {
        super(category, urgency, message);
        this.overlayType = "framing_template";
        this.templateType = templateType == null
                ? CompositionTemplate.Type.RULE_OF_THIRDS : templateType;
        this.targetCropNorm = targetCropNorm;
        this.anchorNorm = anchorNorm;
        this.liveSubjectCenterNorm = liveSubjectCenterNorm;
                    this.fastLiveSubjectCenterNorm = fastLiveSubjectCenterNorm;
        this.sketchParams = sketchParams == null ? new float[0] : sketchParams;
        this.alignmentScore = alignmentScore;
        this.state = state == null ? TemplateState.GUIDING : state;
        this.coachHint = coachHint == null ? "" : coachHint;
        this.shortName = shortName == null ? "" : shortName;
        this.targetZoomRatio = targetZoomRatio;
        this.subjectGhostSizeNorm = subjectGhostSizeNorm;
        this.panHints = panHints == null ? new float[]{0f, 0f, 0f, 0f} : panHints;
        this.matchScore = matchScore;
        this.externalSubjectBboxNorm = externalSubjectBboxNorm;
        this.externalSubjectCenterNorm = externalSubjectCenterNorm;
        this.externalCropNorm = externalCropNorm;
        this.externalCropAestheticScore = externalCropAestheticScore;
        this.rawSuggestedCropNorm = rawSuggestedCropNorm;
        this.targetLocked = targetLocked;
        this.targetLockProgress = Math.max(0f, Math.min(1f, targetLockProgress));
        this.targetRevision = targetRevision;
        this.targetSource = targetSource == null ? "live" : targetSource;
    }

    public CompositionTemplate.Type getTemplateType() { return templateType; }
    public float[] getTargetCropNorm() { return targetCropNorm; }
    public float[] getAnchorNorm() { return anchorNorm; }
    /** Backward-compatible alias for the anchor. */
    public float[] getSubjectAnchorNorm() { return anchorNorm; }
    public float[] getLiveSubjectCenterNorm() { return liveSubjectCenterNorm; }
    public float[] getFastLiveSubjectCenterNorm() { return fastLiveSubjectCenterNorm; }
    public float[] getDisplayLiveSubjectCenterNorm() {
        return fastLiveSubjectCenterNorm != null ? fastLiveSubjectCenterNorm : liveSubjectCenterNorm;
    }
    public float[] getSketchParams() { return sketchParams; }
    public float getAlignmentScore() { return alignmentScore; }
    public TemplateState getState() { return state; }
    public String getCoachHint() { return coachHint; }
    public String getShortName() { return shortName; }
    public float getTargetZoomRatio() { return targetZoomRatio; }

    public float[] getSubjectGhostSizeNorm() { return subjectGhostSizeNorm; }
    public float[] getPanHints() { return panHints; }
    public float getMatchScore() { return matchScore; }

    public float[] getExternalSubjectBboxNorm() { return externalSubjectBboxNorm; }
    public float[] getExternalSubjectCenterNorm() { return externalSubjectCenterNorm; }
    public float[] getExternalCropNorm() { return externalCropNorm; }
    public float getExternalCropAestheticScore() { return externalCropAestheticScore; }
    public float[] getRawSuggestedCropNorm() { return rawSuggestedCropNorm; }
    public boolean isTargetLocked() { return targetLocked; }
    public float getTargetLockProgress() { return targetLockProgress; }
    public int getTargetRevision() { return targetRevision; }
    public String getTargetSource() { return targetSource; }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("template_type", templateType.name());
        map.put("target_crop", targetCropNorm);
        map.put("anchor", anchorNorm);
        map.put("live_subject_center", liveSubjectCenterNorm);
        if (fastLiveSubjectCenterNorm != null) map.put("fast_live_subject_center", fastLiveSubjectCenterNorm);
        map.put("alignment_score", alignmentScore);
        map.put("match_score", matchScore);
        map.put("state", state.name());
        map.put("coach_hint", coachHint);
        map.put("short_name", shortName);
        map.put("target_zoom", targetZoomRatio);
        map.put("pan_hints", panHints);
        map.put("subject_ghost_size", subjectGhostSizeNorm);
        map.put("target_locked", targetLocked);
        map.put("target_lock_progress", targetLockProgress);
        map.put("target_revision", targetRevision);
        map.put("target_source", targetSource);
        if (externalSubjectBboxNorm != null) map.put("ext_subject_bbox", externalSubjectBboxNorm);
        if (externalSubjectCenterNorm != null) map.put("ext_subject_center", externalSubjectCenterNorm);
        if (externalCropNorm != null) map.put("ext_crop", externalCropNorm);
        return map;
    }
}
