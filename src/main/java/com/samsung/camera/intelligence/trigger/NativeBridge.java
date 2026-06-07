package com.samsung.camera.intelligence.trigger;

import android.util.Log;

/**
 * Thin JNI passthrough to {@code libcamera_native.so}.
 *
 * <p>The L0 trigger rule engine (gate / arbitration / hysteresis) and the CV
 * proxies (bokeh, lens-corner) are implemented in native C++ so that the
 * scoring logic, thresholds and rule weights are compiled into the binary and
 * cannot be inspected or tampered with via the APK assets. This class only
 * marshals data across the JNI boundary — it contains no algorithm logic.</p>
 *
 * <p>If the native library fails to load (e.g. on an unsupported ABI), {@link
 * #isAvailable()} returns {@code false} and callers must fall back to the pure
 * Java implementations.</p>
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";
    private static final boolean AVAILABLE;
    private static final String[] NAMES;

    static {
        boolean ok = false;
        String[] names = null;
        try {
            System.loadLibrary("camera_native");
            names = nTriggerNames();
            ok = names != null && names.length > 0;
        } catch (Throwable t) {  // UnsatisfiedLinkError or any init failure
            Log.w(TAG, "native trigger engine unavailable, using Java fallback: " + t);
        }
        AVAILABLE = ok;
        NAMES = names;
    }

    private NativeBridge() {}

    /** True when libcamera_native.so loaded and is usable. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Canonical trigger order reported by the native engine (or null). */
    public static String[] triggerNames() {
        return NAMES;
    }

    // --- Java-friendly wrappers -------------------------------------------

    /** Score one frame. Returns scores keyed by canonical trigger name. */
    public static float[] score(String[] keys, float[] values) {
        return nScore(keys, values);
    }

    /** In-place mutual-exclusion arbitration; returns new score array. */
    public static float[] arbitrate(float[] scores, float[] thresholds) {
        return nArbitrate(scores, thresholds);
    }

    public static long newHysteresis(int enterFrames, int exitFrames, float releaseRatio) {
        return nNewHysteresis(enterFrames, exitFrames, releaseRatio);
    }

    public static float[] stabilize(long handle, float[] scores, float[] thresholds) {
        return nStabilize(handle, scores, thresholds);
    }

    public static void freeHysteresis(long handle) {
        nFreeHysteresis(handle);
    }

    /** @return {bgSharpRatio, bgBlurStrength}. */
    public static float[] bokeh(int[] argb, int width, int height,
                                float bx, float by, float bw, float bh) {
        return nBokeh(argb, width, height, bx, by, bw, bh);
    }

    /** @return {probability, numBlockedPatches}. */
    public static float[] lensCorner(int[] argb, int width, int height) {
        return nLensCorner(argb, width, height);
    }

    /** Generates a 33x33x33 LUT atlas as packed ARGB pixels. */
    public static int[] generateLut(float contrast, float highlights, float shadows,
                                    float saturation, float highlightWarmth, float shadowTint) {
        return nGenerateLut(contrast, highlights, shadows, saturation, highlightWarmth, shadowTint);
    }

    public static int[] identityLut() {
        return nIdentityLut();
    }

    public static int[] composeLut(int[] basePixels, int width, int height,
                                   float contrast, float highlights, float shadows,
                                   float saturation, float highlightWarmth, float shadowTint) {
        return nComposeLut(basePixels, width, height,
                contrast, highlights, shadows, saturation, highlightWarmth, shadowTint);
    }

    public static int[] applyLut(int[] imagePixels, int width, int height,
                                 int[] lutPixels, int lutWidth, int lutHeight) {
        return nApplyLut(imagePixels, width, height, lutPixels, lutWidth, lutHeight);
    }

    /** Returns 6 values: contrast, highlights, shadows, saturation, warmth, tint. */
    public static float[] extractToneParams(int[] argb, int width, int height) {
        return nExtractToneParams(argb, width, height);
    }

    public static float[] buildToneCurve(float contrast, float highlights, float shadows,
                                         int maxPoints) {
        return nBuildToneCurve(contrast, highlights, shadows, maxPoints);
    }

    public static float[] identityToneCurve(int maxPoints) {
        return nIdentityToneCurve(maxPoints);
    }

    public static float[] buildSaturationMatrix(float saturation) {
        return nBuildSaturationMatrix(saturation);
    }

    /** Returns packed [index0, sim0, index1, sim1, ...] for the top-K matches. */
    public static float[] masterMatchTopK(float[] query, float[] flatEmbeddings,
                                          int count, int dim, int[] candidateIndices,
                                          int topK) {
        return nMasterMatchTopK(query, flatEmbeddings, count, dim, candidateIndices, topK);
    }

    /** Returns [iso, shutterIndex, ev, wbModeCode, wbKelvinOrMinus1, meteringCode]. */
    public static float[] mapExposure(float fixedAperture, int baseIso, int maxUsableIso,
                                      float minShutterSeconds, float maxShutterSeconds,
                                      float refAperture, String refShutterSpeed, int refIso,
                                      int refWbKelvin, String sensorType, float currentNoise,
                                      String currentLighting, String currentMotion) {
        return nMapExposure(fixedAperture, baseIso, maxUsableIso,
                minShutterSeconds, maxShutterSeconds,
                refAperture, refShutterSpeed, refIso, refWbKelvin,
                sensorType, currentNoise, currentLighting, currentMotion);
    }

    // --- Native declarations ----------------------------------------------

    private static native int nTriggerCount();
    private static native String[] nTriggerNames();
    private static native float[] nScore(String[] keys, float[] values);
    private static native float[] nArbitrate(float[] scores, float[] thresholds);
    private static native long nNewHysteresis(int enterFrames, int exitFrames, float releaseRatio);
    private static native float[] nStabilize(long handle, float[] scores, float[] thresholds);
    private static native void nFreeHysteresis(long handle);
    private static native float[] nBokeh(int[] argb, int width, int height,
                                         float bx, float by, float bw, float bh);
    private static native float[] nLensCorner(int[] argb, int width, int height);
    private static native int[] nGenerateLut(float contrast, float highlights, float shadows,
                                             float saturation, float highlightWarmth, float shadowTint);
    private static native int[] nIdentityLut();
    private static native int[] nComposeLut(int[] basePixels, int width, int height,
                                            float contrast, float highlights, float shadows,
                                            float saturation, float highlightWarmth, float shadowTint);
    private static native int[] nApplyLut(int[] imagePixels, int width, int height,
                                          int[] lutPixels, int lutWidth, int lutHeight);
    private static native float[] nExtractToneParams(int[] argb, int width, int height);
    private static native float[] nBuildToneCurve(float contrast, float highlights, float shadows,
                                                  int maxPoints);
    private static native float[] nIdentityToneCurve(int maxPoints);
    private static native float[] nBuildSaturationMatrix(float saturation);
    private static native float[] nMasterMatchTopK(float[] query, float[] flatEmbeddings,
                                                   int count, int dim, int[] candidateIndices,
                                                   int topK);
    private static native float[] nMapExposure(float fixedAperture, int baseIso, int maxUsableIso,
                                               float minShutterSeconds, float maxShutterSeconds,
                                               float refAperture, String refShutterSpeed, int refIso,
                                               int refWbKelvin, String sensorType, float currentNoise,
                                               String currentLighting, String currentMotion);
}
