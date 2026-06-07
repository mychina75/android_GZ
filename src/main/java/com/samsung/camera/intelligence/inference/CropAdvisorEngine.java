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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 0.3 \u2014 wrapper around the GAIC v2 (Grid-Anchor-based Image
 * Cropping, MobileNetV2 backbone) aesthetic crop scorer
 * ({@code gaic_mbv2_fp16.tflite}).
 *
 * Produces a ranked list of crop candidates with aesthetic scores. Falls
 * back to no-op when the asset is missing.
 *
 * <b>Throttling.</b> Aesthetic crop search is the most expensive sub-task in
 * the pipeline; callers should only invoke {@link #suggest(Bitmap, int)}
 * when {@link #shouldRecompute(int, float)} is true.
 */
public class CropAdvisorEngine {

    private static final String TAG = "CropAdvisorEngine";
    private static final String MODEL_ASSET = "gaic_mbv2_fp16.tflite";
    private static final int INPUT_SIZE = 256;

    // ImageNet mean/std (the GAIC v2 training preprocessing).
    private static final float[] RGB_MEAN = { 0.485f, 0.456f, 0.406f };
    private static final float[] RGB_STD  = { 0.229f, 0.224f, 0.225f };

    // The exported model bakes a constant 90-anchor grid into its graph and
    // returns one score per anchor. The xywh coordinates below are the same
    // anchor list (normalized to [0, 1] of the 256x256 input image).
    // Generated from croppingDataset.generate_bboxes(bins=12) for a square
    // 256-pixel input — keep in lock-step with build_gaic_mobile.py.
    private static final float[][] ANCHORS_XYWH = new float[][]{
        {0.04167f,0.04167f,0.75000f,0.66667f},
        {0.04167f,0.04167f,0.83333f,0.66667f},
        {0.04167f,0.04167f,0.91667f,0.66667f},
        {0.04167f,0.04167f,0.66667f,0.75000f},
        {0.04167f,0.04167f,0.75000f,0.75000f},
        {0.04167f,0.04167f,0.83333f,0.75000f},
        {0.04167f,0.04167f,0.91667f,0.75000f},
        {0.04167f,0.04167f,0.66667f,0.83333f},
        {0.04167f,0.04167f,0.75000f,0.83333f},
        {0.04167f,0.04167f,0.83333f,0.83333f},
        {0.04167f,0.04167f,0.91667f,0.83333f},
        {0.04167f,0.04167f,0.66667f,0.91667f},
        {0.04167f,0.04167f,0.75000f,0.91667f},
        {0.04167f,0.04167f,0.83333f,0.91667f},
        {0.04167f,0.04167f,0.91667f,0.91667f},
        {0.12500f,0.04167f,0.75000f,0.66667f},
        {0.12500f,0.04167f,0.83333f,0.66667f},
        {0.12500f,0.04167f,0.66667f,0.75000f},
        {0.12500f,0.04167f,0.75000f,0.75000f},
        {0.12500f,0.04167f,0.83333f,0.75000f},
        {0.12500f,0.04167f,0.66667f,0.83333f},
        {0.12500f,0.04167f,0.75000f,0.83333f},
        {0.12500f,0.04167f,0.83333f,0.83333f},
        {0.12500f,0.04167f,0.58333f,0.91667f},
        {0.12500f,0.04167f,0.66667f,0.91667f},
        {0.12500f,0.04167f,0.75000f,0.91667f},
        {0.12500f,0.04167f,0.83333f,0.91667f},
        {0.20833f,0.04167f,0.75000f,0.66667f},
        {0.20833f,0.04167f,0.66667f,0.75000f},
        {0.20833f,0.04167f,0.75000f,0.75000f},
        {0.20833f,0.04167f,0.66667f,0.83333f},
        {0.20833f,0.04167f,0.75000f,0.83333f},
        {0.20833f,0.04167f,0.58333f,0.91667f},
        {0.20833f,0.04167f,0.66667f,0.91667f},
        {0.20833f,0.04167f,0.75000f,0.91667f},
        {0.29167f,0.04167f,0.66667f,0.75000f},
        {0.29167f,0.04167f,0.66667f,0.83333f},
        {0.29167f,0.04167f,0.58333f,0.91667f},
        {0.29167f,0.04167f,0.66667f,0.91667f},
        {0.04167f,0.12500f,0.91667f,0.58333f},
        {0.04167f,0.12500f,0.75000f,0.66667f},
        {0.04167f,0.12500f,0.83333f,0.66667f},
        {0.04167f,0.12500f,0.91667f,0.66667f},
        {0.04167f,0.12500f,0.66667f,0.75000f},
        {0.04167f,0.12500f,0.75000f,0.75000f},
        {0.04167f,0.12500f,0.83333f,0.75000f},
        {0.04167f,0.12500f,0.91667f,0.75000f},
        {0.04167f,0.12500f,0.66667f,0.83333f},
        {0.04167f,0.12500f,0.75000f,0.83333f},
        {0.04167f,0.12500f,0.83333f,0.83333f},
        {0.04167f,0.12500f,0.91667f,0.83333f},
        {0.12500f,0.12500f,0.75000f,0.66667f},
        {0.12500f,0.12500f,0.83333f,0.66667f},
        {0.12500f,0.12500f,0.66667f,0.75000f},
        {0.12500f,0.12500f,0.75000f,0.75000f},
        {0.12500f,0.12500f,0.83333f,0.75000f},
        {0.12500f,0.12500f,0.66667f,0.83333f},
        {0.12500f,0.12500f,0.75000f,0.83333f},
        {0.12500f,0.12500f,0.83333f,0.83333f},
        {0.20833f,0.12500f,0.75000f,0.66667f},
        {0.20833f,0.12500f,0.66667f,0.75000f},
        {0.20833f,0.12500f,0.75000f,0.75000f},
        {0.20833f,0.12500f,0.66667f,0.83333f},
        {0.20833f,0.12500f,0.75000f,0.83333f},
        {0.29167f,0.12500f,0.66667f,0.75000f},
        {0.29167f,0.12500f,0.66667f,0.83333f},
        {0.04167f,0.20833f,0.91667f,0.58333f},
        {0.04167f,0.20833f,0.75000f,0.66667f},
        {0.04167f,0.20833f,0.83333f,0.66667f},
        {0.04167f,0.20833f,0.91667f,0.66667f},
        {0.04167f,0.20833f,0.66667f,0.75000f},
        {0.04167f,0.20833f,0.75000f,0.75000f},
        {0.04167f,0.20833f,0.83333f,0.75000f},
        {0.04167f,0.20833f,0.91667f,0.75000f},
        {0.12500f,0.20833f,0.75000f,0.66667f},
        {0.12500f,0.20833f,0.83333f,0.66667f},
        {0.12500f,0.20833f,0.66667f,0.75000f},
        {0.12500f,0.20833f,0.75000f,0.75000f},
        {0.12500f,0.20833f,0.83333f,0.75000f},
        {0.20833f,0.20833f,0.75000f,0.66667f},
        {0.20833f,0.20833f,0.66667f,0.75000f},
        {0.20833f,0.20833f,0.75000f,0.75000f},
        {0.29167f,0.20833f,0.66667f,0.75000f},
        {0.04167f,0.29167f,0.91667f,0.58333f},
        {0.04167f,0.29167f,0.75000f,0.66667f},
        {0.04167f,0.29167f,0.83333f,0.66667f},
        {0.04167f,0.29167f,0.91667f,0.66667f},
        {0.12500f,0.29167f,0.75000f,0.66667f},
        {0.12500f,0.29167f,0.83333f,0.66667f},
        {0.20833f,0.29167f,0.75000f,0.66667f}
    };
    private static final int NUM_ANCHORS = ANCHORS_XYWH.length;

    private final Context context;
    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private boolean available;

    private int lastFrame = Integer.MIN_VALUE;
    private float lastSaliencyIou = 1f;

    public static class CropCandidate implements Comparable<CropCandidate> {
        public final float[] cropNorm; // [x, y, w, h]
        public final float aestheticScore;
        public CropCandidate(float[] cropNorm, float aestheticScore) {
            this.cropNorm = cropNorm;
            this.aestheticScore = aestheticScore;
        }
        @Override public int compareTo(CropCandidate o) {
            return Float.compare(o.aestheticScore, aestheticScore);
        }
    }

    public CropAdvisorEngine(Context context) {
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

    /**
     * Lightweight throttle: only recompute every N frames, or when the
     * salient region changed substantially since last computation.
     */
    public boolean shouldRecompute(int currentFrame, float saliencyIouSinceLast) {
        int interval = 5;
        if (currentFrame - lastFrame < interval && saliencyIouSinceLast >= 0.5f) {
            return false;
        }
        lastFrame = currentFrame;
        lastSaliencyIou = saliencyIouSinceLast;
        return true;
    }

    private void loadIfAvailable() {
        try {
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
            Log.i(TAG, MODEL_ASSET + " not present in assets \u2014 falling back to main head.");
            available = false;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to initialize CropAdvisorEngine", t);
            available = false;
        }
    }

    /**
     * Run aesthetic cropping. Returns top-K candidates ordered by score (best
     * first), or empty list when unavailable.
     */
    public List<CropCandidate> suggest(Bitmap frame, int topK) {
        if (!available || interpreter == null || frame == null) {
            return Collections.emptyList();
        }
        try {
            Bitmap resized = Bitmap.createScaledBitmap(frame, INPUT_SIZE, INPUT_SIZE, true);

            // GAIC v2 expects ImageNet-normalised RGB (NHWC, float32, /256 then
            // mean/std). The conversion script bakes preprocessing OUTSIDE the
            // model, so we have to apply it here.
            ByteBuffer in = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
            for (int p : pixels) {
                float r = ((p >> 16) & 0xFF) / 256f;
                float g = ((p >> 8)  & 0xFF) / 256f;
                float b = ( p        & 0xFF) / 256f;
                in.putFloat((r - RGB_MEAN[0]) / RGB_STD[0]);
                in.putFloat((g - RGB_MEAN[1]) / RGB_STD[1]);
                in.putFloat((b - RGB_MEAN[2]) / RGB_STD[2]);
            }
            in.rewind();

            // Single output: scores [1, 90].
            float[][] scoresOut = new float[1][NUM_ANCHORS];
            interpreter.run(in, scoresOut);
            if (resized != frame) resized.recycle();

            return rank(scoresOut[0], ANCHORS_XYWH, topK);
        } catch (Throwable t) {
            Log.w(TAG, "Aesthetic crop inference failed", t);
            return Collections.emptyList();
        }
    }

    private static List<CropCandidate> rank(float[] scores, float[][] anchors, int topK) {
        List<CropCandidate> all = new ArrayList<>(scores.length);
        for (int i = 0; i < scores.length; i++) {
            float[] xywh = new float[]{
                    Math.max(0f, Math.min(1f, anchors[i][0])),
                    Math.max(0f, Math.min(1f, anchors[i][1])),
                    Math.max(0.05f, Math.min(1f, anchors[i][2])),
                    Math.max(0.05f, Math.min(1f, anchors[i][3]))
            };
            all.add(new CropCandidate(xywh, scores[i]));
        }
        Collections.sort(all);
        if (topK > 0 && all.size() > topK) {
            return all.subList(0, topK);
        }
        return all;
    }
}
