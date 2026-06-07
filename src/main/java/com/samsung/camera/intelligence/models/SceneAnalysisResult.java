package com.samsung.camera.intelligence.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete result of scene analysis.
 * Ported from Python SceneAnalysisResult dataclass in models/enums.py.
 */
public class SceneAnalysisResult {

    // Primary classifications
    private SceneType sceneType;
    private float sceneTypeConfidence;
    private LightingCondition lightingCondition;
    private float lightingConfidence;
    private MotionType motionType;
    private float motionConfidence;
    private MainSubject mainSubject;
    private float subjectConfidence;
    private float contrastValue;
    private float sharpnessValue;
    private float noiseLevel;

    // Secondary scene types
    private List<SceneType> secondarySceneTypes = new ArrayList<>();

    // Lighting analysis
    private Float estimatedLux; // nullable

    // Motion analysis
    private Float motionSpeed; // nullable, pixels/frame
    private String motionDirection; // nullable

    // Subject identification
    private float[] subjectBoundingBox; // nullable, [x, y, w, h] normalized

    // Additional features
    private boolean hasFace = false;
    private int faceCount = 0;
    private boolean hasText = false;
    private boolean isTilted = false;
    private float tiltAngle = 0.0f;
    private boolean hasShadow = false;
    private boolean hasReflection = false;
    private boolean hasBackgroundPeople = false;
    private boolean hasFlare = false;
    private boolean hasMoire = false;

    // Composition analysis
    private float compositionScore = 0.5f;
    private List<String> compositionIssues = new ArrayList<>();
    private boolean needsCompositionEdit = false;
    private float[] suggestedCrop; // nullable, [x, y, w, h] normalized

    // Resolution recommendation (populated by recommender, not model)
    private CameraResolution recommendedResolution;

    // Raw feature vectors (for advanced processing)
    private float[] featureEmbedding; // nullable

    // Motion capture advisory (populated by MotionCaptureAdvisor)
    private boolean preferVideo = false;
    private Integer recommendedFps = null; // 30, 60, 120, or 240
    private String motionSpeedTier = null; // MotionSpeedTier value
    private String captureWarning = null;
    private String recommendedShutter = null; // e.g. "1/500"

    // Raw feature vectors (separate channels matching Python)
    private float[] siglipFeatures = null;
    private float[] motionFeatures = null;

    // Camera state (set by caller, not by model)
    private boolean useFrontCamera = false;

    // Phase 1b composition feature heads
    private boolean hasSymmetry = false;
    private boolean hasDiagonalLines = false;
    private boolean hasLeadingLines = false;
    private float subjectFillRatio = 0.0f;
    private int subjectCount = 0;
    private float visualComplexity = 0.5f;
    private int sceneDepthLayers = 1;

    // Edit-tool regression heads
    private float brightnessValue = 0.5f;
    private float blurLevel = 0.0f;

    public SceneAnalysisResult() {}

    public SceneAnalysisResult(SceneType sceneType, float sceneTypeConfidence,
                               LightingCondition lightingCondition, float lightingConfidence,
                               MotionType motionType, float motionConfidence,
                               MainSubject mainSubject, float subjectConfidence,
                               float contrastValue, float sharpnessValue, float noiseLevel) {
        this.sceneType = sceneType;
        this.sceneTypeConfidence = sceneTypeConfidence;
        this.lightingCondition = lightingCondition;
        this.lightingConfidence = lightingConfidence;
        this.motionType = motionType;
        this.motionConfidence = motionConfidence;
        this.mainSubject = mainSubject;
        this.subjectConfidence = subjectConfidence;
        this.contrastValue = contrastValue;
        this.sharpnessValue = sharpnessValue;
        this.noiseLevel = noiseLevel;
    }

    // Getters and Setters
    public SceneType getSceneType() { return sceneType; }
    public void setSceneType(SceneType sceneType) { this.sceneType = sceneType; }

    public float getSceneTypeConfidence() { return sceneTypeConfidence; }
    public void setSceneTypeConfidence(float sceneTypeConfidence) { this.sceneTypeConfidence = sceneTypeConfidence; }

    public LightingCondition getLightingCondition() { return lightingCondition; }
    public void setLightingCondition(LightingCondition lightingCondition) { this.lightingCondition = lightingCondition; }

    public float getLightingConfidence() { return lightingConfidence; }
    public void setLightingConfidence(float lightingConfidence) { this.lightingConfidence = lightingConfidence; }

    public MotionType getMotionType() { return motionType; }
    public void setMotionType(MotionType motionType) { this.motionType = motionType; }

    public float getMotionConfidence() { return motionConfidence; }
    public void setMotionConfidence(float motionConfidence) { this.motionConfidence = motionConfidence; }

    public MainSubject getMainSubject() { return mainSubject; }
    public void setMainSubject(MainSubject mainSubject) { this.mainSubject = mainSubject; }

    public float getSubjectConfidence() { return subjectConfidence; }
    public void setSubjectConfidence(float subjectConfidence) { this.subjectConfidence = subjectConfidence; }

    public float getContrastValue() { return contrastValue; }
    public void setContrastValue(float contrastValue) { this.contrastValue = contrastValue; }

    public float getSharpnessValue() { return sharpnessValue; }
    public void setSharpnessValue(float sharpnessValue) { this.sharpnessValue = sharpnessValue; }

    public float getNoiseLevel() { return noiseLevel; }
    public void setNoiseLevel(float noiseLevel) { this.noiseLevel = noiseLevel; }

    public List<SceneType> getSecondarySceneTypes() { return secondarySceneTypes; }
    public void setSecondarySceneTypes(List<SceneType> secondarySceneTypes) { this.secondarySceneTypes = secondarySceneTypes; }

    public Float getEstimatedLux() { return estimatedLux; }
    public void setEstimatedLux(Float estimatedLux) { this.estimatedLux = estimatedLux; }

    public Float getMotionSpeed() { return motionSpeed; }
    public void setMotionSpeed(Float motionSpeed) { this.motionSpeed = motionSpeed; }

    public String getMotionDirection() { return motionDirection; }
    public void setMotionDirection(String motionDirection) { this.motionDirection = motionDirection; }

    public float[] getSubjectBoundingBox() { return subjectBoundingBox; }
    public void setSubjectBoundingBox(float[] subjectBoundingBox) { this.subjectBoundingBox = subjectBoundingBox; }

    public boolean isHasFace() { return hasFace; }
    public void setHasFace(boolean hasFace) { this.hasFace = hasFace; }

    public int getFaceCount() { return faceCount; }
    public void setFaceCount(int faceCount) { this.faceCount = faceCount; }

    public boolean isHasText() { return hasText; }
    public void setHasText(boolean hasText) { this.hasText = hasText; }

    public boolean isTilted() { return isTilted; }
    public void setTilted(boolean tilted) { isTilted = tilted; }

    public float getTiltAngle() { return tiltAngle; }
    public void setTiltAngle(float tiltAngle) { this.tiltAngle = tiltAngle; }

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

    public float getCompositionScore() { return compositionScore; }
    public void setCompositionScore(float compositionScore) { this.compositionScore = compositionScore; }

    public List<String> getCompositionIssues() { return compositionIssues; }
    public void setCompositionIssues(List<String> compositionIssues) { this.compositionIssues = compositionIssues; }

    public boolean isNeedsCompositionEdit() { return needsCompositionEdit; }
    public void setNeedsCompositionEdit(boolean needsCompositionEdit) { this.needsCompositionEdit = needsCompositionEdit; }

    public float[] getSuggestedCrop() { return suggestedCrop; }
    public void setSuggestedCrop(float[] suggestedCrop) { this.suggestedCrop = suggestedCrop; }

    public CameraResolution getRecommendedResolution() { return recommendedResolution; }
    public void setRecommendedResolution(CameraResolution recommendedResolution) { this.recommendedResolution = recommendedResolution; }

    public float[] getFeatureEmbedding() { return featureEmbedding; }
    public void setFeatureEmbedding(float[] featureEmbedding) { this.featureEmbedding = featureEmbedding; }

    // Motion capture advisory getters/setters
    public boolean isPreferVideo() { return preferVideo; }
    public void setPreferVideo(boolean preferVideo) { this.preferVideo = preferVideo; }

    public Integer getRecommendedFps() { return recommendedFps; }
    public void setRecommendedFps(Integer recommendedFps) { this.recommendedFps = recommendedFps; }

    public String getMotionSpeedTier() { return motionSpeedTier; }
    public void setMotionSpeedTier(String motionSpeedTier) { this.motionSpeedTier = motionSpeedTier; }

    public String getCaptureWarning() { return captureWarning; }
    public void setCaptureWarning(String captureWarning) { this.captureWarning = captureWarning; }

    public String getRecommendedShutter() { return recommendedShutter; }
    public void setRecommendedShutter(String recommendedShutter) { this.recommendedShutter = recommendedShutter; }

    // Raw feature vector getters/setters
    public float[] getSiglipFeatures() { return siglipFeatures; }
    public void setSiglipFeatures(float[] siglipFeatures) { this.siglipFeatures = siglipFeatures; }

    public float[] getMotionFeatures() { return motionFeatures; }
    public void setMotionFeatures(float[] motionFeatures) { this.motionFeatures = motionFeatures; }

    // Phase 1b composition feature getters/setters
    public boolean isHasSymmetry() { return hasSymmetry; }
    public void setHasSymmetry(boolean hasSymmetry) { this.hasSymmetry = hasSymmetry; }

    public boolean isHasDiagonalLines() { return hasDiagonalLines; }
    public void setHasDiagonalLines(boolean hasDiagonalLines) { this.hasDiagonalLines = hasDiagonalLines; }

    public boolean isHasLeadingLines() { return hasLeadingLines; }
    public void setHasLeadingLines(boolean hasLeadingLines) { this.hasLeadingLines = hasLeadingLines; }

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

    public boolean isUseFrontCamera() { return useFrontCamera; }
    public void setUseFrontCamera(boolean useFrontCamera) { this.useFrontCamera = useFrontCamera; }
}
