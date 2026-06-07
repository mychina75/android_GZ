package com.samsung.camera.intelligence.guidance;

import android.util.Log;

/**
 * Phase 2 — keeps {@code suggested_crop} stable across frames so the
 * Framing Template overlay does not slide around every time the
 * regression head wobbles.
 *
 * Sticky lock:
 *   - Accept a new crop only when (a) the previous crop is null, or
 *     (b) the IoU between the new and the locked crop dropped below
 *     {@link #cropIouReplanThreshold}, or (c) we have
 *     consecutively received N degenerate (full-frame) crops and
 *     decided to fall back to the heuristic.
 *
 * Degenerate detection:
 *   - A crop that covers ≥ 0.95 of width AND ≥ 0.95 of height is
 *     considered degenerate (the regression head defaulted to "no crop").
 *   - After {@link #degenerateBeforeFallbackFrames} consecutive degenerate
 *     frames the stabilizer signals callers to use a heuristic fallback
 *     (we don't compute one here; that lives in {@code MainActivity}).
 */
public class SuggestedCropStabilizer {

    private static final String TAG = "CropStabilizer";

    private float cropIouReplanThreshold = 0.55f;
    private int degenerateBeforeFallbackFrames = 6;
    private float cropFollowAlpha = 0.18f;
    private float cropReplanAlpha = 0.45f;

    private float[] lockedCrop = null;
    private int degenerateCount = 0;

    public void setSubjectMoveThreshold(float v) { }
    public void setCropIouReplanThreshold(float v) { this.cropIouReplanThreshold = v; }
    public void setCropFollowAlpha(float v) { this.cropFollowAlpha = clamp(v, 0.02f, 1.0f); }
    public void setCropReplanAlpha(float v) { this.cropReplanAlpha = clamp(v, 0.02f, 1.0f); }
    public void setDegenerateBeforeFallbackFrames(int v) {
        this.degenerateBeforeFallbackFrames = Math.max(1, v);
    }

    public float[] getLockedCrop() { return lockedCrop; }
    public boolean isHeadDegenerated() {
        return degenerateCount >= degenerateBeforeFallbackFrames;
    }

    public void reset() {
        lockedCrop = null;
        degenerateCount = 0;
    }

    /**
     * Update the stabilizer with the latest model proposal and current
     * subject center. Returns the crop the renderer should use.
     *
     * @param newCrop          fresh suggested_crop from the head, or null
     * @param subjectCenter    current subject (cx, cy), or null
     * @return the locked / accepted crop, or null if nothing usable yet
     */
    public float[] update(float[] newCrop, float[] subjectCenter) {
        // Track degenerate proposals separately so we can ask for a fallback.
        boolean degenerate = isDegenerate(newCrop);
        if (degenerate) {
            degenerateCount++;
        } else if (newCrop != null) {
            degenerateCount = 0;
        }

        if (newCrop == null || newCrop.length < 4) {
            return lockedCrop;
        }

        if (lockedCrop == null) {
            if (!degenerate) {
                lockedCrop = newCrop.clone();
                Log.i(TAG, "Initial crop locked: "
                        + lockedCrop[0] + "," + lockedCrop[1]
                        + "," + lockedCrop[2] + "x" + lockedCrop[3]);
            }
            return lockedCrop;
        }

        boolean cropDrifted = !degenerate
                && iou(lockedCrop, newCrop) < cropIouReplanThreshold;

        if (cropDrifted) {
            if (!degenerate) {
                lockedCrop = blendCrop(lockedCrop, newCrop, cropReplanAlpha);
                Log.i(TAG, "Crop replanned: cropDrifted=" + cropDrifted);
            }
        } else if (!degenerate) {
            lockedCrop = blendCrop(lockedCrop, newCrop, cropFollowAlpha);
        }
        return lockedCrop;
    }

    /** Replace the current locked crop with an externally-computed fallback. */
    public void overrideWithFallback(float[] fallback) {
        if (fallback == null || fallback.length < 4) {
            return;
        }
        lockedCrop = lockedCrop == null
                ? fallback.clone()
                : blendCrop(lockedCrop, fallback, cropReplanAlpha);
    }

    private static float[] blendCrop(float[] current, float[] incoming, float alpha) {
        float[] out = current.clone();
        for (int i = 0; i < 4; i++) {
            out[i] += alpha * (incoming[i] - out[i]);
        }
        return out;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean isDegenerate(float[] c) {
        if (c == null || c.length < 4) return false;
        return c[2] >= 0.95f && c[3] >= 0.95f;
    }

    /**
     * Axis-aligned IoU on [x, y, w, h] boxes. Returns 0 for invalid boxes.
     */
    public static float iou(float[] a, float[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0f;
        float ax1 = a[0], ay1 = a[1], ax2 = a[0] + a[2], ay2 = a[1] + a[3];
        float bx1 = b[0], by1 = b[1], bx2 = b[0] + b[2], by2 = b[1] + b[3];
        float ix1 = Math.max(ax1, bx1);
        float iy1 = Math.max(ay1, by1);
        float ix2 = Math.min(ax2, bx2);
        float iy2 = Math.min(ay2, by2);
        float iw = Math.max(0f, ix2 - ix1);
        float ih = Math.max(0f, iy2 - iy1);
        float inter = iw * ih;
        float union = a[2] * a[3] + b[2] * b[3] - inter;
        if (union <= 0f) return 0f;
        return inter / union;
    }
}
