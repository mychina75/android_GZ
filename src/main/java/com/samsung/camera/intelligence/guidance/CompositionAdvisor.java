package com.samsung.camera.intelligence.guidance;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central composition advice scheduler. Owns the {@link TechniqueRule}
 * list (one rule per PetaPixel principle wired to current model heads),
 * picks the highest-priority firing rule as the per-frame primary advice,
 * and emits a secondary tip only when the user is stable AND no primary
 * fired. Maintains per-rule cooldowns so the same chip does not re-fire
 * inside {@code adviceCoolDownMs}.
 *
 * <p>The advisor is intentionally <b>pure scheduler logic</b>: it does
 * not own UI, threading, or i18n. Callers (e.g. {@link OverlayGenerator})
 * pass in a fresh {@link FrameAnalysis} every frame and forward the
 * resulting {@link CompositionAdvice} (may be {@code null}) to the
 * overlay view.
 *
 * Phase 1 of plan-compositionGuidanceV2.
 */
public final class CompositionAdvisor {

    private static final long DEFAULT_COOLDOWN_MS = 2500L;
    /** Wall-clock dwell required before a secondary tip is allowed. */
    private static final long SECONDARY_DWELL_MS = 2500L;
    /** After a chip fires we suppress all chips briefly to avoid flicker. */
    private static final long PRIMARY_REFRACTORY_MS = 600L;

    // ---- Scene set helpers (mirrors CompositionGuide constants) ----
    private static final Set<String> ARCHITECTURE_SCENES = new HashSet<>(Arrays.asList(
            "architecture", "architecture_exterior", "architecture_interior", "cityscape"
    ));
    private static final Set<String> LANDSCAPE_SCENES = new HashSet<>(Arrays.asList(
            "landscape", "cityscape", "panoramic", "mountain", "beach", "waterfall"
    ));
    private static final Set<String> ISOLATABLE_SUBJECTS = new HashSet<>(Arrays.asList(
            "human_single", "human_face", "animal_pet", "animal_wildlife", "animal_bird",
            "plant_flower", "food_dish", "food_ingredient", "food_drink", "object_product"
    ));
    private static final Set<String> HUMAN_INTEREST_SCENES = new HashSet<>(Arrays.asList(
            "landscape", "cityscape", "architecture", "architecture_exterior",
            "panoramic", "mountain"
    ));
    private static final Set<String> DEPTH_SCENE_TYPES = new HashSet<>(Arrays.asList(
            "landscape", "cityscape", "panoramic", "waterfall",
            "architecture", "beach", "mountain"
    ));

    // ---- Issue label constants (must match FrameAnalyzer COMPOSITION_ISSUE_LABELS) ----
    private static final String ISSUE_OFF_CENTER = "subject_off_center";
    private static final String ISSUE_POOR_THIRDS = "poor_rule_of_thirds";
    private static final String ISSUE_TOO_HEADROOM = "too_much_headroom";
    private static final String ISSUE_NEED_HEADROOM = "insufficient_headroom";
    private static final String ISSUE_CLUTTER = "cluttered_background";
    private static final String ISSUE_DISTRACTING = "distracting_elements";
    private static final String ISSUE_HORIZON = "horizon_not_level";
    private static final String ISSUE_CUTOFF = "subject_cut_off";
    private static final String ISSUE_UNBALANCED = "unbalanced";
    private static final String ISSUE_AWKWARD = "awkward_cropping";
    private static final String ISSUE_RECOMPOSE = "needs_recomposition";
    private static final String ISSUE_TOO_SMALL = "subject_too_small";

    private long adviceCoolDownMs = DEFAULT_COOLDOWN_MS;
    private final List<TechniqueRule> rules;
    private final Map<String, Long> lastFiredAt = new HashMap<>();
    private long lastPrimaryFiredAt = 0L;
    private long stableSinceMs = 0L;
    private long mutedUntilMs = 0L;
    @Nullable private String lastSceneType = null;
    @Nullable private String lastMainSubject = null;

    public CompositionAdvisor() {
        this.rules = Collections.unmodifiableList(buildDefaultRules());
    }

    /** Override the per-rule cool-down (Phase 5 settings hook). */
    public void setAdviceCoolDownMs(long ms) {
        this.adviceCoolDownMs = Math.max(500L, ms);
    }

    /**
     * Mute all advice for the given duration, e.g. when MATCH_LOCK fires
     * the consumer UI plays its own affirm coach for ~1.5s.
     */
    public void muteFor(long ms) {
        this.mutedUntilMs = System.currentTimeMillis() + Math.max(0L, ms);
    }

    public void reset() {
        lastFiredAt.clear();
        lastPrimaryFiredAt = 0L;
        stableSinceMs = 0L;
        mutedUntilMs = 0L;
        lastSceneType = null;
        lastMainSubject = null;
    }

    /**
     * Evaluate all rules and return at most one advice for the current
     * frame. Returns null when no rule fires AND no secondary tip is due.
     */
    @Nullable
    public CompositionAdvice advise(FrameAnalysis analysis,
                                    boolean targetLocked,
                                    float alignmentScore) {
        if (analysis == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now < mutedUntilMs) {
            return null;
        }

        // Track scene/subject stability for secondary-tip gating.
        if (sceneOrSubjectChanged(analysis)) {
            stableSinceMs = now;
        }
        lastSceneType = analysis.getSceneType();
        lastMainSubject = analysis.getMainSubject();

        TechniqueRule.Context ctx = new TechniqueRule.Context(
                analysis, targetLocked, alignmentScore, now);

        // Pass 1: highest-priority CRITICAL/WARN/COACH that survives cooldown.
        CompositionAdvice primary = pickPrimary(ctx);
        if (primary != null) {
            lastFiredAt.put(primary.techniqueId, now);
            lastPrimaryFiredAt = now;
            return primary;
        }

        // Pass 2: secondary tip, only when stable ≥ SECONDARY_DWELL_MS and
        // we are clear of the primary refractory window.
        if (now - lastPrimaryFiredAt < PRIMARY_REFRACTORY_MS) {
            return null;
        }
        if (now - stableSinceMs < SECONDARY_DWELL_MS) {
            return null;
        }
        return pickSecondary(ctx);
    }

    /** Visible for tests. */
    public List<TechniqueRule> getRules() {
        return rules;
    }

    // ----------------------------------------------------------------
    // Internal scheduler
    // ----------------------------------------------------------------

    private boolean sceneOrSubjectChanged(FrameAnalysis a) {
        if (lastSceneType == null && lastMainSubject == null) return true;
        return !equalsSafe(a.getSceneType(), lastSceneType)
                || !equalsSafe(a.getMainSubject(), lastMainSubject);
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Nullable
    private CompositionAdvice pickPrimary(TechniqueRule.Context ctx) {
        CompositionAdvice best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (TechniqueRule rule : rules) {
            if (rule.priority >= bestPriority) continue;
            if (rule.requiresTargetLock && !ctx.targetLocked) continue;
            // Secondary-only rules (severity INFO) handled in pickSecondary.
            CompositionAdvice candidate = rule.trigger.tryFire(ctx);
            if (candidate == null) continue;
            if (candidate.severity == CompositionAdvice.Severity.INFO) continue;
            if (!cooldownClear(rule, ctx.nowMs)) continue;
            best = candidate;
            bestPriority = rule.priority;
        }
        return best;
    }

    @Nullable
    private CompositionAdvice pickSecondary(TechniqueRule.Context ctx) {
        CompositionAdvice best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (TechniqueRule rule : rules) {
            if (rule.priority >= bestPriority) continue;
            CompositionAdvice candidate = rule.trigger.tryFire(ctx);
            if (candidate == null) continue;
            if (candidate.severity != CompositionAdvice.Severity.INFO) continue;
            if (!cooldownClear(rule, ctx.nowMs)) continue;
            best = candidate;
            bestPriority = rule.priority;
        }
        if (best != null) {
            lastFiredAt.put(best.techniqueId, ctx.nowMs);
        }
        return best;
    }

    private boolean cooldownClear(TechniqueRule rule, long nowMs) {
        Long last = lastFiredAt.get(rule.techniqueId);
        long gate = Math.max(rule.cooldownMs, adviceCoolDownMs);
        return last == null || (nowMs - last) >= gate;
    }

    // ----------------------------------------------------------------
    // Rule table — wires PetaPixel principles to current model heads.
    // Strings are deliberately literal (Chinese consumer copy). They
    // can be migrated to strings.xml in v3 by replacing them with keys.
    // ----------------------------------------------------------------

    private static List<TechniqueRule> buildDefaultRules() {
        List<TechniqueRule> r = new ArrayList<>();

        // ---- High-severity warnings (priority 1–9) ----
        // #cutoff: subject is cut off — always wins.
        r.add(new TechniqueRule(1, "subject_cut_off", false, 1500L, ctx -> {
            if (!hasIssue(ctx.analysis, ISSUE_CUTOFF)) return null;
            return CompositionAdvice.builder("subject_cut_off")
                    .chip("主体被裁切")
                    .coach("主体被画框切到了，向后退或调整角度")
                    .severity(CompositionAdvice.Severity.CRITICAL)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.9f)
                    .ttlMs(3000L)
                    .build();
        }));

        // Tilt warning — combines is_tilted bool + tilt_angle regression.
        r.add(new TechniqueRule(5, "horizon_not_level", false, 1500L, ctx -> {
            float angle = ctx.analysis.getTiltAngle();
            boolean issueHit = hasIssue(ctx.analysis, ISSUE_HORIZON);
            if (!issueHit && Math.abs(angle) < 1.5f) return null;
            String coach = String.format(Locale.US, "画面倾斜 %.1f°，水平拉直", angle);
            return CompositionAdvice.builder("horizon_not_level")
                    .chip("水平校正")
                    .coach(coach)
                    .severity(CompositionAdvice.Severity.WARN)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.85f)
                    .ttlMs(2500L)
                    .build();
        }));

        // ---- Composition correction coach (priority 10–19) ----
        r.add(new TechniqueRule(10, "off_center_after_lock", true, 2000L, ctx -> {
            // Only nag about off-center after a target is locked AND the user
            // still hasn't aligned (alignmentScore < 0.6). Confidence drops if
            // model said "off center" but headroom suggests otherwise.
            boolean issue = hasIssue(ctx.analysis, ISSUE_OFF_CENTER)
                    || hasIssue(ctx.analysis, ISSUE_POOR_THIRDS);
            if (!issue) return null;
            if (ctx.alignmentScore >= 0.60f) return null;
            return CompositionAdvice.builder("off_center_after_lock")
                    .coach("把主体移到白点上对齐三分点")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.7f)
                    .ttlMs(2000L)
                    .build();
        }));

        r.add(new TechniqueRule(11, "too_much_headroom", false, 2000L, ctx -> {
            boolean issue = hasIssue(ctx.analysis, ISSUE_TOO_HEADROOM);
            float top = ctx.analysis.getHeadroomTop();
            // Confirm with derived headroom — both must agree to avoid noise.
            if (!issue && (Float.isNaN(top) || top < 0.35f)) return null;
            return CompositionAdvice.builder("too_much_headroom")
                    .coach("头顶空间太多，向上略微抬高镜头")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(issue && !Float.isNaN(top) && top > 0.35f ? 0.9f : 0.6f)
                    .ttlMs(2200L)
                    .build();
        }));

        r.add(new TechniqueRule(12, "insufficient_headroom", false, 2000L, ctx -> {
            boolean issue = hasIssue(ctx.analysis, ISSUE_NEED_HEADROOM);
            float top = ctx.analysis.getHeadroomTop();
            if (!issue && (Float.isNaN(top) || top > 0.05f)) return null;
            return CompositionAdvice.builder("insufficient_headroom")
                    .coach("主体头顶贴边了，下移镜头给点余量")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(issue && !Float.isNaN(top) && top < 0.05f ? 0.9f : 0.6f)
                    .ttlMs(2200L)
                    .build();
        }));

        r.add(new TechniqueRule(13, "distracting_elements", false, 3000L, ctx -> {
            if (!hasIssue(ctx.analysis, ISSUE_DISTRACTING)) return null;
            return CompositionAdvice.builder("distracting_elements")
                    .coach("画面有干扰物，重新构图")
                    .severity(CompositionAdvice.Severity.WARN)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.75f)
                    .ttlMs(2500L)
                    .build();
        }));

        r.add(new TechniqueRule(14, "isolate_subject", false, 4000L, ctx -> {
            if (!hasIssue(ctx.analysis, ISSUE_CLUTTER)) return null;
            String subj = ctx.analysis.getMainSubject();
            if (subj == null || !ISOLATABLE_SUBJECTS.contains(subj)) return null;
            return CompositionAdvice.builder("isolate_subject")
                    .chip("浅景深")
                    .coach("背景较杂，尝试浅景深或换角度")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.8f)
                    .ttlMs(3000L)
                    .build();
        }));

        r.add(new TechniqueRule(15, "fill_the_frame", false, 2500L, ctx -> {
            float fr = ctx.analysis.getSubjectFillRatio();
            if (fr <= 0.001f || fr >= 0.15f) return null;
            return CompositionAdvice.builder("fill_the_frame")
                    .coach("靠近一些，让主体填满画面")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.85f)
                    .ttlMs(2500L)
                    .build();
        }));

        r.add(new TechniqueRule(16, "negative_space", false, 2500L, ctx -> {
            float fr = ctx.analysis.getSubjectFillRatio();
            if (fr <= 0.85f) return null;
            return CompositionAdvice.builder("negative_space")
                    .coach("主体过大，退后留白")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.85f)
                    .ttlMs(2500L)
                    .build();
        }));

        // ---- Technique chips driven by binary heads (priority 20–29) ----
        r.add(new TechniqueRule(20, "symmetry", false, 4000L, ctx -> {
            if (!ctx.analysis.isHasSymmetry()) return null;
            return CompositionAdvice.builder("symmetry")
                    .chip("对称构图")
                    .coach("把对称轴居中")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.8f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(21, "golden_ratio", false, 4000L, ctx -> {
            if (!ctx.analysis.isHasSymmetry()) return null;
            String scene = ctx.analysis.getSceneType();
            if (scene == null || !ARCHITECTURE_SCENES.contains(scene)) return null;
            return CompositionAdvice.builder("golden_ratio")
                    .chip("黄金分割")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.75f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(22, "leading_lines", false, 4000L, ctx -> {
            if (!ctx.analysis.isHasLeadingLines()) return null;
            return CompositionAdvice.builder("leading_lines")
                    .chip("引导线")
                    .coach("顺着引导线放主体")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.75f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(23, "diagonals", false, 4000L, ctx -> {
            if (!ctx.analysis.isHasDiagonalLines()) return null;
            return CompositionAdvice.builder("diagonals")
                    .chip("对角线")
                    .coach("沿对角线安排主体")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.75f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(24, "golden_triangles", false, 4000L, ctx -> {
            if (!ctx.analysis.isHasDiagonalLines()) return null;
            String scene = ctx.analysis.getSceneType();
            if (scene == null
                    || !(LANDSCAPE_SCENES.contains(scene) || ARCHITECTURE_SCENES.contains(scene))) {
                return null;
            }
            return CompositionAdvice.builder("golden_triangles")
                    .chip("金三角")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.7f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(25, "color_combination", false, 5000L, ctx -> {
            if (!ctx.analysis.isHasComplementaryColors()) return null;
            return CompositionAdvice.builder("color_combination")
                    .chip("配色互补")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.HEURISTIC)
                    .confidence(0.65f)
                    .ttlMs(4000L)
                    .build();
        }));

        r.add(new TechniqueRule(26, "bw_suggestion", false, 6000L, ctx -> {
            if (!ctx.analysis.isSuggestBw()) return null;
            String scene = ctx.analysis.getSceneType();
            if (scene == null) return null;
            if (!(scene.startsWith("portrait") || ARCHITECTURE_SCENES.contains(scene))) return null;
            return CompositionAdvice.builder("bw_suggestion")
                    .chip("黑白潜力")
                    .severity(CompositionAdvice.Severity.COACH)
                    .source(CompositionAdvice.Source.HEURISTIC)
                    .confidence(0.6f)
                    .ttlMs(4000L)
                    .build();
        }));

        // ---- Secondary tips (severity INFO, priority 30+) — only fire on
        //      stable scenes when no primary rule won.
        r.add(new TechniqueRule(30, "rule_of_odds", false, 5000L, ctx -> {
            int n = ctx.analysis.getSubjectCount();
            if (n < 2 || (n & 1) == 1) return null;
            return CompositionAdvice.builder("rule_of_odds")
                    .secondary("奇数主体往往更耐看")
                    .severity(CompositionAdvice.Severity.INFO)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.6f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(31, "depth_layers", false, 5000L, ctx -> {
            if (ctx.analysis.getSceneDepthLayers() != 1) return null;
            String scene = ctx.analysis.getSceneType();
            if (scene == null || !DEPTH_SCENE_TYPES.contains(scene)) return null;
            return CompositionAdvice.builder("depth_layers")
                    .secondary("加点前景增加纵深")
                    .severity(CompositionAdvice.Severity.INFO)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.6f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(32, "simplicity", false, 5000L, ctx -> {
            float vc = ctx.analysis.getVisualComplexity();
            if (vc >= 0.20f) return null;
            return CompositionAdvice.builder("simplicity")
                    .secondary("极简画面，留白即美")
                    .severity(CompositionAdvice.Severity.INFO)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.55f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(33, "eye_wander", false, 5000L, ctx -> {
            float vc = ctx.analysis.getVisualComplexity();
            if (vc <= 0.80f) return null;
            return CompositionAdvice.builder("eye_wander")
                    .secondary("层次丰富，可关注分层节奏")
                    .severity(CompositionAdvice.Severity.INFO)
                    .source(CompositionAdvice.Source.MODEL_HEAD)
                    .confidence(0.55f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(34, "human_interest", false, 6000L, ctx -> {
            if (ctx.analysis.isHasFace()) return null;
            String scene = ctx.analysis.getSceneType();
            if (scene == null || !HUMAN_INTEREST_SCENES.contains(scene)) return null;
            return CompositionAdvice.builder("human_interest")
                    .secondary("加入人物可以衬出尺度")
                    .severity(CompositionAdvice.Severity.INFO)
                    .source(CompositionAdvice.Source.SCENE_RULE)
                    .confidence(0.5f)
                    .ttlMs(3500L)
                    .build();
        }));

        r.add(new TechniqueRule(35, "background_context", false, 8000L, ctx -> {
            if (hasIssue(ctx.analysis, ISSUE_CLUTTER)) return null;
            if (ctx.analysis.getCompositionScore() < 0.75f) return null;
            return CompositionAdvice.builder("background_context")
                    .secondary("背景干净，构图很稳")
                    .severity(CompositionAdvice.Severity.INFO)
                    .source(CompositionAdvice.Source.SCENE_RULE)
                    .confidence(0.5f)
                    .ttlMs(2500L)
                    .build();
        }));

        // ---- Phase 2 placeholder rules (need new model heads, no-op) ----
        // These rules deliberately never fire today; their presence
        // documents the future wiring point for has_pattern,
        // has_natural_frame, subject_facing_direction, decisive_moment.
        // TODO(plan-compositionGuidanceV2 Phase 3.16): wire when heads land.
        return r;
    }

    private static boolean hasIssue(FrameAnalysis a, String label) {
        List<String> issues = a.getCompositionIssues();
        if (issues == null || issues.isEmpty()) return false;
        for (String s : issues) {
            if (label.equals(s)) return true;
        }
        return false;
    }
}
