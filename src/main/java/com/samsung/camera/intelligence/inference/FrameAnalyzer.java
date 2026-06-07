package com.samsung.camera.intelligence.inference;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.samsung.camera.intelligence.guidance.FrameAnalysis;
import com.samsung.camera.intelligence.trigger.BackgroundBokehStats;
import com.samsung.camera.intelligence.trigger.LensBlockedCornerStats;
import com.samsung.camera.intelligence.trigger.TriggerArbiter;
import com.samsung.camera.intelligence.trigger.TriggerExternalHead;
import com.samsung.camera.intelligence.trigger.TriggerHysteresis;
import com.samsung.camera.intelligence.trigger.TriggerLogitContract;
import com.samsung.camera.intelligence.trigger.TriggerNames;
import com.samsung.camera.intelligence.trigger.TriggerScorer;
import com.samsung.camera.intelligence.trigger.TriggerSignals;
import com.samsung.camera.intelligence.trigger.TextRegionStats;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes raw TFLite model outputs into FrameAnalysis.
 * Ported from Python FrameAnalyzer in guidance/frame_analyzer.py.
 *
 * Responsibilities:
 *   1. Accept a raw camera frame (Android Bitmap).
 *   2. Resize / normalize to model input size via ImagePreprocessor.
 *   3. Run model inference via the configured runtime engine.
 *   4. Decode classification logits, regression values, and binary flags
 *      into a FrameAnalysis.
 *   5. Run a lightweight finger-obstruction heuristic on the raw frame.
 *   6. Frame-skip mechanism (every N-th frame) to hit 10-15 FPS budget.
 */
public class FrameAnalyzer {

    private static final String TAG = "FrameAnalyzer";

    // Label maps — must match training order
    public static final String[] SCENE_LABELS = {
        "portrait", "group_portrait", "selfie", "landscape", "cityscape",
        "architecture", "food", "product", "document", "pet",
        "wildlife", "macro", "flower", "night", "sunset_sunrise",
        "night_sky", "night_portrait", "night_cityscape", "backlit_portrait",
        "fast_moving", "slow_moving", "repeating_motion", "sports", "vehicle",
        "waterfall", "panoramic", "general"
    };

    public static final String[] LIGHTING_LABELS = {
        "very_low_light", "low_light", "indoor", "cloudy", "normal",
        "bright", "very_bright", "backlit", "mixed", "artificial",
        "golden_hour", "blue_hour"
    };

    public static final String[] MOTION_LABELS = {
        "static", "slow", "normal", "fast", "very_fast",
        "repeating", "chaotic"
    };

    public static final String[] SUBJECT_LABELS = {
        "human_single", "human_group", "human_face", "human_full_body",
        "animal_pet", "animal_wildlife", "animal_bird",
        "food_dish", "food_ingredient", "food_drink",
        "landscape_nature", "landscape_urban",
        "architecture_exterior", "architecture_interior",
        "object_product", "object_vehicle", "object_document",
        "plant_flower", "plant_tree", "sky_day", "sky_night",
        "water_body", "none"
    };

    public static final String[] COMPOSITION_ISSUE_LABELS = {
        "subject_off_center", "poor_rule_of_thirds", "unbalanced",
        "distracting_elements", "too_much_headroom", "insufficient_headroom",
        "poor_framing", "horizon_not_level", "cluttered_background",
        "awkward_cropping", "needs_recomposition",
        "subject_too_small", "subject_cut_off"
    };

    // "general" is the last label (index 26).  The model has a strong prior
    // toward this catch-all class.  Subtracting a logit bias before softmax
    // makes specific scene classes more likely while keeping "general" as a
    // valid (but lower-priority) prediction.
    private static final float GENERAL_LOGIT_SUPPRESSION = 3.0f;
    private static final int GENERAL_LABEL_INDEX = SCENE_LABELS.length - 1;

    // Per-head calibrated thresholds for binary heads with positive bias.
    // Recommendation-related thresholds were rechecked against a 200-image
    // Python-vs-TFLite parity sweep on 2026-03-24. The five focus heads
    // (has_shadow / has_reflection / has_flare / has_background_people /
    // has_moire) are recalibrated for the v5 head-only finetune (May 2026)
    // using the per-class best-threshold sweep at
    // experiment_outputs/finetune_defectupdate_eval_5models_v5/.
    private static final Map<String, Float> CALIBRATED_THRESHOLDS = new HashMap<>();
    static {
        CALIBRATED_THRESHOLDS.put("needs_composition_edit", 0.62f);
        CALIBRATED_THRESHOLDS.put("is_tilted", 0.62f);
        CALIBRATED_THRESHOLDS.put("has_text", 0.65f);     // model mean=0.617 std=0.003, effectively disabled
        // v5-calibrated focus heads (best F1 thresholds on 8K new-focus eval).
        CALIBRATED_THRESHOLDS.put("has_shadow", 0.60f);            // v5 best_threshold = 0.60, F1 0.97
        CALIBRATED_THRESHOLDS.put("has_reflection", 0.70f);        // v5 best_threshold = 0.70, F1 0.91
        CALIBRATED_THRESHOLDS.put("has_flare", 0.75f);             // v5 best_threshold = 0.75, F1 0.91
        CALIBRATED_THRESHOLDS.put("has_background_people", 0.70f); // v5 best_threshold = 0.70, F1 0.95
        CALIBRATED_THRESHOLDS.put("has_moire", 0.60f);             // v5 best_threshold = 0.60, F1 0.90
        CALIBRATED_THRESHOLDS.put("has_diagonal_lines", 0.75f); // model mean=0.600, 45%→~10% active
        CALIBRATED_THRESHOLDS.put("has_leading_lines", 0.75f);  // model mean=0.616, 53%→~10% active
        CALIBRATED_THRESHOLDS.put("has_symmetry", 0.70f);       // model mean=0.544, 20%→~5% active
    }

    // -----------------------------------------------------------------
    // Preview-time trigger guards.
    //
    // The L1 external head is currently mis-calibrated for several
    // weak-evidence triggers: business_card / wifi_credential / lens_blocked
    // get saturated to ≈1.0 by huge biases regardless of the scene. We
    // therefore enforce evidence-based caps and thresholds on the path
    // that the preview UI actually reads (FrameAnalysis trigger scores +
    // thresholds map). We never *force* a trigger true; we only require
    // raw-frame / scalar-head evidence before letting it surface.
    //
    // ND filter and out_of_focus paths are intentionally left untouched
    // so the demo can still show those when warranted.
    // -----------------------------------------------------------------

    private static final float GUARD_CARD_DISPLAY_THRESHOLD     = 0.78f;
    private static final float GUARD_WIFI_DISPLAY_THRESHOLD     = 0.85f;
    private static final float GUARD_LENS_OK_DISPLAY_THRESHOLD  = 0.60f;
    private static final float GUARD_LENS_NOEVIDENCE_THRESHOLD  = 0.85f;
    private static final float GUARD_SUPPRESSED_CAP             = 0.35f;
    private static final float GUARD_HARD_VETO_CAP              = 0.20f;

    // Configuration
    private final int frameSkip;
    private final float binaryThreshold;
    private final float compositionIssueThreshold;
    private final float fingerEdgeFraction;
    private final float fingerBrightnessRatio;
    private final float fingerBlurRatio;

    // State
    private InferenceEngine engine;
    private ImagePreprocessor preprocessor;
    private ImagePreprocessor segPreprocessor;
    private ImagePreprocessor.NormMode backboneNormMode = ImagePreprocessor.NormMode.CLIP;
    private int frameCounter = 0;
    private FrameAnalysis lastAnalysis = null;
    private Bitmap previousFrame = null; // Real previous frame for motion estimation
    private ByteBuffer zeroSegInput = null;

    // Phase 0.3 — optional external sub-models. When wired and enabled,
    // their outputs are attached to the FrameAnalysis as `external*` fields
    // AND optionally override the main backbone heads (subject_bbox /
    // suggested_crop) so the framing template uses the cleaner signal.
    private SubjectDetectorEngine externalSubjectDetector;
    private CropAdvisorEngine externalCropAdvisor;
    private TriggerScorer triggerScorer;
    private TriggerExternalHead triggerExternalHead;
    private TriggerLogitContract triggerLogitContract;
    private final LensBlockedCornerStats lensBlockedCornerStats = new LensBlockedCornerStats();
    private final TextRegionStats textRegionStats = new TextRegionStats();
    private final BackgroundBokehStats backgroundBokehStats = new BackgroundBokehStats();
    private TriggerArbiter triggerArbiter;
    private TriggerHysteresis triggerHysteresis;
    private boolean useExternalSubjectDetector = true;
    private boolean useExternalCropAdvisor = true;
    private float lastSubjectIou = 0f;
    private float[] lastExternalBbox = null;

    /**
     * Return the NormMode appropriate for the given backbone name.
     * mobilenetv3, efficientnet_b2 and the mobilenetv4_hybrid_* family use
     * ImageNet normalization; CLIP-family backbones use CLIP normalization.
     */
    public static ImagePreprocessor.NormMode normModeForBackbone(String backbone) {
        if (backbone == null) {
            return ImagePreprocessor.NormMode.CLIP;
        }
        String b = backbone.trim().toLowerCase();
        if (b.contains("mobilenetv3")
                || b.contains("efficientnet")
                || b.contains("mobilenetv4_hybrid")) {
            return ImagePreprocessor.NormMode.IMAGENET;
        }
        return ImagePreprocessor.NormMode.CLIP;
    }

    /** Set the normalization mode used for the backbone (first) input. */
    public void setBackboneNormMode(ImagePreprocessor.NormMode mode) {
        this.backboneNormMode = mode;
        Log.i(TAG, "Backbone normalization set to " + mode);
    }

    /** Phase 0.3 — wire optional U²-Netp salient object detector. */
    public void setExternalSubjectDetector(SubjectDetectorEngine detector) {
        this.externalSubjectDetector = detector;
    }

    /** Phase 0.3 — wire optional GAIC v2 aesthetic crop advisor. */
    public void setExternalCropAdvisor(CropAdvisorEngine advisor) {
        this.externalCropAdvisor = advisor;
    }

    public void setUseExternalSubjectDetector(boolean v) {
        this.useExternalSubjectDetector = v;
    }

    public void setUseExternalCropAdvisor(boolean v) {
        this.useExternalCropAdvisor = v;
    }

    /** Wire L0/L1 trigger scoring for the currently loaded backbone. */
    public void setTriggerEngines(TriggerScorer scorer, TriggerExternalHead externalHead) {
        this.triggerScorer = scorer;
        this.triggerExternalHead = externalHead;
        // Build the mutual-exclusion arbiter and temporal hysteresis from the
        // same rules asset so they stay in sync with the scorer.
        if (scorer != null) {
            this.triggerArbiter = scorer.arbiter();
            this.triggerHysteresis = TriggerHysteresis.fromConfig(scorer.hysteresisConfig());
        } else {
            this.triggerArbiter = null;
            this.triggerHysteresis = null;
        }
    }

    public void setTriggerLogitContract(TriggerLogitContract contract) {
        this.triggerLogitContract = contract;
    }

    public FrameAnalyzer() {
        this(null, 224, 3, 0.6f, 0.5f);
    }

    public FrameAnalyzer(InferenceEngine engine, int modelInputSize) {
        this(engine, modelInputSize, 3, 0.6f, 0.5f);
    }

    /**
     * @param engine            TFLite inference engine (null for decode-only mode)
     * @param modelInputSize    Spatial H=W expected by model
     * @param frameSkip         Process every N-th frame
     * @param binaryThreshold   Sigmoid threshold for binary flags
     * @param compositionIssueThreshold Threshold for composition issues
     */
    public FrameAnalyzer(InferenceEngine engine, int modelInputSize,
                         int frameSkip, float binaryThreshold,
                         float compositionIssueThreshold) {
        this.engine = engine;
        this.preprocessor = new ImagePreprocessor(modelInputSize);
        this.frameSkip = frameSkip;
        this.binaryThreshold = binaryThreshold;
        this.compositionIssueThreshold = compositionIssueThreshold;
        this.fingerEdgeFraction = 0.10f;
        this.fingerBrightnessRatio = 0.18f;
        this.fingerBlurRatio = 0.15f;
    }

    /**
     * Process a raw camera frame (Android Bitmap).
     * Returns null on skipped frames (returns cached analysis).
     *
     * @param frame Raw camera preview Bitmap (ARGB_8888)
     * @return FrameAnalysis or the most recent cached one on skip frames
     */
    public FrameAnalysis analyze(Bitmap frame) {
        return analyze(frame, false);
    }

    /** Analyze a frame, requesting the expensive 640x640 segmentation output only when needed. */
    public FrameAnalysis analyze(Bitmap frame, boolean includeSegmentation) {
        frameCounter++;

        // Frame-skip: re-use cached result for non-key frames
        if (frameCounter % frameSkip != 1 && lastAnalysis != null) {
            return lastAnalysis;
        }

        long t0 = System.nanoTime();

        // Finger heuristic on the raw (full-res) frame
        boolean finger = detectFingerObstruction(frame);

        if (engine == null) {
            throw new RuntimeException(
                "No inference engine set. Pass engine to constructor "
                + "or use decode() with pre-computed outputs.");
        }

        // Pre-process for model (3 inputs: backbone-normalized, ImageNet-normalized, motion frame)
        Map<String, float[]> outputs;
        if (engine.isCombinedSceneSegModel()) {
            ByteBuffer backboneInput = preprocessor.preprocess(frame, backboneNormMode);
            ByteBuffer imagenetInput = preprocessor.preprocess(frame, ImagePreprocessor.NormMode.IMAGENET);
            ByteBuffer motionInput;
            if (previousFrame != null) {
                motionInput = preprocessor.preprocess(previousFrame, ImagePreprocessor.NormMode.RAW);
            } else {
                motionInput = preprocessor.createZeroBuffer();
            }
            ByteBuffer segInput;
            if (includeSegmentation) {
                segInput = getSegPreprocessor().preprocess(frame, ImagePreprocessor.NormMode.CLIP);
            } else {
                segInput = getZeroSegInput();
            }
            outputs = engine.runCombined(backboneInput, imagenetInput, motionInput, segInput,
                    includeSegmentation);
        } else if (engine.getInputCount() >= 3) {
            ByteBuffer backboneInput = preprocessor.preprocess(frame, backboneNormMode);
            ByteBuffer imagenetInput = preprocessor.preprocess(frame, ImagePreprocessor.NormMode.IMAGENET);
            ByteBuffer motionInput;
            if (previousFrame != null) {
                motionInput = preprocessor.preprocess(previousFrame, ImagePreprocessor.NormMode.RAW);
            } else {
                motionInput = preprocessor.createZeroBuffer();
            }
            outputs = engine.run(backboneInput, imagenetInput, motionInput);
        } else {
            ByteBuffer modelInput = preprocessor.preprocess(frame);
            outputs = engine.run(modelInput);
        }

        // Store current frame as previous for next inference
        if (previousFrame != null && previousFrame != frame) {
            previousFrame.recycle();
        }
        previousFrame = frame.copy(frame.getConfig(), false);
        Set<String> keys = outputs.keySet();
        Log.d(TAG, "Inference output keys=" + Arrays.toString(keys.toArray())
                + " backboneNorm=" + backboneNormMode);

        float inferenceMs = (System.nanoTime() - t0) / 1_000_000.0f;

        // Decode
        FrameAnalysis analysis = decode(outputs);
        analysis.setFingerObstruction(finger);
        analysis.setInferenceMs(inferenceMs);
        populateTriggerScores(outputs, frame, analysis);
        Log.i(TAG,
            "TopK scene=" + topKSummary(outputs, "scene_type", SCENE_LABELS, 3)
                + " subject=" + topKSummary(outputs, "subject", SUBJECT_LABELS, 3)
                + " lighting=" + topKSummary(outputs, "lighting", LIGHTING_LABELS, 2)
                + " motion=" + topKSummary(outputs, "motion", MOTION_LABELS, 2));

        // Phase 0.3 — optional external sub-models. Always runs when the
        // engine is loaded so the UI can render comparison overlays even
        // when the user has disabled the "use external as primary" flag.
        runExternalSubjectDetector(frame, analysis);
        runExternalCropAdvisor(frame, analysis);

        lastAnalysis = analysis;
        return analysis;
    }

    private void populateTriggerScores(Map<String, float[]> outputs, Bitmap frame, FrameAnalysis analysis) {
        if (outputs == null || analysis == null) {
            return;
        }
        try {
            Map<String, Float> signals = TriggerSignals.extract(outputs);
            LensBlockedCornerStats.Result lensResult = lensBlockedCornerStats.analyze(frame);
            signals.put("lens_blocked_corner_prob", lensResult.probability);
            signals.put("lens_blocked_corner_count", (float) lensResult.numBlockedPatches);
            int lensEdgeBlocked = countEdgeBlockedPatches(lensResult);
            signals.put("lens_blocked_edge_count", (float) lensEdgeBlocked);
            textRegionStats.injectInto(frame, signals);
            // Background bokeh proxy: estimate defocus strength from the frame
            // so tele_portrait can be gated when the optics already blurred it.
            backgroundBokehStats.injectInto(frame, signals);
            analysis.setTriggerSignals(signals);

            Map<String, Float> l0 = triggerScorer != null
                    ? triggerScorer.score(signals)
                    : new java.util.LinkedHashMap<>();
            Map<String, Float> l1 = triggerExternalHead != null
                    ? triggerExternalHead.score(signals, outputs.get("feature_embedding"))
                    : new java.util.LinkedHashMap<>();
                float[] triggerLogits = outputs.get("trigger_logits");
                if (triggerLogitContract != null && triggerLogits != null) {
                if (!triggerLogitContract.matchesLogits(triggerLogits)) {
                    Log.w(TAG, "Direct trigger logits size mismatch: got=" + triggerLogits.length
                        + " expected=" + triggerLogitContract.labelNames().length + "; falling back to L0/L1");
                } else {
                    analysis.setTriggerL0Scores(l0);
                    analysis.setTriggerL1Scores(l1);
                    analysis.setTriggerScores(triggerLogitContract.scoreLogits(triggerLogits));
                    analysis.setTriggerThresholds(triggerLogitContract.thresholdMap());
                    return;
                }
                }
            Map<String, Float> finalScores = triggerExternalHead != null
                    ? triggerExternalHead.hybrid(l0, signals, outputs.get("feature_embedding"))
                    : l0;
            Map<String, Float> thresholds = new java.util.LinkedHashMap<>();
            for (int i = 0; i < TriggerNames.COUNT; i++) {
                String name = TriggerNames.ALL.get(i);
                float threshold = 0.5f;
                if (triggerExternalHead != null && triggerExternalHead.useL1(i)) {
                    threshold = triggerExternalHead.threshold(i);
                }
                if (TriggerNames.LENS_BLOCKED.equals(name)) {
                    float existing = finalScores.getOrDefault(name, 0f);
                    finalScores.put(name, Math.max(existing, lensResult.probability));
                    l0.put(name, Math.max(l0.getOrDefault(name, 0f), lensResult.probability));
                }
                thresholds.put(name, threshold);
            }
            // Evidence-based preview guards. Must run AFTER hybrid fusion so it
            // overrides the saturated L1 outputs (esp. business_card /
            // wifi_credential / lens_blocked). Never forces a trigger true.
            applyPreviewTriggerGuards(finalScores, l0, l1, thresholds, signals, lensResult, lensEdgeBlocked);
            // Mutual-exclusion arbitration (e.g. only_me vs tele_portrait vs
            // uw_selfie) then temporal hysteresis to remove single-frame flicker.
            if (triggerArbiter != null) {
                triggerArbiter.apply(finalScores, thresholds);
            }
            if (triggerHysteresis != null) {
                finalScores = triggerHysteresis.stabilize(finalScores, thresholds);
            }
            analysis.setTriggerL0Scores(l0);
            analysis.setTriggerL1Scores(l1);
            analysis.setTriggerScores(finalScores);
            analysis.setTriggerThresholds(thresholds);
        } catch (Exception e) {
            Log.w(TAG, "Trigger scoring failed", e);
        }
    }

    /**
     * Count how many of the corner/edge-midpoint patches (first 8 entries of
     * {@code lensResult.perPatch}) are flagged blocked. Center grid patches
     * are ignored on purpose: a finger usually intrudes from the frame edge,
     * so requiring edge evidence rules out interior dark/warm content like
     * cables on a desk or shadow under a face.
     */
    private static int countEdgeBlockedPatches(LensBlockedCornerStats.Result r) {
        if (r == null || r.perPatch == null) return 0;
        int effectiveCount = Math.max(0, r.patchCount);
        int n = Math.min(Math.min(8, effectiveCount), r.perPatch.length);
        int blocked = 0;
        for (int i = 0; i < n; i++) {
            if (r.perPatch[i] != null && r.perPatch[i].isBlocked) blocked++;
        }
        return blocked;
    }

    private static float clamp01f(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static float sig(Map<String, Float> s, String k) {
        Float v = s == null ? null : s.get(k);
        return v == null ? 0f : v;
    }

    /**
     * Preview-time guards for triggers that the L1 head over-predicts.
     *
     * <p>This intentionally does NOT touch nd_filter / out_of_focus / only_me /
     * tele_portrait / etc. — only the three over-fired classes are gated.
     * If supporting evidence is missing we (a) cap the displayed score to a
     * sub-threshold value and (b) raise the display threshold so the pill
     * does not surface. If evidence IS present we leave the original score
     * intact (only nudging the threshold down for lens_blocked so a real
     * obstruction can still fire).</p>
     */
    private static void applyPreviewTriggerGuards(
            Map<String, Float> finalScores,
            Map<String, Float> l0,
            Map<String, Float> l1,
            Map<String, Float> thresholds,
            Map<String, Float> signals,
            LensBlockedCornerStats.Result lensResult,
            int lensEdgeBlocked) {

        float hasText        = sig(signals, "has_text");
        float hasFace        = sig(signals, "has_face");
        float hasMoire       = sig(signals, "has_moire");
        float textProb       = sig(signals, "text_region_prob");
        float cardProb       = sig(signals, "document_card_prob");
        float textPatches    = sig(signals, "text_region_patch_count");
        float textCenter     = sig(signals, "text_region_center_score");
        float subjectProb    = sig(signals, "subject_prob");
        int   subjectId      = (int) sig(signals, "subject_id");
        int   sceneId        = (int) sig(signals, "scene_id");
        float lensCornerProb = lensResult == null ? 0f : lensResult.probability;
        int   lensCount      = lensResult == null ? 0 : lensResult.numBlockedPatches;

        // --- business_card -------------------------------------------------
        boolean cardEvidence =
                hasText >= 0.65f
                && (cardProb >= 0.55f || textProb >= 0.60f || textPatches >= 4f)
                && textCenter >= 0.08f
                && hasFace < 0.55f;
        // Hard veto: clearly a person/portrait scene with no text at all.
        boolean cardHardVeto = hasFace >= 0.50f && hasText < 0.40f && textProb < 0.30f;
        capTrigger(finalScores, l0, l1, thresholds, TriggerNames.BUSINESS_CARD,
                cardEvidence, cardHardVeto,
                GUARD_CARD_DISPLAY_THRESHOLD, GUARD_SUPPRESSED_CAP, GUARD_HARD_VETO_CAP);

        // --- wifi_credential ----------------------------------------------
        // Wi-Fi cards/QR posters: must look like a card AND show moiré or
        // dense small-text patches; otherwise reject.
        boolean wifiEvidence =
                cardEvidence
                && (hasMoire >= 0.50f || textPatches >= 5f || cardProb >= 0.65f);
        boolean wifiHardVeto = cardHardVeto || (hasText < 0.45f && hasMoire < 0.30f);
        capTrigger(finalScores, l0, l1, thresholds, TriggerNames.WIFI_CREDENTIAL,
                wifiEvidence, wifiHardVeto,
                GUARD_WIFI_DISPLAY_THRESHOLD, GUARD_SUPPRESSED_CAP, GUARD_HARD_VETO_CAP);

        // --- lens_blocked --------------------------------------------------
        // Trust the raw-frame heuristic as ground truth. Require either a
        // strong corner-prob OR multiple edge patches flagged blocked.
        boolean lensEvidence =
                (lensCornerProb >= 0.55f && lensEdgeBlocked >= 2)
                || (lensCornerProb >= 0.70f && lensCount >= 3);
        // No evidence at all → keep the score but raise threshold so it does
        // not surface; if any moderate edge signal is present we leave it
        // alone (so real obstructions still fire on small lensResult.prob).
        if (!lensEvidence) {
            // Cap score so debug overlay stops showing 0.95.
            float score = finalScores.getOrDefault(TriggerNames.LENS_BLOCKED, 0f);
            float capped = Math.min(score, Math.max(lensCornerProb, GUARD_SUPPRESSED_CAP));
            finalScores.put(TriggerNames.LENS_BLOCKED, capped);
            l0.put(TriggerNames.LENS_BLOCKED,
                    Math.min(l0.getOrDefault(TriggerNames.LENS_BLOCKED, 0f), capped));
            l1.put(TriggerNames.LENS_BLOCKED,
                    Math.min(l1.getOrDefault(TriggerNames.LENS_BLOCKED, 0f), capped));
            thresholds.put(TriggerNames.LENS_BLOCKED, GUARD_LENS_NOEVIDENCE_THRESHOLD);
        } else {
            thresholds.put(TriggerNames.LENS_BLOCKED, GUARD_LENS_OK_DISPLAY_THRESHOLD);
        }
    }

    /**
     * Apply an evidence/veto cap to a single trigger across all score maps
     * the preview UI may read, then write the corresponding display
     * threshold. Never increases the score.
     */
    private static void capTrigger(
            Map<String, Float> finalScores,
            Map<String, Float> l0,
            Map<String, Float> l1,
            Map<String, Float> thresholds,
            String name,
            boolean hasEvidence,
            boolean hardVeto,
            float displayThreshold,
            float suppressedCap,
            float hardVetoCap) {
        if (hardVeto) {
            finalScores.put(name, Math.min(finalScores.getOrDefault(name, 0f), hardVetoCap));
            l0.put(name, Math.min(l0.getOrDefault(name, 0f), hardVetoCap));
            l1.put(name, Math.min(l1.getOrDefault(name, 0f), hardVetoCap));
        } else if (!hasEvidence) {
            finalScores.put(name, Math.min(finalScores.getOrDefault(name, 0f), suppressedCap));
            l0.put(name, Math.min(l0.getOrDefault(name, 0f), suppressedCap));
            l1.put(name, Math.min(l1.getOrDefault(name, 0f), suppressedCap));
        }
        thresholds.put(name, displayThreshold);
    }

    private void runExternalSubjectDetector(Bitmap frame, FrameAnalysis analysis) {
        if (externalSubjectDetector == null || !externalSubjectDetector.isAvailable()) {
            return;
        }
        try {
            SubjectDetectorEngine.SaliencyResult r = externalSubjectDetector.detect(frame);
            if (r == null) {
                return;
            }
            analysis.setExternalSubjectBboxNorm(r.bboxNorm);
            analysis.setExternalSubjectCenterNorm(r.subjectCenterNorm);
            analysis.setExternalSubjectFillRatio(r.fillRatio);

            // Track IoU for crop-advisor throttling.
            if (lastExternalBbox != null && r.bboxNorm != null) {
                lastSubjectIou = iou(lastExternalBbox, r.bboxNorm);
            }
            lastExternalBbox = r.bboxNorm == null ? null : r.bboxNorm.clone();

            // Override main-head subject signal when the user has opted in.
            if (useExternalSubjectDetector) {
                analysis.setSubjectBbox(r.bboxNorm);
                if (r.subjectCenterNorm != null && r.subjectCenterNorm.length >= 2) {
                    analysis.setSubjectCenterX(r.subjectCenterNorm[0]);
                    analysis.setSubjectCenterY(r.subjectCenterNorm[1]);
                    // Lift confidence so the framing template trusts the signal.
                    analysis.setSubjectConfidence(Math.max(analysis.getSubjectConfidence(), 0.8f));
                }
                analysis.setSubjectFillRatio(r.fillRatio);
            }
        } catch (Throwable t) {
            Log.w(TAG, "External subject detector failed", t);
        }
    }

    private void runExternalCropAdvisor(Bitmap frame, FrameAnalysis analysis) {
        if (externalCropAdvisor == null || !externalCropAdvisor.isAvailable()) {
            return;
        }
        // Throttle: only re-run when frame index advances or subject IoU dropped.
        if (!externalCropAdvisor.shouldRecompute(frameCounter, lastSubjectIou)) {
            // Reuse previously published external crop on cached frames.
            if (lastAnalysis != null) {
                analysis.setExternalCropNorm(lastAnalysis.getExternalCropNorm());
                analysis.setExternalCropAestheticScore(lastAnalysis.getExternalCropAestheticScore());
                if (useExternalCropAdvisor && lastAnalysis.getExternalCropNorm() != null) {
                    analysis.setSuggestedCrop(lastAnalysis.getExternalCropNorm());
                }
            }
            return;
        }
        try {
            java.util.List<CropAdvisorEngine.CropCandidate> cs =
                    externalCropAdvisor.suggest(frame, 1);
            if (cs == null || cs.isEmpty()) {
                return;
            }
            CropAdvisorEngine.CropCandidate best = cs.get(0);
            analysis.setExternalCropNorm(best.cropNorm);
            analysis.setExternalCropAestheticScore(best.aestheticScore);
            if (useExternalCropAdvisor) {
                analysis.setSuggestedCrop(best.cropNorm);
            }
        } catch (Throwable t) {
            Log.w(TAG, "External crop advisor failed", t);
        }
    }

    private static float iou(float[] a, float[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0f;
        float ax2 = a[0] + a[2], ay2 = a[1] + a[3];
        float bx2 = b[0] + b[2], by2 = b[1] + b[3];
        float ix1 = Math.max(a[0], b[0]);
        float iy1 = Math.max(a[1], b[1]);
        float ix2 = Math.min(ax2, bx2);
        float iy2 = Math.min(ay2, by2);
        float iw = Math.max(0f, ix2 - ix1);
        float ih = Math.max(0f, iy2 - iy1);
        float inter = iw * ih;
        float ua = a[2] * a[3] + b[2] * b[3] - inter;
        return ua <= 1e-6f ? 0f : inter / ua;
    }

    private ImagePreprocessor getSegPreprocessor() {
        if (segPreprocessor == null) {
            segPreprocessor = new ImagePreprocessor(engine.getSegInputSize());
        }
        return segPreprocessor;
    }

    private ByteBuffer getZeroSegInput() {
        int bytes = 1 * engine.getSegInputSize() * engine.getSegInputSize() * 3 * 4;
        if (zeroSegInput == null || zeroSegInput.capacity() != bytes) {
            zeroSegInput = ByteBuffer.allocateDirect(bytes);
            zeroSegInput.order(java.nio.ByteOrder.nativeOrder());
        }
        zeroSegInput.rewind();
        return zeroSegInput;
    }

    /**
     * Decode a raw output map (tensor name → float array) into a FrameAnalysis.
     * This is the core decoder — usable without an inference engine.
     *
     * @param outputs Map of output tensor names to float arrays
     * @return Decoded FrameAnalysis
     */
    public FrameAnalysis decode(Map<String, float[]> outputs) {
        FrameAnalysis analysis = new FrameAnalysis();

        // --- Classifications (top-2 argmax + softmax confidence) ---

        // Suppress "general" (catch-all) logit before softmax so that
        // specific scene classes surface more naturally.
        String[] sceneResult = decodeClassificationWithSuppression(
                outputs, "scene_type", SCENE_LABELS,
                GENERAL_LABEL_INDEX, GENERAL_LOGIT_SUPPRESSION);
        String primaryScene = sceneResult[0];
        float primarySceneConfidence = Float.parseFloat(sceneResult[1]);
        String secondaryScene = sceneResult[2];
        float secondarySceneConfidence = Float.parseFloat(sceneResult[3]);
        // Safety net: if "general" still comes out on top, promote secondary.
        if ("general".equals(primaryScene) && secondaryScene != null && !secondaryScene.isEmpty()) {
            analysis.setSceneType(secondaryScene);
            analysis.setSceneConfidence(secondarySceneConfidence);
            analysis.setSceneType2(primaryScene);
            analysis.setSceneConfidence2(primarySceneConfidence);
        } else {
            analysis.setSceneType(primaryScene);
            analysis.setSceneConfidence(primarySceneConfidence);
            analysis.setSceneType2(secondaryScene);
            analysis.setSceneConfidence2(secondarySceneConfidence);
        }

        String[] lightingResult = decodeClassification(outputs, "lighting", LIGHTING_LABELS);
        analysis.setLightingCondition(lightingResult[0]);
        analysis.setLightingConfidence(Float.parseFloat(lightingResult[1]));
        analysis.setLightingCondition2(lightingResult[2]);
        analysis.setLightingConfidence2(Float.parseFloat(lightingResult[3]));

        String[] motionResult = decodeClassification(outputs, "motion", MOTION_LABELS);
        analysis.setMotionType(motionResult[0]);
        analysis.setMotionConfidence(Float.parseFloat(motionResult[1]));
        analysis.setMotionType2(motionResult[2]);
        analysis.setMotionConfidence2(Float.parseFloat(motionResult[3]));

        String[] subjectResult = decodeClassification(outputs, "subject", SUBJECT_LABELS);
        analysis.setMainSubject(subjectResult[0]);
        analysis.setSubjectConfidence(Float.parseFloat(subjectResult[1]));

        // --- Regression scalars (clamped 0-1) ---
        analysis.setContrastValue(decodeScalar(outputs, "contrast", 0.5f, true));
        analysis.setSharpnessValue(decodeScalar(outputs, "sharpness", 0.5f, true));
        analysis.setNoiseLevel(decodeScalar(outputs, "noise", 0.1f, true));
        analysis.setCompositionScore(decodeScalar(outputs, "composition_score", 0.5f, true));
        analysis.setFlowMagnitude(decodeScalar(outputs, "flow_magnitude", 0.0f, false));

        // --- Binary flags (sigmoid > threshold) ---
        // Use per-head calibrated thresholds when available to compensate for
        // output bias observed in certain heads (has_shadow, has_text, has_flare).
        analysis.setNeedsCompositionEdit(decodeBinaryCalibratedRecorded(outputs, "needs_composition_edit", analysis));
        analysis.setTilted(decodeBinaryCalibratedRecorded(outputs, "is_tilted", analysis));
        analysis.setHasFace(decodeBinaryCalibratedRecorded(outputs, "has_face", analysis));
        analysis.setHasText(decodeBinaryCalibratedRecorded(outputs, "has_text", analysis));
        analysis.setHasShadow(decodeBinaryCalibratedRecorded(outputs, "has_shadow", analysis));
        analysis.setHasReflection(decodeBinaryCalibratedRecorded(outputs, "has_reflection", analysis));
        analysis.setHasBackgroundPeople(decodeBinaryCalibratedRecorded(outputs, "has_background_people", analysis));
        analysis.setHasFlare(decodeBinaryCalibratedRecorded(outputs, "has_flare", analysis));
        analysis.setHasMoire(decodeBinaryCalibratedRecorded(outputs, "has_moire", analysis));

        // Face count (regression, rounded)
        if (outputs.containsKey("face_count")) {
            float fc = outputs.get("face_count")[0];
            analysis.setFaceCount(Math.max(0, Math.round(fc)));
        }

        // --- Composition issues (multi-label sigmoid) ---
        if (outputs.containsKey("composition_issues")) {
            float[] raw = outputs.get("composition_issues");
            List<String> issues = new ArrayList<>();
            for (int i = 0; i < Math.min(raw.length, COMPOSITION_ISSUE_LABELS.length); i++) {
                if (decodeProbabilityLike(raw[i]) > compositionIssueThreshold) {
                    issues.add(COMPOSITION_ISSUE_LABELS[i]);
                }
            }
            analysis.setCompositionIssues(issues);
        }

        // --- Tilt angle regression (optional) ---
        if (outputs.containsKey("tilt_angle")) {
            analysis.setTiltAngle(outputs.get("tilt_angle")[0]);
        } else if (analysis.isTilted()) {
            analysis.setTiltAngle(3.0f); // Fallback default
        }

        // --- Subject center regression (optional) ---
        if (outputs.containsKey("subject_center")) {
            float[] sc = outputs.get("subject_center");
            if (sc.length >= 2) {
                analysis.setSubjectCenterX(Math.max(0.0f, Math.min(1.0f, sc[0])));
                analysis.setSubjectCenterY(Math.max(0.0f, Math.min(1.0f, sc[1])));
            }
        }

        // --- Feature embedding (for master match / aesthetic transfer) ---
        // if (outputs.containsKey("feature_embedding")) {
        //     float[] emb = outputs.get("feature_embedding");
        //     if (emb.length > 0) {
        //         // L2-normalize
        //         float norm = 0.0f;
        //         for (float v : emb) {
        //             norm += v * v;
        //         }
        //         norm = (float) Math.sqrt(norm);
        //         if (norm > 0) {
        //             float[] normalized = new float[emb.length];
        //             for (int i = 0; i < emb.length; i++) {
        //                 normalized[i] = emb[i] / norm;
        //             }
        //             analysis.setFeatureEmbedding(normalized);
        //         } else {
        //             analysis.setFeatureEmbedding(emb);
        //         }
        //     }
        // }
        // --- Feature embedding (for master match / aesthetic transfer) ---
        if (outputs.containsKey("feature_embedding")) {
            float[] emb = outputs.get("feature_embedding");
            if (emb.length > 0) {
                // L2-normalize
                float norm = 0.0f;
                for (float v : emb) {
                    norm += v * v;
                }
                norm = (float) Math.sqrt(norm);
                if (norm > 0) {
                    float[] normalized = new float[emb.length];
                    for (int i = 0; i < emb.length; i++) {
                        normalized[i] = emb[i] / norm;
                    }
                    analysis.setFeatureEmbedding(normalized);
                    if (frameCounter % 30 == 1) {
                        Log.w(TAG, "[DIAG] feature_embedding: dim=" + emb.length
                                + " norm=" + String.format("%.4f", norm));
                    }
                } else {
                    analysis.setFeatureEmbedding(emb);
                    Log.w(TAG, "[DIAG] feature_embedding: zero norm!");
                }
            } else {
                Log.w(TAG, "[DIAG] feature_embedding: empty array");
            }
        } else {
            Log.w(TAG, "[DIAG] feature_embedding: NOT in outputs. keys="
                    + outputs.keySet());
        }
        // --- Phase 1b composition feature heads ---
        analysis.setHasSymmetry(decodeBinaryCalibrated(outputs, "has_symmetry"));
        analysis.setHasDiagonalLines(decodeBinaryCalibrated(outputs, "has_diagonal_lines"));
        analysis.setHasLeadingLines(decodeBinaryCalibrated(outputs, "has_leading_lines"));
        analysis.setSubjectFillRatio(decodeScalar(outputs, "subject_fill_ratio", 0.0f, false));

        if (outputs.containsKey("subject_count")) {
            float scRaw = outputs.get("subject_count")[0];
            analysis.setSubjectCount(Math.max(0, Math.round(scRaw)));
        }

        analysis.setVisualComplexity(decodeScalar(outputs, "visual_complexity", 0.5f, false));

        if (outputs.containsKey("scene_depth_layers")) {
            float sdlRaw = outputs.get("scene_depth_layers")[0];
            analysis.setSceneDepthLayers(Math.max(1, Math.min(3, Math.round(sdlRaw))));
        }

        // --- Edit-tool regression heads ---
        analysis.setBrightnessValue(decodeScalar(outputs, "brightness_value", 0.5f, true));
        analysis.setBlurLevel(decodeScalar(outputs, "blur_level", 0.0f, true));

        // --- Bounding-box regression heads ---
        if (outputs.containsKey("subject_bbox")) {
            float[] sb = outputs.get("subject_bbox");
            if (sb != null && sb.length >= 4) {
                float[] box = new float[]{
                    Math.max(0f, Math.min(1f, sb[0])),
                    Math.max(0f, Math.min(1f, sb[1])),
                    Math.max(0f, Math.min(1f, sb[2])),
                    Math.max(0f, Math.min(1f, sb[3]))
                };
                // Only set if box has positive width and height
                if (box[2] > 0.01f && box[3] > 0.01f) {
                    analysis.setSubjectBbox(box);
                }
            }
        }
        if (outputs.containsKey("suggested_crop")) {
            float[] sc = outputs.get("suggested_crop");
            if (sc != null && sc.length >= 4) {
                float[] box = new float[]{
                    Math.max(0f, Math.min(1f, sc[0])),
                    Math.max(0f, Math.min(1f, sc[1])),
                    Math.max(0.05f, Math.min(1f, sc[2])),
                    Math.max(0.05f, Math.min(1f, sc[3]))
                };
                analysis.setSuggestedCrop(box);
            }
        }

        // Phase 1.3 \u2014 single, structured log line so we can confirm whether
        // the regression heads are actually firing in the field.
        if (frameCounter % 30 == 0) {
            android.util.Log.i("FrameAnalyzer",
                    "bbox/crop subject_bbox=" + java.util.Arrays.toString(analysis.getSubjectBbox())
                            + " suggested_crop=" + java.util.Arrays.toString(analysis.getSuggestedCrop()));
        }

        // --- Phase 3 (Composition v2) — derive headroom ratios + facing hint ---
        populateDerivedHeadroomAndFacing(analysis);

        // --- Combined model segmentation logits (NHWC [1,160,160,5]) ---
        if (outputs.containsKey("seg_logits")) {
            float[] logits = outputs.get("seg_logits");
            if (logits != null && logits.length > 0) {
                analysis.setDefectSegmentationLogits(logits, 160, 160, 5);
            }
        }

        return analysis;
    }

    /**
     * Reset frame counter and cached analysis.
     */
    public void reset() {
        frameCounter = 0;
        lastAnalysis = null;
        if (previousFrame != null) {
            previousFrame.recycle();
            previousFrame = null;
        }
    }

    /**
     * Phase 3 (Composition v2) — populate derived headroom ratios and
     * facing-direction hint from already-decoded heads. Pure data
     * derivation; no model inference required.
     */
    private void populateDerivedHeadroomAndFacing(
            com.samsung.camera.intelligence.guidance.FrameAnalysis analysis) {
        float[] sb = analysis.getSubjectBbox();
        if (sb != null && sb.length >= 4) {
            float left = Math.max(0f, sb[0]);
            float top = Math.max(0f, sb[1]);
            float right = Math.min(1f, sb[0] + sb[2]);
            float bottom = Math.min(1f, sb[1] + sb[3]);
            analysis.setHeadroomTop(Math.max(0f, top));
            analysis.setHeadroomBottom(Math.max(0f, 1f - bottom));
            analysis.setHeadroomLeft(Math.max(0f, left));
            analysis.setHeadroomRight(Math.max(0f, 1f - right));
        }

        // Approximate facing hint: GAIC tends to leave room on the side the
        // subject is facing. So crop center > subject center → subject likely
        // facing right (positive); < → facing left (negative).
        float[] crop = analysis.getExternalCropNorm();
        if (crop == null) {
            crop = analysis.getSuggestedCrop();
        }
        if (crop != null && crop.length >= 4 && sb != null && sb.length >= 4) {
            float cropCenterX = crop[0] + crop[2] * 0.5f;
            float subjectCenterX = sb[0] + sb[2] * 0.5f;
            float dx = cropCenterX - subjectCenterX;
            // Clamp to [-1, +1]; small offsets snap to 0 to avoid noise.
            if (Math.abs(dx) < 0.04f) {
                analysis.setFacingHint(0f);
            } else {
                analysis.setFacingHint(Math.max(-1f, Math.min(1f, dx * 4f)));
            }
        }
    }

    // ---------------------------------------------------------------
    // Finger / lens obstruction heuristic
    // ---------------------------------------------------------------

    /**
     * Lightweight edge-strip heuristic for detecting finger on lens.
     * Divides frame edges into 8 strips. If any strip has both low
     * brightness and low variance relative to the image center,
     * we flag a potential obstruction.
     *
     * @param bitmap Raw camera frame
     * @return true if finger obstruction detected
     */
    public boolean detectFingerObstruction(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int ew = Math.max(1, (int) (w * fingerEdgeFraction));
        int eh = Math.max(1, (int) (h * fingerEdgeFraction));

        // Center patch stats
        int cx = w / 2;
        int cy = h / 2;
        float[] centerStats = getRegionStats(bitmap, cx - ew, cy - eh, cx + ew, cy + eh);
        float centerMean = centerStats[0];
        float centerVar = centerStats[1];
        if (centerMean < 1.0f) return false;

        // Edge strips: 8 regions (corners + mid-edges)
        int[][] strips = {
            {0, 0, ew, eh},                     // top-left
            {w - ew, 0, w, eh},                  // top-right
            {0, h - eh, ew, h},                  // bottom-left
            {w - ew, h - eh, w, h},              // bottom-right
            {ew, 0, w - ew, eh},                 // top-center
            {ew, h - eh, w - ew, h},             // bottom-center
            {0, eh, ew, h - eh},                 // left-center
            {w - ew, eh, w, h - eh},             // right-center
        };

        // Count how many edge strips look obstructed; a real finger
        // covers multiple adjacent regions, so require >= 2 hits.
        int obstructedCount = 0;
        for (int[] strip : strips) {
            float[] stats = getRegionStats(bitmap, strip[0], strip[1], strip[2], strip[3]);
            float sMean = stats[0];
            float sVar = stats[1];
            if (sMean / Math.max(centerMean, 1e-6f) < fingerBrightnessRatio
                && sVar / Math.max(centerVar, 1e-6f) < fingerBlurRatio) {
                obstructedCount++;
            }
        }
        return obstructedCount >= 2;
    }

    // ---------------------------------------------------------------
    // Decoding helpers
    // ---------------------------------------------------------------

    /**
     * Returns String[4]: [top1_label, top1_conf, top2_label, top2_conf]
     */
    private static String[] decodeClassification(Map<String, float[]> outputs,
                                                  String key, String[] labels) {
        return decodeClassificationWithSuppression(outputs, key, labels, -1, 0.0f);
    }

    /**
     * Returns String[4]: [top1_label, top1_conf, top2_label, top2_conf].
     * When {@code suppressIndex >= 0}, the logit at that position is reduced
     * by {@code suppressBias} before softmax so that the corresponding class
     * is less likely to be chosen as top-1.
     */
    private static String[] decodeClassificationWithSuppression(
            Map<String, float[]> outputs, String key, String[] labels,
            int suppressIndex, float suppressBias) {
        String defaultLabel = labels.length > 0 ? labels[labels.length - 1] : "unknown";
        if (!outputs.containsKey(key)) {
            return new String[]{defaultLabel, "0.0", "", "0.0"};
        }

        float[] logits = outputs.get(key);
        if (logits == null || logits.length == 0) {
            return new String[]{defaultLabel, "0.0", "", "0.0"};
        }
        if (logits.length == 1) {
            if (!isFinite(logits[0]) || labels.length == 0) {
                return new String[]{defaultLabel, "0.0", "", "0.0"};
            }
            int idx = (int) logits[0];
            idx = idx % labels.length;
            if (idx < 0) {
                idx += labels.length;
            }
            return new String[]{labels[idx], "1.0", "", "0.0"};
        }

        // Apply optional logit suppression (e.g. to de-prioritise "general")
        float[] adjusted;
        if (suppressIndex >= 0 && suppressIndex < logits.length && suppressBias > 0.0f) {
            adjusted = Arrays.copyOf(logits, logits.length);
            adjusted[suppressIndex] -= suppressBias;
        } else {
            adjusted = logits;
        }

        if (!hasFiniteValue(adjusted)) {
            return new String[]{defaultLabel, "0.0", "", "0.0"};
        }

        float[] probs = softmax(adjusted);
        if (probs.length == 0) {
            return new String[]{defaultLabel, "0.0", "", "0.0"};
        }
        // top-1
        int idx1 = argmax(probs);
        if (idx1 < 0 || idx1 >= probs.length) {
            return new String[]{defaultLabel, "0.0", "", "0.0"};
        }
        String label1 = idx1 < labels.length ? labels[idx1] : "unknown";
        String conf1 = isFinite(probs[idx1]) ? String.valueOf(probs[idx1]) : "0.0";
        // top-2
        int idx2 = -1;
        float best2 = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < probs.length; i++) {
            if (i != idx1 && isFinite(probs[i]) && probs[i] > best2) {
                best2 = probs[i];
                idx2 = i;
            }
        }
        String label2 = (idx2 >= 0 && idx2 < labels.length) ? labels[idx2] : "";
        String conf2 = (idx2 >= 0 && isFinite(probs[idx2])) ? String.valueOf(probs[idx2]) : "0.0";
        return new String[]{label1, conf1, label2, conf2};
    }

    /**
     * Decode a single regression output.
     */
    private static float decodeScalar(Map<String, float[]> outputs, String key,
                                       float defaultVal, boolean clamp) {
        if (!outputs.containsKey(key) || outputs.get(key) == null || outputs.get(key).length == 0) {
            return defaultVal;
        }
        float val = outputs.get(key)[0];
        if (!isFinite(val)) {
            return defaultVal;
        }
        if (clamp) {
            val = Math.max(0.0f, Math.min(1.0f, val));
        }
        return val;
    }

    /**
     * Decode a binary flag via sigmoid.
     */
    private static boolean decodeBinary(Map<String, float[]> outputs, String key,
                                         float threshold) {
        if (!outputs.containsKey(key) || outputs.get(key) == null || outputs.get(key).length == 0) {
            return false;
        }
        float logit = outputs.get(key)[0];
        if (!isFinite(logit)) {
            return false;
        }
        return sigmoid(logit) > threshold;
    }

    /**
     * Some exported heads are already probabilities in [0, 1] rather than raw logits.
     * Avoid applying sigmoid twice in that case.
     */
    private static float decodeProbabilityLike(float value) {
        if (!isFinite(value)) {
            return 0.0f;
        }
        if (value >= 0.0f && value <= 1.0f) {
            return value;
        }
        return sigmoid(value);
    }

    /**
     * Decode a binary flag using per-head calibrated threshold if available,
     * otherwise fall back to the default binaryThreshold.
     */
    private boolean decodeBinaryCalibrated(Map<String, float[]> outputs, String key) {
        Float calibrated = CALIBRATED_THRESHOLDS.get(key);
        float threshold = (calibrated != null) ? calibrated : binaryThreshold;
        return decodeBinary(outputs, key, threshold);
    }

    /**
     * Same as {@link #decodeBinaryCalibrated} but also records the raw sigmoid
     * probability into {@link com.samsung.camera.intelligence.guidance.FrameAnalysis#putBinaryHeadScore}
     * so debug overlays can display "score vs threshold" without re-running inference.
     */
    private boolean decodeBinaryCalibratedRecorded(
            Map<String, float[]> outputs, String key,
            com.samsung.camera.intelligence.guidance.FrameAnalysis analysis) {
        Float calibrated = CALIBRATED_THRESHOLDS.get(key);
        float threshold = (calibrated != null) ? calibrated : binaryThreshold;
        if (outputs.containsKey(key)) {
            float prob = sigmoid(outputs.get(key)[0]);
            if (analysis != null) analysis.putBinaryHeadScore(key, prob);
            return prob > threshold;
        }
        return false;
    }

    // ---------------------------------------------------------------
    // Math utilities
    // ---------------------------------------------------------------

    /**
     * Softmax over a float array.
     */
    public static float[] softmax(float[] logits) {
        if (logits == null || logits.length == 0) {
            return new float[0];
        }
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) {
            if (isFinite(v) && v > max) {
                max = v;
            }
        }
        if (!isFinite(max)) {
            return new float[logits.length];
        }
        float sum = 0.0f;
        float[] exp = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            if (!isFinite(logits[i])) {
                exp[i] = 0.0f;
                continue;
            }
            float shifted = Math.max(-80.0f, Math.min(80.0f, logits[i] - max));
            exp[i] = (float) Math.exp(shifted);
            sum += exp[i];
        }
        if (!isFinite(sum) || sum <= 0.0f) {
            return new float[logits.length];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }

    /**
     * Sigmoid function.
     */
    public static float sigmoid(float x) {
        x = Math.max(-20.0f, Math.min(20.0f, x));
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    /**
     * Argmax of a float array.
     */
    public static int argmax(float[] arr) {
        if (arr == null || arr.length == 0) {
            return -1;
        }
        int idx = -1;
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < arr.length; i++) {
            if (!isFinite(arr[i])) {
                continue;
            }
            if (idx < 0 || arr[i] > max) {
                max = arr[i];
                idx = i;
            }
        }
        return idx >= 0 ? idx : 0;
    }

    private static String topKSummary(Map<String, float[]> outputs, String key, String[] labels, int k) {
        if (!outputs.containsKey(key) || labels == null || labels.length == 0) {
            return "n/a";
        }
        float[] logits = outputs.get(key);
        if (logits == null || logits.length == 0) {
            return "empty";
        }
        if (logits.length == 1) {
            if (!isFinite(logits[0])) {
                return "n/a";
            }
            int idx = ((int) logits[0]) % labels.length;
            return labels[Math.max(0, idx)];
        }
        if (!hasFiniteValue(logits)) {
            return "n/a";
        }

        float[] probs = softmax(logits);
        boolean[] used = new boolean[probs.length];
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(k, Math.min(probs.length, labels.length));
        for (int rank = 0; rank < limit; rank++) {
            int bestIdx = -1;
            float bestVal = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < probs.length; i++) {
                if (!used[i] && isFinite(probs[i]) && probs[i] > bestVal) {
                    bestVal = probs[i];
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            used[bestIdx] = true;
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            String label = labels[bestIdx];
            if (rank == 0 && "scene_type".equals(key) && "general".equals(label)) {
                continue;
            }
            sb.append(label)
                    .append('=')
                    .append(String.format(java.util.Locale.US, "%.2f", probs[bestIdx]));
        }
        return sb.length() > 0 ? sb.toString() : "n/a";
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean hasFiniteValue(float[] values) {
        if (values == null) {
            return false;
        }
        for (float value : values) {
            if (isFinite(value)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------
    // Image region statistics
    // ---------------------------------------------------------------

    /**
     * Compute mean brightness and variance of a rectangular region.
     * Returns float[]{mean, variance}.
     */
    private float[] getRegionStats(Bitmap bitmap, int x1, int y1, int x2, int y2) {
        x1 = Math.max(0, x1);
        y1 = Math.max(0, y1);
        x2 = Math.min(bitmap.getWidth(), x2);
        y2 = Math.min(bitmap.getHeight(), y2);

        if (x2 <= x1 || y2 <= y1) return new float[]{0.0f, 0.0f};

        // Sample every 4th pixel for speed
        int step = 4;
        float sum = 0.0f;
        float sumSq = 0.0f;
        int count = 0;

        for (int y = y1; y < y2; y += step) {
            for (int x = x1; x < x2; x += step) {
                int pixel = bitmap.getPixel(x, y);
                float gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3.0f;
                sum += gray;
                sumSq += gray * gray;
                count++;
            }
        }

        if (count == 0) return new float[]{0.0f, 0.0f};

        float mean = sum / count;
        float variance = (sumSq / count) - (mean * mean);
        return new float[]{mean, variance};
    }
}
