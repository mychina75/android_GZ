package com.samsung.camera.intelligence.inference;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TFLite-based inference engine for the multi-task scene analysis model.
 * Replaces Python MobileInferenceEngine (ONNX Runtime) with TFLite Interpreter.
 *
 * Usage:
 *   TFLiteInferenceEngine engine = new TFLiteInferenceEngine(context, "model.tflite");
 *   Map<Integer, float[]> outputs = engine.run(inputBuffer);
 *   engine.close();
 */
public class TFLiteInferenceEngine implements InferenceEngine {

    private static final String TAG = "TFLiteInferenceEngine";
    private static final String[] SPLIT_ENCODER_OUTPUT_NAMES = new String[] {
            "encoder_0_layer_2",
            "encoder_1_layer_5",
            "encoder_2_layer_8",
            "encoder_3_layer_11",
            "encoder_4_final"
    };

    // Must stay aligned with models.output_types.MultiTaskOutputs field order.
    private static final String[] CANONICAL_OUTPUT_KEYS = new String[] {
            "scene_type",
            "lighting",
            "motion",
            "subject",
            "contrast",
            "sharpness",
            "noise",
            "composition_score",
            "needs_composition_edit",
            "composition_issues",
            "has_face",
            "has_text",
            "is_tilted",
            "has_shadow",
            "has_reflection",
            "has_background_people",
            "has_flare",
            "is_repeating_motion",
            "face_count",
            "motion_logits_flow",
            "motion_pred_flow",
            "speed_logits",
            "direction_logits",
            "flow_magnitude",
            "is_repeating_motion_flow",
            "contrast_quality",
            "sharpness_quality",
            "noise_quality",
            "overall_quality",
            "tilt_angle",
            "subject_center",
            "feature_embedding",
            "has_symmetry",
            "has_diagonal_lines",
            "has_leading_lines",
            "subject_fill_ratio",
            "subject_count",
            "visual_complexity",
            "scene_depth_layers",
            "brightness_value",
            "blur_level",
            "subject_bbox",
            "suggested_crop",
            "seg_logits"
    };

    public enum ModelType {
        SCENE_ONLY,
        SEG_ONLY,
        COMBINED_SCENE_SEG
    }

    private Interpreter interpreter;
    private Interpreter encoderInterpreter;
    private Interpreter triggerHeadsInterpreter;
    private GpuDelegate gpuDelegate;
    private String[] outputNames;
    private String[] semanticOutputNames;
    private int[] outputSizes;
    private DataType[] outputDataTypes;
    private int inputSize; // scene spatial dimension (e.g. 224)
    private int segInputSize; // spatial dimension for combined seg input (e.g. 640)
    private int inputCount; // number of model inputs (1, 3, or 4)
    private int[] inputIndexMap; // maps our convention order to model tensor indices
    private ModelType modelType;
    private boolean splitModel = false;
    private int[] splitHeadsEncoderInputIndices;
    private int splitHeadsRawImageInputIndex = -1;
    private int splitHeadsMotionFrameInputIndex = -1;
    private ByteBuffer splitEncoderInputBuffer;
    private float[][][][] splitEncoderOutputs;
    private float[][][][] splitHeadsEncoderInputs;
    private int[] triggerHeadsEncoderInputIndices;
    private int triggerHeadsRawImageInputIndex = -1;
    private int triggerHeadsMotionFrameInputIndex = -1;
    private float[][][][] triggerHeadsEncoderInputs;
    private float[][] triggerHeadsOutput;

    private static final Map<String, String> RAW_OUTPUT_ALIASES = new HashMap<>();

    // Alphabetically-sorted canonical output keys for PartitionedCall:N mapping
    private static final String[] ALPHABETICAL_OUTPUT_KEYS;

    // Optional output heads that may be absent in some model variants.
    // When the model has fewer outputs than CANONICAL, these are excluded
    // from the alphabetical mapping to keep PartitionedCall:N aligned.
    private static final Set<String> OPTIONAL_OUTPUT_HEADS = new HashSet<>(Arrays.asList(
            "subject_bbox", "suggested_crop", "seg_logits"
    ));

    // Instance-level alphabetical keys, adjusted for model's actual output count
    private String[] effectiveAlphabeticalKeys;

    // Exact PyTorch field order from models.output_types.MultiTaskOutputs.
    // Used for the new build_220Kshadow pipeline whose ONNX -> SavedModel
    // conversion (onnx2tf) loses the original output_names and renames each
    // head to "output_K".  After lex-sort by SavedModel signature key the
    // resulting PartitionedCall:N indices need to be permuted back to
    // PyTorch's K-th field to recover the semantic name.
    //
    // Note: this list intentionally differs from CANONICAL_OUTPUT_KEYS:
    //   - Includes "has_moire" at index 41 (present in PyTorch model, omitted
    //     from CANONICAL because Android UI does not consume it).
    //   - Does NOT include "seg_logits" (CANONICAL has it as a future head
    //     for combined scene+seg models; pure scene models never emit it).
    private static final String[] PYTORCH_FIELD_ORDER = new String[] {
            "scene_type", "lighting", "motion", "subject",
            "contrast", "sharpness", "noise",
            "composition_score", "needs_composition_edit", "composition_issues",
            "has_face", "has_text", "is_tilted",
            "has_shadow", "has_reflection", "has_background_people",
            "has_flare", "is_repeating_motion", "face_count",
            "motion_logits_flow", "motion_pred_flow",
            "speed_logits", "direction_logits", "flow_magnitude",
            "is_repeating_motion_flow",
            "contrast_quality", "sharpness_quality", "noise_quality", "overall_quality",
            "tilt_angle", "subject_center", "feature_embedding",
            "has_symmetry", "has_diagonal_lines", "has_leading_lines",
            "subject_fill_ratio", "subject_count", "visual_complexity",
            "scene_depth_layers",
            "brightness_value", "blur_level", "has_moire",
            "subject_bbox", "suggested_crop"
    };

    // Permutation that converts a PartitionedCall:N index (lex-sorted by
    // "output_K" string) back into PyTorch field index K.  Built lazily per
    // output count.  Only used when raw tensor name has the "Stateful"
    // prefix, which signals the new (output_K) pipeline.
    private int[] statefulLexSortPermutation;

    static {
        RAW_OUTPUT_ALIASES.put("motion_logits", "motion_logits_flow");

        ALPHABETICAL_OUTPUT_KEYS = CANONICAL_OUTPUT_KEYS.clone();
        Arrays.sort(ALPHABETICAL_OUTPUT_KEYS);
    }

    /**
     * Build the lex-sort permutation that maps PartitionedCall:N (after lex
     * sorting "output_0".."output_(N-1)") back to PyTorch field index K.
     * Result perm[i] = K means PartitionedCall:i is the K-th PyTorch output.
     */
    private static int[] buildStatefulLexSortPermutation(int outputCount) {
        Integer[] indices = new Integer[outputCount];
        for (int i = 0; i < outputCount; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> ("output_" + a).compareTo("output_" + b));
        int[] perm = new int[outputCount];
        for (int i = 0; i < outputCount; i++) perm[i] = indices[i];
        return perm;
    }

    /**
     * Build an alphabetical output key list that matches the model's actual
     * output count.  When the model has fewer outputs than our canonical list,
     * optional heads (e.g. bbox heads added in later training phases) are
     * dropped so that PartitionedCall:N indices stay correctly aligned.
     */
    private static String[] buildAlphabeticalKeysForCount(int outputCount) {
        if (outputCount >= ALPHABETICAL_OUTPUT_KEYS.length) {
            return ALPHABETICAL_OUTPUT_KEYS;
        }
        int diff = ALPHABETICAL_OUTPUT_KEYS.length - outputCount;
        List<String> filtered = new ArrayList<>(ALPHABETICAL_OUTPUT_KEYS.length);
        int removed = 0;
        for (String key : ALPHABETICAL_OUTPUT_KEYS) {
            if (removed < diff && OPTIONAL_OUTPUT_HEADS.contains(key)) {
                removed++;
            } else {
                filtered.add(key);
            }
        }
        if (filtered.size() == outputCount) {
            Log.i(TAG, "Adjusted alphabetical output keys: removed " + removed
                    + " optional heads for " + outputCount + "-output model");
            return filtered.toArray(new String[0]);
        }
        // Could not reconcile; fall back to full list
        Log.w(TAG, "Could not reconcile output count " + outputCount
                + " with canonical list (" + ALPHABETICAL_OUTPUT_KEYS.length + ")");
        return ALPHABETICAL_OUTPUT_KEYS;
    }

    /**
     * Load TFLite model from Android assets folder.
     *
     * @param context   Android context for asset access
     * @param modelPath Path to .tflite file in assets (e.g. "model.tflite")
     * @param useGpu    Whether to enable GPU delegate acceleration
     */
    public TFLiteInferenceEngine(Context context, String modelPath, boolean useGpu) throws IOException {
        Interpreter.Options options = buildInterpreterOptions(useGpu);
        interpreter = loadAssetInterpreter(context, modelPath, options);
        splitModel = false;
        inputCount = interpreter.getInputTensorCount();
        inputSize = detectSceneInputSize(224);
        segInputSize = detectSegInputSize(inputSize);
        Log.i(TAG, "Model loaded: " + inputCount + " input(s), inputSize=" + inputSize);
        initInputMapping();
        initOutputMetadata();
        modelType = detectModelType();
        Log.i(TAG, "Detected model type: " + modelType + " segInputSize=" + segInputSize);
    }

    /**
     * Load split V-JEPA encoder and heads TFLite models from Android assets.
     */
    public TFLiteInferenceEngine(Context context, String encoderModelPath,
                                 String headsModelPath, boolean useGpu) throws IOException {
        if (useGpu) {
            Log.w(TAG, "GPU delegate is not enabled for split V-JEPA models yet; using CPU");
        }
        Interpreter.Options options = buildInterpreterOptions(false, false);
        encoderInterpreter = loadAssetInterpreter(context, encoderModelPath, options);
        interpreter = loadAssetInterpreter(context, headsModelPath, options);
        splitModel = true;
        inputCount = 3;
        inputSize = detectSceneInputSize(224);
        segInputSize = inputSize;
        initSplitHeadsInputMapping();
        initSplitBuffers();
        initOutputMetadata();
        modelType = ModelType.SCENE_ONLY;
        Log.i(TAG, "Split V-JEPA models loaded from assets: encoder=" + encoderModelPath
                + " heads=" + headsModelPath + " inputSize=" + inputSize);
    }

    /**
     * Load TFLite model from a file path (not assets).
     */
    public TFLiteInferenceEngine(String modelFilePath, boolean useGpu) {
        Interpreter.Options options = buildInterpreterOptions(useGpu);
        interpreter = loadFileInterpreter(modelFilePath, options);
        splitModel = false;
        inputCount = interpreter.getInputTensorCount();
        inputSize = detectSceneInputSize(224);
        segInputSize = detectSegInputSize(inputSize);
        Log.i(TAG, "Model loaded from file: " + inputCount + " input(s), inputSize=" + inputSize);
        initInputMapping();
        initOutputMetadata();
        modelType = detectModelType();
        Log.i(TAG, "Detected model type: " + modelType + " segInputSize=" + segInputSize);
    }

    /**
     * Load split V-JEPA encoder and heads TFLite models from file paths.
     */
    public TFLiteInferenceEngine(String encoderModelFilePath, String headsModelFilePath, boolean useGpu) {
        if (useGpu) {
            Log.w(TAG, "GPU delegate is not enabled for split V-JEPA models yet; using CPU");
        }
        Interpreter.Options options = buildInterpreterOptions(false, false);
        encoderInterpreter = loadFileInterpreter(encoderModelFilePath, options);
        interpreter = loadFileInterpreter(headsModelFilePath, options);
        splitModel = true;
        inputCount = 3;
        inputSize = detectSceneInputSize(224);
        segInputSize = inputSize;
        initSplitHeadsInputMapping();
        initSplitBuffers();
        initOutputMetadata();
        modelType = ModelType.SCENE_ONLY;
        Log.i(TAG, "Split V-JEPA models loaded from files: encoder=" + encoderModelFilePath
                + " heads=" + headsModelFilePath + " inputSize=" + inputSize);
    }

    public void attachTriggerHeadsAsset(Context context, String triggerHeadsModelPath) throws IOException {
        if (!splitModel || encoderInterpreter == null) {
            throw new IllegalStateException("Trigger heads can only attach to an initialized split V-JEPA engine");
        }
        detachTriggerHeads();
        triggerHeadsInterpreter = loadAssetInterpreter(context, triggerHeadsModelPath, buildInterpreterOptions(false, false));
        initTriggerHeadsInputMapping();
        initTriggerHeadsBuffers();
        Log.i(TAG, "Attached direct trigger heads asset: " + triggerHeadsModelPath);
    }

    public void attachTriggerHeadsFile(String triggerHeadsModelFilePath) {
        if (!splitModel || encoderInterpreter == null) {
            throw new IllegalStateException("Trigger heads can only attach to an initialized split V-JEPA engine");
        }
        detachTriggerHeads();
        triggerHeadsInterpreter = loadFileInterpreter(triggerHeadsModelFilePath, buildInterpreterOptions(false, false));
        initTriggerHeadsInputMapping();
        initTriggerHeadsBuffers();
        Log.i(TAG, "Attached direct trigger heads file: " + triggerHeadsModelFilePath);
    }

    public void detachTriggerHeads() {
        if (triggerHeadsInterpreter != null) {
            triggerHeadsInterpreter.close();
            triggerHeadsInterpreter = null;
        }
        triggerHeadsEncoderInputIndices = null;
        triggerHeadsRawImageInputIndex = -1;
        triggerHeadsMotionFrameInputIndex = -1;
        triggerHeadsEncoderInputs = null;
        triggerHeadsOutput = null;
    }

    /**
     * Run inference on a single preprocessed input buffer.
     * For models with multiple inputs, the same buffer is used for image and
     * raw_image, and a zero buffer is used for motion_frame.
     *
     * @param inputBuffer ByteBuffer of shape [1, H, W, 3] float32, direct-allocated, native byte order
     * @return Map of output tensor name → float array
     */
    public Map<String, float[]> run(ByteBuffer inputBuffer) {
        if (inputCount <= 1) {
            return runMultiInput(new ByteBuffer[]{inputBuffer});
        }
        // For 3-input model: duplicate image for raw_image, zeros for motion_frame
        ByteBuffer motionZeros = ByteBuffer.allocateDirect(inputBuffer.capacity());
        motionZeros.order(ByteOrder.nativeOrder());
        // zero-filled by default in Java
        return runMultiInput(new ByteBuffer[]{inputBuffer, inputBuffer, motionZeros});
    }

    /**
     * Run inference with separate inputs for image, raw_image, and motion_frame.
     *
     * @param image       CLIP-normalized [1, H, W, 3] float32 NHWC
     * @param rawImage    ImageNet-normalized [1, H, W, 3] float32 NHWC
     * @param motionFrame Raw [0,1] scaled [1, H, W, 3] float32 NHWC (zeros if no previous frame)
     * @return Map of output tensor name → float array
     */
    public Map<String, float[]> run(ByteBuffer image, ByteBuffer rawImage, ByteBuffer motionFrame) {
        return runMultiInput(new ByteBuffer[]{image, rawImage, motionFrame});
    }

    /**
     * Run a combined scene+segmentation model.
     * Calling convention: scene_image, raw_image, motion_frame, seg_image.
     */
    public Map<String, float[]> runCombined(ByteBuffer sceneImage, ByteBuffer rawImage,
                                            ByteBuffer motionFrame, ByteBuffer segImage) {
        return runCombined(sceneImage, rawImage, motionFrame, segImage, true);
        }

        /**
         * Run combined model, optionally omitting the segmentation output buffer.
         * The current monolithic TFLite graph still requires the 4th input tensor;
         * skipping seg_logits here avoids 640x640 preprocessing in live preview and
         * avoids copying/decoding the large mask unless a caller explicitly needs it.
         */
        public Map<String, float[]> runCombined(ByteBuffer sceneImage, ByteBuffer rawImage,
                            ByteBuffer motionFrame, ByteBuffer segImage,
                            boolean includeSegmentationOutput) {
        return runMultiInput(new ByteBuffer[]{sceneImage, rawImage, motionFrame, segImage},
            includeSegmentationOutput);
    }

    /**
     * Get the number of model inputs.
     */
    public int getInputCount() {
        return inputCount;
    }

    public int getSegInputSize() {
        return segInputSize;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public boolean isCombinedSceneSegModel() {
        return modelType == ModelType.COMBINED_SCENE_SEG;
    }

    private Map<String, float[]> runMultiInput(ByteBuffer[] inputs) {
        return runMultiInput(inputs, true);
    }

    private Map<String, float[]> runMultiInput(ByteBuffer[] inputs, boolean includeSegmentationOutput) {
        if (splitModel) {
            return runSplitModel(inputs, includeSegmentationOutput);
        }

        Object[] inputArray = new Object[inputCount];
        for (int i = 0; i < Math.min(inputs.length, inputCount); i++) {
            int modelIdx = (inputIndexMap != null && i < inputIndexMap.length)
                    ? inputIndexMap[i] : i;
            inputs[i].rewind();
            inputArray[modelIdx] = inputs[i];
        }
        for (int i = 0; i < inputCount; i++) {
            if (inputArray[i] == null) {
                inputArray[i] = createZeroInputBuffer(interpreter, i);
            }
        }

        Map<Integer, Object> outputMap = allocateOutputMap(includeSegmentationOutput);

        interpreter.runForMultipleInputsOutputs(inputArray, outputMap);

        return decodeOutputMap(outputMap);
    }

    private Map<String, float[]> runSplitModel(ByteBuffer[] inputs, boolean includeSegmentationOutput) {
        if (encoderInterpreter == null) {
            throw new IllegalStateException("Split encoder interpreter not initialized");
        }
        if (inputs.length == 0) {
            throw new IllegalArgumentException("Split model requires at least the scene image input");
        }

        ByteBuffer sceneImage = inputs[0];
        ByteBuffer rawImage = inputs.length > 1 ? inputs[1] : inputs[0];
        ByteBuffer motionFrame = inputs.length > 2 ? inputs[2]
                : createZeroImageLikeBuffer(rawImage != null ? rawImage : sceneImage);

        Map<Integer, Object> encoderOutputMap = new HashMap<>();
        for (int i = 0; i < SPLIT_ENCODER_OUTPUT_NAMES.length; i++) {
            encoderOutputMap.put(i, splitEncoderOutputs[i]);
        }
        Object[] encoderInputs = new Object[]{convertSceneInputToEncoderBuffer(sceneImage)};
        encoderInterpreter.runForMultipleInputsOutputs(encoderInputs, encoderOutputMap);

        Object[] headInputs = new Object[interpreter.getInputTensorCount()];
        for (int i = 0; i < SPLIT_ENCODER_OUTPUT_NAMES.length; i++) {
            copyEncoderTokensIntoInput(splitEncoderOutputs[i], splitHeadsEncoderInputs[i]);
            headInputs[splitHeadsEncoderInputIndices[i]] = splitHeadsEncoderInputs[i];
        }

        rawImage.rewind();
        motionFrame.rewind();
        headInputs[splitHeadsRawImageInputIndex] = rawImage;
        headInputs[splitHeadsMotionFrameInputIndex] = motionFrame;

        for (int i = 0; i < headInputs.length; i++) {
            if (headInputs[i] == null) {
                headInputs[i] = createZeroInputBuffer(interpreter, i);
            }
        }

        Map<Integer, Object> outputMap = allocateOutputMap(includeSegmentationOutput);
        interpreter.runForMultipleInputsOutputs(headInputs, outputMap);
        Map<String, float[]> result = decodeOutputMap(outputMap);

        if (triggerHeadsInterpreter != null) {
            try {
                Object[] triggerInputs = new Object[triggerHeadsInterpreter.getInputTensorCount()];
                for (int i = 0; i < SPLIT_ENCODER_OUTPUT_NAMES.length; i++) {
                    if (triggerHeadsEncoderInputIndices[i] < 0 || triggerHeadsEncoderInputs[i] == null) {
                        continue;
                    }
                    copyEncoderTokensIntoInput(splitEncoderOutputs[i], triggerHeadsEncoderInputs[i]);
                    triggerInputs[triggerHeadsEncoderInputIndices[i]] = triggerHeadsEncoderInputs[i];
                }
                rawImage.rewind();
                motionFrame.rewind();
                triggerInputs[triggerHeadsRawImageInputIndex] = rawImage;
                triggerInputs[triggerHeadsMotionFrameInputIndex] = motionFrame;
                for (int i = 0; i < triggerInputs.length; i++) {
                    if (triggerInputs[i] == null) {
                        triggerInputs[i] = createZeroInputBuffer(triggerHeadsInterpreter, i);
                    }
                }
                Map<Integer, Object> triggerOutputMap = new HashMap<>();
                triggerOutputMap.put(0, triggerHeadsOutput);
                triggerHeadsInterpreter.runForMultipleInputsOutputs(triggerInputs, triggerOutputMap);
                if (triggerHeadsOutput.length > 0) {
                    result.put("trigger_logits", triggerHeadsOutput[0].clone());
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Direct trigger heads inference failed; disabling optional trigger logits", e);
                detachTriggerHeads();
            }
        }

        return result;
    }

    private Map<Integer, Object> allocateOutputMap(boolean includeSegmentationOutput) {
        // Prepare output buffers
        Map<Integer, Object> outputMap = new HashMap<>();

        for (int i = 0; i < outputNames.length; i++) {
            if (!includeSegmentationOutput && "seg_logits".equals(semanticOutputNames[i])) {
                continue;
            }
            int bytesPerElement = (outputDataTypes[i] == DataType.INT64) ? 8 : 4;
            ByteBuffer outBuf = ByteBuffer.allocateDirect(outputSizes[i] * bytesPerElement);
            outBuf.order(ByteOrder.nativeOrder());
            outputMap.put(i, outBuf);
        }

        return outputMap;
    }

    private Map<String, float[]> decodeOutputMap(Map<Integer, Object> outputMap) {
        Map<String, float[]> result = new HashMap<>();
        for (int i = 0; i < outputNames.length; i++) {
            if (!outputMap.containsKey(i)) {
                continue;
            }
            ByteBuffer outBuf = (ByteBuffer) outputMap.get(i);
            outBuf.rewind();
            float[] values = new float[outputSizes[i]];
            if (outputDataTypes[i] == DataType.FLOAT32) {
                outBuf.asFloatBuffer().get(values);
            } else if (outputDataTypes[i] == DataType.INT32) {
                int[] intValues = new int[outputSizes[i]];
                outBuf.asIntBuffer().get(intValues);
                for (int j = 0; j < intValues.length; j++) {
                    values[j] = intValues[j];
                }
            } else if (outputDataTypes[i] == DataType.INT64) {
                long[] longValues = new long[outputSizes[i]];
                outBuf.asLongBuffer().get(longValues);
                for (int j = 0; j < longValues.length; j++) {
                    values[j] = longValues[j];
                }
            } else {
                Log.w(TAG, "Unsupported output dtype '" + outputDataTypes[i]
                        + "' for semantic key '" + semanticOutputNames[i]
                        + "'. Attempting float32 decode.");
                outBuf.asFloatBuffer().get(values);
            }
            if (result.containsKey(semanticOutputNames[i])) {
                Log.w(TAG, "Duplicate semantic output key '" + semanticOutputNames[i]
                        + "' from raw tensor '" + outputNames[i] + "'. Overwriting previous value.");
            }
            result.put(semanticOutputNames[i], values);
        }

        return result;
    }

    /**
     * Get model input spatial size (H=W).
     */
    public int getInputSize() {
        return inputSize;
    }

    /**
     * Get output tensor names.
     */
    public String[] getOutputNames() {
        return semanticOutputNames;
    }

    private ModelType detectModelType() {
        boolean hasSeg = false;
        for (String key : semanticOutputNames) {
            if ("seg_logits".equals(key)) {
                hasSeg = true;
                break;
            }
        }
        if (hasSeg && inputCount >= 4) {
            return ModelType.COMBINED_SCENE_SEG;
        }
        if (hasSeg) {
            return ModelType.SEG_ONLY;
        }
        return ModelType.SCENE_ONLY;
    }

    private int detectSegInputSize(int fallback) {
        int best = fallback;
        for (int i = 0; i < interpreter.getInputTensorCount(); i++) {
            int[] shape = interpreter.getInputTensor(i).shape();
            if (shape.length >= 3) {
                int spatial = spatialSizeForInputShape(shape);
                String name = normalizedTensorName(interpreter.getInputTensor(i).name());
                if (isSegInputName(name) || spatial > best) {
                    best = spatial;
                }
            }
        }
        return best;
    }

    private int detectSceneInputSize(int fallback) {
        Interpreter target = splitModel && encoderInterpreter != null ? encoderInterpreter : interpreter;
        int bestNamed = -1;
        int smallest = Integer.MAX_VALUE;
        for (int i = 0; i < target.getInputTensorCount(); i++) {
            int[] shape = target.getInputTensor(i).shape();
            if (shape.length < 3) {
                continue;
            }
            int spatial = spatialSizeForInputShape(shape);
            if (spatial > 0 && spatial < smallest) {
                smallest = spatial;
            }
            String name = normalizedTensorName(target.getInputTensor(i).name());
            if (isSceneInputName(name)) {
                bestNamed = spatial;
            }
        }
        if (bestNamed > 0) {
            return bestNamed;
        }
        return smallest != Integer.MAX_VALUE ? smallest : fallback;
    }

    private String resolveSemanticOutputName(String rawName, int outputIndex) {
        String[] positionalOutputKeys = positionalOutputKeys();
        if (rawName != null) {
            String trimmed = rawName.trim();
            if (trimmed.contains("/")) {
                trimmed = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            }

            String unstrippedNormalized = trimmed.toLowerCase();

            // New 220Kshadow pipeline: onnx2tf renames outputs to
            // "output_K".  The SavedModel signature lex-sorts these strings
            // ("output_0", "output_1", "output_10", "output_11", ...),
            // and TFLite assigns PartitionedCall:N in that lex-sorted order
            // with the "StatefulPartitionedCall" prefix.  Recover K via the
            // precomputed permutation, then map K to the PyTorch field name.
            //
            // Important: parse this BEFORE stripping a trailing ":0".  For
            // raw tensor name "StatefulPartitionedCall:0", the suffix is the
            // output index, not a TensorFlow output-port marker.  Stripping it
            // would drop the scene_type head and make the UI fall back to
            // "general" with 0% confidence.
            if (unstrippedNormalized.startsWith("statefulpartitionedcall:")) {
                try {
                    int pcIdx = Integer.parseInt(
                            unstrippedNormalized.substring("statefulpartitionedcall:".length()));
                    if (statefulLexSortPermutation != null
                            && pcIdx >= 0 && pcIdx < statefulLexSortPermutation.length) {
                        int pyIdx = statefulLexSortPermutation[pcIdx];
                        if (pyIdx >= 0 && pyIdx < PYTORCH_FIELD_ORDER.length) {
                            return PYTORCH_FIELD_ORDER[pyIdx];
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to semantic-name parsing
                }
            }

            // Legacy SavedModel quantized exports use "PartitionedCall:N"
            // (no "Stateful" prefix) where N is the alphabetical index of
            // the semantic output key.  This must also run before stripping
            // trailing ":0" so PartitionedCall:0 remains a valid index.
            if (unstrippedNormalized.startsWith("partitionedcall:")) {
                try {
                    int pcIdx = Integer.parseInt(
                            unstrippedNormalized.substring("partitionedcall:".length()));
                    if (effectiveAlphabeticalKeys != null
                            && pcIdx >= 0 && pcIdx < effectiveAlphabeticalKeys.length) {
                        return effectiveAlphabeticalKeys[pcIdx];
                    }
                    if (pcIdx >= 0 && pcIdx < ALPHABETICAL_OUTPUT_KEYS.length) {
                        return ALPHABETICAL_OUTPUT_KEYS[pcIdx];
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to semantic-name parsing
                }
            }

            if (trimmed.endsWith(":0")) {
                trimmed = trimmed.substring(0, trimmed.length() - 2);
            }
            String normalized = trimmed.toLowerCase();

            String alias = RAW_OUTPUT_ALIASES.get(normalized);
            if (alias != null) {
                return alias;
            }

            for (String key : CANONICAL_OUTPUT_KEYS) {
                if (normalized.equals(key)) {
                    return key;
                }
            }
            for (String key : PYTORCH_FIELD_ORDER) {
                if (normalized.equals(key)) {
                    return key;
                }
            }

            // onnx2tf outputs are named "Identity", "Identity_1", …
            // Use the numeric suffix as the canonical position instead of
            // the interpreter's iteration order (which may differ).
            if ("identity".equals(normalized)) {
                return positionalOutputKeys[0];
            }
            if (normalized.startsWith("identity_")) {
                try {
                    int identityIdx = Integer.parseInt(normalized.substring("identity_".length()));
                    if (identityIdx >= 0 && identityIdx < positionalOutputKeys.length) {
                        return positionalOutputKeys[identityIdx];
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to positional fallback
                }
            }

        }

        if (outputIndex >= 0 && outputIndex < positionalOutputKeys.length) {
            return positionalOutputKeys[outputIndex];
        }

        return rawName != null ? rawName : String.valueOf(outputIndex);
    }

    @Override
    public void close() {
        if (triggerHeadsInterpreter != null) {
            triggerHeadsInterpreter.close();
            triggerHeadsInterpreter = null;
        }
        if (encoderInterpreter != null) {
            encoderInterpreter.close();
            encoderInterpreter = null;
        }
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
    }

    /**
     * Determine input tensor ordering by reading tensor names.
    * Maps our calling convention (0=image, 1=raw_image, 2=motion_frame, 3=seg_image)
     * to the model's actual tensor indices.
     */
    private void initInputMapping() {
        if (inputCount <= 1) {
            inputIndexMap = new int[]{0};
            return;
        }
        inputIndexMap = new int[inputCount];
        // Default: identity mapping
        for (int i = 0; i < inputCount; i++) {
            inputIndexMap[i] = i;
        }
        if (inputCount < 3) return;

        // Detect named inputs: image/scene_image (CLIP), raw_image (ImageNet), motion_frame, seg_image.
        int imageIdx = -1, rawImageIdx = -1, motionIdx = -1, segIdx = -1;
        for (int i = 0; i < inputCount; i++) {
            String name = normalizedTensorName(interpreter.getInputTensor(i).name());
            if (isSegInputName(name)) {
                segIdx = i;
            } else if (isMotionInputName(name)) {
                motionIdx = i;
            } else if (isRawInputName(name)) {
                rawImageIdx = i;
            } else if (isSceneInputName(name)) {
                imageIdx = i;
            }
        }
        if (imageIdx >= 0 && rawImageIdx >= 0 && motionIdx >= 0) {
            inputIndexMap[0] = imageIdx;      // image (CLIP) → model's image slot
            inputIndexMap[1] = rawImageIdx;   // raw_image (ImageNet) → model's raw_image slot
            inputIndexMap[2] = motionIdx;     // motion_frame → model's motion_frame slot
            if (segIdx >= 0 && inputIndexMap.length > 3) {
                inputIndexMap[3] = segIdx;     // seg_image → model's seg slot
            }
            Log.i(TAG, "Input mapping: image→" + imageIdx
                    + " raw_image→" + rawImageIdx + " motion_frame→" + motionIdx
                    + (segIdx >= 0 ? " seg_image→" + segIdx : ""));
        } else {
            Log.w(TAG, "Could not detect named inputs, using positional order");
        }
    }

    private String normalizedTensorName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String name = rawName.toLowerCase();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(":0")) {
            name = name.substring(0, name.length() - 2);
        }
        if (name.startsWith("serving_default_")) {
            name = name.substring("serving_default_".length());
        }
        return name;
    }

    private boolean isSegInputName(String name) {
        return "seg_image".equals(name) || name.endsWith("_seg_image");
    }

    private boolean isSceneInputName(String name) {
        return "scene_image".equals(name) || "image".equals(name) || name.endsWith("_scene_image");
    }

    private boolean isRawInputName(String name) {
        return "raw_image".equals(name) || name.endsWith("_raw_image");
    }

    private boolean isMotionInputName(String name) {
        return "motion_frame".equals(name)
                || name.endsWith("_motion_frame")
                || "prev_frame".equals(name)
                || name.endsWith("_prev_frame");
    }

    private ByteBuffer createZeroInputBuffer(int inputIndex) {
        return createZeroInputBuffer(interpreter, inputIndex);
    }

    private ByteBuffer createZeroInputBuffer(Interpreter targetInterpreter, int inputIndex) {
        int[] shape = targetInterpreter.getInputTensor(inputIndex).shape();
        int elements = 1;
        for (int dim : shape) {
            elements *= Math.max(1, dim);
        }
        ByteBuffer zeros = ByteBuffer.allocateDirect(elements * 4);
        zeros.order(ByteOrder.nativeOrder());
        return zeros;
    }

    private ByteBuffer createZeroImageLikeBuffer(ByteBuffer template) {
        ByteBuffer zeros = ByteBuffer.allocateDirect(template.capacity());
        zeros.order(ByteOrder.nativeOrder());
        return zeros;
    }

    private int spatialSizeForInputShape(int[] shape) {
        if (shape.length >= 5) {
            return Math.max(shape[shape.length - 1], shape[shape.length - 2]);
        }
        if (shape.length == 4) {
            return shape[1];
        }
        return shape[2];
    }

    private Interpreter.Options buildInterpreterOptions(boolean useGpu) {
        return buildInterpreterOptions(useGpu, true);
    }

    private Interpreter.Options buildInterpreterOptions(boolean useGpu, boolean useXnnpack) {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        options.setUseXNNPACK(useXnnpack);

        if (useGpu) {
            try {
                gpuDelegate = new GpuDelegate();
                options.addDelegate(gpuDelegate);
            } catch (Exception e) {
                gpuDelegate = null;
            }
        }
        if (!useGpu && !useXnnpack) {
            Log.i(TAG, "Default XNNPACK delegate disabled for this interpreter");
        }
        return options;
    }

    private Interpreter loadAssetInterpreter(Context context, String modelPath, Interpreter.Options options) throws IOException {
        try {
            MappedByteBuffer modelBuffer = loadModelFile(context, modelPath);
            Interpreter loaded = new Interpreter(modelBuffer, options);
            Log.i(TAG, "Model loaded via mmap from asset: " + modelPath);
            return loaded;
        } catch (Exception mmapEx) {
            Log.w(TAG, "mmap load failed for " + modelPath + ": " + mmapEx.getMessage()
                    + ", trying file-based fallback");
            File cached = copyAssetToCache(context, modelPath);
            Interpreter loaded = new Interpreter(cached, options);
            Log.i(TAG, "Model loaded via cache file: " + cached.getAbsolutePath());
            return loaded;
        }
    }

    private Interpreter loadFileInterpreter(String modelFilePath, Interpreter.Options options) {
        Interpreter loaded = new Interpreter(new java.io.File(modelFilePath), options);
        Log.i(TAG, "Model loaded from file: " + modelFilePath);
        return loaded;
    }

    private void initOutputMetadata() {
        int outputCount = interpreter.getOutputTensorCount();
        effectiveAlphabeticalKeys = buildAlphabeticalKeysForCount(outputCount);
        statefulLexSortPermutation = buildStatefulLexSortPermutation(outputCount);
        Log.i(TAG, "Output parser v2: parse PartitionedCall:N before stripping tensor-port :0");
        outputNames = new String[outputCount];
        semanticOutputNames = new String[outputCount];
        outputSizes = new int[outputCount];
        outputDataTypes = new DataType[outputCount];
        for (int i = 0; i < outputCount; i++) {
            outputNames[i] = interpreter.getOutputTensor(i).name();
            semanticOutputNames[i] = resolveSemanticOutputName(outputNames[i], i);
            outputDataTypes[i] = interpreter.getOutputTensor(i).dataType();
            int[] shape = interpreter.getOutputTensor(i).shape();
            int size = 1;
            for (int dim : shape) {
                size *= dim;
            }
            outputSizes[i] = size;

            if (i < 4) {
                Log.i(TAG, "Output head[" + i + "] semantic='" + semanticOutputNames[i]
                        + "' shape=" + java.util.Arrays.toString(shape));
            }

            if ("scene_type".equals(semanticOutputNames[i]) && size != 27) {
                Log.w(TAG, "Unexpected scene_type head size: " + size + " (expected 27)");
            } else if ("lighting".equals(semanticOutputNames[i]) && size != 12) {
                Log.w(TAG, "Unexpected lighting head size: " + size + " (expected 12)");
            } else if ("motion".equals(semanticOutputNames[i]) && size != 7) {
                Log.w(TAG, "Unexpected motion head size: " + size + " (expected 7)");
            } else if ("subject".equals(semanticOutputNames[i]) && size != 23) {
                Log.w(TAG, "Unexpected subject head size: " + size + " (expected 23)");
            } else if ("speed_logits".equals(semanticOutputNames[i]) && size != 5) {
                Log.w(TAG, "Unexpected speed_logits head size: " + size + " (expected 5)");
            } else if ("direction_logits".equals(semanticOutputNames[i]) && size != 5) {
                Log.w(TAG, "Unexpected direction_logits head size: " + size + " (expected 5)");
            }

            if (!outputNames[i].equals(semanticOutputNames[i])) {
                Log.i(TAG, "Output tensor mapped: raw='" + outputNames[i]
                        + "' -> semantic='" + semanticOutputNames[i] + "'");
            }
        }
    }

    private void initSplitHeadsInputMapping() {
        splitHeadsEncoderInputIndices = new int[SPLIT_ENCODER_OUTPUT_NAMES.length];
        Arrays.fill(splitHeadsEncoderInputIndices, -1);
        splitHeadsRawImageInputIndex = -1;
        splitHeadsMotionFrameInputIndex = -1;

        for (int i = 0; i < interpreter.getInputTensorCount(); i++) {
            String name = normalizedTensorName(interpreter.getInputTensor(i).name());
            boolean matched = false;
            for (int featureIdx = 0; featureIdx < SPLIT_ENCODER_OUTPUT_NAMES.length; featureIdx++) {
                if (SPLIT_ENCODER_OUTPUT_NAMES[featureIdx].equals(name)) {
                    splitHeadsEncoderInputIndices[featureIdx] = i;
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }
            if (isRawInputName(name)) {
                splitHeadsRawImageInputIndex = i;
            } else if (isMotionInputName(name)) {
                splitHeadsMotionFrameInputIndex = i;
            }
        }

        for (int i = 0; i < splitHeadsEncoderInputIndices.length; i++) {
            if (splitHeadsEncoderInputIndices[i] < 0) {
                throw new IllegalStateException("Missing split heads encoder input: " + SPLIT_ENCODER_OUTPUT_NAMES[i]);
            }
        }
        if (splitHeadsRawImageInputIndex < 0 || splitHeadsMotionFrameInputIndex < 0) {
            throw new IllegalStateException("Missing split heads raw_image or motion_frame input");
        }
    }

    private void initSplitBuffers() {
        splitEncoderInputBuffer = ByteBuffer.allocateDirect(1 * 3 * 1 * inputSize * inputSize * 4);
        splitEncoderInputBuffer.order(ByteOrder.nativeOrder());
        splitEncoderOutputs = new float[SPLIT_ENCODER_OUTPUT_NAMES.length][][][];
        splitHeadsEncoderInputs = new float[SPLIT_ENCODER_OUTPUT_NAMES.length][][][];
        for (int i = 0; i < SPLIT_ENCODER_OUTPUT_NAMES.length; i++) {
            int[] encoderShape = encoderInterpreter.getOutputTensor(i).shape();
            int[] headsShape = interpreter.getInputTensor(splitHeadsEncoderInputIndices[i]).shape();
            if (encoderShape.length != 3 || headsShape.length != 3) {
                throw new IllegalStateException("Unexpected split token tensor rank for feature "
                        + SPLIT_ENCODER_OUTPUT_NAMES[i]);
            }
            splitEncoderOutputs[i] = new float[encoderShape[0]][encoderShape[1]][encoderShape[2]];
            splitHeadsEncoderInputs[i] = new float[headsShape[0]][headsShape[1]][headsShape[2]];
        }
    }

    private void initTriggerHeadsInputMapping() {
        triggerHeadsEncoderInputIndices = new int[SPLIT_ENCODER_OUTPUT_NAMES.length];
        Arrays.fill(triggerHeadsEncoderInputIndices, -1);
        triggerHeadsRawImageInputIndex = -1;
        triggerHeadsMotionFrameInputIndex = -1;

        for (int i = 0; i < triggerHeadsInterpreter.getInputTensorCount(); i++) {
            String name = normalizedTensorName(triggerHeadsInterpreter.getInputTensor(i).name());
            boolean matched = false;
            for (int featureIdx = 0; featureIdx < SPLIT_ENCODER_OUTPUT_NAMES.length; featureIdx++) {
                if (SPLIT_ENCODER_OUTPUT_NAMES[featureIdx].equals(name)) {
                    triggerHeadsEncoderInputIndices[featureIdx] = i;
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }
            if (isRawInputName(name)) {
                triggerHeadsRawImageInputIndex = i;
            } else if (isMotionInputName(name)) {
                triggerHeadsMotionFrameInputIndex = i;
            }
        }

        if (triggerHeadsRawImageInputIndex < 0 || triggerHeadsMotionFrameInputIndex < 0) {
            throw new IllegalStateException("Missing direct trigger raw_image or prev_frame input");
        }
    }

    private void initTriggerHeadsBuffers() {
        triggerHeadsEncoderInputs = new float[SPLIT_ENCODER_OUTPUT_NAMES.length][][][];
        for (int i = 0; i < SPLIT_ENCODER_OUTPUT_NAMES.length; i++) {
            if (triggerHeadsEncoderInputIndices[i] < 0) {
                continue;
            }
            int[] headsShape = triggerHeadsInterpreter.getInputTensor(triggerHeadsEncoderInputIndices[i]).shape();
            if (headsShape.length != 3) {
                throw new IllegalStateException("Unexpected direct trigger token tensor rank for feature "
                        + SPLIT_ENCODER_OUTPUT_NAMES[i]);
            }
            triggerHeadsEncoderInputs[i] = new float[headsShape[0]][headsShape[1]][headsShape[2]];
        }

        int[] outputShape = triggerHeadsInterpreter.getOutputTensor(0).shape();
        if (outputShape.length != 2) {
            throw new IllegalStateException("Expected direct trigger logits output rank 2, got "
                    + Arrays.toString(outputShape));
        }
        triggerHeadsOutput = new float[outputShape[0]][outputShape[1]];
    }

    private ByteBuffer convertSceneInputToEncoderBuffer(ByteBuffer nhwcImage) {
        ByteBuffer src = nhwcImage.duplicate().order(ByteOrder.nativeOrder());
        src.rewind();
        FloatBuffer srcFloats = src.asFloatBuffer();
        splitEncoderInputBuffer.rewind();
        for (int channel = 0; channel < 3; channel++) {
            for (int y = 0; y < inputSize; y++) {
                for (int x = 0; x < inputSize; x++) {
                    int srcIndex = ((y * inputSize) + x) * 3 + channel;
                    splitEncoderInputBuffer.putFloat(srcFloats.get(srcIndex));
                }
            }
        }
        splitEncoderInputBuffer.rewind();
        return splitEncoderInputBuffer;
    }

    private String[] positionalOutputKeys() {
        if (usesPytorchIdentityOrder()) {
            return PYTORCH_FIELD_ORDER;
        }
        return CANONICAL_OUTPUT_KEYS;
    }

    private boolean usesPytorchIdentityOrder() {
        if (interpreter == null || interpreter.getOutputTensorCount() != PYTORCH_FIELD_ORDER.length) {
            return false;
        }
        try {
            return elementCount(interpreter.getOutputTensor(41).shape()) == 1
                    && elementCount(interpreter.getOutputTensor(42).shape()) == 4
                    && elementCount(interpreter.getOutputTensor(43).shape()) == 4;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to inspect Identity output ordering; using canonical mapping", e);
            return false;
        }
    }

    private static int elementCount(int[] shape) {
        int size = 1;
        for (int dim : shape) {
            size *= dim;
        }
        return size;
    }

    private void copyEncoderTokensIntoInput(float[][][] source, float[][][] target) {
        if (source.length != target.length) {
            throw new IllegalStateException("Encoder token batch mismatch: source=" + source.length
                    + " target=" + target.length);
        }
        int tokenCount = source[0].length;
        int featureDim = source[0][0].length;

        if (target[0].length == tokenCount && target[0][0].length == featureDim) {
            for (int batch = 0; batch < source.length; batch++) {
                for (int token = 0; token < tokenCount; token++) {
                    System.arraycopy(source[batch][token], 0, target[batch][token], 0, featureDim);
                }
            }
            return;
        }

        if (target[0].length == featureDim && target[0][0].length == tokenCount) {
            for (int batch = 0; batch < source.length; batch++) {
                for (int feature = 0; feature < featureDim; feature++) {
                    for (int token = 0; token < tokenCount; token++) {
                        target[batch][feature][token] = source[batch][token][feature];
                    }
                }
            }
            return;
        }

        throw new IllegalStateException("Unsupported encoder token layout: source="
                + Arrays.toString(new int[]{source.length, tokenCount, featureDim})
                + " target="
                + Arrays.toString(new int[]{target.length, target[0].length, target[0][0].length}));
    }

    /**
     * Load a TFLite model from the assets folder via memory-mapped file descriptor.
     */
    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        inputStream.close();
        return buffer;
    }

    /**
     * Fallback: copy asset to cache directory and return the File for Interpreter to load.
     */
    private File copyAssetToCache(Context context, String modelPath) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "tflite_models");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create cache dir: " + cacheDir);
        }
        String safeName = modelPath.replace('/', '_');
        File cached = new File(cacheDir, safeName);
        if (cached.exists() && cached.length() > 0) {
            return cached;
        }
        try (InputStream in = context.getAssets().open(modelPath);
             FileOutputStream out = new FileOutputStream(cached)) {
            byte[] buf = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            if (cached.exists()) cached.delete();
            throw e;
        }
        return cached;
    }
}
