package com.samsung.camera.intelligence.inference;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Phase 0.3 \u2014 wrapper around the dedicated U\u00b2-Netp salient object
 * detection model ({@code u2netp_int8.tflite}).
 *
 * Loads from {@code assets/u2netp_int8.tflite} when present; otherwise
 * {@link #isAvailable()} returns false and {@link #detect(Bitmap)} returns
 * null so the rest of the pipeline silently falls back to the main backbone's
 * {@code subject_bbox} head.
 */
public class SubjectDetectorEngine {

    private static final String TAG = "SubjectDetectorEngine";
    private static final String MODEL_ASSET = "u2netp_int8.tflite";
    private static final int INPUT_SIZE = 320;

    private final Context context;
    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private boolean available;

    public static class SaliencyResult {
        /** Subject bounding box in normalized [x, y, w, h]. */
        public final float[] bboxNorm;
        /** 0..1 fraction of pixels above the saliency threshold. */
        public final float fillRatio;
        /** Raw mask bytes laid out row-major, size = maskW * maskH. */
        public final byte[] maskBytes;
        public final int maskW;
        public final int maskH;
        public final float[] subjectCenterNorm;

        public SaliencyResult(float[] bboxNorm, float fillRatio,
                              byte[] maskBytes, int maskW, int maskH,
                              float[] subjectCenterNorm) {
            this.bboxNorm = bboxNorm;
            this.fillRatio = fillRatio;
            this.maskBytes = maskBytes;
            this.maskW = maskW;
            this.maskH = maskH;
            this.subjectCenterNorm = subjectCenterNorm;
        }
    }

    public SubjectDetectorEngine(Context context) {
        this.context = context;
        loadIfAvailable();
    }

    public boolean isAvailable() { return available; }

    public synchronized void close() {
        try {
            if (interpreter != null) interpreter.close();
            if (gpuDelegate != null) gpuDelegate.close();
        } catch (Throwable ignore) { /* defensive */ }
        interpreter = null;
        gpuDelegate = null;
        available = false;
    }

    private void loadIfAvailable() {
        try {
            // Probe asset existence first so we can fail silently in builds
            // that ship without the SOD model.
            try (AssetFileDescriptor afd = context.getAssets().openFd(MODEL_ASSET)) {
                FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
                FileChannel ch = fis.getChannel();
                MappedByteBuffer modelBuf = ch.map(FileChannel.MapMode.READ_ONLY,
                        afd.getStartOffset(), afd.getDeclaredLength());

                Interpreter.Options opts = new Interpreter.Options();
                CompatibilityList compat = new CompatibilityList();
                if (compat.isDelegateSupportedOnThisDevice()) {
                    try {
                        gpuDelegate = new GpuDelegate();
                        opts.addDelegate(gpuDelegate);
                    } catch (Throwable t) {
                        Log.w(TAG, "GPU delegate unavailable, using CPU XNNPACK", t);
                        gpuDelegate = null;
                        opts.setUseXNNPACK(true);
                        opts.setNumThreads(2);
                    }
                } else {
                    opts.setUseXNNPACK(true);
                    opts.setNumThreads(2);
                }
                interpreter = new Interpreter(modelBuf, opts);
                available = true;
                Log.i(TAG, "Loaded " + MODEL_ASSET);
            }
        } catch (IOException e) {
            // Asset missing is the expected fallback path.
            Log.i(TAG, MODEL_ASSET + " not present in assets \u2014 falling back to main head.");
            available = false;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to initialize SubjectDetectorEngine", t);
            available = false;
        }
    }

    /**
     * Run U\u00b2-Netp on the given frame. Returns null when the engine is not
     * loaded or the input bitmap is unusable.
     */
    public SaliencyResult detect(Bitmap frame) {
        if (!available || interpreter == null || frame == null) return null;
        try {
            Bitmap resized = Bitmap.createScaledBitmap(frame, INPUT_SIZE, INPUT_SIZE, true);

            ByteBuffer in = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
            for (int p : pixels) {
                in.putFloat(((p >> 16) & 0xFF) / 255f);
                in.putFloat(((p >> 8) & 0xFF) / 255f);
                in.putFloat((p & 0xFF) / 255f);
            }
            in.rewind();

            float[][][][] out = new float[1][INPUT_SIZE][INPUT_SIZE][1];
            // U²-Netp emits 7 sigmoid maps (d0..d6); the first one (d0) is the
            // fused full-resolution mask. Use runForMultipleInputsOutputs so
            // we don't crash on the 6 unused outputs.
            try {
                java.util.HashMap<Integer, Object> outputs = new java.util.HashMap<>();
                outputs.put(0, out);
                int extra = interpreter.getOutputTensorCount() - 1;
                float[][][][][] dummy = extra > 0 ? new float[extra][1][INPUT_SIZE][INPUT_SIZE][1] : null;
                for (int i = 0; i < extra; i++) outputs.put(i + 1, dummy[i]);
                interpreter.runForMultipleInputsOutputs(new Object[]{in}, outputs);
            } catch (Throwable singleOutFallback) {
                interpreter.run(in, out);
            }
            if (resized != frame) resized.recycle();

            // Threshold + bbox.
            int minX = INPUT_SIZE, minY = INPUT_SIZE, maxX = -1, maxY = -1;
            float sumX = 0, sumY = 0;
            int countAbove = 0;
            byte[] maskBytes = new byte[INPUT_SIZE * INPUT_SIZE];
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    float v = out[0][y][x][0];
                    if (v > 0.5f) {
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                        sumX += x;
                        sumY += y;
                        countAbove++;
                        maskBytes[y * INPUT_SIZE + x] = (byte) 255;
                    }
                }
            }
            if (countAbove < (int) (0.005f * INPUT_SIZE * INPUT_SIZE)) {
                return null;
            }
            float bw = (maxX - minX + 1) / (float) INPUT_SIZE;
            float bh = (maxY - minY + 1) / (float) INPUT_SIZE;
            float bx = minX / (float) INPUT_SIZE;
            float by = minY / (float) INPUT_SIZE;
            float[] bbox = new float[]{bx, by, bw, bh};
            float[] center = new float[]{
                    (sumX / countAbove) / INPUT_SIZE,
                    (sumY / countAbove) / INPUT_SIZE
            };
            float fillRatio = countAbove / (float) (INPUT_SIZE * INPUT_SIZE);
            return new SaliencyResult(bbox, fillRatio, maskBytes, INPUT_SIZE, INPUT_SIZE, center);
        } catch (Throwable t) {
            Log.w(TAG, "SOD inference failed", t);
            return null;
        }
    }
}
