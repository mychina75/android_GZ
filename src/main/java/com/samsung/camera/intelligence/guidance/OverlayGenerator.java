package com.samsung.camera.intelligence.guidance;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Overlay Generator — Central orchestrator for the real-time guidance pipeline.
 *
 * Merges overlay lists from {@link CompositionGuide}, {@link TechnicalGuide},
 * {@link AngleGuide}, and optionally {@link MasterMatchGuide}, applies
 * priority-based deduplication and type-based caps, then wraps the result
 * in a {@link GuidanceFrame} ready for JSON serialization to the Android renderer.
 *
 * Caps (configurable):
 *   - max 1 GridOverlay
 *   - max 1 HorizonLine
 *   - max 2 DirectionArrows
 *   - max 1 AngleSuggestion
 *   - max 3 AlertBadges (highest urgency first)
 *   - max 1 SymmetryGuideOverlay
 *   - max 2 CompositionTipOverlay
 *   - max 3 MasterMatchOverlay
 *
 * Ported from Python overlay_generator.py
 */
public class OverlayGenerator {

    // Urgency priority (higher = more important)
    private static final Map<GuidanceUrgency, Integer> URGENCY_RANK = new HashMap<>();
    static {
        URGENCY_RANK.put(GuidanceUrgency.CRITICAL, 4);
        URGENCY_RANK.put(GuidanceUrgency.WARNING, 3);
        URGENCY_RANK.put(GuidanceUrgency.SUGGESTION, 2);
        URGENCY_RANK.put(GuidanceUrgency.INFO, 1);
    }

    // ---- Config ----
    private static final String TAG = "OverlayGen";
    
    private int maxGrids = 1;
    private int maxHorizons = 1;
    private int maxArrows = 2;
    private int maxSubjectGuides = 1;
    private int maxAngleSuggestions = 1;
    private int maxAlertBadges = 3;
    private int maxSymmetryGuides = 1;
    private int maxCompositionTips = 2;
    private int maxMasterMatches = 3;
    private boolean enableSmoothing = true;
    private boolean enableMasterMatch = false;
    private boolean enableColorAnalysis = true;
    private int bwAnalysisIntervalFrames = 3;
    private int colorAnalysisIntervalFrames = 8;
    private float colorAnalysisContrastDelta = 0.15f;

    private int frameIndex = 0;
    private int lastBwAnalysisFrameIdx = -1;
    private int lastColorAnalysisFrameIdx = -1;
    private String lastAnalysisSceneType = null;
    private String lastAnalysisSubject = null;
    private Float lastAnalysisContrast = null;
    private boolean cachedSuggestBw = false;
    private List<String> cachedDominantColors = new ArrayList<>();
    private boolean cachedHasComplementaryColors = false;

    private static final int COLOR_ANALYSIS_SIZE = 64;
    private static final float BW_SATURATION_THRESHOLD = 0.25f;
    private static final float BW_CONTRAST_THRESHOLD = 0.6f;
    private static final float COMPLEMENTARY_HUE_TOLERANCE = 15.0f;

    // ---- Sub-modules ----

    private final CompositionGuide compositionGuide;
    private final TechnicalGuide technicalGuide;
    private final AngleGuide angleGuide;
    private TemporalSmoother smoother;
    private MasterMatchGuide masterMatchGuide;
    // Phase 1 (Composition v2): central advice scheduler. Lives here so a
    // single instance retains per-rule cool-downs across frames.
    private final CompositionAdvisor compositionAdvisor;
    private boolean compositionAdviceEnabled = true;
    // Updated by MainActivity from its MatchLockListener / overlay state.
    private volatile boolean currentTargetLocked = false;
    private volatile float currentAlignmentScore = 0f;

    // Phase 8–10 (Composition v2 — Plan A): silhouette overlay state.
    // Default OFF — acts as the 7-day kill-switch flag described in the
    // plan. Flipped on via Settings → silhouette guide once the experiment
    // ships to the demo build.
    private final SilhouetteController silhouetteController = new SilhouetteController();
    private boolean silhouetteOverlayEnabled = false;

    public OverlayGenerator() {
        this.compositionGuide = new CompositionGuide();
        this.technicalGuide = new TechnicalGuide();
        this.angleGuide = new AngleGuide();
        this.smoother = new TemporalSmoother();
        this.masterMatchGuide = null;
        this.compositionAdvisor = new CompositionAdvisor();
    }

    // ---- Config setters ----

    public void setMaxGrids(int v) { this.maxGrids = v; }
    public void setMaxHorizons(int v) { this.maxHorizons = v; }
    public void setMaxArrows(int v) { this.maxArrows = v; }
    public void setMaxSubjectGuides(int v) { this.maxSubjectGuides = v; }
    public void setMaxAngleSuggestions(int v) { this.maxAngleSuggestions = v; }
    public void setMaxAlertBadges(int v) { this.maxAlertBadges = v; }
    public void setMaxSymmetryGuides(int v) { this.maxSymmetryGuides = v; }
    public void setMaxCompositionTips(int v) { this.maxCompositionTips = v; }
    public void setMaxMasterMatches(int v) { this.maxMasterMatches = v; }
    public void setEnableColorAnalysis(boolean v) { this.enableColorAnalysis = v; }
    public void setBwAnalysisIntervalFrames(int v) { this.bwAnalysisIntervalFrames = Math.max(1, v); }
    public void setColorAnalysisIntervalFrames(int v) { this.colorAnalysisIntervalFrames = Math.max(1, v); }
    public void setColorAnalysisContrastDelta(float v) { this.colorAnalysisContrastDelta = Math.max(0.0f, v); }

    public void setEnableSmoothing(boolean v) {
        this.enableSmoothing = v;
        if (v && smoother == null) {
            smoother = new TemporalSmoother();
        } else if (!v) {
            smoother = null;
        }
    }

    public void setEnableMasterMatch(boolean v) {
        this.enableMasterMatch = v;
        if (v && masterMatchGuide == null) {
            masterMatchGuide = new MasterMatchGuide();
        } else if (!v) {
            masterMatchGuide = null;
        }
    }

    // Access to sub-modules for configuration
    public CompositionGuide getCompositionGuide() { return compositionGuide; }
    public TechnicalGuide getTechnicalGuide() { return technicalGuide; }
    public AngleGuide getAngleGuide() { return angleGuide; }
    public TemporalSmoother getSmoother() { return smoother; }
    public MasterMatchGuide getMasterMatchGuide() { return masterMatchGuide; }

    // Phase 1 (Composition v2) — advisor accessors.
    public CompositionAdvisor getCompositionAdvisor() { return compositionAdvisor; }
    public void setCompositionAdviceEnabled(boolean v) { this.compositionAdviceEnabled = v; }
    public void setAdviceCoolDownMs(long ms) { compositionAdvisor.setAdviceCoolDownMs(ms); }
    /** Push current target-lock state from MainActivity / overlay view. */
    public void setTargetLockState(boolean locked, float alignmentScore) {
        this.currentTargetLocked = locked;
        this.currentAlignmentScore = alignmentScore;
    }

    // Phase 8–10 (Composition v2 — Plan A) — silhouette overlay accessors.
    public SilhouetteController getSilhouetteController() { return silhouetteController; }
    public void setSilhouetteOverlayEnabled(boolean v) { this.silhouetteOverlayEnabled = v; }
    public boolean isSilhouetteOverlayEnabled() { return silhouetteOverlayEnabled; }

    // ---- Public API ----

    /**
     * Full pipeline: evaluate rules → merge → smooth → cap → frame.
     *
     * @param analysis Decoded per-frame analysis (optionally pre-smoothed)
     * @return A {@link GuidanceFrame} ready for JSON serialization
     */
    public GuidanceFrame generate(FrameAnalysis analysis) {
        return generate(analysis, null);
    }

    /**
     * Full pipeline with optional raw bitmap for lightweight colour analysis.
     *
     * @param analysis Decoded per-frame analysis (optionally pre-smoothed)
     * @param frame Raw preview bitmap used for low-frequency colour analysis
     * @return A {@link GuidanceFrame} ready for JSON serialization
     */
    public GuidanceFrame generate(FrameAnalysis analysis, Bitmap frame) {
        long startMs = System.currentTimeMillis();
        long startNano = System.nanoTime();
        frameIndex++;

        // Optionally smooth the analysis itself (EMA + hysteresis)
        if (smoother != null && enableSmoothing) {
            analysis = smoother.smoothAnalysis(analysis);
        }

        if (frame != null && enableColorAnalysis) {
            applyCachedColorAnalysis(analysis, frame);
        }

        // Collect overlays from all three guides
        List<GuidanceOverlay> compositionOverlays = compositionGuide.evaluate(analysis);
        List<GuidanceOverlay> technicalOverlays = technicalGuide.evaluate(analysis);
        List<GuidanceOverlay> angleOverlays = angleGuide.evaluate(analysis);

        List<GuidanceOverlay> allOverlays = new ArrayList<>();
        allOverlays.addAll(compositionOverlays);
        allOverlays.addAll(technicalOverlays);
        allOverlays.addAll(angleOverlays);

        // Layer 4 — Master match (aesthetic transfer)
        if (enableMasterMatch && masterMatchGuide != null) {
            List<MasterMatchOverlay> masterOverlays = masterMatchGuide.evaluate(analysis);
            allOverlays.addAll(masterOverlays);
            if (frameIndex % 30 == 1) {
                Log.w(TAG, "[DIAG] MasterMatch: enabled=true, results=" + masterOverlays.size());
            }
        } else if (frameIndex % 30 == 1) {
            Log.w(TAG, "[DIAG] MasterMatch: enabled=" + enableMasterMatch
                    + ", guide=" + (masterMatchGuide != null ? "loaded" : "null"));
        }        
        // // Layer 4 — Master match (aesthetic transfer)
        // if (enableMasterMatch && masterMatchGuide != null) {
        //     List<MasterMatchOverlay> masterOverlays = masterMatchGuide.evaluate(analysis);
        //     allOverlays.addAll(masterOverlays);
        // }

        // Smooth overlays (debounce alerts, EMA arrows)
        if (smoother != null && enableSmoothing) {
            allOverlays = smoother.smoothOverlays(allOverlays);
        }

        // Deduplicate and cap
        List<GuidanceOverlay> capped = capOverlays(allOverlays);

        // Phase 8–10 (Composition v2 — Plan A) — silhouette outline driven
        // by the GAIC top-1 crop carried inside the FramingTemplateOverlay.
        // Flag-gated (off by default) so the experiment can be killed in
        // <7 days without a code change.
        if (silhouetteOverlayEnabled) {
            FramingTemplateOverlay tpl = null;
            for (GuidanceOverlay o : capped) {
                if (o instanceof FramingTemplateOverlay) {
                    tpl = (FramingTemplateOverlay) o;
                    break;
                }
            }
            SilhouetteOverlay silhouette = silhouetteController.tick(
                    tpl, analysis.getSceneType(), startMs);
            if (silhouette != null) {
                capped.add(silhouette);
            }
        } else if (silhouetteController.getState() != SilhouetteOverlay.State.OFF) {
            silhouetteController.reset();
        }

        // Phase 1 (Composition v2) — compute single-source-of-truth advice.
        if (compositionAdviceEnabled) {
            CompositionAdvice advice = compositionAdvisor.advise(
                    analysis, currentTargetLocked, currentAlignmentScore);
            analysis.setLatestAdvice(advice);
        }

        double elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0;

        GuidanceFrame result = new GuidanceFrame();
        result.setOverlays(capped);
        result.setAnalysis(analysis);
        result.setTimestampMs((double) startMs);
        result.setPipelineLatencyMs(elapsedMs);
        return result;
    }

    /**
     * Reset temporal state (e.g. on camera switch).
     */
    public void reset() {
        compositionGuide.reset();
        if (smoother != null) {
            smoother.reset();
        }
        if (masterMatchGuide != null) {
            masterMatchGuide.reset();
        }
        compositionAdvisor.reset();
        silhouetteController.reset();
        frameIndex = 0;
        lastBwAnalysisFrameIdx = -1;
        lastColorAnalysisFrameIdx = -1;
        lastAnalysisSceneType = null;
        lastAnalysisSubject = null;
        lastAnalysisContrast = null;
        cachedSuggestBw = false;
        cachedDominantColors = new ArrayList<>();
        cachedHasComplementaryColors = false;
    }

    private void applyCachedColorAnalysis(FrameAnalysis analysis, Bitmap frame) {
        if (shouldRefreshBw(analysis)) {
            cachedSuggestBw = suggestBw(frame, analysis.getContrastValue());
            lastBwAnalysisFrameIdx = frameIndex;
        }

        if (shouldRefreshColor(analysis)) {
            ColorAnalysisResult result = analyzeColors(frame);
            cachedDominantColors = result.dominantColors;
            cachedHasComplementaryColors = result.hasComplementaryColors;
            lastColorAnalysisFrameIdx = frameIndex;
            lastAnalysisSceneType = analysis.getSceneType();
            lastAnalysisSubject = analysis.getMainSubject();
            lastAnalysisContrast = analysis.getContrastValue();
        }

        analysis.setSuggestBw(cachedSuggestBw);
        analysis.setDominantColors(new ArrayList<>(cachedDominantColors));
        analysis.setHasComplementaryColors(cachedHasComplementaryColors);
    }

    private boolean shouldRefreshBw(FrameAnalysis analysis) {
        if (lastBwAnalysisFrameIdx < 0) {
            return true;
        }
        if (analysisContextChanged(analysis)) {
            return true;
        }
        return (frameIndex - lastBwAnalysisFrameIdx) >= Math.max(1, bwAnalysisIntervalFrames);
    }

    private boolean shouldRefreshColor(FrameAnalysis analysis) {
        if (lastColorAnalysisFrameIdx < 0) {
            return true;
        }
        if (analysisContextChanged(analysis)) {
            return true;
        }
        return (frameIndex - lastColorAnalysisFrameIdx) >= Math.max(1, colorAnalysisIntervalFrames);
    }

    private boolean analysisContextChanged(FrameAnalysis analysis) {
        if (lastAnalysisSceneType == null || lastAnalysisSubject == null || lastAnalysisContrast == null) {
            return true;
        }
        if (!analysis.getSceneType().equals(lastAnalysisSceneType)) {
            return true;
        }
        if (!analysis.getMainSubject().equals(lastAnalysisSubject)) {
            return true;
        }
        return Math.abs(analysis.getContrastValue() - lastAnalysisContrast) >= colorAnalysisContrastDelta;
    }

    private boolean suggestBw(Bitmap frame, float contrastValue) {
        if (contrastValue <= BW_CONTRAST_THRESHOLD) {
            return false;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(frame, COLOR_ANALYSIS_SIZE, COLOR_ANALYSIS_SIZE, true);
        int width = scaled.getWidth();
        int height = scaled.getHeight();
        int count = width * height;
        int[] pixels = new int[count];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] hsv = new float[3];
        float saturationSum = 0.0f;
        for (int pixel : pixels) {
            Color.colorToHSV(pixel, hsv);
            saturationSum += hsv[1];
        }

        return count > 0 && (saturationSum / count) < BW_SATURATION_THRESHOLD;
    }

    private ColorAnalysisResult analyzeColors(Bitmap frame) {
        Bitmap scaled = Bitmap.createScaledBitmap(frame, COLOR_ANALYSIS_SIZE, COLOR_ANALYSIS_SIZE, true);
        int width = scaled.getWidth();
        int height = scaled.getHeight();
        int count = width * height;
        int[] pixels = new int[count];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);

        Map<String, Integer> colorCounts = new HashMap<>();
        List<Float> chromaticHues = new ArrayList<>();
        float[] hsv = new float[3];
        for (int pixel : pixels) {
            Color.colorToHSV(pixel, hsv);
            float saturation = hsv[1];
            String colorName;
            if (saturation < 0.10f) {
                colorName = "neutral";
            } else {
                colorName = hueToName(hsv[0]);
            }
            colorCounts.put(colorName, colorCounts.getOrDefault(colorName, 0) + 1);
            if (saturation >= 0.15f) {
                chromaticHues.add(hsv[0]);
            }
        }

        List<Map.Entry<String, Integer>> sortedCounts = new ArrayList<>(colorCounts.entrySet());
        sortedCounts.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> dominantColors = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : sortedCounts) {
            if (!dominantColors.contains(entry.getKey())) {
                dominantColors.add(entry.getKey());
            }
            if (dominantColors.size() >= 3) {
                break;
            }
        }

        boolean hasComplementaryColors = false;
        for (int i = 0; i < chromaticHues.size() && !hasComplementaryColors; i++) {
            for (int j = i + 1; j < chromaticHues.size(); j++) {
                float diff = Math.abs(chromaticHues.get(i) - chromaticHues.get(j));
                diff = Math.min(diff, 360.0f - diff);
                if (Math.abs(diff - 180.0f) <= COMPLEMENTARY_HUE_TOLERANCE) {
                    hasComplementaryColors = true;
                    break;
                }
            }
        }

        return new ColorAnalysisResult(dominantColors, hasComplementaryColors);
    }

    private static String hueToName(float hueDeg) {
        float hue = (hueDeg % 360.0f + 360.0f) % 360.0f;
        if (hue < 15.0f) return "red";
        if (hue < 45.0f) return "orange";
        if (hue < 75.0f) return "yellow";
        if (hue < 150.0f) return "green";
        if (hue < 195.0f) return "cyan";
        if (hue < 255.0f) return "blue";
        if (hue < 285.0f) return "purple";
        if (hue < 345.0f) return "pink";
        return "red";
    }

    private static class ColorAnalysisResult {
        final List<String> dominantColors;
        final boolean hasComplementaryColors;

        ColorAnalysisResult(List<String> dominantColors, boolean hasComplementaryColors) {
            this.dominantColors = dominantColors != null ? dominantColors : Collections.emptyList();
            this.hasComplementaryColors = hasComplementaryColors;
        }
    }

    // ---- Internal: cap overlays by type ----

    private List<GuidanceOverlay> capOverlays(List<GuidanceOverlay> overlays) {
        List<GuidanceOverlay> grids = new ArrayList<>();
        List<GuidanceOverlay> horizons = new ArrayList<>();
        List<GuidanceOverlay> arrows = new ArrayList<>();
        List<GuidanceOverlay> guides = new ArrayList<>();
        List<GuidanceOverlay> angleSuggestions = new ArrayList<>();
        List<AlertBadge> badges = new ArrayList<>();
        List<GuidanceOverlay> symmetryGuides = new ArrayList<>();
        List<GuidanceOverlay> compositionTips = new ArrayList<>();
        List<GuidanceOverlay> masterMatches = new ArrayList<>();
        List<GuidanceOverlay> others = new ArrayList<>();

        for (GuidanceOverlay o : overlays) {
            if (o instanceof GridOverlay) {
                grids.add(o);
            } else if (o instanceof HorizonLine) {
                horizons.add(o);
            } else if (o instanceof DirectionArrow) {
                arrows.add(o);
            } else if (o instanceof SubjectGuide) {
                guides.add(o);
            } else if (o instanceof AngleSuggestion) {
                angleSuggestions.add(o);
            } else if (o instanceof AlertBadge) {
                badges.add((AlertBadge) o);
            } else if (o instanceof SymmetryGuideOverlay) {
                symmetryGuides.add(o);
            } else if (o instanceof CompositionTipOverlay) {
                compositionTips.add(o);
            } else if (o instanceof MasterMatchOverlay) {
                masterMatches.add(o);
            } else {
                others.add(o);
            }
        }

        // Sort each list by urgency descending
        grids.sort((a, b) -> urgencyRank(b) - urgencyRank(a));
        horizons.sort((a, b) -> urgencyRank(b) - urgencyRank(a));
        arrows.sort((a, b) -> urgencyRank(b) - urgencyRank(a));
        guides.sort((a, b) -> urgencyRank(b) - urgencyRank(a));
        angleSuggestions.sort((a, b) -> urgencyRank(b) - urgencyRank(a));
        symmetryGuides.sort((a, b) -> urgencyRank(b) - urgencyRank(a));
        compositionTips.sort((a, b) -> urgencyRank(b) - urgencyRank(a));

        // Badges: deduplicate by alert_id (keep highest urgency)
        badges.sort((a, b) -> urgencyRank(b) - urgencyRank(a));
        Set<String> seenIds = new HashSet<>();
        List<AlertBadge> uniqueBadges = new ArrayList<>();
        for (AlertBadge b : badges) {
            if (!seenIds.contains(b.getAlertId())) {
                seenIds.add(b.getAlertId());
                uniqueBadges.add(b);
            }
        }

        // Apply caps
        List<GuidanceOverlay> result = new ArrayList<>();
        result.addAll(grids.subList(0, Math.min(grids.size(), maxGrids)));
        result.addAll(horizons.subList(0, Math.min(horizons.size(), maxHorizons)));
        result.addAll(arrows.subList(0, Math.min(arrows.size(), maxArrows)));
        result.addAll(guides.subList(0, Math.min(guides.size(), maxSubjectGuides)));
        result.addAll(angleSuggestions.subList(0, Math.min(angleSuggestions.size(), maxAngleSuggestions)));
        result.addAll(uniqueBadges.subList(0, Math.min(uniqueBadges.size(), maxAlertBadges)));
        result.addAll(symmetryGuides.subList(0, Math.min(symmetryGuides.size(), maxSymmetryGuides)));
        result.addAll(compositionTips.subList(0, Math.min(compositionTips.size(), maxCompositionTips)));
        result.addAll(masterMatches.subList(0, Math.min(masterMatches.size(), maxMasterMatches)));
        result.addAll(others); // No cap on generic overlays

        return result;
    }

    private static int urgencyRank(GuidanceOverlay o) {
        Integer rank = URGENCY_RANK.get(o.getUrgency());
        return rank != null ? rank : 0;
    }
}
