package com.samsung.camera.intelligence.inference;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight wrapper around the MobileSAM TFLite encoder + decoder pair.
 *
 * <p>MobileSAM (Zhang et al., Apache-2.0) is a distilled variant of Segment
 * Anything that uses a tiny ViT image encoder. We run it as <em>zero-shot</em>
 * promptable segmentation for post-capture defect refinement: a heuristic
 * detector (or a person/face detector) emits a coarse box / point, and SAM
 * lifts that to a clean pixel mask.
 *
 * <p>Encoder embeddings are cached per source bitmap (keyed by
 * {@link System#identityHashCode(Object)}), so when several defects appear in
 * the same photo only one encoder pass is paid.
 *
 * <h3>Expected TFLite signatures</h3>
 * Encoder ({@code mobilesam_encoder.tflite}):
 * <pre>
 *   input  "image"         float32 [1, ENCODER_SIZE, ENCODER_SIZE, 3]   (RGB, 0..1, mean/std applied externally if needed)
 *   output "image_embedding" float32 [1, 64, 64, 256]
 * </pre>
 *
 * Decoder ({@code mobilesam_decoder.tflite}) supports both the Qualcomm
 * AI Hub point-only signature and the older onnx2tf signature:
 * <pre>
 *   Qualcomm inputs:  image_embeddings [1,64,64,256], point_coords [1,1,2], point_labels [1,1]
 *   Qualcomm outputs: masks [1,256,256,1], scores [1,1]
 * </pre>
 * Legacy onnx2tf signature:
 * <pre>
 *   inputs:
 *     "image_embedding"   float32 [1, 64, 64, 256]
 *     "point_coords"      float32 [1, 2, N]   (xy in encoder pixel space, last point is the BOX corner sentinel when used)
 *     "point_labels"      float32 [1, N]      (1 = fg, 0 = bg, 2 = top-left box, 3 = bottom-right box, -1 = padding)
 *     "mask_input"        float32 [1, 256, 256, 1] (zeros)
 *     "has_mask_input"    float32 [1]         (0)
 *   outputs:
 *     "low_res_masks"     float32 [1, K, 256, 256]   (logits, K candidate masks)
 *     "iou_predictions"   float32 [1, K]
 * </pre>
 *
 * <p>The exact tensor names from the converter often have onnx2tf-style
 * suffixes (e.g. {@code serving_default_image:0}); to stay robust the runner
 * resolves tensor indices from names/shapes at load time.
 */
public class MobileSamRunner {

    private static final String TAG = "MobileSamRunner";

    /** Encoder input resolution (MobileSAM uses 1024×1024). */
    public static final int ENCODER_SIZE = 1024;

    /** Encoder output spatial size. */
    public static final int EMBED_SIZE = 64;

    /** Encoder output channel count. */
    public static final int EMBED_CHANNELS = 256;

    /** Decoder low-res mask side. */
    public static final int MASK_SIZE = 256;

    /** Number of candidate masks the decoder returns. */
    private static final int NUM_MASK_CANDIDATES = 4;

    private final Context context;
    private final String encoderAsset;
    private final String decoderAsset;
    private final int maxPrompts;

    private Interpreter encoder;
    private Interpreter decoder;
    private boolean loaded = false;
    private int decoderImageInput = 1;
    private int decoderPointCoordsInput = 3;
    private int decoderPointLabelsInput = 0;
    private int decoderMaskInput = 2;
    private int decoderHasMaskInput = 4;
    private int decoderMasksOutput = 1;
    private int decoderIouOutput = 0;
    private boolean qualcommPointDecoder = false;
    private int originalWidth = 0;
    private int originalHeight = 0;
    private int resizedWidth = ENCODER_SIZE;
    private int resizedHeight = ENCODER_SIZE;

    /** Cached encoder embedding for the most recent bitmap (identity-keyed). */
    private int cachedBitmapId = 0;
    private float[][][][] cachedEmbedding;

    public MobileSamRunner(Context context) {
        this(context, "models/mobilesam_encoder.tflite",
                "models/mobilesam_decoder.tflite", 16);
    }

    public MobileSamRunner(Context context,
                           String encoderAsset,
                           String decoderAsset,
                           int maxPrompts) {
        this.context = context;
        this.encoderAsset = encoderAsset;
        this.decoderAsset = decoderAsset;
        this.maxPrompts = maxPrompts;
    }

    public synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            encoder = new Interpreter(loadModel(encoderAsset));
            decoder = new Interpreter(loadModel(decoderAsset));
            resolveDecoderSignature();
            loaded = true;
            Log.i(TAG, "MobileSAM loaded (" + encoderAsset + " + " + decoderAsset + ")");
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "MobileSAM unavailable: " + e.getMessage());
            encoder = null;
            decoder = null;
            return false;
        }
    }

    public boolean isAvailable() {
        return ensureLoaded();
    }

    /**
     * Run the encoder once per bitmap and cache the embedding. Idempotent for
     * the same bitmap reference.
     */
    private synchronized float[][][][] embed(Bitmap bitmap) {
        int id = System.identityHashCode(bitmap);
        if (id == cachedBitmapId && cachedEmbedding != null) {
            return cachedEmbedding;
        }
        if (!ensureLoaded()) return null;

        ByteBuffer input = bitmapToSamInputBuffer(bitmap);
        float[][][][] embedding = new float[1][EMBED_SIZE][EMBED_SIZE][EMBED_CHANNELS];
        try {
            encoder.run(input, embedding);
        } catch (RuntimeException e) {
            Log.w(TAG, "MobileSAM encoder failed: " + e.getMessage());
            return null;
        }

        cachedBitmapId = id;
        cachedEmbedding = embedding;
        return embedding;
    }

    /**
     * Segment using a normalized bounding box prompt.
     *
     * @param bitmap   source image
     * @param boxNorm  normalized [0,1] xywh box in image space
     * @return best mask resampled to original {@code bitmap} size, values in
     *         [0,1]; or {@code null} on failure.
     */
    public float[][] runWithBox(Bitmap bitmap, RectF boxNorm) {
        if (ensureLoaded() && qualcommPointDecoder) {
            return runWithPoint(bitmap,
                    (boxNorm.left + boxNorm.right) * 0.5f,
                    (boxNorm.top + boxNorm.bottom) * 0.5f);
        }
        float[][][][] embedding = embed(bitmap);
        if (embedding == null) return null;

        // Two-point box prompt in encoder pixel space (top-left + bottom-right).
        float x1 = clamp(boxNorm.left, 0f, 1f) * ENCODER_SIZE;
        float y1 = clamp(boxNorm.top, 0f, 1f) * ENCODER_SIZE;
        float x2 = clamp(boxNorm.right, 0f, 1f) * ENCODER_SIZE;
        float y2 = clamp(boxNorm.bottom, 0f, 1f) * ENCODER_SIZE;

        return runDecoder(bitmap, embedding,
                new float[][]{{x1, y1}, {x2, y2}},
                new float[]{2f, 3f});
    }

    /**
     * Segment using a single foreground point prompt.
     *
     * @param bitmap   source image
     * @param xyNorm   normalized [0,1] (x, y) point
     */
    public float[][] runWithPoint(Bitmap bitmap, float xyNormX, float xyNormY) {
        float[][][][] embedding = embed(bitmap);
        if (embedding == null) return null;

        float scale = ENCODER_SIZE / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
        float x = clamp(xyNormX, 0f, 1f) * bitmap.getWidth() * scale;
        float y = clamp(xyNormY, 0f, 1f) * bitmap.getHeight() * scale;
        return runDecoder(bitmap, embedding,
                new float[][]{{x, y}},
                new float[]{1f});
    }

    /** Combined box + interior point — usually highest-quality. */
    public float[][] runWithBoxAndPoint(Bitmap bitmap, RectF boxNorm,
                                        float pointXNorm, float pointYNorm) {
        if (ensureLoaded() && qualcommPointDecoder) {
            return runWithPoint(bitmap, pointXNorm, pointYNorm);
        }
        float[][][][] embedding = embed(bitmap);
        if (embedding == null) return null;

        float x1 = clamp(boxNorm.left, 0f, 1f) * ENCODER_SIZE;
        float y1 = clamp(boxNorm.top, 0f, 1f) * ENCODER_SIZE;
        float x2 = clamp(boxNorm.right, 0f, 1f) * ENCODER_SIZE;
        float y2 = clamp(boxNorm.bottom, 0f, 1f) * ENCODER_SIZE;
        float px = clamp(pointXNorm, 0f, 1f) * ENCODER_SIZE;
        float py = clamp(pointYNorm, 0f, 1f) * ENCODER_SIZE;
        return runDecoder(bitmap, embedding,
                new float[][]{{x1, y1}, {x2, y2}, {px, py}},
                new float[]{2f, 3f, 1f});
    }

    private float[][] runDecoder(Bitmap bitmap,
                                 float[][][][] embedding,
                                 float[][] points,
                                 float[] labels) {
        if (decoder == null) return null;

        if (qualcommPointDecoder) {
            float[][][] coords = new float[1][1][2];
            coords[0][0][0] = points.length > 0 ? points[0][0] : bitmap.getWidth() * 0.5f;
            coords[0][0][1] = points.length > 0 ? points[0][1] : bitmap.getHeight() * 0.5f;
            float[][] lbl = new float[][]{{1f}};

            Map<Integer, Object> outputs = new HashMap<>();
            float[][][][] lowResMasks = new float[1][MASK_SIZE][MASK_SIZE][1];
            float[][] scores = new float[1][1];
            outputs.put(decoderMasksOutput, lowResMasks);
            outputs.put(decoderIouOutput, scores);

            Object[] inputs = new Object[decoder.getInputTensorCount()];
            inputs[decoderImageInput] = embedding;
            inputs[decoderPointCoordsInput] = coords;
            inputs[decoderPointLabelsInput] = lbl;

            try {
                decoder.runForMultipleInputsOutputs(inputs, outputs);
            } catch (RuntimeException e) {
                Log.w(TAG, "MobileSAM Qualcomm decoder failed: " + e.getMessage());
                return null;
            }
            return resampleQualcommSigmoid(lowResMasks[0], bitmap.getWidth(), bitmap.getHeight(),
                    resizedWidth, resizedHeight);
        }

        int n = Math.min(points.length, maxPrompts);

        // point_coords [1, 2, maxPrompts] as exported by onnx2tf.
        float[][][] coords = new float[1][2][maxPrompts];
        float[][] lbl = new float[1][maxPrompts];
        for (int i = 0; i < maxPrompts; i++) {
            if (i < n) {
                coords[0][0][i] = points[i][0];
                coords[0][1][i] = points[i][1];
                lbl[0][i] = labels[i];
            } else {
                coords[0][0][i] = 0f;
                coords[0][1][i] = 0f;
                lbl[0][i] = -1f;
            }
        }

        float[][][][] maskInput = new float[1][MASK_SIZE][MASK_SIZE][1];
        float[] hasMask = new float[]{0f};

        Map<Integer, Object> outputs = new HashMap<>();
        float[][][][] lowResMasks = new float[1][NUM_MASK_CANDIDATES][MASK_SIZE][MASK_SIZE];
        float[][] iouPredictions = new float[1][NUM_MASK_CANDIDATES];
        outputs.put(decoderMasksOutput, lowResMasks);
        outputs.put(decoderIouOutput, iouPredictions);

        Object[] inputs = new Object[decoder.getInputTensorCount()];
        inputs[decoderImageInput] = embedding;
        inputs[decoderPointCoordsInput] = coords;
        inputs[decoderPointLabelsInput] = lbl;
        inputs[decoderMaskInput] = maskInput;
        inputs[decoderHasMaskInput] = hasMask;

        try {
            decoder.runForMultipleInputsOutputs(inputs, outputs);
        } catch (RuntimeException e) {
            Log.w(TAG, "MobileSAM decoder failed: " + e.getMessage());
            return null;
        }

        // Select the highest-IoU candidate.
        int best = 0;
        float bestIou = iouPredictions[0][0];
        for (int k = 1; k < NUM_MASK_CANDIDATES; k++) {
            if (iouPredictions[0][k] > bestIou) {
                bestIou = iouPredictions[0][k];
                best = k;
            }
        }

        return resampleSigmoid(lowResMasks[0][best], bitmap.getWidth(), bitmap.getHeight());
    }

    /**
     * Bilinear resample a low-res logit map to {@code (outW, outH)} and apply
     * a sigmoid so the returned mask has values in [0, 1].
     */
    private static float[][] resampleSigmoid(float[][] lowRes, int outW, int outH) {
        int srcH = lowRes.length;
        int srcW = lowRes[0].length;
        float[][] dst = new float[outH][outW];
        float scaleX = (float) srcW / outW;
        float scaleY = (float) srcH / outH;
        for (int y = 0; y < outH; y++) {
            float fy = y * scaleY;
            int y0 = Math.min((int) fy, srcH - 1);
            int y1 = Math.min(y0 + 1, srcH - 1);
            float wy = fy - y0;
            for (int x = 0; x < outW; x++) {
                float fx = x * scaleX;
                int x0 = Math.min((int) fx, srcW - 1);
                int x1 = Math.min(x0 + 1, srcW - 1);
                float wx = fx - x0;
                float a = lowRes[y0][x0];
                float b = lowRes[y0][x1];
                float c = lowRes[y1][x0];
                float d = lowRes[y1][x1];
                float top = a + (b - a) * wx;
                float bot = c + (d - c) * wx;
                float v = top + (bot - top) * wy;
                dst[y][x] = (float) (1.0 / (1.0 + Math.exp(-v)));
            }
        }
        return dst;
    }

    /**
     * Qualcomm decoder returns 256×256 logits for a 1024×1024 padded SAM canvas.
     * This maps output pixels through the cropped resized image region, then
     * samples the low-res logits and applies sigmoid.
     */
    private static float[][] resampleQualcommSigmoid(float[][][] lowRes, int outW, int outH,
                                                     int resizedW, int resizedH) {
        float[][] dst = new float[outH][outW];
        for (int y = 0; y < outH; y++) {
            float canvasY = (y + 0.5f) * resizedH / Math.max(1, outH);
            float lowY = canvasY * MASK_SIZE / ENCODER_SIZE;
            int y0 = Math.min((int) lowY, MASK_SIZE - 1);
            int y1 = Math.min(y0 + 1, MASK_SIZE - 1);
            float wy = lowY - y0;
            for (int x = 0; x < outW; x++) {
                float canvasX = (x + 0.5f) * resizedW / Math.max(1, outW);
                float lowX = canvasX * MASK_SIZE / ENCODER_SIZE;
                int x0 = Math.min((int) lowX, MASK_SIZE - 1);
                int x1 = Math.min(x0 + 1, MASK_SIZE - 1);
                float wx = lowX - x0;
                float a = lowRes[y0][x0][0];
                float b = lowRes[y0][x1][0];
                float c = lowRes[y1][x0][0];
                float d = lowRes[y1][x1][0];
                float top = a + (b - a) * wx;
                float bot = c + (d - c) * wx;
                float v = top + (bot - top) * wy;
                dst[y][x] = (float) (1.0 / (1.0 + Math.exp(-v)));
            }
        }
        return dst;
    }

    /** Resize longest side to 1024 and pad bottom/right with SAM pixel mean. */
    private ByteBuffer bitmapToSamInputBuffer(Bitmap bitmap) {
        originalWidth = bitmap.getWidth();
        originalHeight = bitmap.getHeight();
        float scale = ENCODER_SIZE / (float) Math.max(originalWidth, originalHeight);
        resizedWidth = Math.max(1, Math.round(originalWidth * scale));
        resizedHeight = Math.max(1, Math.round(originalHeight * scale));
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true);
        Bitmap padded = Bitmap.createBitmap(ENCODER_SIZE, ENCODER_SIZE, Bitmap.Config.ARGB_8888);
        padded.eraseColor(Color.rgb(124, 116, 104));
        Canvas canvas = new Canvas(padded);
        canvas.drawBitmap(resized, 0f, 0f, null);
        ByteBuffer buffer = bitmapToFloatBuffer(padded);
        if (resized != bitmap) resized.recycle();
        padded.recycle();
        return buffer;
    }

    private static ByteBuffer bitmapToFloatBuffer(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(4 * h * w * 3);
        buf.order(ByteOrder.nativeOrder());
        FloatBuffer f = buf.asFloatBuffer();

        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int p : pixels) {
            f.put(((p >> 16) & 0xFF) / 255f);
            f.put(((p >> 8) & 0xFF) / 255f);
            f.put((p & 0xFF) / 255f);
        }
        return buf;
    }

    private MappedByteBuffer loadModel(String pathOrAsset) throws IOException {
        File f = new File(pathOrAsset);
        if (f.exists()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                return fis.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, f.length());
            }
        }
        AssetFileDescriptor afd = context.getAssets().openFd(pathOrAsset);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel ch = fis.getChannel();
            return ch.map(FileChannel.MapMode.READ_ONLY,
                    afd.getStartOffset(), afd.getDeclaredLength());
        } finally {
            afd.close();
        }
    }

    private void resolveDecoderSignature() {
        qualcommPointDecoder = decoder.getInputTensorCount() == 3;
        if (qualcommPointDecoder) {
            decoderImageInput = 0;
            decoderPointCoordsInput = 1;
            decoderPointLabelsInput = 2;
            decoderMasksOutput = 0;
            decoderIouOutput = 1;
        }
        for (int i = 0; i < decoder.getInputTensorCount(); i++) {
            String name = decoder.getInputTensor(i).name().toLowerCase();
            if (name.contains("image_embedding") || name.contains("image_embeddings")) {
                decoderImageInput = i;
            } else if (name.contains("point_coords")) {
                decoderPointCoordsInput = i;
            } else if (name.contains("point_labels")) {
                decoderPointLabelsInput = i;
            } else if (name.contains("has_mask_input")) {
                decoderHasMaskInput = i;
            } else if (name.contains("mask_input")) {
                decoderMaskInput = i;
            }
        }
        for (int i = 0; i < decoder.getOutputTensorCount(); i++) {
            String name = decoder.getOutputTensor(i).name().toLowerCase();
            int[] shape = decoder.getOutputTensor(i).shape();
            if (name.contains("score") || (shape.length == 2
                    && (shape[shape.length - 1] == NUM_MASK_CANDIDATES || shape[shape.length - 1] == 1))) {
                decoderIouOutput = i;
            } else if (name.contains("mask")) {
                decoderMasksOutput = i;
            } else if (shape.length == 4 && shape[1] == NUM_MASK_CANDIDATES
                    && shape[2] == MASK_SIZE && shape[3] == MASK_SIZE) {
                decoderMasksOutput = i;
            } else if (shape.length == 4 && shape[1] == MASK_SIZE && shape[2] == MASK_SIZE) {
                decoderMasksOutput = i;
            }
        }
        Log.i(TAG, "Decoder signature inputs image=" + decoderImageInput
                + " coords=" + decoderPointCoordsInput
                + " labels=" + decoderPointLabelsInput
                + " mask=" + decoderMaskInput
                + " hasMask=" + decoderHasMaskInput
                + " outputs masks=" + decoderMasksOutput
                + " iou=" + decoderIouOutput
                + " qualcomm=" + qualcommPointDecoder);
    }

    public synchronized void close() {
        if (encoder != null) { encoder.close(); encoder = null; }
        if (decoder != null) { decoder.close(); decoder = null; }
        cachedEmbedding = null;
        cachedBitmapId = 0;
        loaded = false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Lightweight description for debug logs / about screens. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("encoder", encoderAsset);
        m.put("decoder", decoderAsset);
        m.put("encoder_size", ENCODER_SIZE);
        m.put("max_prompts", maxPrompts);
        m.put("loaded", loaded);
        return m;
    }
}
