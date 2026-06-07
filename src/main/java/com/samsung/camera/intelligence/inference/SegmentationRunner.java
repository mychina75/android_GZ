package com.samsung.camera.intelligence.inference;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import com.samsung.camera.intelligence.recommendation.DefectLocalizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * TFLite inference wrapper for segmentation models.
 *
 * Loads a segmentation model lazily on first use to avoid startup cost
 * when defect detection is not needed. Produces per-pixel probability masks
 * that can be converted to bounding boxes for backward compatibility, or
 * delivered raw for mask-based editing tools.
 *
 * <p>Thread-safe: interpreters are created per-call (lightweight for segmentation
 * models under 5 MB).
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SegmentationRunner runner = new SegmentationRunner(context, "shadow_u2net_lite.tflite");
 * float[][] mask = runner.runSegmentation(bitmap);
 * List<DefectLocalizer.DefectRegion> regions = runner.maskToBoundingBoxes(
 *         mask, DefectLocalizer.DefectType.SHADOW, 0.5f, 0.003f, 0.50f);
 * }</pre>
 */
public class SegmentationRunner {

    private static final String TAG = "SegmentationRunner";

    private final Context context;
    private final String modelFileName;
    private final int inputWidth;
    private final int inputHeight;

    private Interpreter interpreter;
    private boolean loaded = false;

    /**
     * Create a segmentation runner.
     *
     * @param context        Android context (for asset loading)
     * @param modelFileName  TFLite model file name (in assets or absolute path)
     * @param inputWidth     Model input width (default 256)
     * @param inputHeight    Model input height (default 256)
     */
    public SegmentationRunner(Context context, String modelFileName,
                              int inputWidth, int inputHeight) {
        this.context = context;
        this.modelFileName = modelFileName;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
    }

    public SegmentationRunner(Context context, String modelFileName) {
        this(context, modelFileName, 256, 256);
    }

    /**
     * Lazily load the TFLite model.
     *
     * @return true if model is ready for inference
     */
    public synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            File modelFile = new File(modelFileName);
            if (modelFile.exists()) {
                // Load from absolute path
                interpreter = new Interpreter(modelFile);
            } else {
                // Load from assets
                MappedByteBuffer buffer = loadMappedFileFromAssets(context, modelFileName);
                interpreter = new Interpreter(buffer);
            }
            loaded = true;
            Log.i(TAG, "Segmentation model loaded: " + modelFileName);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load segmentation model: " + modelFileName, e);
            return false;
        }
    }

    /**
     * Run segmentation on a bitmap and return a probability mask.
     *
     * @param bitmap Input image (any size, will be resized internally)
     * @return float[origH][origW] probability mask in [0, 1], or null on failure
     */
    public float[][] runSegmentation(Bitmap bitmap) {
        if (!ensureLoaded()) return null;

        int origW = bitmap.getWidth();
        int origH = bitmap.getHeight();

        // Preprocess: resize to model input size
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        ByteBuffer inputBuffer = bitmapToByteBuffer(resized);

        int[] outShape = interpreter.getOutputTensor(0).shape();
        int outHeight = outShape.length >= 2 ? outShape[1] : inputHeight;
        int outWidth = outShape.length >= 3 ? outShape[2] : inputWidth;
        int outChannels = outShape.length >= 4 ? outShape[3] : 1;
        float[][][][] output = new float[1][outHeight][outWidth][Math.max(1, outChannels)];

        try {
            interpreter.run(inputBuffer, output);
        } catch (Exception e) {
            Log.e(TAG, "Segmentation inference failed", e);
            return null;
        }

        // Extract and resize mask to original dimensions
        float[][] mask = new float[origH][origW];
        for (int y = 0; y < origH; y++) {
            for (int x = 0; x < origW; x++) {
                // Nearest-neighbor sampling from model output
                int sy = Math.min((int) ((float) y / origH * outHeight), outHeight - 1);
                int sx = Math.min((int) ((float) x / origW * outWidth), outWidth - 1);
                float[] logits = output[0][sy][sx];
                mask[y][x] = Math.max(0f, Math.min(1f, foregroundProbability(logits)));
            }
        }

        return mask;
    }

    private static float foregroundProbability(float[] values) {
        if (values == null || values.length == 0) {
            return 0f;
        }
        if (values.length == 1) {
            float v = values[0];
            if (v < -0.5f || v > 1.5f) {
                v = (float) (1.0 / (1.0 + Math.exp(-v)));
            }
            return v;
        }
        float max = values[0];
        for (float value : values) {
            if (value > max) {
                max = value;
            }
        }
        float sum = 0f;
        float fg = 0f;
        for (int i = 0; i < values.length; i++) {
            float exp = (float) Math.exp(values[i] - max);
            if (i == values.length - 1) {
                fg = exp;
            }
            sum += exp;
        }
        return sum > 0f ? fg / sum : 0f;
    }

    /**
     * Convert a probability mask to bounding box defect regions.
     *
     * Uses a simple grid-based connected component approximation
     * (Android-friendly, no OpenCV dependency).
     *
     * @param mask            float[H][W] probability mask
     * @param defectType      Defect type for the regions
     * @param threshold       Binarization threshold (default 0.5)
     * @param minRegionRatio  Minimum blob size as fraction of image area
     * @param maxRegionRatio  Maximum blob size as fraction of image area
     * @return List of DefectRegion with normalized bounding boxes
     */
    public List<DefectLocalizer.DefectRegion> maskToBoundingBoxes(
            float[][] mask,
            DefectLocalizer.DefectType defectType,
            float threshold,
            float minRegionRatio,
            float maxRegionRatio) {

        int h = mask.length;
        int w = mask[0].length;
        int totalArea = h * w;

        // Binarize
        boolean[][] binary = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                binary[y][x] = mask[y][x] >= threshold;
            }
        }

        // Simple flood-fill connected components
        int[][] labels = new int[h][w];
        int nextLabel = 1;
        List<int[]> componentBounds = new ArrayList<>(); // [minX, minY, maxX, maxY, area]
        List<Float> componentConfidences = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (binary[y][x] && labels[y][x] == 0) {
                    // BFS flood fill
                    int label = nextLabel++;
                    int[] bounds = {x, y, x, y, 0}; // minX, minY, maxX, maxY, area
                    float confSum = 0;
                    Queue<int[]> queue = new LinkedList<>();
                    queue.add(new int[]{x, y});
                    labels[y][x] = label;

                    while (!queue.isEmpty()) {
                        int[] pos = queue.poll();
                        int px = pos[0], py = pos[1];
                        bounds[0] = Math.min(bounds[0], px);
                        bounds[1] = Math.min(bounds[1], py);
                        bounds[2] = Math.max(bounds[2], px);
                        bounds[3] = Math.max(bounds[3], py);
                        bounds[4]++;
                        confSum += mask[py][px];

                        // 4-connected neighbors
                        int[][] neighbors = {{px-1, py}, {px+1, py}, {px, py-1}, {px, py+1}};
                        for (int[] n : neighbors) {
                            int nx = n[0], ny = n[1];
                            if (nx >= 0 && nx < w && ny >= 0 && ny < h
                                    && binary[ny][nx] && labels[ny][nx] == 0) {
                                labels[ny][nx] = label;
                                queue.add(new int[]{nx, ny});
                            }
                        }
                    }

                    componentBounds.add(bounds);
                    componentConfidences.add(confSum / bounds[4]);
                }
            }
        }

        // Convert to DefectRegion
        List<DefectLocalizer.DefectRegion> regions = new ArrayList<>();
        for (int i = 0; i < componentBounds.size(); i++) {
            int[] bounds = componentBounds.get(i);
            float area = (float) bounds[4] / totalArea;
            if (area < minRegionRatio || area > maxRegionRatio) continue;

            float bx = (float) bounds[0] / w;
            float by = (float) bounds[1] / h;
            float bw = (float) (bounds[2] - bounds[0] + 1) / w;
            float bh = (float) (bounds[3] - bounds[1] + 1) / h;
            float confidence = componentConfidences.get(i);

            regions.add(new DefectLocalizer.DefectRegion(
                    defectType,
                    new DefectLocalizer.BoundingBox(bx, by, bw, bh, confidence),
                    confidence,
                    String.format("%s region (%.1f%% of image, model=%s)",
                            defectType.value, area * 100, modelFileName)
            ));
        }

        return regions;
    }

    /**
     * Encode the mask as a flat byte array for delivery to editing tools.
     *
     * @param mask      float[H][W] probability mask
     * @param threshold Binarization threshold
     * @return byte[] with 0 or 255 per pixel (row-major), or null if mask is null
     */
    public static byte[] encodeMaskBytes(float[][] mask, float threshold) {
        if (mask == null) return null;
        int h = mask.length;
        int w = mask[0].length;
        byte[] encoded = new byte[h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                encoded[y * w + x] = mask[y][x] >= threshold ? (byte) 255 : 0;
            }
        }
        return encoded;
    }

    /**
     * Convert a bitmap to a ByteBuffer in NHWC float32 format.
     */
    private ByteBuffer bitmapToByteBuffer(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * h * w * 3);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);  // G
            buffer.putFloat((pixel & 0xFF) / 255.0f);          // B
        }

        buffer.rewind();
        return buffer;
    }

    /**
     * Release the TFLite interpreter resources.
     */
    public synchronized void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
            loaded = false;
        }
    }

    private static MappedByteBuffer loadMappedFileFromAssets(Context context, String assetName) throws IOException {
        android.content.res.AssetFileDescriptor afd = context.getAssets().openFd(assetName);
        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        long startOffset = afd.getStartOffset();
        long declaredLength = afd.getDeclaredLength();
        MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        fis.close();
        afd.close();
        return buffer;
    }
}
