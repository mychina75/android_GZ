package com.samsung.camera.intelligence.guidance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight per-frame analysis result.
 * Contains the decoded model predictions for the current camera preview frame.
 * All classification fields are decoded (string names), not raw logits.
 * Ported from Python FrameAnalysis dataclass in guidance/enums.py.
 */
public class FrameAnalysis {

    // Classifications
    private String sceneType = "general";
    private float sceneConfidence = 0.0f;
    private String sceneType2 = "";
    private float sceneConfidence2 = 0.0f;
    private String lightingCondition = "normal";
    private float lightingConfidence = 0.0f;
    private String lightingCondition2 = "";
    private float lightingConfidence2 = 0.0f;
    private String motionType = "static";
    private float motionConfidence = 0.0f;
    private String motionType2 = "";
    private float motionConfidence2 = 0.0f;
    private String mainSubject = "none";
    private float subjectConfidence = 0.0f;

    // Quality metrics (0-1)
    private float contrastValue = 0.5f;
    private float sharpnessValue = 0.5f;
    private float noiseLevel = 0.1f;

    // Composition
    private float compositionScore = 0.5f;
    private boolean needsCompositionEdit = false;
    private List<String> compositionIssues = new ArrayList<>();
    private boolean isTilted = false;
    private float tiltAngle = 0.0f;

    // Subject location (normalized 0-1; null if unknown)
    private Float subjectCenterX = null;
    private Float subjectCenterY = null;
    private Float fastSubjectCenterX = null;
    private Float fastSubjectCenterY = null;

    // Binary flags
    private boolean hasFace = false;
    private int faceCount = 0;
    private boolean hasText = false;
    private boolean hasShadow = false;
    private boolean hasReflection = false;
    private boolean hasBackgroundPeople = false;
    private boolean hasFlare = false;
    private boolean hasMoire = false;

    // Raw sigmoid probabilities for defect / binary heads, keyed by head name
    // (e.g. "has_shadow"). Populated by FrameAnalyzer alongside the binary
    // setters above so debug overlays can show "score vs threshold" without
    // re-running inference.
    private final LinkedHashMap<String, Float> binaryHeadScores = new LinkedHashMap<>();

    // Trigger nudge outputs (11 canonical trigger names). Populated by
    // FrameAnalyzer from TriggerSignals + TriggerScorer/TriggerExternalHead.
    private final LinkedHashMap<String, Float> triggerSignals = new LinkedHashMap<>();
    private final LinkedHashMap<String, Float> triggerScores = new LinkedHashMap<>();
    private final LinkedHashMap<String, Float> triggerThresholds = new LinkedHashMap<>();
    private final LinkedHashMap<String, Float> triggerL0Scores = new LinkedHashMap<>();
    private final LinkedHashMap<String, Float> triggerL1Scores = new LinkedHashMap<>();

    // Motion
    private float flowMagnitude = 0.0f;

    // Color analysis (filled by color_guide helpers in Phase 1a)
    private List<String> dominantColors = new ArrayList<>();
    private boolean hasComplementaryColors = false;
    private boolean suggestBw = false;

    // Composition feature heads (filled by frame_analyzer in Phase 1b)
    private boolean hasSymmetry = false;
    private String symmetryAxis = "vertical";
    private boolean hasLeadingLines = false;
    private boolean hasDiagonalLines = false;
    private float subjectFillRatio = 0.0f;
    private int subjectCount = 0;
    private float visualComplexity = 0.5f;
    private int sceneDepthLayers = 1;

    // Edit-tool regression heads
    private float brightnessValue = 0.5f;
    private float blurLevel = 0.0f;

    // Bounding-box regression heads (normalized [x, y, w, h] 0-1, null if not available)
    private float[] subjectBbox = null;
    private float[] suggestedCrop = null;

    // External sub-model outputs (filled by FrameAnalyzer when U²-Netp /
    // GAIC v2 are enabled). Kept separate from the main backbone heads so
    // the UI can render BOTH side by side for comparison.
    private float[] externalSubjectBboxNorm = null;
    private float[] externalSubjectCenterNorm = null;
    private float externalSubjectFillRatio = 0f;
    private float[] externalCropNorm = null;
    private float externalCropAestheticScore = 0f;

    // Phase 3 (Composition v2) — derived headroom ratios, computed from
    // subject_bbox edges to picture edges. NaN if subject_bbox unavailable.
    // Used by CompositionAdvisor to cross-check too_much_headroom /
    // insufficient_headroom multi-label outputs and to drive #18 Rule of
    // Space coaching when paired with facingHint.
    private float headroomTop = Float.NaN;
    private float headroomBottom = Float.NaN;
    private float headroomLeft = Float.NaN;
    private float headroomRight = Float.NaN;
    // Approximate "subject is facing" hint: -1=left, 0=center, +1=right.
    // Derived from suggested_crop offset relative to subject_center
    // (GAIC tends to place subjects on the side opposite their facing).
    // Documented as APPROXIMATE; not used for arrow rendering, only as
    // a soft weighting input for advisor rule-of-space scoring.
    private float facingHint = 0f;

    // Phase 1 (Composition v2) — most recent advice produced by
    // CompositionAdvisor for this frame. May be null when no rule fires.
    // Carried on the analysis object so OverlayGenerator stays read-only
    // and MainActivity can read the advice via the GuidanceFrame.
    private CompositionAdvice latestAdvice = null;

    // Lighting
    private Float estimatedLux = null;

    // Edge-obstruction heuristic (filled by FrameAnalyzer)
    private boolean fingerObstruction = false;

    // Backbone feature embedding (L2-normalised; used by MasterMatchGuide)
    private float[] featureEmbedding = null;

    // Combined CLIP-B16+SegNeXt model output: NHWC logits [1, H, W, C]
    private float[] defectSegmentationLogits = null;
    private int defectSegmentationHeight = 0;
    private int defectSegmentationWidth = 0;
    private int defectSegmentationClasses = 0;

    // Timing
    private float inferenceMs = 0.0f;

    public FrameAnalysis() {}

    // Getters and Setters
    public String getSceneType() { return sceneType; }
    public void setSceneType(String sceneType) { this.sceneType = sceneType; }

    public float getSceneConfidence() { return sceneConfidence; }
    public void setSceneConfidence(float sceneConfidence) { this.sceneConfidence = sceneConfidence; }

    public String getSceneType2() { return sceneType2; }
    public void setSceneType2(String sceneType2) { this.sceneType2 = sceneType2; }

    public float getSceneConfidence2() { return sceneConfidence2; }
    public void setSceneConfidence2(float sceneConfidence2) { this.sceneConfidence2 = sceneConfidence2; }

    public String getLightingCondition() { return lightingCondition; }
    public void setLightingCondition(String lightingCondition) { this.lightingCondition = lightingCondition; }

    public float getLightingConfidence() { return lightingConfidence; }
    public void setLightingConfidence(float lightingConfidence) { this.lightingConfidence = lightingConfidence; }

    public String getLightingCondition2() { return lightingCondition2; }
    public void setLightingCondition2(String lightingCondition2) { this.lightingCondition2 = lightingCondition2; }

    public float getLightingConfidence2() { return lightingConfidence2; }
    public void setLightingConfidence2(float lightingConfidence2) { this.lightingConfidence2 = lightingConfidence2; }

    public String getMotionType() { return motionType; }
    public void setMotionType(String motionType) { this.motionType = motionType; }

    public float getMotionConfidence() { return motionConfidence; }
    public void setMotionConfidence(float motionConfidence) { this.motionConfidence = motionConfidence; }

    public String getMotionType2() { return motionType2; }
    public void setMotionType2(String motionType2) { this.motionType2 = motionType2; }

    public float getMotionConfidence2() { return motionConfidence2; }
    public void setMotionConfidence2(float motionConfidence2) { this.motionConfidence2 = motionConfidence2; }

    public String getMainSubject() { return mainSubject; }
    public void setMainSubject(String mainSubject) { this.mainSubject = mainSubject; }

    public float getSubjectConfidence() { return subjectConfidence; }
    public void setSubjectConfidence(float subjectConfidence) { this.subjectConfidence = subjectConfidence; }

    public float getContrastValue() { return contrastValue; }
    public void setContrastValue(float contrastValue) { this.contrastValue = contrastValue; }

    public float getSharpnessValue() { return sharpnessValue; }
    public void setSharpnessValue(float sharpnessValue) { this.sharpnessValue = sharpnessValue; }

    public float getNoiseLevel() { return noiseLevel; }
    public void setNoiseLevel(float noiseLevel) { this.noiseLevel = noiseLevel; }

    public float getCompositionScore() { return compositionScore; }
    public void setCompositionScore(float compositionScore) { this.compositionScore = compositionScore; }

    public boolean isNeedsCompositionEdit() { return needsCompositionEdit; }
    public void setNeedsCompositionEdit(boolean needsCompositionEdit) { this.needsCompositionEdit = needsCompositionEdit; }

    public List<String> getCompositionIssues() { return compositionIssues; }
    public void setCompositionIssues(List<String> compositionIssues) { this.compositionIssues = compositionIssues; }

    public boolean isTilted() { return isTilted; }
    public void setTilted(boolean tilted) { isTilted = tilted; }

    public float getTiltAngle() { return tiltAngle; }
    public void setTiltAngle(float tiltAngle) { this.tiltAngle = tiltAngle; }

    public Float getSubjectCenterX() { return subjectCenterX; }
    public void setSubjectCenterX(Float subjectCenterX) { this.subjectCenterX = subjectCenterX; }

    public Float getSubjectCenterY() { return subjectCenterY; }
    public void setSubjectCenterY(Float subjectCenterY) { this.subjectCenterY = subjectCenterY; }

    public Float getFastSubjectCenterX() { return fastSubjectCenterX; }
    public void setFastSubjectCenterX(Float fastSubjectCenterX) { this.fastSubjectCenterX = fastSubjectCenterX; }

    public Float getFastSubjectCenterY() { return fastSubjectCenterY; }
    public void setFastSubjectCenterY(Float fastSubjectCenterY) { this.fastSubjectCenterY = fastSubjectCenterY; }

    public boolean isHasFace() { return hasFace; }
    public void setHasFace(boolean hasFace) { this.hasFace = hasFace; }

    public int getFaceCount() { return faceCount; }
    public void setFaceCount(int faceCount) { this.faceCount = faceCount; }

    public boolean isHasText() { return hasText; }
    public void setHasText(boolean hasText) { this.hasText = hasText; }

    public boolean isHasShadow() { return hasShadow; }
    public void setHasShadow(boolean hasShadow) { this.hasShadow = hasShadow; }

    public boolean isHasReflection() { return hasReflection; }
    public void setHasReflection(boolean hasReflection) { this.hasReflection = hasReflection; }

    public boolean isHasBackgroundPeople() { return hasBackgroundPeople; }
    public void setHasBackgroundPeople(boolean hasBackgroundPeople) { this.hasBackgroundPeople = hasBackgroundPeople; }

    public boolean isHasFlare() { return hasFlare; }
    public void setHasFlare(boolean hasFlare) { this.hasFlare = hasFlare; }

    public boolean isHasMoire() { return hasMoire; }
    public void setHasMoire(boolean hasMoire) { this.hasMoire = hasMoire; }

    /** Record the raw sigmoid probability for a binary/defect head. */
    public void putBinaryHeadScore(String key, float score) {
        if (key != null) binaryHeadScores.put(key, score);
    }
    /** Raw sigmoid probability for a binary head, or null if not recorded. */
    public Float getBinaryHeadScore(String key) {
        return key == null ? null : binaryHeadScores.get(key);
    }
    /** Snapshot of all recorded binary head scores. */
    public Map<String, Float> getBinaryHeadScores() {
        return new LinkedHashMap<>(binaryHeadScores);
    }

    public void setTriggerSignals(Map<String, Float> values) {
        triggerSignals.clear();
        if (values != null) triggerSignals.putAll(values);
    }
    public Map<String, Float> getTriggerSignals() {
        return new LinkedHashMap<>(triggerSignals);
    }

    public void setTriggerScores(Map<String, Float> values) {
        triggerScores.clear();
        if (values != null) triggerScores.putAll(values);
    }
    public Map<String, Float> getTriggerScores() {
        return new LinkedHashMap<>(triggerScores);
    }

    public void setTriggerThresholds(Map<String, Float> values) {
        triggerThresholds.clear();
        if (values != null) triggerThresholds.putAll(values);
    }
    public Map<String, Float> getTriggerThresholds() {
        return new LinkedHashMap<>(triggerThresholds);
    }

    public void setTriggerL0Scores(Map<String, Float> values) {
        triggerL0Scores.clear();
        if (values != null) triggerL0Scores.putAll(values);
    }
    public Map<String, Float> getTriggerL0Scores() {
        return new LinkedHashMap<>(triggerL0Scores);
    }

    public void setTriggerL1Scores(Map<String, Float> values) {
        triggerL1Scores.clear();
        if (values != null) triggerL1Scores.putAll(values);
    }
    public Map<String, Float> getTriggerL1Scores() {
        return new LinkedHashMap<>(triggerL1Scores);
    }

    public float getFlowMagnitude() { return flowMagnitude; }
    public void setFlowMagnitude(float flowMagnitude) { this.flowMagnitude = flowMagnitude; }

    public Float getEstimatedLux() { return estimatedLux; }
    public void setEstimatedLux(Float estimatedLux) { this.estimatedLux = estimatedLux; }

    public boolean isFingerObstruction() { return fingerObstruction; }
    public void setFingerObstruction(boolean fingerObstruction) { this.fingerObstruction = fingerObstruction; }

    public float[] getFeatureEmbedding() { return featureEmbedding; }
    public void setFeatureEmbedding(float[] featureEmbedding) { this.featureEmbedding = featureEmbedding; }

    public float[] getDefectSegmentationLogits() { return defectSegmentationLogits; }
    public int getDefectSegmentationHeight() { return defectSegmentationHeight; }
    public int getDefectSegmentationWidth() { return defectSegmentationWidth; }
    public int getDefectSegmentationClasses() { return defectSegmentationClasses; }
    public boolean hasDefectSegmentationLogits() { return defectSegmentationLogits != null; }
    public void setDefectSegmentationLogits(float[] logits, int height, int width, int classes) {
        this.defectSegmentationLogits = logits;
        this.defectSegmentationHeight = height;
        this.defectSegmentationWidth = width;
        this.defectSegmentationClasses = classes;
    }

    public float getInferenceMs() { return inferenceMs; }
    public void setInferenceMs(float inferenceMs) { this.inferenceMs = inferenceMs; }

    // Color analysis getters/setters
    public List<String> getDominantColors() { return dominantColors; }
    public void setDominantColors(List<String> dominantColors) { this.dominantColors = dominantColors; }

    public boolean isHasComplementaryColors() { return hasComplementaryColors; }
    public void setHasComplementaryColors(boolean hasComplementaryColors) { this.hasComplementaryColors = hasComplementaryColors; }

    public boolean isSuggestBw() { return suggestBw; }
    public void setSuggestBw(boolean suggestBw) { this.suggestBw = suggestBw; }

    // Phase 1b composition feature getters/setters
    public boolean isHasSymmetry() { return hasSymmetry; }
    public void setHasSymmetry(boolean hasSymmetry) { this.hasSymmetry = hasSymmetry; }

    public String getSymmetryAxis() { return symmetryAxis; }
    public void setSymmetryAxis(String symmetryAxis) { this.symmetryAxis = symmetryAxis; }

    public boolean isHasLeadingLines() { return hasLeadingLines; }
    public void setHasLeadingLines(boolean hasLeadingLines) { this.hasLeadingLines = hasLeadingLines; }

    public boolean isHasDiagonalLines() { return hasDiagonalLines; }
    public void setHasDiagonalLines(boolean hasDiagonalLines) { this.hasDiagonalLines = hasDiagonalLines; }

    public float getSubjectFillRatio() { return subjectFillRatio; }
    public void setSubjectFillRatio(float subjectFillRatio) { this.subjectFillRatio = subjectFillRatio; }

    public int getSubjectCount() { return subjectCount; }
    public void setSubjectCount(int subjectCount) { this.subjectCount = subjectCount; }

    public float getVisualComplexity() { return visualComplexity; }
    public void setVisualComplexity(float visualComplexity) { this.visualComplexity = visualComplexity; }

    public int getSceneDepthLayers() { return sceneDepthLayers; }
    public void setSceneDepthLayers(int sceneDepthLayers) { this.sceneDepthLayers = sceneDepthLayers; }

    public float getBrightnessValue() { return brightnessValue; }
    public void setBrightnessValue(float brightnessValue) { this.brightnessValue = brightnessValue; }

    public float getBlurLevel() { return blurLevel; }
    public void setBlurLevel(float blurLevel) { this.blurLevel = blurLevel; }

    public float[] getSubjectBbox() { return subjectBbox; }
    public void setSubjectBbox(float[] subjectBbox) { this.subjectBbox = subjectBbox; }

    public float[] getSuggestedCrop() { return suggestedCrop; }
    public void setSuggestedCrop(float[] suggestedCrop) { this.suggestedCrop = suggestedCrop; }

    // External sub-model getters/setters
    public float[] getExternalSubjectBboxNorm() { return externalSubjectBboxNorm; }
    public void setExternalSubjectBboxNorm(float[] v) { this.externalSubjectBboxNorm = v; }
    public float[] getExternalSubjectCenterNorm() { return externalSubjectCenterNorm; }
    public void setExternalSubjectCenterNorm(float[] v) { this.externalSubjectCenterNorm = v; }
    public float getExternalSubjectFillRatio() { return externalSubjectFillRatio; }
    public void setExternalSubjectFillRatio(float v) { this.externalSubjectFillRatio = v; }
    public float[] getExternalCropNorm() { return externalCropNorm; }
    public void setExternalCropNorm(float[] v) { this.externalCropNorm = v; }
    public float getExternalCropAestheticScore() { return externalCropAestheticScore; }
    public void setExternalCropAestheticScore(float v) { this.externalCropAestheticScore = v; }

    // Phase 3 — derived headroom + facing direction.
    public float getHeadroomTop() { return headroomTop; }
    public void setHeadroomTop(float v) { this.headroomTop = v; }
    public float getHeadroomBottom() { return headroomBottom; }
    public void setHeadroomBottom(float v) { this.headroomBottom = v; }
    public float getHeadroomLeft() { return headroomLeft; }
    public void setHeadroomLeft(float v) { this.headroomLeft = v; }
    public float getHeadroomRight() { return headroomRight; }
    public void setHeadroomRight(float v) { this.headroomRight = v; }
    public float getFacingHint() { return facingHint; }
    public void setFacingHint(float v) { this.facingHint = v; }

    // Phase 1 — composition advice attached by OverlayGenerator.
    public CompositionAdvice getLatestAdvice() { return latestAdvice; }
    public void setLatestAdvice(CompositionAdvice v) { this.latestAdvice = v; }
}
