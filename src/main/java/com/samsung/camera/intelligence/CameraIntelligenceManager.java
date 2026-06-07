package com.samsung.camera.intelligence;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.samsung.camera.intelligence.config.DeviceProfile;
import com.samsung.camera.intelligence.config.Settings;
import com.samsung.camera.intelligence.guidance.AnalysisBridge;
import com.samsung.camera.intelligence.guidance.CompositionGuide;
import com.samsung.camera.intelligence.guidance.FrameAnalysis;
import com.samsung.camera.intelligence.guidance.GuidanceFrame;
import com.samsung.camera.intelligence.guidance.MasterMatchAssetLoader;
import com.samsung.camera.intelligence.guidance.MasterMatchGuide;
import com.samsung.camera.intelligence.guidance.OverlayGenerator;
import com.samsung.camera.intelligence.inference.FrameAnalyzer;
import com.samsung.camera.intelligence.inference.InferenceEngine;
import com.samsung.camera.intelligence.inference.OnnxSplitInferenceEngine;
import com.samsung.camera.intelligence.inference.SubjectDetectorEngine;
import com.samsung.camera.intelligence.inference.CropAdvisorEngine;
import com.samsung.camera.intelligence.inference.TFLiteInferenceEngine;
import com.samsung.camera.intelligence.models.SceneAnalysisResult;
import com.samsung.camera.intelligence.recommendation.ToolRecommender;
import com.samsung.camera.intelligence.trigger.TriggerExternalHead;
import com.samsung.camera.intelligence.trigger.TriggerLogitContract;
import com.samsung.camera.intelligence.trigger.TriggerScorer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level facade for the Intelligent Camera System on Android.
 *
 * Integrates TFLite inference, scene analysis, tool recommendations, and
 * real-time guidance overlays into a single easy-to-use API.
 *
 * Typical usage:
 * <pre>
 *   CameraIntelligenceManager manager = new CameraIntelligenceManager(context);
 *   manager.initialize("model.tflite");
 *
 *   // Per-frame in camera preview callback:
 *   CameraIntelligenceManager.FrameResult result = manager.processFrame(bitmap);
 *   GuidanceFrame guidance = result.guidanceFrame;
 *   ToolRecommendation[] tools = result.tools;
 *
 *   // On camera switch / pause:
 *   manager.reset();
 *
 *   // On destroy:
 *   manager.release();
 * </pre>
 */
public class CameraIntelligenceManager {

    private static final String TAG = "CameraIntelligence";
        public static final String DEFAULT_MASTER_MATCH_EMBEDDINGS_ASSET =
            "master_match/master_match_embeddings.json";
        public static final String DEFAULT_MASTER_MATCH_EMBEDDINGS_BIN_ASSET =
            "master_match/master_match_embeddings.bin";
        public static final String DEFAULT_MASTER_MATCH_RECORDS_ASSET =
            "master_match/master_match_records.json";

    // Sub-systems
    private InferenceEngine engine;
    private FrameAnalyzer frameAnalyzer;
    private OverlayGenerator overlayGenerator;
    private ToolRecommender toolRecommender;
    // Phase 0.3 — optional sub-models, lazily loaded from assets if present.
    private SubjectDetectorEngine externalSubjectDetector;
    private CropAdvisorEngine externalCropAdvisor;
    private Context appContext;

    /**
     * Lifecycle lock guarding any operation that touches the native TFLite
     * Interpreter (engine), the FrameAnalyzer, or the optional sub-model
     * engines. Without this, an Apply-settings model reload on the UI thread
     * could race with processFrame() on the camera worker thread and free
     * the Interpreter mid-inference → SIGSEGV in native code. All public
     * entry points that read or write those fields must hold this monitor.
     */
    private final Object lifecycleLock = new Object();

    // Configuration
    private final Settings settings;
    private final DeviceProfile deviceProfile;
    private boolean initialized = false;

    // State
    private int frameCount = 0;
    private long lastRecommendTimeMs = 0;
    private SceneAnalysisResult lastSceneResult;
    private com.samsung.camera.intelligence.models.ToolRecommendationResult lastToolResult;
    private boolean useFrontCamera = false;

    // Throttle: how often to re-run tool recommendations (ms)
    private long recommendIntervalMs = 2000;

    /**
     * Create with default settings.
     */
    public CameraIntelligenceManager(Context context) {
        this.settings = new Settings();
        this.deviceProfile = DeviceProfile.detect(context);
        this.overlayGenerator = new OverlayGenerator();
        this.toolRecommender = new ToolRecommender();
        this.appContext = context == null ? null : context.getApplicationContext();
        attachMobileSamIfAvailable();
    }

    /**
     * Create with custom settings.
     */
    public CameraIntelligenceManager(Context context, Settings settings) {
        this.settings = settings;
        this.deviceProfile = DeviceProfile.detect(context);
        this.overlayGenerator = new OverlayGenerator();
        this.toolRecommender = new ToolRecommender();
        this.appContext = context == null ? null : context.getApplicationContext();
        attachMobileSamIfAvailable();
    }

    /**
     * Lazily attach a {@link com.samsung.camera.intelligence.inference.MobileSamRunner}
     * to the {@link ToolRecommender}'s {@link com.samsung.camera.intelligence.recommendation.DefectLocalizer}.
     * Silently no-ops if the SAM TFLite assets are absent (heuristic detectors
     * still produce bbox-based regions).
     */
    private void attachMobileSamIfAvailable() {
        if (appContext == null) return;
        try {
            com.samsung.camera.intelligence.inference.MobileSamRunner sam =
                    new com.samsung.camera.intelligence.inference.MobileSamRunner(appContext);
            // Probe load lazily so we don't pay the cost up-front; expose to the
            // localizer so it can decide per-defect whether to refine.
            toolRecommender.getDefectLocalizer().setMobileSam(sam);
        } catch (Throwable t) {
            Log.w(TAG, "MobileSAM attach skipped: " + t.getMessage());
        }
    }

    /**
     * Initialize the TFLite inference engine.
     *
     * @param context   Android context
     * @param modelPath Path to .tflite model file in assets
     * @param useGpu    Whether to enable GPU delegate
     */
    public void initialize(Context context, String modelPath, boolean useGpu) {
        String err = initializeWithError(context, modelPath, useGpu);
        if (err != null) {
            Log.e(TAG, "Failed to load model: " + modelPath + " — " + err);
        }
    }

    /**
     * Initialize with default settings (no GPU).
     */
    public void initialize(Context context, String modelPath) {
        initialize(context, modelPath, false);
    }

    /**
     * Initialize from asset path; returns null on success or error message on failure.
     */
    public String initializeWithError(Context context, String modelPath, boolean useGpu) {
        synchronized (lifecycleLock) {
            try {
                if (context != null) {
                    appContext = context.getApplicationContext();
                }
                engine = new TFLiteInferenceEngine(context, modelPath, useGpu);
                frameAnalyzer = createFrameAnalyzer(engine);
                applyCompositionGuidanceSettings();
                initialized = true;
                Log.i(TAG, "Initialized with model: " + modelPath + " (GPU=" + useGpu + ")");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load model: " + modelPath, e);
                initialized = false;
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * Initialize from asset paths for split V-JEPA encoder/heads models.
     */
    public String initializeSplitWithError(Context context, String encoderModelPath,
                                           String headsModelPath, boolean useGpu) {
        synchronized (lifecycleLock) {
            try {
                if (context != null) {
                    appContext = context.getApplicationContext();
                }
                engine = new TFLiteInferenceEngine(context, encoderModelPath, headsModelPath, useGpu);
                frameAnalyzer = createFrameAnalyzer(engine);
                applyCompositionGuidanceSettings();
                initialized = true;
                Log.i(TAG, "Initialized split models: encoder=" + encoderModelPath
                        + " heads=" + headsModelPath + " (GPU=" + useGpu + ")");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load split models: encoder=" + encoderModelPath
                        + " heads=" + headsModelPath, e);
                initialized = false;
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * Initialize from an absolute model file path on device storage.
     */
    public void initialize(String modelFilePath, boolean useGpu) {
        String err = initializeWithError(modelFilePath, useGpu);
        if (err != null) {
            Log.e(TAG, "Failed to load model file: " + modelFilePath + " — " + err);
        }
    }

    /**
     * Initialize from file path; returns null on success or error message on failure.
     */
    public String initializeWithError(String modelFilePath, boolean useGpu) {
        synchronized (lifecycleLock) {
            try {
                engine = new TFLiteInferenceEngine(modelFilePath, useGpu);
                frameAnalyzer = createFrameAnalyzer(engine);
                applyCompositionGuidanceSettings();
                initialized = true;
                Log.i(TAG, "Initialized with model file: " + modelFilePath + " (GPU=" + useGpu + ")");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load model file: " + modelFilePath, e);
                initialized = false;
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * Initialize from absolute file paths for split V-JEPA encoder/heads models.
     */
    public String initializeSplitWithError(String encoderModelFilePath,
                                           String headsModelFilePath,
                                           boolean useGpu) {
        synchronized (lifecycleLock) {
            try {
                engine = new TFLiteInferenceEngine(encoderModelFilePath, headsModelFilePath, useGpu);
                frameAnalyzer = createFrameAnalyzer(engine);
                applyCompositionGuidanceSettings();
                initialized = true;
                Log.i(TAG, "Initialized split model files: encoder=" + encoderModelFilePath
                        + " heads=" + headsModelFilePath + " (GPU=" + useGpu + ")");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load split model files: encoder=" + encoderModelFilePath
                        + " heads=" + headsModelFilePath, e);
                initialized = false;
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * Initialize from asset paths for the split FastViT Round-3 ONNX pair.
     */
    public String initializeOnnxSplitWithError(Context context, String encoderModelPath,
                                               String headsModelPath, boolean useGpu) {
        synchronized (lifecycleLock) {
            try {
                if (context != null) {
                    appContext = context.getApplicationContext();
                }
                engine = new OnnxSplitInferenceEngine(context, encoderModelPath, headsModelPath, useGpu);
                frameAnalyzer = createFrameAnalyzer(engine);
                applyCompositionGuidanceSettings();
                initialized = true;
                Log.i(TAG, "Initialized ONNX split models: encoder=" + encoderModelPath
                        + " heads=" + headsModelPath + " (GPU=" + useGpu + ")");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load ONNX split models: encoder=" + encoderModelPath
                        + " heads=" + headsModelPath, e);
                initialized = false;
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * Initialize from absolute file paths for the split FastViT Round-3 ONNX pair.
     */
    public String initializeOnnxSplitWithError(String encoderModelFilePath,
                                               String headsModelFilePath,
                                               boolean useGpu) {
        synchronized (lifecycleLock) {
            try {
                engine = new OnnxSplitInferenceEngine(encoderModelFilePath, headsModelFilePath, useGpu);
                frameAnalyzer = createFrameAnalyzer(engine);
                applyCompositionGuidanceSettings();
                initialized = true;
                Log.i(TAG, "Initialized ONNX split model files: encoder=" + encoderModelFilePath
                        + " heads=" + headsModelFilePath + " (GPU=" + useGpu + ")");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to load ONNX split model files: encoder=" + encoderModelFilePath
                        + " heads=" + headsModelFilePath, e);
                initialized = false;
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * Initialize from an absolute model file path on device storage (CPU mode).
     */
    public void initialize(String modelFilePath) {
        initialize(modelFilePath, false);
    }

    private FrameAnalyzer createFrameAnalyzer(InferenceEngine loadedEngine) {
        // The combined CLIP-B16 + SegNeXt model runs the B16 backbone on three
        // 224x224 inputs plus a 640x640 SegNeXt branch per call, so a single
        // inference is ~3-4x heavier than the legacy single-task models.
        // Skip more frames in that mode to keep the preview responsive.
        int frameSkip = loadedEngine.isCombinedSceneSegModel() ? 6 : 3;
        FrameAnalyzer fa = new FrameAnalyzer(loadedEngine, loadedEngine.getInputSize(), frameSkip, 0.6f, 0.5f);
        // Phase 0.3 — wire optional sub-models if their assets are present.
        ensureExternalEngines();
        if (externalSubjectDetector != null) {
            fa.setExternalSubjectDetector(externalSubjectDetector);
        }
        if (externalCropAdvisor != null) {
            fa.setExternalCropAdvisor(externalCropAdvisor);
        }
        Settings.CompositionGuidanceSettings cg = settings.getCompositionGuidance();
        fa.setUseExternalSubjectDetector(cg.isUseAiSubjectDetector());
        fa.setUseExternalCropAdvisor(cg.isUseAiCropAdvisor());
        return fa;
    }

    private void ensureExternalEngines() {
        if (appContext == null) return;
        if (externalSubjectDetector == null) {
            try {
                externalSubjectDetector = new SubjectDetectorEngine(appContext);
                Log.i(TAG, "External subject detector available=" + externalSubjectDetector.isAvailable());
            } catch (Throwable t) {
                Log.w(TAG, "Failed to construct SubjectDetectorEngine", t);
            }
        }
        if (externalCropAdvisor == null) {
            try {
                externalCropAdvisor = new CropAdvisorEngine(appContext);
                Log.i(TAG, "External crop advisor available=" + externalCropAdvisor.isAvailable());
            } catch (Throwable t) {
                Log.w(TAG, "Failed to construct CropAdvisorEngine", t);
            }
        }
    }

    /** Settings hook so the UI dialog can flip the external-model toggles live. */
    public void applyCompositionGuidanceSettings() {
        synchronized (lifecycleLock) {
            Settings.CompositionGuidanceSettings cg = settings.getCompositionGuidance();
            if (frameAnalyzer != null) {
                frameAnalyzer.setUseExternalSubjectDetector(cg.isUseAiSubjectDetector());
                frameAnalyzer.setUseExternalCropAdvisor(cg.isUseAiCropAdvisor());
            }
            if (overlayGenerator != null) {
                CompositionGuide guide = overlayGenerator.getCompositionGuide();
                guide.setShowGrid(cg.isShowGrid());
                guide.setEnableSubjectGuide(cg.isShowSubjectGuide());
                guide.setEnableDirectionArrow(cg.isShowDirectionArrow());
                guide.setEnableFramingTemplate(cg.isShowFramingTemplate());
                guide.setSubjectGuideMinConfidence(cg.getSubjectGuideMinConfidence());
                guide.setDirectionArrowScoreCeiling(cg.getDirectionArrowScoreCeiling());
                guide.setFillRatioMin(cg.getFillRatioMin());
                guide.setFillRatioMax(cg.getFillRatioMax());
                guide.getCropStabilizer().setSubjectMoveThreshold(cg.getCropReplanSubjectMove());
                guide.getCropStabilizer().setCropIouReplanThreshold(cg.getCropReplanIou());
                guide.setTargetLockEnabled(cg.isTargetLockEnabled());
                guide.setTargetLockAcquireMs(cg.getTargetAcquireMs());
                guide.setTargetLockMinStableFrames(cg.getTargetMinStableFrames());
                guide.setTargetLockCandidateIouThreshold(cg.getTargetCandidateIouThreshold());
                guide.setTargetLockAnchorTolerance(cg.getTargetAnchorTolerance());
                // Phase 8–10 (Plan A) — silhouette overlay kill-switch.
                overlayGenerator.setSilhouetteOverlayEnabled(cg.isUseSilhouetteOverlay());
                overlayGenerator.getSilhouetteController()
                        .setSceneFilterEnabled(cg.isSilhouetteSceneFilterEnabled());
            }
        }
    }

    public boolean isExternalSubjectDetectorAvailable() {
        return externalSubjectDetector != null && externalSubjectDetector.isAvailable();
    }

    public boolean isExternalCropAdvisorAvailable() {
        return externalCropAdvisor != null && externalCropAdvisor.isAvailable();
    }

    /**
     * Run a minimal inference to verify the model can actually execute.
     * Returns null on success, or error message on failure.
     * If inference fails, the manager is released and marked not-initialized.
     */
    public String probeInference() {
        synchronized (lifecycleLock) {
            if (!initialized || engine == null) {
                return "not initialized";
            }
            try {
                int sz = engine.getInputSize();
                java.nio.ByteBuffer scene = allocateFloatImageBuffer(sz);
                if (engine.isCombinedSceneSegModel()) {
                    java.nio.ByteBuffer raw = allocateFloatImageBuffer(sz);
                    java.nio.ByteBuffer motion = allocateFloatImageBuffer(sz);
                    int segSize = engine.getSegInputSize();
                    java.nio.ByteBuffer seg = allocateFloatImageBuffer(segSize);
                    engine.runCombined(scene, raw, motion, seg);
                } else {
                    engine.run(scene);
                }
                Log.i(TAG, "Probe inference OK");
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Probe inference failed", e);
                release();
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    private java.nio.ByteBuffer allocateFloatImageBuffer(int size) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(1 * size * size * 3 * 4);
        buffer.order(java.nio.ByteOrder.nativeOrder());
        return buffer;
    }

    /**
     * Configure backbone-specific input normalization.
     * Must be called after {@link #initialize} while the manager is initialized.
     *
     * @param backbone Backbone name (e.g. "mobilenetv3", "clip_vit_b16")
     */
    public void setBackboneNormalization(String backbone) {
        if (frameAnalyzer != null) {
            frameAnalyzer.setBackboneNormMode(
                    com.samsung.camera.intelligence.inference.FrameAnalyzer.normModeForBackbone(backbone));
            configureTriggerEngines(backbone);
        }
    }

    private void configureTriggerEngines(String backbone) {
        if (appContext == null || frameAnalyzer == null) {
            return;
        }
        try {
            TriggerScorer scorer = TriggerScorer.fromAssetForBackbone(appContext, backbone);
            TriggerExternalHead head = null;
            boolean disableL1 = backbone != null
                    && backbone.toLowerCase().contains("fastvit_sa36");
            if (!disableL1) {
                head = TriggerExternalHead.fromAssetForBackbone(appContext, backbone);
            } else {
                Log.i(TAG, "Trigger L1 disabled for FastViT Round-3 until a dedicated Android asset is bundled");
            }
            if (head != null && !head.isNumericallyHealthyForPreview()) {
                Log.w(TAG, "Trigger L1 disabled for backbone=" + backbone
                    + " due unstable head params maxW=" + head.maxAbsWeight()
                    + " maxB=" + head.maxAbsBias());
                head = null;
            }
            frameAnalyzer.setTriggerEngines(scorer, head);
            Log.i(TAG, "Trigger engines loaded for backbone=" + backbone
                    + " l1=" + (head != null ? "enabled" : "disabled"));
        } catch (Exception e) {
            Log.w(TAG, "Trigger engines unavailable for backbone=" + backbone + ": " + e.getMessage());
            frameAnalyzer.setTriggerEngines(null, null);
        }
    }

    public String attachTriggerSplitWithError(Context context,
                                              String triggerHeadsModelPath,
                                              String contractAssetPath) {
        synchronized (lifecycleLock) {
            try {
                if (context != null) {
                    appContext = context.getApplicationContext();
                }
                if (engine == null || frameAnalyzer == null || appContext == null) {
                    return "Split trigger attach requires initialized engine + frameAnalyzer + appContext";
                }
                if (!(engine instanceof TFLiteInferenceEngine)) {
                    return "Direct trigger attach is only supported for TFLite split engines";
                }
                ((TFLiteInferenceEngine) engine).attachTriggerHeadsAsset(appContext, triggerHeadsModelPath);
                TriggerLogitContract contract = TriggerLogitContract.fromAsset(appContext, contractAssetPath);
                frameAnalyzer.setTriggerLogitContract(contract);
                Log.i(TAG, "Attached direct trigger heads asset: " + triggerHeadsModelPath
                        + " bestEpoch=" + contract.bestEpoch()
                        + " macroF1=" + contract.macroF1Calibrated());
                return null;
            } catch (Exception e) {
                Log.w(TAG, "Failed to attach direct trigger heads asset: " + triggerHeadsModelPath, e);
                if (engine instanceof TFLiteInferenceEngine) {
                    ((TFLiteInferenceEngine) engine).detachTriggerHeads();
                }
                if (frameAnalyzer != null) {
                    frameAnalyzer.setTriggerLogitContract(null);
                }
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    public String attachTriggerSplitWithError(String triggerHeadsModelFilePath,
                                              String contractFilePath) {
        synchronized (lifecycleLock) {
            try {
                if (engine == null || frameAnalyzer == null) {
                    return "Split trigger attach requires initialized engine + frameAnalyzer";
                }
                if (!(engine instanceof TFLiteInferenceEngine)) {
                    return "Direct trigger attach is only supported for TFLite split engines";
                }
                ((TFLiteInferenceEngine) engine).attachTriggerHeadsFile(triggerHeadsModelFilePath);
                TriggerLogitContract contract = TriggerLogitContract.fromFile(contractFilePath);
                frameAnalyzer.setTriggerLogitContract(contract);
                Log.i(TAG, "Attached direct trigger heads file: " + triggerHeadsModelFilePath
                        + " bestEpoch=" + contract.bestEpoch()
                        + " macroF1=" + contract.macroF1Calibrated());
                return null;
            } catch (Exception e) {
                Log.w(TAG, "Failed to attach direct trigger heads file: " + triggerHeadsModelFilePath, e);
                if (engine instanceof TFLiteInferenceEngine) {
                    ((TFLiteInferenceEngine) engine).detachTriggerHeads();
                }
                if (frameAnalyzer != null) {
                    frameAnalyzer.setTriggerLogitContract(null);
                }
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    /**
     * Process a single camera preview frame.
     *
     * Returns a {@link FrameResult} containing:
     *   - {@link GuidanceFrame} with real-time overlay directives
     *   - {@link com.samsung.camera.intelligence.models.ToolRecommendationResult}
     *     (updated periodically, not every frame)
     *
     * @param bitmap Camera preview frame
     * @return Combined result, or null if not initialized
     */
    public FrameResult processFrame(Bitmap bitmap) {
        return processFrame(bitmap, false);
    }

    /**
     * Process a frame and optionally request the combined model's expensive
     * 640x640 segmentation output. Live preview should pass false; Gallery or
     * defect-mask requests can pass true on demand.
     */
    public FrameResult processFrame(Bitmap bitmap, boolean includeSegmentation) {
        // Snapshot lifecycle state under the monitor so a parallel release()
        // (e.g. settings dialog "Apply" reloading the model) cannot null the
        // engine pointer while we're still mid-inference. We hold the lock
        // for the inference itself — release() will simply wait for us.
        synchronized (lifecycleLock) {
            if (!initialized || frameAnalyzer == null) {
                Log.w(TAG, "processFrame called before initialize()");
                return null;
            }

            frameCount++;
            if (frameCount % 60 == 1) {
                Log.w(TAG, "[DIAG] processFrame alive frameCount=" + frameCount);
            }

            // Step 1: Run TFLite inference → FrameAnalysis
            FrameAnalysis frameAnalysis = frameAnalyzer.analyze(bitmap, includeSegmentation);
            if (frameAnalysis == null) {
                return null;
            }

            // Step 2: Generate guidance overlays (every frame)
            GuidanceFrame guidanceFrame = overlayGenerator.generate(frameAnalysis, bitmap);

            // Step 3: Run tool recommendations (throttled)
            long now = System.currentTimeMillis();
            if (now - lastRecommendTimeMs >= recommendIntervalMs) {
                lastRecommendTimeMs = now;
                try {
                    // Convert FrameAnalysis → SceneAnalysisResult for recommender
                    SceneAnalysisResult sceneResult = frameAnalysisToSceneResult(frameAnalysis);
                    sceneResult.setUseFrontCamera(useFrontCamera);
                    lastSceneResult = sceneResult;
                    lastToolResult = toolRecommender.recommend(sceneResult, false, false, true, bitmap);
                    Log.i(TAG, "Recommendation scene=" + sceneResult.getSceneType().getValue()
                            + " subject=" + sceneResult.getMainSubject().getValue()
                            + " suggestedMode=" + extractRecommendedMode(lastToolResult)
                            + " tools=" + summarizeTools(lastToolResult));
                } catch (Exception e) {
                    Log.w(TAG, "Tool recommendation failed", e);
                }
            }

            return new FrameResult(guidanceFrame, lastToolResult, frameAnalysis);
        }
    }

    /**
     * Process a pre-decoded FrameAnalysis (e.g., from a bridge or test).
     */
    public FrameResult processAnalysis(FrameAnalysis frameAnalysis) {
        synchronized (lifecycleLock) {
            GuidanceFrame guidanceFrame = overlayGenerator.generate(frameAnalysis);

            long now = System.currentTimeMillis();
            if (now - lastRecommendTimeMs >= recommendIntervalMs) {
                lastRecommendTimeMs = now;
                try {
                    SceneAnalysisResult sceneResult = frameAnalysisToSceneResult(frameAnalysis);
                    sceneResult.setUseFrontCamera(useFrontCamera);
                    lastSceneResult = sceneResult;
                    lastToolResult = toolRecommender.recommend(sceneResult);
                } catch (Exception e) {
                    Log.w(TAG, "Tool recommendation failed", e);
                }
            }

            return new FrameResult(guidanceFrame, lastToolResult, frameAnalysis);
        }
    }

    /**
     * Reset temporal state (e.g. on camera switch, mode change).
     */
    public void reset() {
        synchronized (lifecycleLock) {
            overlayGenerator.reset();
            if (frameAnalyzer != null) {
                frameAnalyzer.reset();
            }
            frameCount = 0;
            lastRecommendTimeMs = 0;
            lastSceneResult = null;
            lastToolResult = null;
        }
    }

    /**
     * Release all resources. Safe to call from any thread — will block until
     * any in-flight processFrame() completes so the native Interpreter is
     * never freed underneath an active inference call.
     */
    public void release() {
        synchronized (lifecycleLock) {
            // Mark not-initialized FIRST so any subsequent processFrame call
            // that grabs the lock right after we release it returns early.
            initialized = false;
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing engine", e);
                }
                engine = null;
            }
            if (externalSubjectDetector != null) {
                try { externalSubjectDetector.close(); } catch (Throwable ignore) {}
                externalSubjectDetector = null;
            }
            if (externalCropAdvisor != null) {
                try { externalCropAdvisor.close(); } catch (Throwable ignore) {}
                externalCropAdvisor = null;
            }
            frameAnalyzer = null;
        }
    }

    private static String extractRecommendedMode(com.samsung.camera.intelligence.models.ToolRecommendationResult result) {
        if (result == null || result.getTools() == null) {
            return "none";
        }
        for (com.samsung.camera.intelligence.models.ToolRecommendation tool : result.getTools()) {
            if (tool != null && "Camera_ChangeMode".equals(tool.getToolName()) && tool.getParameters() != null) {
                Object modeName = tool.getParameters().get("ModeName");
                if (modeName != null) {
                    return String.valueOf(modeName);
                }
            }
        }
        return "none";
    }

    private static String summarizeTools(com.samsung.camera.intelligence.models.ToolRecommendationResult result) {
        if (result == null || result.getTools() == null) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        for (com.samsung.camera.intelligence.models.ToolRecommendation tool : result.getTools()) {
            if (tool != null && tool.getToolName() != null) {
                names.add(tool.getToolName());
            }
            if (names.size() >= 6) {
                break;
            }
        }
        return names.toString();
    }

    // ---- Getters ----

    public boolean isInitialized() { return initialized; }
    public Settings getSettings() { return settings; }
    public DeviceProfile getDeviceProfile() { return deviceProfile; }
    public OverlayGenerator getOverlayGenerator() { return overlayGenerator; }
    public ToolRecommender getToolRecommender() { return toolRecommender; }
    public int getFrameCount() { return frameCount; }
    public long getRecommendIntervalMs() { return recommendIntervalMs; }
    public void setRecommendIntervalMs(long ms) { this.recommendIntervalMs = ms; }
    public boolean isUseFrontCamera() { return useFrontCamera; }
    public void setUseFrontCamera(boolean useFrontCamera) { this.useFrontCamera = useFrontCamera; }

    /**
     * Enable MasterMatch and load its index from Android assets.
     *
     * @param context Android context
     * @param embeddingsAssetPath Asset path to embeddings JSON
     * @param recordsAssetPath Asset path to records JSON
     * @return true if dataset loaded successfully
     */
    public boolean enableMasterMatchFromAssets(
            Context context,
            String embeddingsAssetPath,
            String recordsAssetPath) {
        Log.w(TAG, "[DIAG] enableMasterMatchFromAssets start emb=" + embeddingsAssetPath
                + " rec=" + recordsAssetPath);
        overlayGenerator.setEnableMasterMatch(true);
        MasterMatchGuide guide = overlayGenerator.getMasterMatchGuide();
        if (guide == null) {
            Log.e(TAG, "[DIAG] Failed to enable MasterMatch guide");
            return false;
        }
        boolean loaded = MasterMatchAssetLoader.loadFromAssets(
                context,
                guide,
                embeddingsAssetPath,
                recordsAssetPath
        );
        if (!loaded) {
            overlayGenerator.setEnableMasterMatch(false);
        }
        Log.w(TAG, "[DIAG] enableMasterMatchFromAssets result=" + loaded);
        return loaded;
    }

    /**
     * Enable MasterMatch from default asset locations.
     */
    public boolean enableMasterMatchFromAssets(Context context) {
        return enableMasterMatchFromAssets(
                context,
                DEFAULT_MASTER_MATCH_EMBEDDINGS_ASSET,
                DEFAULT_MASTER_MATCH_RECORDS_ASSET
        );
    }

    /**
     * Disable MasterMatch guide.
     */
    public void disableMasterMatch() {
        overlayGenerator.setEnableMasterMatch(false);
    }

    // ---- Internal conversion ----

    /**
     * Convert a FrameAnalysis (string-based) back to a SceneAnalysisResult (enum-based)
     * for the tool recommender.
     */
    private static SceneAnalysisResult frameAnalysisToSceneResult(FrameAnalysis fa) {
        SceneAnalysisResult scene = new SceneAnalysisResult();

        scene.setSceneType(
                com.samsung.camera.intelligence.models.SceneType.fromValue(fa.getSceneType()));
        scene.setSceneTypeConfidence(fa.getSceneConfidence());
        scene.setLightingCondition(
                com.samsung.camera.intelligence.models.LightingCondition.fromValue(fa.getLightingCondition()));
        scene.setLightingConfidence(fa.getLightingConfidence());
        scene.setMotionType(
                com.samsung.camera.intelligence.models.MotionType.fromValue(fa.getMotionType()));
        scene.setMotionConfidence(fa.getMotionConfidence());
        scene.setMainSubject(
                com.samsung.camera.intelligence.models.MainSubject.fromValue(fa.getMainSubject()));
        scene.setSubjectConfidence(fa.getSubjectConfidence());

        scene.setContrastValue(fa.getContrastValue());
        scene.setSharpnessValue(fa.getSharpnessValue());
        scene.setNoiseLevel(fa.getNoiseLevel());

        scene.setCompositionScore(fa.getCompositionScore());
        scene.setNeedsCompositionEdit(fa.isNeedsCompositionEdit());
        scene.setCompositionIssues(fa.getCompositionIssues());
        scene.setTilted(fa.isTilted());
        scene.setTiltAngle(fa.getTiltAngle());

        // Subject location: prefer model-decoded bbox, fall back to center point
        if (fa.getSubjectBbox() != null) {
            scene.setSubjectBoundingBox(fa.getSubjectBbox());
        } else if (fa.getSubjectCenterX() != null && fa.getSubjectCenterY() != null) {
            scene.setSubjectBoundingBox(new float[]{
                    fa.getSubjectCenterX(), fa.getSubjectCenterY(), 0f, 0f
            });
        }

        // Suggested crop: from model output
        if (fa.getSuggestedCrop() != null) {
            scene.setSuggestedCrop(fa.getSuggestedCrop());
        }

        scene.setHasFace(fa.isHasFace());
        scene.setFaceCount(fa.getFaceCount());
        scene.setHasText(fa.isHasText());
        scene.setHasShadow(fa.isHasShadow());
        scene.setHasReflection(fa.isHasReflection());
        scene.setHasBackgroundPeople(fa.isHasBackgroundPeople());
        scene.setHasFlare(fa.isHasFlare());
        scene.setHasMoire(fa.isHasMoire());

        scene.setMotionSpeed(fa.getFlowMagnitude());
        scene.setEstimatedLux(fa.getEstimatedLux());
        scene.setFeatureEmbedding(fa.getFeatureEmbedding());

        scene.setBrightnessValue(fa.getBrightnessValue());
        scene.setBlurLevel(fa.getBlurLevel());

        return scene;
    }

    // ---- Result container ----

    /**
     * Combined result of a single frame processing pass.
     */
    public static class FrameResult {
        public final GuidanceFrame guidanceFrame;
        public final com.samsung.camera.intelligence.models.ToolRecommendationResult toolResult;
        public final FrameAnalysis frameAnalysis;

        public FrameResult(
                GuidanceFrame guidanceFrame,
                com.samsung.camera.intelligence.models.ToolRecommendationResult toolResult,
                FrameAnalysis frameAnalysis) {
            this.guidanceFrame = guidanceFrame;
            this.toolResult = toolResult;
            this.frameAnalysis = frameAnalysis;
        }
    }
}
