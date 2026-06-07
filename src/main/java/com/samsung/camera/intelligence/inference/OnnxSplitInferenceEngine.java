package com.samsung.camera.intelligence.inference;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONNX Runtime engine for the split Round-3 FastViT encoder + heads pair.
 */
public final class OnnxSplitInferenceEngine implements InferenceEngine {

    private static final String TAG = "OnnxSplitInferenceEngine";

    private static final String ENCODER_INPUT_NAME = "image";
    private static final String POOLED_INPUT_NAME = "pooled";
    private static final String FINAL_MAP_INPUT_NAME = "final_map";

    private static final String[] HEAD_OUTPUT_NAMES = new String[] {
            "scene_type",
            "subject",
            "lighting",
            "motion",
            "binary_logits",
            "scalars",
            "subject_bbox",
            "feature_embedding",
    };

    private static final String[] BINARY_HEAD_NAMES = new String[] {
            "has_face",
            "has_text",
            "has_background_people",
            "has_moire",
            "is_repeating_motion",
            "is_repeating_motion_flow",
            "business_card",
            "wifi_credential",
            "finger_cover",
            "motion_blur_present",
            "text_region_prob",
            // Round-3.1: appended at the END to keep the first 11 indices
            // byte-compatible with previously exported assets.
            "is_group_selfie",
    };

    private static final String[] SCALAR_HEAD_NAMES = new String[] {
            "face_count",
            "subject_count",
            "flow_magnitude",
            "blur_level",
            "brightness_value",
            "sharpness",
            "sharpness_quality",
            "contrast_quality",
            "overall_quality",
            "scene_depth_layers",
            "visual_complexity",
            "text_region_patch_count",
            "speed_logits",
            "direction_logits",
            "motion_logits_flow",
            "motion_pred_flow",
    };

    private static final int DEFAULT_INPUT_SIZE = 320;

    private final OrtEnvironment environment;
    private final OrtSession encoderSession;
    private final OrtSession headsSession;
    private final int inputSize;
    private final FloatBuffer encoderInputBuffer;
    private final String[] outputNames;

    public OnnxSplitInferenceEngine(Context context, String encoderModelPath,
                                    String headsModelPath, boolean useGpu)
            throws IOException, OrtException {
        this(copyAssetToCache(context, encoderModelPath), copyAssetToCache(context, headsModelPath), useGpu);
    }

    public OnnxSplitInferenceEngine(String encoderModelFilePath,
                                    String headsModelFilePath,
                                    boolean useGpu) throws OrtException {
        this(encoderModelFilePath, headsModelFilePath, useGpu, false);
    }

    /**
     * @param preferNpuHeads when true the heads session attempts to run on the
     *     device NPU via the NNAPI execution provider (which routes to the
     *     Qualcomm Hexagon DSP on Snapdragon). Intended for the INT8 (A8W8)
     *     {@code multi_task_heads_int8.onnx}. Falls back to CPU if the provider
     *     is unavailable, so the call is always safe.
     */
    public OnnxSplitInferenceEngine(String encoderModelFilePath,
                                    String headsModelFilePath,
                                    boolean useGpu,
                                    boolean preferNpuHeads) throws OrtException {
        if (useGpu) {
            Log.w(TAG, "GPU execution is not configured for the Android ONNX path yet; using CPU");
        }
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions encoderOptions = new OrtSession.SessionOptions();
             OrtSession.SessionOptions headsOptions = new OrtSession.SessionOptions()) {
            if (preferNpuHeads) {
                applyNpuProvider(headsOptions);
            }
            encoderSession = environment.createSession(encoderModelFilePath, encoderOptions);
            headsSession = environment.createSession(headsModelFilePath, headsOptions);
        }
        inputSize = DEFAULT_INPUT_SIZE;
        encoderInputBuffer = ByteBuffer
                .allocateDirect(1 * 3 * inputSize * inputSize * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        outputNames = buildOutputNames();
        Log.i(TAG, "Loaded ONNX split pair: encoder=" + encoderModelFilePath
                + " heads=" + headsModelFilePath + " inputSize=" + inputSize
                + " npuHeads=" + preferNpuHeads);
    }

    /**
     * Best-effort: append the NNAPI execution provider so the quantized heads
     * can execute on the NPU/DSP. Resolved via reflection so the project still
     * compiles against ORT builds that ship without the NNAPI provider; on
     * those builds (and on devices lacking the accelerator) the heads simply
     * keep running on CPU.
     */
    private static void applyNpuProvider(OrtSession.SessionOptions options) {
        try {
            Class<?> flagsCls = Class.forName(
                    "ai.onnxruntime.OrtSession$SessionOptions$NNAPIFlags");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Enum cpuDisabled = Enum.valueOf((Class) flagsCls, "CPU_DISABLED");
            @SuppressWarnings({"unchecked", "rawtypes"})
            java.util.EnumSet flagSet = java.util.EnumSet.noneOf((Class) flagsCls);
            flagSet.add(cpuDisabled);
            java.lang.reflect.Method addNnapi =
                    OrtSession.SessionOptions.class.getMethod("addNnapi", java.util.EnumSet.class);
            addNnapi.invoke(options, flagSet);
            Log.i(TAG, "NNAPI execution provider enabled for INT8 heads");
        } catch (Throwable t) {
            Log.w(TAG, "NNAPI provider unavailable, heads run on CPU: " + t);
        }
    }

    @Override
    public Map<String, float[]> run(ByteBuffer inputBuffer) {
        ByteBuffer zeros = ByteBuffer.allocateDirect(inputBuffer.capacity()).order(ByteOrder.nativeOrder());
        return run(inputBuffer, inputBuffer, zeros);
    }

    @Override
    public Map<String, float[]> run(ByteBuffer image, ByteBuffer rawImage, ByteBuffer motionFrame) {
        try {
            fillEncoderInput(image);
            try (OnnxTensor imageTensor = OnnxTensor.createTensor(environment, encoderInputBuffer, new long[] {1, 3, inputSize, inputSize});
                 OrtSession.Result encoderResult = encoderSession.run(Map.of(ENCODER_INPUT_NAME, imageTensor))) {
                float[][] pooled = (float[][]) encoderResult.get(0).getValue();
                float[][][][] finalMap = (float[][][][]) encoderResult.get(1).getValue();
                try (OnnxTensor pooledTensor = OnnxTensor.createTensor(environment, pooled);
                     OnnxTensor finalMapTensor = OnnxTensor.createTensor(environment, finalMap);
                     OrtSession.Result headsResult = headsSession.run(Map.of(
                             POOLED_INPUT_NAME, pooledTensor,
                             FINAL_MAP_INPUT_NAME, finalMapTensor))) {
                    return decodeHeadsOutputs(headsResult);
                }
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed", e);
        }
    }

    @Override
    public int getInputCount() {
        return 3;
    }

    @Override
    public int getSegInputSize() {
        return inputSize;
    }

    @Override
    public boolean isCombinedSceneSegModel() {
        return false;
    }

    @Override
    public int getInputSize() {
        return inputSize;
    }

    @Override
    public String[] getOutputNames() {
        return outputNames.clone();
    }

    @Override
    public void close() {
        try {
            headsSession.close();
        } catch (OrtException e) {
            Log.w(TAG, "Failed closing heads session", e);
        }
        try {
            encoderSession.close();
        } catch (OrtException e) {
            Log.w(TAG, "Failed closing encoder session", e);
        }
    }

    private void fillEncoderInput(ByteBuffer nhwcImage) {
        FloatBuffer src = nhwcImage.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        encoderInputBuffer.rewind();
        for (int channel = 0; channel < 3; channel++) {
            for (int y = 0; y < inputSize; y++) {
                for (int x = 0; x < inputSize; x++) {
                    int srcIndex = ((y * inputSize) + x) * 3 + channel;
                    encoderInputBuffer.put(src.get(srcIndex));
                }
            }
        }
        encoderInputBuffer.rewind();
    }

    private Map<String, float[]> decodeHeadsOutputs(OrtSession.Result headsResult) throws OrtException {
        Map<String, float[]> outputs = new LinkedHashMap<>();

        float[][] sceneType = (float[][]) headsResult.get(0).getValue();
        float[][] subject = (float[][]) headsResult.get(1).getValue();
        float[][] lighting = (float[][]) headsResult.get(2).getValue();
        float[][] motion = (float[][]) headsResult.get(3).getValue();
        float[][] binaryLogits = (float[][]) headsResult.get(4).getValue();
        float[][] scalars = (float[][]) headsResult.get(5).getValue();
        float[][] subjectBbox = (float[][]) headsResult.get(6).getValue();
        float[][] featureEmbedding = (float[][]) headsResult.get(7).getValue();

        outputs.put("scene_type", sceneType[0].clone());
        outputs.put("subject", subject[0].clone());
        outputs.put("lighting", lighting[0].clone());
        outputs.put("motion", motion[0].clone());
        outputs.put("binary_logits", binaryLogits[0].clone());
        outputs.put("scalars", scalars[0].clone());
        outputs.put("subject_bbox", subjectBbox[0].clone());
        outputs.put("feature_embedding", featureEmbedding[0].clone());

        flattenVector(outputs, BINARY_HEAD_NAMES, binaryLogits[0]);
        flattenVector(outputs, SCALAR_HEAD_NAMES, scalars[0]);
        outputs.put("document_card_prob", outputs.get("business_card"));
        return outputs;
    }

    private static void flattenVector(Map<String, float[]> outputs, String[] names, float[] values) {
        int n = Math.min(names.length, values.length);
        for (int i = 0; i < n; i++) {
            outputs.put(names[i], new float[] {values[i]});
        }
    }

    private static String[] buildOutputNames() {
        String[] names = Arrays.copyOf(HEAD_OUTPUT_NAMES,
                HEAD_OUTPUT_NAMES.length + BINARY_HEAD_NAMES.length + SCALAR_HEAD_NAMES.length + 1);
        int offset = HEAD_OUTPUT_NAMES.length;
        System.arraycopy(BINARY_HEAD_NAMES, 0, names, offset, BINARY_HEAD_NAMES.length);
        offset += BINARY_HEAD_NAMES.length;
        System.arraycopy(SCALAR_HEAD_NAMES, 0, names, offset, SCALAR_HEAD_NAMES.length);
        names[names.length - 1] = "document_card_prob";
        return names;
    }

    private static String copyAssetToCache(Context context, String assetPath) throws IOException {
        AssetManager assetManager = context.getAssets();
        File outFile = new File(context.getCacheDir(), assetPath.replace('/', '_'));
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create cache dir for " + outFile.getAbsolutePath());
        }
        try (InputStream in = assetManager.open(assetPath);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
        return outFile.getAbsolutePath();
    }
}
