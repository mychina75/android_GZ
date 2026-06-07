package com.samsung.camera.intelligence.guidance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 1 — Aesthetic Compositional Guidance.
 *
 * Stateless rule engine that evaluates a decoded {@link FrameAnalysis} and produces
 * visual overlay directives for real-time composition coaching.
 *
 * Rules:
 *   - Horizon / vertical correction (tilt detection)
 *   - Subject positioning via Rule of Thirds
 *   - Headroom correction
 *   - Cluttered background warning
 *   - Finger / lens obstruction alert
 *   - Low composition-score general nudge
 *   - Poor framing / awkward cropping alerts
 *
 * Ported from Python composition_guide.py
 */
public class CompositionGuide {

    // Rule-of-thirds power points (normalized)
    private static final float[][] ROT_POINTS = {
        {1f / 3f, 1f / 3f},
        {2f / 3f, 1f / 3f},
        {1f / 3f, 2f / 3f},
        {2f / 3f, 2f / 3f},
    };

    // Golden-ratio (phi) power points (normalised)
    private static final float PHI = 1.0f / 1.618f;
    private static final float[][] PHI_POINTS = {
        {PHI, PHI}, {1 - PHI, PHI}, {PHI, 1 - PHI}, {1 - PHI, 1 - PHI},
    };

    // Golden-triangles diagonal endpoints (main diagonal + two perpendiculars)
    private static final float[][] GT_DIAGONALS = {
        {0.0f, 0.0f, 1.0f, 1.0f},   // main diagonal
        {0.0f, 1.0f, 0.6f, 0.0f},   // perpendicular from bottom-left to top
        {1.0f, 0.0f, 0.4f, 1.0f},   // perpendicular from top-right to bottom
    };

    // Subject types that benefit from shallow-DoF isolation guidance
    private static final Set<String> ISOLATABLE_SUBJECTS = new HashSet<>(Arrays.asList(
        "human_single", "human_face", "human_full_body",
        "animal_pet", "animal_wildlife", "animal_bird",
        "plant", "food_dish", "food_ingredient", "food_drink"
    ));

    // Scene types where adding a human element improves the composition
    private static final Set<String> HUMAN_INTEREST_SCENES = new HashSet<>(Arrays.asList(
        "landscape", "cityscape", "architecture", "architecture_exterior",
        "architecture_interior", "panoramic", "waterfall"
    ));

    // Scene types → golden ratio grid
    private static final Set<String> GOLDEN_RATIO_SCENE_TYPES = new HashSet<>(Arrays.asList(
        "architecture", "architecture_exterior", "architecture_interior"
    ));

    // Scene types → golden triangles grid
    private static final Set<String> GOLDEN_TRIANGLES_SCENE_TYPES = new HashSet<>(Arrays.asList(
        "landscape", "cityscape", "panoramic"
    ));

    // Depth-aware scene types
    private static final Set<String> DEPTH_SCENE_TYPES = new HashSet<>(Arrays.asList(
        "landscape", "cityscape", "panoramic", "waterfall",
        "architecture", "beach", "mountain"
    ));

    // ---- Config ----

    private float tiltThresholdDeg = 2.0f;
    private float subjectOffsetThreshold = 0.18f;
    private float subjectArrowScale = 1.2f;
    private float lowCompositionThreshold = 0.40f;
    private boolean showGrid = false;
    private float fillRatioMin = 0.15f;
    private float fillRatioMax = 0.85f;
    // Phase A.2 — minimum subject confidence required before drawing
    // SubjectGuide / DirectionArrow overlays. Older backbones produce
    // unreliable subject_center; require at least 0.5 to surface them.
    private float subjectGuideMinConfidence = 0.5f;
    // Phase A.3 — only emit DirectionArrow when overall composition is
    // weak (otherwise the user is probably already framed well).
    private float directionArrowScoreCeiling = 0.55f;
    // Phase C — when composition is already acceptable, do not nag the user
    // with rule-of-thirds reframing. This kills the oscillation where the
    // target jumps between RoT intersections every time subject_center
    // wobbles across the diagonal.
    private float subjectGuideScoreCeiling = 0.55f;
    // Phase C — hysteresis on the chosen rule-of-thirds power point so the
    // "Aim" target does not flip diagonals when subject_center wobbles.
    // Once a target is locked we only switch when a different point becomes
    // closer by at least this margin (in normalised distance units).
    private float powerPointSwitchMargin = 0.10f;

    // Phase C — per-instance state for power-point hysteresis. Stateless
    // semantics are preserved for callers that build a fresh CompositionGuide
    // per evaluation, but OverlayGenerator reuses one instance for the
    // lifetime of the analyzer, which is exactly where we want stability.
    private float[] lastPowerPoint = null;

    // Phase 1.2 — feature flags so the new Framing Template overlay can
    // replace the abstract SubjectGuide / DirectionArrow without losing
    // the analytical view (kept as opt-in Pro mode).
    private boolean enableSubjectGuide = false;
    private boolean enableDirectionArrow = false;
    private boolean enableFramingTemplate = true;

    // Phase 2 — crop stabilizer used for the Framing Template overlay.
    private final SuggestedCropStabilizer cropStabilizer = new SuggestedCropStabilizer();
    private final CompositionTargetLockController targetLockController =
            new CompositionTargetLockController();

    // Phase 3.5 — EMA-smoothed live subject center / ghost size used by the
    // Framing Template so the dot/ghost don't jitter every frame. Reset to
    // null when the head reports nothing usable so the renderer can hide
    // those elements rather than drawing stale geometry.
    private float[] subjectCenterEma = null;
    private float[] subjectSizeEma = null;
    private static final float SUBJECT_EMA_ALPHA_SLOW = 0.35f;
    private static final float SUBJECT_EMA_ALPHA_FAST = 0.70f;
    private static final float SUBJECT_FAST_MOVE_THRESHOLD = 0.018f;
    private int noSubjectFrames = 0;

    // Phase A — template chooser carries hysteresis state across frames.
    private final TemplateChooser templateChooser = new TemplateChooser();

    public CompositionGuide() {}

    // Config setters

    public void setTiltThresholdDeg(float v) { this.tiltThresholdDeg = v; }
    public void setSubjectOffsetThreshold(float v) { this.subjectOffsetThreshold = v; }
    public void setSubjectArrowScale(float v) { this.subjectArrowScale = v; }
    public void setLowCompositionThreshold(float v) { this.lowCompositionThreshold = v; }
    public void setShowGrid(boolean v) { this.showGrid = v; }
    public void setFillRatioMin(float v) { this.fillRatioMin = v; }
    public void setFillRatioMax(float v) { this.fillRatioMax = v; }
    public void setSubjectGuideMinConfidence(float v) { this.subjectGuideMinConfidence = v; }
    public void setDirectionArrowScoreCeiling(float v) { this.directionArrowScoreCeiling = v; }

    // Phase 1.2 / 3 feature flags.
    public void setEnableSubjectGuide(boolean v) { this.enableSubjectGuide = v; }
    public void setEnableDirectionArrow(boolean v) { this.enableDirectionArrow = v; }
    public void setEnableFramingTemplate(boolean v) {
        if (this.enableFramingTemplate != v) {
            targetLockController.reset();
        }
        this.enableFramingTemplate = v;
    }
    public void setSubjectGuideScoreCeiling(float v) { this.subjectGuideScoreCeiling = v; }
    public void setPowerPointSwitchMargin(float v) { this.powerPointSwitchMargin = v; }
    public SuggestedCropStabilizer getCropStabilizer() { return cropStabilizer; }
    public CompositionTargetLockController getTargetLockController() { return targetLockController; }
    public void setTargetLockEnabled(boolean v) {
        targetLockController.setEnabled(v);
        if (!v) targetLockController.reset();
    }
    public void setTargetLockAcquireMs(long v) { targetLockController.setAcquireMs(v); }
    public void setTargetLockMinStableFrames(int v) { targetLockController.setMinStableFrames(v); }
    public void setTargetLockCandidateIouThreshold(float v) {
        targetLockController.setCandidateIouThreshold(v);
    }
    public void setTargetLockAnchorTolerance(float v) { targetLockController.setAnchorTolerance(v); }

    public void reset() {
        lastPowerPoint = null;
        subjectCenterEma = null;
        subjectSizeEma = null;
        noSubjectFrames = 0;
        cropStabilizer.reset();
        targetLockController.reset();
    }

    // ---- Public API ----

    /**
     * Evaluate composition rules and return overlay directives.
     */
    public List<GuidanceOverlay> evaluate(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();

        // 1 — Composition grid (scene-type-aware selection)
        if (showGrid) {
            overlays.add(selectGrid(analysis));
        }

        // 2 — Horizon / tilt correction (always-on level indicator)
        overlays.add(buildHorizonIndicator(analysis));

        // 3 — Subject positioning (Phase A.2 — re-enabled with confidence gate)
        if (enableSubjectGuide || enableDirectionArrow) {
            overlays.addAll(checkSubjectPosition(analysis));
        } else {
            // Even when the abstract overlays are off, run the position check
            // for its side-effect on lastPowerPoint state so toggling Pro mode
            // back on doesn't surprise the user with a stale lock.
            lastPowerPoint = null;
        }

        // 3b — Framing Template overlay (Phase 3). Replaces SubjectGuide /
        // DirectionArrow with a literal target rectangle the user pans to.
        if (enableFramingTemplate) {
            FramingTemplateOverlay template = buildFramingTemplate(analysis);
            if (template != null) {
                overlays.add(template);
            }
        }

        // 4 — Composition issue-specific alerts
        overlays.addAll(checkCompositionIssues(analysis));

        // 4b — Advanced composition tips (Phase 1a)
        overlays.addAll(checkIsolateSubject(analysis));
        overlays.addAll(checkHumanInterest(analysis));
        overlays.addAll(checkColorTip(analysis));
        overlays.addAll(checkBwTip(analysis));

        // 4c — Phase 1b model-head-driven rules
        overlays.addAll(checkSymmetry(analysis));
        overlays.addAll(checkFillRatio(analysis));
        overlays.addAll(checkRuleOfOdds(analysis));
        overlays.addAll(checkLeadingLines(analysis));
        overlays.addAll(checkDiagonals(analysis));
        overlays.addAll(checkDepthLayers(analysis));
        overlays.addAll(checkSimplicity(analysis));

        // 5 — Finger / obstruction
        if (analysis.isFingerObstruction()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.OBSTRUCTION,
                    GuidanceUrgency.CRITICAL,
                    "Finger may be blocking the lens",
                    "finger_obstruction",
                    "warning",
                    3.0f
            ));
        }

        // 6 — General low-composition nudge (only if no more specific issue)
        if (analysis.getCompositionScore() < lowCompositionThreshold
                && (analysis.getCompositionIssues() == null
                    || analysis.getCompositionIssues().isEmpty())) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Try adjusting your framing",
                    "low_composition",
                    "composition"
            ));
        }

        return overlays;
    }

    // ---- Individual rule implementations ----

    /**
     * Always-on horizon level indicator.
     * Shows current tilt angle; when level (< threshold), shows as INFO.
     */
    private HorizonLine buildHorizonIndicator(FrameAnalysis analysis) {
        float angle = analysis.getTiltAngle();
        boolean tilted = analysis.isTilted() && Math.abs(angle) >= tiltThresholdDeg;

        GuidanceUrgency urgency;
        String message;
        if (!tilted || Math.abs(angle) < tiltThresholdDeg) {
            urgency = GuidanceUrgency.INFO;
            message = "Level";
        } else if (Math.abs(angle) > 5.0f) {
            urgency = GuidanceUrgency.WARNING;
            message = String.format("Level the horizon (%+.1f\u00b0)", angle);
        } else {
            urgency = GuidanceUrgency.SUGGESTION;
            message = String.format("Level the horizon (%+.1f\u00b0)", angle);
        }
        return new HorizonLine(GuidanceCategory.COMPOSITION, urgency, message, angle);
    }

    private HorizonLine checkTilt(FrameAnalysis analysis) {
        if (!analysis.isTilted()) {
            return null;
        }
        float angle = analysis.getTiltAngle();
        if (Math.abs(angle) < tiltThresholdDeg) {
            return null;
        }
        GuidanceUrgency urgency = Math.abs(angle) > 5.0f
                ? GuidanceUrgency.WARNING
                : GuidanceUrgency.SUGGESTION;

        return new HorizonLine(
                GuidanceCategory.COMPOSITION,
                urgency,
                String.format("Level the horizon (%+.1f°)", angle),
                angle
        );
    }

    private List<GuidanceOverlay> checkSubjectPosition(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        Float cx = analysis.getSubjectCenterX();
        Float cy = analysis.getSubjectCenterY();
        if (cx == null || cy == null) {
            return overlays;
        }

        // Phase A.2 — confidence gate. Use subjectConfidence if available,
        // otherwise compositionScore as a coarser proxy. Skip overlays when
        // we cannot trust the prediction.
        float conf = analysis.getSubjectConfidence();
        if (conf <= 0.0f) {
            // Many backbones don't expose a real subject_confidence; fall back
            // to a strong-enough composition score so we at least skip junk frames.
            conf = analysis.getCompositionScore();
        }
        if (conf < subjectGuideMinConfidence) {
            lastPowerPoint = null;
            return overlays;
        }

        // Phase C — "good framing" deadzone. If the composition is already
        // strong AND the model isn't asking for a recompose, don't draw the
        // Aim ring at all. This stops the bounce-around-RoT-intersections
        // behavior for users who are already centered nicely.
        if (!analysis.isNeedsCompositionEdit()
                && analysis.getCompositionScore() >= subjectGuideScoreCeiling) {
            lastPowerPoint = null;
            return overlays;
        }

        // Phase C — hysteresis on the selected power point. We compute the
        // nearest one, but only switch away from the previously locked one
        // if the new candidate is meaningfully closer.
        float[] nearest = nearestPowerPoint(cx, cy);
        float ppX = nearest[0];
        float ppY = nearest[1];
        float dist = nearest[2];

        if (lastPowerPoint != null) {
            float lastDist = (float) Math.hypot(lastPowerPoint[0] - cx, lastPowerPoint[1] - cy);
            // Stick with the previously chosen target unless the new nearest
            // is closer by more than the switch margin. This prevents the
            // diagonal jump every time subject_center crosses (0.5, 0.5).
            if (lastDist - dist < powerPointSwitchMargin) {
                ppX = lastPowerPoint[0];
                ppY = lastPowerPoint[1];
                dist = lastDist;
            }
        }

        if (dist < subjectOffsetThreshold) {
            // Already on / near the locked target — clear lock so the next
            // genuinely-needed reframe can pick the closest point fresh.
            lastPowerPoint = null;
            return overlays;
        }

        lastPowerPoint = new float[]{ppX, ppY};

        // Subject-guide dot
        overlays.add(new SubjectGuide(
                GuidanceCategory.COMPOSITION,
                GuidanceUrgency.SUGGESTION,
                "Move subject to intersection",
                cx, cy, ppX, ppY
        ));

        // Phase A.3 — only emit DirectionArrow when overall composition is weak.
        if (analysis.getCompositionScore() < directionArrowScoreCeiling) {
            // Phase C — the arrow represents the direction the user should
            // PAN THE CAMERA, not the direction the subject should move on
            // screen. Camera-pan is opposite to subject-shift: if the subject
            // needs to move right within the frame, the camera should pan
            // left so the framing slides that way.
            float dx = ppX - cx;
            float dy = ppY - cy;
            String panDirection = dominantDirection(-dx, -dy);
            float magnitude = Math.min(dist * subjectArrowScale, 1.0f);

            overlays.add(new DirectionArrow(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Pan camera " + panDirection,
                    panDirection,
                    magnitude
            ));
        }

        return overlays;
    }

    private List<GuidanceOverlay> checkCompositionIssues(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        Set<String> issues = new HashSet<>(analysis.getCompositionIssues());

        if (issues.contains("too_much_headroom")) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Tilt camera down slightly",
                    "down", 0.4f
            ));
        }

        if (issues.contains("insufficient_headroom")) {
            overlays.add(new DirectionArrow(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Tilt camera up slightly",
                    "up", 0.4f
            ));
        }

        // Subject positioning issues — disabled (subject_center not reliable)
        // subject_off_center and poor_rule_of_thirds arrows suppressed

        if (issues.contains("cluttered_background")) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Busy background — try a different angle",
                    "cluttered_background",
                    "background"
            ));
        }

        if (issues.contains("poor_framing") || issues.contains("awkward_cropping")) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Adjust framing to avoid cutting off the subject",
                    "poor_framing",
                    "framing"
            ));
        }

        if (issues.contains("horizon_not_level") && !analysis.isTilted()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Straighten the horizon line",
                    "horizon_not_level",
                    "horizon"
            ));
        }

        if (issues.contains("unbalanced")) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Composition looks unbalanced",
                    "unbalanced",
                    "balance"
            ));
        }

        if (issues.contains("distracting_elements")) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Distracting elements in frame",
                    "distracting_elements",
                    "warning"
            ));
        }

        if (issues.contains("subject_too_small")) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Subject is too small \u2014 step closer or zoom in",
                    "subject_too_small",
                    "zoom_in"
            ));
        }

        if (issues.contains("subject_cut_off")) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.WARNING,
                    "Subject is partially cut off \u2014 step back to include fully",
                    "subject_cut_off",
                    "framing"
            ));
        }

        if (issues.contains("needs_recomposition")) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Try recomposing the frame",
                    "needs_recomposition",
                    "recompose"
            ));
        }

        return overlays;
    }

    // ---- Grid selection (scene-type-aware) ----

    private GridOverlay selectGrid(FrameAnalysis analysis) {
        String scene = analysis.getSceneType();

        // Phase 1b model-head signals take priority over scene-type heuristic
        boolean hasSym = analysis.isHasSymmetry();
        boolean hasDiag = analysis.isHasDiagonalLines();

        if (hasSym || GOLDEN_RATIO_SCENE_TYPES.contains(scene)) {
            List<float[]> phiPoints = java.util.Arrays.asList(PHI_POINTS);
            return new GridOverlay(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Golden-ratio grid",
                    "golden_ratio",
                    phiPoints,
                    null
            );
        }

        if (hasDiag || GOLDEN_TRIANGLES_SCENE_TYPES.contains(scene)) {
            // Phase A.1 — emit golden_triangles with diagonal endpoints so
            // the renderer can draw the dynamic diagonal guide-lines.
            List<float[]> diag = new java.util.ArrayList<>();
            for (float[] p : GT_DIAGONALS) {
                diag.add(p);
            }
            return new GridOverlay(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Golden-triangles grid",
                    "golden_triangles",
                    java.util.Arrays.asList(ROT_POINTS),
                    diag
            );
        }

        // Default — rule of thirds (explicit type so renderer doesn't have to guess)
        return new GridOverlay(
                GuidanceCategory.COMPOSITION,
                GuidanceUrgency.INFO,
                "Rule-of-thirds grid",
                "rule_of_thirds",
                java.util.Arrays.asList(ROT_POINTS),
                null
        );
    }

    // ---- Phase 1a advanced composition rules ----

    /**
     * #14 — Suggest shallow DoF when background is cluttered and subject
     * is an isolatable type (person, pet, flower, food …).
     */
    private List<GuidanceOverlay> checkIsolateSubject(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (analysis.getCompositionIssues().contains("cluttered_background")
                && ISOLATABLE_SUBJECTS.contains(analysis.getMainSubject())) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Use shallow depth of field to isolate the subject",
                    "isolate_subject",
                    "depth"
            ));
        }
        return overlays;
    }

    /**
     * #27 — In landscape / architecture scenes without people, suggest
     * adding a human element for scale and interest.
     */
    private List<GuidanceOverlay> checkHumanInterest(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (HUMAN_INTEREST_SCENES.contains(analysis.getSceneType())
                && !analysis.isHasFace()
                && !analysis.isHasBackgroundPeople()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Adding a person can provide scale and interest",
                    "human_interest",
                    "person"
            ));
        }
        return overlays;
    }

    /**
     * #17 — Acknowledge complementary colours when detected.
     */
    private List<GuidanceOverlay> checkColorTip(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (analysis.isHasComplementaryColors()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Great complementary colors \u2014 use them as focal contrast",
                    "complementary_colors",
                    "color"
            ));
        }
        return overlays;
    }

    /**
     * #13 — Suggest B&W mode when the scene is low-saturation + high-contrast.
     */
    private List<GuidanceOverlay> checkBwTip(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (analysis.isSuggestBw()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "This scene may look great in Black & White",
                    "suggest_bw",
                    "bw"
            ));
        }
        return overlays;
    }

    // ---- Phase 1b model-head-driven rules ----

    /**
     * #2 Symmetry — When symmetry is detected, suggest centering the
     * subject for symmetrical balance.
     */
    private List<GuidanceOverlay> checkSymmetry(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (analysis.isHasSymmetry()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Center your subject for symmetrical balance",
                    "symmetry_tip",
                    "symmetry"
            ));
        }
        return overlays;
    }

    /**
     * #10 Fill Frame / #11 Negative Space — Subject too small or too
     * large relative to the frame.
     */
    private List<GuidanceOverlay> checkFillRatio(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        float ratio = analysis.getSubjectFillRatio();
        if (ratio <= 0) {
            return overlays; // unknown / not computed
        }
        if (ratio < fillRatioMin) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Move closer to fill the frame with your subject",
                    "fill_frame",
                    "zoom_in"
            ));
        } else if (ratio > fillRatioMax) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.SUGGESTION,
                    "Leave some breathing room around the subject",
                    "negative_space",
                    "zoom_out"
            ));
        }
        return overlays;
    }

    /**
     * #9 Rule of Odds — Even subject counts feel static; suggest odd.
     */
    private List<GuidanceOverlay> checkRuleOfOdds(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        int count = analysis.getSubjectCount();
        if (count > 0 && count % 2 == 0) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Odd numbers of subjects create more dynamic compositions",
                    "rule_of_odds",
                    "composition"
            ));
        }
        return overlays;
    }

    /**
     * #5 Leading Lines — Positive acknowledgement when detected.
     */
    private List<GuidanceOverlay> checkLeadingLines(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (analysis.isHasLeadingLines()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Use the leading lines to guide the viewer's eye",
                    "leading_lines",
                    "leading_lines"
            ));
        }
        return overlays;
    }

    /**
     * #6 Diagonals — Suggest aligning with diagonals for dynamic tension.
     */
    private List<GuidanceOverlay> checkDiagonals(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (analysis.isHasDiagonalLines()) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Align elements with the diagonals for dynamic tension",
                    "diagonals_tip",
                    "diagonal"
            ));
        }
        return overlays;
    }

    /**
     * #3 Depth / #26 Layers — Single-layer landscape scenes benefit from
     * foreground interest.
     */
    private List<GuidanceOverlay> checkDepthLayers(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        if (analysis.getSceneDepthLayers() >= 2) {
            return overlays;
        }
        if (!DEPTH_SCENE_TYPES.contains(analysis.getSceneType())) {
            return overlays;
        }
        overlays.add(new AlertBadge(
                GuidanceCategory.COMPOSITION,
                GuidanceUrgency.SUGGESTION,
                "Add foreground interest for a sense of depth",
                "depth_layers",
                "layers"
        ));
        return overlays;
    }

    /**
     * #12 Simplicity / #25 Eye Wander — Positive affirmation for clean or
     * rich compositions.
     */
    private List<GuidanceOverlay> checkSimplicity(FrameAnalysis analysis) {
        List<GuidanceOverlay> overlays = new ArrayList<>();
        float vc = analysis.getVisualComplexity();
        if (vc < 0.2f) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Clean, minimalist composition",
                    "simplicity",
                    "simplicity"
            ));
        } else if (vc > 0.8f) {
            overlays.add(new AlertBadge(
                    GuidanceCategory.COMPOSITION,
                    GuidanceUrgency.INFO,
                    "Rich, layered scene \u2014 let the eye explore",
                    "visual_richness",
                    "layers"
            ));
        }
        return overlays;
    }

    // ---- Geometry helpers ----

    /**
     * Find the nearest rule-of-thirds intersection to (x, y).
     * Returns float[3]: {ppX, ppY, distance}.
     */
    static float[] nearestPowerPoint(float x, float y) {
        float bestX = ROT_POINTS[0][0];
        float bestY = ROT_POINTS[0][1];
        float bestDist = Float.MAX_VALUE;
        for (float[] pp : ROT_POINTS) {
            float dx = pp[0] - x;
            float dy = pp[1] - y;
            float d = (float) Math.hypot(dx, dy);
            if (d < bestDist) {
                bestDist = d;
                bestX = pp[0];
                bestY = pp[1];
            }
        }
        return new float[]{bestX, bestY, bestDist};
    }

    /**
     * Return the dominant cardinal direction for a 2D offset.
     */
    static String dominantDirection(float dx, float dy) {
        if (Math.abs(dx) > Math.abs(dy)) {
            return dx > 0 ? "right" : "left";
        }
        return dy > 0 ? "down" : "up";
    }

    // ---- Phase 3 \u2014 Framing Template overlay ----

    /**
     * Build the {@link FramingTemplateOverlay} for the current frame, or
     * return {@code null} if the user is already framed well enough.
     *
     * The layout: target frame == suggested_crop (stabilized); subject anchor
     * == nearest rule-of-thirds power point inside that crop; pan hints ==
     * how much of the target frame is currently off the live preview on each
     * side. Match score combines IoU(live, target) + (1 - subject distance).
     */
    private FramingTemplateOverlay buildFramingTemplate(FrameAnalysis analysis) {
        // ---- 1. Smooth the subject signal ----
        Float cxBoxed = analysis.getSubjectCenterX();
        Float cyBoxed = analysis.getSubjectCenterY();
        float[] rawSubjectCenter = (cxBoxed != null && cyBoxed != null)
                ? new float[]{cxBoxed, cyBoxed} : null;
        Float fastCxBoxed = analysis.getFastSubjectCenterX();
        Float fastCyBoxed = analysis.getFastSubjectCenterY();
        float[] fastSubjectCenter = (fastCxBoxed != null && fastCyBoxed != null)
            ? new float[]{fastCxBoxed, fastCyBoxed} : null;
        float[] rawSubjectBbox = analysis.getSubjectBbox();
        float subjectConfidence = analysis.getSubjectConfidence();

        boolean subjectUsable = rawSubjectCenter != null
                && subjectConfidence >= 0.30f
                && rawSubjectCenter[0] > 0.02f && rawSubjectCenter[0] < 0.98f
                && rawSubjectCenter[1] > 0.02f && rawSubjectCenter[1] < 0.98f;
        boolean fastSubjectUsable = subjectUsable
                && fastSubjectCenter != null
                && fastSubjectCenter[0] > 0.0f && fastSubjectCenter[0] < 1.0f
                && fastSubjectCenter[1] > 0.0f && fastSubjectCenter[1] < 1.0f;

        if (subjectUsable) {
            if (subjectCenterEma == null) {
                subjectCenterEma = rawSubjectCenter.clone();
            } else {
                float dx = rawSubjectCenter[0] - subjectCenterEma[0];
                float dy = rawSubjectCenter[1] - subjectCenterEma[1];
                float moveSq = dx * dx + dy * dy;
                float alpha = moveSq >= SUBJECT_FAST_MOVE_THRESHOLD * SUBJECT_FAST_MOVE_THRESHOLD
                        ? SUBJECT_EMA_ALPHA_FAST : SUBJECT_EMA_ALPHA_SLOW;
                subjectCenterEma[0] += alpha * dx;
                subjectCenterEma[1] += alpha * dy;
            }
            if (rawSubjectBbox != null && rawSubjectBbox.length >= 4
                    && rawSubjectBbox[2] > 0.02f && rawSubjectBbox[3] > 0.02f) {
                if (subjectSizeEma == null) {
                    subjectSizeEma = new float[]{rawSubjectBbox[2], rawSubjectBbox[3]};
                } else {
                    subjectSizeEma[0] += SUBJECT_EMA_ALPHA_SLOW * (rawSubjectBbox[2] - subjectSizeEma[0]);
                    subjectSizeEma[1] += SUBJECT_EMA_ALPHA_SLOW * (rawSubjectBbox[3] - subjectSizeEma[1]);
                }
            }
            noSubjectFrames = 0;
        } else {
            noSubjectFrames++;
            if (noSubjectFrames > 30) {
                subjectCenterEma = null;
                subjectSizeEma = null;
            }
        }

        float[] subjectCenter = subjectCenterEma;
        float[] displaySubjectCenter = fastSubjectUsable ? fastSubjectCenter : subjectCenter;

        // ---- 2. Determine target crop candidate (with heuristic fallback) ----
        float[] modelCrop = analysis.getSuggestedCrop();
        float[] candidateCrop = null;
        CompositionTemplate candidateTemplate = null;
        float[] candidateGhostSize = null;
        String targetSource = "model";
        boolean candidateUsable = false;

        candidateCrop = cropStabilizer.update(modelCrop, subjectCenter);

        boolean cropMissing = (candidateCrop == null || candidateCrop.length < 4);
        boolean cropFullFrame = !cropMissing && candidateCrop[2] >= 0.93f && candidateCrop[3] >= 0.93f;
        boolean cropMissesSubject = !cropMissing && subjectCenter != null
                && (subjectCenter[0] < candidateCrop[0] - 0.05f
                    || subjectCenter[0] > candidateCrop[0] + candidateCrop[2] + 0.05f
                    || subjectCenter[1] < candidateCrop[1] - 0.05f
                    || subjectCenter[1] > candidateCrop[1] + candidateCrop[3] + 0.05f);
        boolean useHeuristic = cropMissing || cropFullFrame || cropMissesSubject
                || cropStabilizer.isHeadDegenerated();
        targetSource = useHeuristic ? "heuristic" : "model";

        if (useHeuristic) {
            float[] heuristic = heuristicCropFromSubject(subjectCenter, subjectSizeEma);
            if (heuristic != null) {
                cropStabilizer.overrideWithFallback(heuristic);
                candidateCrop = heuristic;
            }
        }
        if (candidateCrop == null) {
            candidateCrop = new float[]{0.05f, 0.05f, 0.90f, 0.90f};
            targetSource = "fallback";
        }

        candidateTemplate = templateChooser.choose(
                analysis, subjectCenter, subjectSizeEma, candidateCrop);
        float candidateGhostW = (subjectSizeEma != null) ? subjectSizeEma[0] : candidateCrop[2] * 0.40f;
        float candidateGhostH = (subjectSizeEma != null) ? subjectSizeEma[1] : candidateCrop[3] * 0.55f;
        candidateGhostSize = new float[]{candidateGhostW, candidateGhostH};
        candidateUsable = candidateTemplate != null;

        CompositionTargetLockController.Result target = targetLockController.update(
                candidateCrop,
                candidateTemplate != null ? candidateTemplate.anchorNorm : null,
                candidateTemplate != null ? candidateTemplate.type : null,
                candidateTemplate != null ? candidateTemplate.sketchParams : null,
                candidateTemplate != null ? candidateTemplate.shortName : null,
                candidateGhostSize,
                targetSource,
                candidateUsable,
                System.currentTimeMillis());

        float[] crop = target.getTargetCropNorm();
        float[] anchor = target.getAnchorNorm();
        if (crop == null || crop.length < 4 || anchor == null || anchor.length < 2) {
            return null;
        }
        float anchorX = anchor[0];
        float anchorY = anchor[1];

        float[] ghostSize = target.getGhostSizeNorm();
        float ghostW = (ghostSize != null && ghostSize.length >= 2) ? ghostSize[0] : crop[2] * 0.40f;
        float ghostH = (ghostSize != null && ghostSize.length >= 2) ? ghostSize[1] : crop[3] * 0.55f;

        // ---- 3. Pan hints ----
        float[] panHints = new float[]{
                Math.max(0f, -crop[0]),
                Math.max(0f, -crop[1]),
                Math.max(0f, (crop[0] + crop[2]) - 1f),
                Math.max(0f, (crop[1] + crop[3]) - 1f)
        };

        // ---- 4. Alignment score: pure radial distance (no fake size term). ----
        float alignmentScore = 0f;
        if (subjectCenter != null) {
            float dist = (float) Math.hypot(subjectCenter[0] - anchorX,
                    subjectCenter[1] - anchorY);
            alignmentScore = Math.max(0f, 1f - dist / 0.30f);
        }

        // ---- 5. State + coach hint ----
        FramingTemplateOverlay.TemplateState state;
        if (!target.isTargetLocked()) {
            state = FramingTemplateOverlay.TemplateState.SEARCHING;
        } else if (subjectCenter == null) {
            state = FramingTemplateOverlay.TemplateState.SEARCHING;
        } else if (alignmentScore >= 0.85f) {
            state = FramingTemplateOverlay.TemplateState.LOCKED;
        } else if (alignmentScore >= 0.65f) {
            state = FramingTemplateOverlay.TemplateState.NEAR;
        } else {
            state = FramingTemplateOverlay.TemplateState.GUIDING;
        }

        String message;
        if (!target.isTargetLocked()) {
            int pct = Math.round(target.getProgress() * 100f);
            message = pct > 0 ? "Hold steady to set target " + pct + "%"
                    : "Hold steady to set framing target";
        } else {
            switch (state) {
                case SEARCHING:
                    message = "Target set - find the subject";
                    break;
                case LOCKED:
                    message = "Aligned - ready to capture";
                    break;
                case NEAR:
                    message = "Almost there - nudge to align";
                    break;
                default: {
                    float dx = anchorX - subjectCenter[0];
                    float dy = anchorY - subjectCenter[1];
                    if (Math.abs(dx) > Math.abs(dy)) {
                        message = dx > 0 ? "Pan left so the subject moves onto the dot"
                                          : "Pan right so the subject moves onto the dot";
                    } else {
                        message = dy > 0 ? "Tilt up so the subject moves down onto the dot"
                                          : "Tilt down so the subject moves up onto the dot";
                    }
                    break;
                }
            }
        }

        // ---- 6. Auto-zoom target — fill the recommended crop. ----
        float maxCropDim = Math.max(crop[2], crop[3]);
        float targetZoomRatio = maxCropDim > 0.05f ? Math.min(5.0f, 1f / maxCropDim) : 1.0f;

        return new FramingTemplateOverlay(
                GuidanceCategory.COMPOSITION,
                state == FramingTemplateOverlay.TemplateState.LOCKED
                        ? GuidanceUrgency.INFO : GuidanceUrgency.SUGGESTION,
                message,
                target.getTemplateType(),
                crop,
                new float[]{anchorX, anchorY},
                subjectCenter,
                displaySubjectCenter,
                target.getSketchParams(),
                alignmentScore,
                state,
                message,
                target.getShortName(),
                targetZoomRatio,
                new float[]{ghostW, ghostH},
                panHints,
                alignmentScore,
                analysis.getExternalSubjectBboxNorm(),
                analysis.getExternalSubjectCenterNorm(),
                analysis.getExternalCropNorm(),
                analysis.getExternalCropAestheticScore(),
                modelCrop != null ? modelCrop.clone() : null,
                target.isTargetLocked(),
                target.getProgress(),
                target.getTargetRevision(),
                target.getSource()
        );
    }

    /**
     * Build a sensible recommended crop from the live subject when the
     * model head is degenerate. The subject is anchored to the closest
     * rule-of-thirds power point.
     */
    private float[] heuristicCropFromSubject(float[] subjectCenter, float[] subjectSize) {
        if (subjectCenter == null) return null;
        float sw = (subjectSize != null) ? Math.max(0.10f, subjectSize[0]) : 0.30f;
        float sh = (subjectSize != null) ? Math.max(0.10f, subjectSize[1]) : 0.45f;

        float bestDist = Float.MAX_VALUE;
        float anchorU = 0.5f, anchorV = 0.5f;
        for (float[] pp : ROT_POINTS) {
            float d = (pp[0] - subjectCenter[0]) * (pp[0] - subjectCenter[0])
                    + (pp[1] - subjectCenter[1]) * (pp[1] - subjectCenter[1]);
            if (d < bestDist) {
                bestDist = d;
                anchorU = pp[0];
                anchorV = pp[1];
            }
        }

        float cropW = Math.min(1f, Math.max(sw * 3.0f, 0.55f));
        float cropH = Math.min(1f, Math.max(sh * 2.2f, 0.65f));
        float cropX = subjectCenter[0] - anchorU * cropW;
        float cropY = subjectCenter[1] - anchorV * cropH;
        cropX = Math.max(0f, Math.min(1f - cropW, cropX));
        cropY = Math.max(0f, Math.min(1f - cropH, cropY));
        return new float[]{cropX, cropY, cropW, cropH};
    }
}
