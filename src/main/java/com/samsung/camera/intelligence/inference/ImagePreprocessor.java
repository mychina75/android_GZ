package com.samsung.camera.intelligence.inference;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Preprocesses Android Bitmap images for TFLite model input.
 * Ported from Python FrameAnalyzer._preprocess() in guidance/frame_analyzer.py.
 *
 * Pipeline: Bitmap → resize to modelInputSize × modelInputSize
 *           → float32 / 255 → normalize → NHWC ByteBuffer
 *
 * The TFLite model (converted with nchw_transpose=True) expects NHWC layout.
 */
public class ImagePreprocessor {

    /** Normalization mode for different model inputs. */
    public enum NormMode {
        /** CLIP ViT normalization (used for backbone image input). */
        CLIP,
        /** ImageNet normalization (used for raw_image / quality analyzer). */
        IMAGENET,
        /** Raw [0,1] scaling, no mean/std normalization (used for motion_frame). */
        RAW
    }

    // ImageNet normalization constants
    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGENET_STD  = {0.229f, 0.224f, 0.225f};

    // CLIP normalization constants
    private static final float[] CLIP_MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] CLIP_STD  = {0.26862954f, 0.26130258f, 0.27577711f};

    private final int modelInputSize;

    // Per-mode reusable buffers
    private ByteBuffer clipBuffer;
    private ByteBuffer imagenetBuffer;
    private ByteBuffer rawBuffer;

    /**
     * @param modelInputSize Spatial H=W expected by model (e.g. 224)
     */
    public ImagePreprocessor(int modelInputSize) {
        this.modelInputSize = modelInputSize;
        int bufferSize = 1 * modelInputSize * modelInputSize * 3 * 4; // NHWC float32
        this.clipBuffer = allocateBuffer(bufferSize);
        this.imagenetBuffer = allocateBuffer(bufferSize);
        this.rawBuffer = allocateBuffer(bufferSize);
    }

    private static ByteBuffer allocateBuffer(int size) {
        ByteBuffer buf = ByteBuffer.allocateDirect(size);
        buf.order(ByteOrder.nativeOrder());
        return buf;
    }

    /**
     * Preprocess a Bitmap with the default (ImageNet) normalization.
     * Kept for backward compatibility.
     */
    public ByteBuffer preprocess(Bitmap bitmap) {
        return preprocess(bitmap, NormMode.IMAGENET);
    }

    /**
     * Preprocess a Bitmap into a model-ready ByteBuffer.
     *
     * @param bitmap Input camera frame (any size, ARGB_8888)
     * @param mode   Normalization mode
     * @return ByteBuffer in NHWC float32 format [1, H, W, 3]
     */
    public ByteBuffer preprocess(Bitmap bitmap, NormMode mode) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true);
        int[] pixels = new int[modelInputSize * modelInputSize];
        resized.getPixels(pixels, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize);

        ByteBuffer buf = getBuffer(mode);
        buf.rewind();

        float[] mean;
        float[] std;
        switch (mode) {
            case CLIP:
                mean = CLIP_MEAN;
                std = CLIP_STD;
                break;
            case RAW:
                mean = null;
                std = null;
                break;
            default: // IMAGENET
                mean = IMAGENET_MEAN;
                std = IMAGENET_STD;
                break;
        }

        // Write NHWC: for each pixel, write R, G, B (interleaved)
        for (int pixel : pixels) {
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;

            if (mean != null) {
                r = (r - mean[0]) / std[0];
                g = (g - mean[1]) / std[1];
                b = (b - mean[2]) / std[2];
            }

            buf.putFloat(r);
            buf.putFloat(g);
            buf.putFloat(b);
        }

        buf.rewind();

        if (resized != bitmap) {
            resized.recycle();
        }

        return buf;
    }

    /**
     * Create a zero-filled buffer (e.g. for motion_frame when no previous frame).
     *
     * @return ByteBuffer of zeros in NHWC float32 format [1, H, W, 3]
     */
    public ByteBuffer createZeroBuffer() {
        rawBuffer.rewind();
        int floats = modelInputSize * modelInputSize * 3;
        for (int i = 0; i < floats; i++) {
            rawBuffer.putFloat(0.0f);
        }
        rawBuffer.rewind();
        return rawBuffer;
    }

    private ByteBuffer getBuffer(NormMode mode) {
        switch (mode) {
            case CLIP:     return clipBuffer;
            case IMAGENET: return imagenetBuffer;
            default:       return rawBuffer;
        }
    }

    /**
     * Get the model input spatial size.
     */
    public int getModelInputSize() {
        return modelInputSize;
    }
}
