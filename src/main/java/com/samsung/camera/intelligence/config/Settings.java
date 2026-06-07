package com.samsung.camera.intelligence.config;

import java.util.Arrays;
import java.util.List;

/**
 * Global configuration container for Android runtime.
 *
 * Mirrors the key defaults from Python config/settings.py that are used
 * by the Android Java port.
 */
public class Settings {

    private final ModelSettings model;
    private final CameraSettings camera;
    private final MasterDatabaseSettings masterDatabase;
    private final CompositionGuidanceSettings compositionGuidance;

    public Settings() {
        this.model = new ModelSettings();
        this.camera = new CameraSettings();
        this.masterDatabase = new MasterDatabaseSettings();
        this.compositionGuidance = new CompositionGuidanceSettings();
    }

    public ModelSettings getModel() {
        return model;
    }

    public CameraSettings getCamera() {
        return camera;
    }

    public MasterDatabaseSettings getMasterDatabase() {
        return masterDatabase;
    }

    public CompositionGuidanceSettings getCompositionGuidance() {
        return compositionGuidance;
    }

    /**
     * Convenience accessor used by CameraIntelligenceManager.
     */
    public int getModelInputSize() {
        return model.getImageSize();
    }

    /**
     * Convenience accessor used by integration logic.
     */
    public List<String> getSupportedResolutions() {
        return camera.getSupportedResolutions();
    }

    public static class ModelSettings {
        private String siglipModel = "openai/clip-vit-base-patch32";
        private int imageSize = 224;
        private int hiddenDim = 256;

        public String getSiglipModel() {
            return siglipModel;
        }

        public void setSiglipModel(String siglipModel) {
            this.siglipModel = siglipModel;
        }

        public int getImageSize() {
            return imageSize;
        }

        public void setImageSize(int imageSize) {
            this.imageSize = imageSize;
        }

        public int getHiddenDim() {
            return hiddenDim;
        }

        public void setHiddenDim(int hiddenDim) {
            this.hiddenDim = hiddenDim;
        }
    }

    public static class CameraSettings {
        private List<String> supportedResolutions =
                Arrays.asList("12MP", "50MP", "108MP", "200MP");

        public List<String> getSupportedResolutions() {
            return supportedResolutions;
        }

        public void setSupportedResolutions(List<String> supportedResolutions) {
            this.supportedResolutions = supportedResolutions;
        }
    }

    public static class MasterDatabaseSettings {
        private boolean enabled = false;
        private String indexPath = "data/master_photos/master_index.faiss";
        private String metadataPath = "data/master_photos/master_index.json";
        private float minSimilarity = 0.65f;
        private int topK = 3;
        private float cooldownS = 5.0f;
        private boolean filterByScene = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIndexPath() {
            return indexPath;
        }

        public void setIndexPath(String indexPath) {
            this.indexPath = indexPath;
        }

        public String getMetadataPath() {
            return metadataPath;
        }

        public void setMetadataPath(String metadataPath) {
            this.metadataPath = metadataPath;
        }

        public float getMinSimilarity() {
            return minSimilarity;
        }

        public void setMinSimilarity(float minSimilarity) {
            this.minSimilarity = minSimilarity;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public float getCooldownS() {
            return cooldownS;
        }

        public void setCooldownS(float cooldownS) {
            this.cooldownS = cooldownS;
        }

        public boolean isFilterByScene() {
            return filterByScene;
        }

        public void setFilterByScene(boolean filterByScene) {
            this.filterByScene = filterByScene;
        }
    }

    /**
     * Phase C.3 — composition-guidance UI feature toggles + thresholds.
     *
     * The master switch ({@link #enabled}) mirrors the SharedPreference
     * {@code show_guidance_overlay}. When the master switch is OFF the
     * GuidanceOverlayView visibility is GONE and none of the per-feature
     * flags below have any effect.
     *
     * Per-feature flags allow advanced users to disable individual canvas
     * widgets (grid, subject guide, fill-ratio bar, color chips, …) while
     * keeping others on. Defaults are tuned for the on-device CLIP-B16-220K
     * backbone evaluated in Mar 2026.
     */
    public static class CompositionGuidanceSettings {
        private boolean enabled = false;
        private boolean showGrid = false;
        // Phase 1.2 — old SubjectGuide / DirectionArrow are now off by default;
        // the Framing Template overlay supersedes them. Pro users can flip
        // these back on via Settings to use the analytical view.
        private boolean showSubjectGuide = false;
        private boolean showDirectionArrow = false;
        private boolean showFillRatioGauge = true;
        private boolean showColorChips = true;
        private boolean showCompositionScore = true;
        private boolean showCompositionTip = true;
        private boolean showSymmetryGuide = true;
        // Phase 3 — new framing-template UX (target rect + subject anchor +
        // edge nudge bars + match ring + sticky lock + haptic). Default ON.
        private boolean showFramingTemplate = true;
        // Phase 4 — auto-apply the AI-suggested crop on capture when locked.
        private boolean snapToTemplateOnCapture = true;
        // Phase 4 — also save the uncropped full frame next to the cropped one.
        private boolean saveUncroppedAlongside = false;
        // Phase 0 — feed the framing-template stack from the dedicated
        // saliency / aesthetic-cropping sub-models when their tflite assets
        // are present. Falls back to the main multi-task heads otherwise.
        private boolean useAiSubjectDetector = true;
        private boolean useAiCropAdvisor = true;
        // Phase 6 — Aim & Capture redesign flags.
        // Show the analytical "Pro HUD" widgets (legacy edge bars, side ring,
        // fill-ratio bar, color chips, score badge, ghost ellipse, dashed
        // target frame). Default OFF for the consumer-friendly redesign.
        private boolean proHudVisible = false;
        // Auto-zoom the preview to the recommended crop on framing lock.
        private boolean autoZoomOnLock = true;
        // Draw external sub-model bbox/center/crop only when explicitly
        // enabled for diagnostics.
        private boolean showExternalDetectorOverlay = false;
        private boolean showExternalCropAdvisorOverlay = false;

        // ---- Composition v2 (plan-compositionGuidanceV2) ----
        // Bottom technique chip + horizon level dashed guide. Both default ON.
        private boolean showAdviceChips = true;
        private boolean showHorizonLevelGuide = true;
        // When true, render the legacy white target crop frame in consumer
        // mode too. Default OFF — the frame is now reserved for Pro HUD or
        // a brief auto-zoom flash.
        private boolean showTargetCropFrameInConsumer = false;
        // Per-rule cool-down for CompositionAdvisor.
        private long adviceCoolDownMs = 2500L;

        // ---- Composition v2 — Phase 8–10 Silhouette UI (Plan A) ----
        // 7-day kill-switch flag for the new silhouette / ghost-frame outline.
        // Default OFF until the experiment graduates.
        private boolean useSilhouetteOverlay = false;
        // When false, silhouette can arm in any scene type. When true,
        // restrict to portrait / landscape / architecture / cityscape
        // (where reframing help is most valuable).
        private boolean silhouetteSceneFilterEnabled = true;

        // Confidence / score thresholds — kept in sync with the JSON file at
        // assets/composition_thresholds.json (regenerated by
        // scripts/calibrate_composition_thresholds.py).
        private float subjectGuideMinConfidence = 0.5f;
        private float directionArrowScoreCeiling = 0.55f;
        private float symmetryThreshold = 0.6f;
        private float leadingLinesThreshold = 0.55f;
        private float diagonalLinesThreshold = 0.55f;
        private float bwSaturationThreshold = 0.25f;
        private float bwContrastThreshold = 0.6f;
        private float fillRatioMin = 0.15f;
        private float fillRatioMax = 0.85f;

        // Phase 3/5 framing-template thresholds.
        private float templateShowScoreCeiling = 0.6f;
        private float templateLockMatchScore = 0.85f;
        private float templateUnlockMatchScore = 0.70f;
        private float cropReplanSubjectMove = 0.10f;
        private float cropReplanIou = 0.55f;
        private float matchRingEmaAlpha = 0.2f;
        private long matchRingMinLockMs = 1000L;
        private boolean targetLockEnabled = true;
        private long targetAcquireMs = 1200L;
        private int targetMinStableFrames = 6;
        private float targetCandidateIouThreshold = 0.80f;
        private float targetAnchorTolerance = 0.04f;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isShowGrid() { return showGrid; }
        public void setShowGrid(boolean v) { this.showGrid = v; }
        public boolean isShowSubjectGuide() { return showSubjectGuide; }
        public void setShowSubjectGuide(boolean v) { this.showSubjectGuide = v; }
        public boolean isShowDirectionArrow() { return showDirectionArrow; }
        public void setShowDirectionArrow(boolean v) { this.showDirectionArrow = v; }
        public boolean isShowFillRatioGauge() { return showFillRatioGauge; }
        public void setShowFillRatioGauge(boolean v) { this.showFillRatioGauge = v; }
        public boolean isShowColorChips() { return showColorChips; }
        public void setShowColorChips(boolean v) { this.showColorChips = v; }
        public boolean isShowCompositionScore() { return showCompositionScore; }
        public void setShowCompositionScore(boolean v) { this.showCompositionScore = v; }
        public boolean isShowCompositionTip() { return showCompositionTip; }
        public void setShowCompositionTip(boolean v) { this.showCompositionTip = v; }
        public boolean isShowSymmetryGuide() { return showSymmetryGuide; }
        public void setShowSymmetryGuide(boolean v) { this.showSymmetryGuide = v; }
        public float getSubjectGuideMinConfidence() { return subjectGuideMinConfidence; }
        public void setSubjectGuideMinConfidence(float v) { this.subjectGuideMinConfidence = v; }
        public float getDirectionArrowScoreCeiling() { return directionArrowScoreCeiling; }
        public void setDirectionArrowScoreCeiling(float v) { this.directionArrowScoreCeiling = v; }
        public float getSymmetryThreshold() { return symmetryThreshold; }
        public void setSymmetryThreshold(float v) { this.symmetryThreshold = v; }
        public float getLeadingLinesThreshold() { return leadingLinesThreshold; }
        public void setLeadingLinesThreshold(float v) { this.leadingLinesThreshold = v; }
        public float getDiagonalLinesThreshold() { return diagonalLinesThreshold; }
        public void setDiagonalLinesThreshold(float v) { this.diagonalLinesThreshold = v; }
        public float getBwSaturationThreshold() { return bwSaturationThreshold; }
        public void setBwSaturationThreshold(float v) { this.bwSaturationThreshold = v; }
        public float getBwContrastThreshold() { return bwContrastThreshold; }
        public void setBwContrastThreshold(float v) { this.bwContrastThreshold = v; }
        public float getFillRatioMin() { return fillRatioMin; }
        public void setFillRatioMin(float v) { this.fillRatioMin = v; }
        public float getFillRatioMax() { return fillRatioMax; }
        public void setFillRatioMax(float v) { this.fillRatioMax = v; }

        public boolean isShowFramingTemplate() { return showFramingTemplate; }
        public void setShowFramingTemplate(boolean v) { this.showFramingTemplate = v; }
        public boolean isSnapToTemplateOnCapture() { return snapToTemplateOnCapture; }
        public void setSnapToTemplateOnCapture(boolean v) { this.snapToTemplateOnCapture = v; }
        public boolean isSaveUncroppedAlongside() { return saveUncroppedAlongside; }
        public void setSaveUncroppedAlongside(boolean v) { this.saveUncroppedAlongside = v; }
        public boolean isUseAiSubjectDetector() { return useAiSubjectDetector; }
        public void setUseAiSubjectDetector(boolean v) { this.useAiSubjectDetector = v; }
        public boolean isUseAiCropAdvisor() { return useAiCropAdvisor; }
        public void setUseAiCropAdvisor(boolean v) { this.useAiCropAdvisor = v; }
        public float getTemplateShowScoreCeiling() { return templateShowScoreCeiling; }
        public void setTemplateShowScoreCeiling(float v) { this.templateShowScoreCeiling = v; }
        public float getTemplateLockMatchScore() { return templateLockMatchScore; }
        public void setTemplateLockMatchScore(float v) { this.templateLockMatchScore = v; }
        public float getTemplateUnlockMatchScore() { return templateUnlockMatchScore; }
        public void setTemplateUnlockMatchScore(float v) { this.templateUnlockMatchScore = v; }
        public float getCropReplanSubjectMove() { return cropReplanSubjectMove; }
        public void setCropReplanSubjectMove(float v) { this.cropReplanSubjectMove = v; }
        public float getCropReplanIou() { return cropReplanIou; }
        public void setCropReplanIou(float v) { this.cropReplanIou = v; }
        public float getMatchRingEmaAlpha() { return matchRingEmaAlpha; }
        public void setMatchRingEmaAlpha(float v) { this.matchRingEmaAlpha = v; }
        public long getMatchRingMinLockMs() { return matchRingMinLockMs; }
        public void setMatchRingMinLockMs(long v) { this.matchRingMinLockMs = v; }
        public boolean isTargetLockEnabled() { return targetLockEnabled; }
        public void setTargetLockEnabled(boolean v) { this.targetLockEnabled = v; }
        public long getTargetAcquireMs() { return targetAcquireMs; }
        public void setTargetAcquireMs(long v) { this.targetAcquireMs = v; }
        public int getTargetMinStableFrames() { return targetMinStableFrames; }
        public void setTargetMinStableFrames(int v) { this.targetMinStableFrames = v; }
        public float getTargetCandidateIouThreshold() { return targetCandidateIouThreshold; }
        public void setTargetCandidateIouThreshold(float v) { this.targetCandidateIouThreshold = v; }
        public float getTargetAnchorTolerance() { return targetAnchorTolerance; }
        public void setTargetAnchorTolerance(float v) { this.targetAnchorTolerance = v; }
        // Phase 6 — Aim & Capture redesign flags.
        public boolean isProHudVisible() { return proHudVisible; }
        public void setProHudVisible(boolean v) { this.proHudVisible = v; }
        public boolean isAutoZoomOnLock() { return autoZoomOnLock; }
        public void setAutoZoomOnLock(boolean v) { this.autoZoomOnLock = v; }
        public boolean isShowExternalDetectorOverlay() { return showExternalDetectorOverlay; }
        public void setShowExternalDetectorOverlay(boolean v) { this.showExternalDetectorOverlay = v; }
        public boolean isShowExternalCropAdvisorOverlay() { return showExternalCropAdvisorOverlay; }
        public void setShowExternalCropAdvisorOverlay(boolean v) { this.showExternalCropAdvisorOverlay = v; }

        // ---- Composition v2 ----
        public boolean isShowAdviceChips() { return showAdviceChips; }
        public void setShowAdviceChips(boolean v) { this.showAdviceChips = v; }
        public boolean isShowHorizonLevelGuide() { return showHorizonLevelGuide; }
        public void setShowHorizonLevelGuide(boolean v) { this.showHorizonLevelGuide = v; }
        public boolean isShowTargetCropFrameInConsumer() { return showTargetCropFrameInConsumer; }
        public void setShowTargetCropFrameInConsumer(boolean v) { this.showTargetCropFrameInConsumer = v; }
        public long getAdviceCoolDownMs() { return adviceCoolDownMs; }
        public void setAdviceCoolDownMs(long v) { this.adviceCoolDownMs = Math.max(500L, v); }

        // ---- Silhouette UI (Plan A) ----
        public boolean isUseSilhouetteOverlay() { return useSilhouetteOverlay; }
        public void setUseSilhouetteOverlay(boolean v) { this.useSilhouetteOverlay = v; }
        public boolean isSilhouetteSceneFilterEnabled() { return silhouetteSceneFilterEnabled; }
        public void setSilhouetteSceneFilterEnabled(boolean v) { this.silhouetteSceneFilterEnabled = v; }
    }
}
