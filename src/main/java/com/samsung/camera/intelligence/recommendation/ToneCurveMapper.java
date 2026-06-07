package com.samsung.camera.intelligence.recommendation;

import android.hardware.camera2.params.TonemapCurve;
import android.util.Log;

import com.samsung.camera.intelligence.trigger.NativeBridge;

/**
 * Converts abstract tone parameters (contrast, highlights, shadows, saturation)
 * into Camera2 TONEMAP_CURVE control points and COLOR_CORRECTION_TRANSFORM data.
 *
 * <p>Input values are in the -100..+100 range (Lightroom convention).
 * Output is an array of [input, output] pairs in [0.0, 1.0] for TONEMAP_CURVE.
 *
 * <h3>Mapping strategy:</h3>
 * <ul>
 *   <li><b>Contrast</b> → S-curve steepness via sigmoid with adjustable slope</li>
 *   <li><b>Highlights</b> → Modify curve points for input > 0.5 (push up/crush down)</li>
 *   <li><b>Shadows</b> → Modify curve points for input < 0.5 (lift/crush)</li>
 *   <li><b>Saturation</b> → COLOR_CORRECTION_TRANSFORM matrix scaling</li>
 * </ul>
 */
public final class ToneCurveMapper {

    private static final String TAG = "ToneCurveMapper";

    // Number of control points in the generated curve (including endpoints).
    // Must not exceed TONEMAP_MAX_CURVE_POINTS reported by the device.
    private static final int DEFAULT_CURVE_POINTS = 32;

    // sRGB gamma approximation exponent (1/2.2).
    // When TONEMAP_MODE_CONTRAST_CURVE is active it REPLACES the camera's
    // built-in tone mapping.  Without the gamma base the image will look
    // washed-out / foggy because the sensor linear-light values are displayed
    // without the standard sRGB gamma correction.
    private static final float GAMMA_EXP = 1.0f / 2.2f;

    private ToneCurveMapper() {}

    /**
     * Generate a tone curve from contrast/highlights/shadows parameters.
     *
     * @param contrast   -100..+100, 0 = neutral
     * @param highlights -100..+100, 0 = neutral
     * @param shadows    -100..+100, 0 = neutral
     * @param maxPoints  Maximum curve points supported by the device
     * @return float array of [in0, out0, in1, out1, ...] pairs in [0.0, 1.0]
     */
    public static float[] buildToneCurve(float contrast, float highlights, float shadows,
                                          int maxPoints) {
        if (NativeBridge.isAvailable()) {
            try {
                float[] nativeCurve = NativeBridge.buildToneCurve(contrast, highlights, shadows, maxPoints);
                if (nativeCurve != null && nativeCurve.length >= 4) {
                    return nativeCurve;
                }
            } catch (Throwable t) {
                Log.w(TAG, "native tone curve failed, using Java fallback", t);
            }
        }
        int numPoints = Math.min(DEFAULT_CURVE_POINTS, maxPoints);
        if (numPoints < 2) numPoints = 2;

        float[] curve = new float[numPoints * 2];

        for (int i = 0; i < numPoints; i++) {
            float x = (float) i / (numPoints - 1); // 0.0 .. 1.0

            // Start from sRGB gamma base curve, NOT identity.
            // This is critical because TONEMAP_MODE_CONTRAST_CURVE replaces
            // the camera's built-in gamma mapping entirely.
            float y = srgbGamma(x);

            // Apply a subtle highlight shoulder to match typical camera
            // HIGH_QUALITY processing.  Camera firmware usually compresses
            // highlights more than pure sRGB — without this, switching
            // from HIGH_QUALITY to CONTRAST_CURVE brightens the upper end.
            y = applyHighlightShoulder(y);

            // Apply contrast: modify the S-shape of the gamma curve
            y = applyContrast(y, x, contrast);

            // Apply highlights adjustment (upper portion, x > 0.5)
            y = applyHighlights(y, x, highlights);

            // Apply shadows adjustment (lower portion, x < 0.5)
            y = applyShadows(y, x, shadows);

            // Clamp to valid range
            y = Math.max(0.0f, Math.min(1.0f, y));

            curve[i * 2] = x;
            curve[i * 2 + 1] = y;
        }

        // Ensure endpoints are anchored
        curve[0] = 0.0f;
        curve[1] = Math.max(0.0f, curve[1]);
        curve[(numPoints - 1) * 2] = 1.0f;
        curve[(numPoints - 1) * 2 + 1] = Math.min(1.0f, curve[(numPoints - 1) * 2 + 1]);

        // Enforce monotonicity: each output must be >= the previous output.
        // A non-monotonic curve causes banding, inversion, and hazy artifacts.
        for (int i = 1; i < numPoints; i++) {
            if (curve[i * 2 + 1] < curve[(i - 1) * 2 + 1]) {
                curve[i * 2 + 1] = curve[(i - 1) * 2 + 1];
            }
        }

        return curve;
    }

    /**
     * Build a gamma (neutral) tone curve — equivalent to no adjustment.
     */
    public static float[] identityCurve(int maxPoints) {
        if (NativeBridge.isAvailable()) {
            try {
                float[] nativeCurve = NativeBridge.identityToneCurve(maxPoints);
                if (nativeCurve != null && nativeCurve.length >= 4) {
                    return nativeCurve;
                }
            } catch (Throwable t) {
                Log.w(TAG, "native identity curve failed, using Java fallback", t);
            }
        }
        int numPoints = Math.min(DEFAULT_CURVE_POINTS, maxPoints);
        if (numPoints < 2) numPoints = 2;
        float[] curve = new float[numPoints * 2];
        for (int i = 0; i < numPoints; i++) {
            float x = (float) i / (numPoints - 1);
            curve[i * 2] = x;
            curve[i * 2 + 1] = applyHighlightShoulder(srgbGamma(x));
        }
        return curve;
    }

    /**
     * Build a 3x3 COLOR_CORRECTION_TRANSFORM matrix for saturation adjustment.
     *
     * <p>Uses the luminance-preserving saturation matrix:
     * <pre>
     *   S*I + (1-S)*L, where L is the luminance weighting row [0.2126, 0.7152, 0.0722]
     * </pre>
     *
     * @param saturation -100..+100, 0 = neutral (satFactor 1.0)
     * @return 9-element float array [r0,r1,r2, g0,g1,g2, b0,b1,b2] for
     *         ColorSpaceTransform (row-major). Values are raw floats; caller
     *         must convert to Rational pairs for the Camera2 API.
     */
    public static float[] buildSaturationMatrix(float saturation) {
        if (NativeBridge.isAvailable()) {
            try {
                float[] nativeMatrix = NativeBridge.buildSaturationMatrix(saturation);
                if (nativeMatrix != null && nativeMatrix.length == 9) {
                    return nativeMatrix;
                }
            } catch (Throwable t) {
                Log.w(TAG, "native saturation matrix failed, using Java fallback", t);
            }
        }
        // Map -100..+100 to saturation factor: 0.0 .. 2.0
        float s = 1.0f + saturation / 100.0f;
        s = Math.max(0.0f, Math.min(2.0f, s));

        // BT.709 luminance weights
        final float lr = 0.2126f;
        final float lg = 0.7152f;
        final float lb = 0.0722f;

        // Saturation matrix: S*I + (1-S)*L
        float sr = (1 - s) * lr;
        float sg = (1 - s) * lg;
        float sb = (1 - s) * lb;

        return new float[] {
            sr + s, sg,     sb,
            sr,     sg + s, sb,
            sr,     sg,     sb + s
        };
    }

    /**
     * Build a per-channel tone curve for saturation approximation.
     *
     * <p>Multiplies each output point's deviation from the gamma midpoint
     * by satFactor.  satFactor > 1 increases colour separation between
     * channels (more saturated); < 1 pulls R/B toward the green reference
     * (desaturated).
     *
     * @param greenCurve the base luminance curve ([in0,out0, in1,out1, ...])
     * @param satFactor  0.5..1.5; 1.0 = identity (same as green)
     * @return a new curve array with the same number of points
     */
    public static float[] buildSatChannelCurve(float[] greenCurve, float satFactor) {
        int len = greenCurve.length;
        float[] ch = new float[len];
        for (int i = 0; i < len; i += 2) {
            ch[i] = greenCurve[i]; // same input
            float g = greenCurve[i + 1];
            // Scale the output relative to green: push R/B away from or toward G
            float mid = g; // use green as the neutral reference
            float out = mid + (g - mid) * satFactor;
            // For actual saturation effect, scale the channel curve steepness
            // relative to the green curve by adjusting the power
            float x = greenCurve[i];
            float gammaBase = srgbGamma(x);
            // Adjust: steeper curve = more channel contrast = more saturation
            float delta = (g - gammaBase) * satFactor;
            out = gammaBase + delta;
            // Also apply a subtle overall channel boost/cut for visible effect
            float channelShift = (satFactor - 1.0f) * 0.03f;
            out += channelShift * (x - 0.5f);
            ch[i + 1] = Math.max(0.0f, Math.min(1.0f, out));
        }
        // Enforce monotonicity
        int numPoints = len / 2;
        for (int i = 1; i < numPoints; i++) {
            if (ch[i * 2 + 1] < ch[(i - 1) * 2 + 1]) {
                ch[i * 2 + 1] = ch[(i - 1) * 2 + 1];
            }
        }
        return ch;
    }

    /**
     * Check whether any tone parameters are non-zero (worth applying).
     */
    public static boolean hasAdjustment(Float contrast, Float highlights,
                                         Float shadows, Float saturation) {
        return (contrast != null && Math.abs(contrast) > 1f)
            || (highlights != null && Math.abs(highlights) > 1f)
            || (shadows != null && Math.abs(shadows) > 1f)
            || (saturation != null && Math.abs(saturation) > 1f);
    }

    // ── Internal curve functions ─────────────────────────────────────────────

    /**
     * sRGB gamma transfer function: maps linear-light [0,1] to gamma-encoded [0,1].
     * This approximates the camera's default HIGH_QUALITY tone mapping.
     */
    private static float srgbGamma(float linear) {
        if (linear <= 0.0031308f) {
            return 12.92f * linear;
        }
        return 1.055f * (float) Math.pow(linear, 1.0 / 2.4) - 0.055f;
    }

    /**
     * Apply contrast adjustment by modifying the gamma curve's shape.
     *
     * contrast > 0: steeper midtones (more contrast, deeper S-curve)
     * contrast < 0: flatter midtones (less contrast, flatter curve)
     *
     * Works by blending the gamma base toward a steeper or flatter power curve.
     */
    private static float applyContrast(float y, float x, float contrast) {
        if (Math.abs(contrast) < 1f) return y;

        // Scale factor: positive pushes gamma exponent lower (steeper midtones),
        // negative pushes it higher (flatter midtones).
        // At +100 → exponent ~0.35 (more contrast); at -100 → exponent ~0.65 (less contrast)
        float exponent = GAMMA_EXP - (contrast / 100.0f) * 0.10f;
        exponent = Math.max(0.25f, Math.min(0.75f, exponent));
        float contrastCurve = (float) Math.pow(x, exponent);

        // Blend between the original gamma value and the contrast-modified value
        float blend = Math.min(1.0f, Math.abs(contrast) / 100.0f);
        return y * (1.0f - blend) + contrastCurve * blend;
    }

    /**
     * Apply highlights adjustment: modify the curve for tones above midpoint.
     * Positive = brighter highlights; negative = compressed highlights.
     */
    private static float applyHighlights(float y, float x, float highlights) {
        if (Math.abs(highlights) < 1f) return y;

        // Weight: affect tones from lower-midtone upward for wider coverage
        float weight = smoothstep(0.35f, 0.75f, x);
        float shift = highlights / 100.0f * 0.25f; // max ±0.25 shift (was ±0.20)

        return y + shift * weight;
    }

    /**
     * Apply shadows adjustment: modify the curve for tones below midpoint.
     * Positive = lifted shadows; negative = crushed shadows.
     */
    private static float applyShadows(float y, float x, float shadows) {
        if (Math.abs(shadows) < 1f) return y;

        // Weight: only affect lower tones (smooth blend from 0.6 down to 0.2)
        float weight = 1.0f - smoothstep(0.2f, 0.55f, x);
        float shift = shadows / 100.0f * 0.18f; // max ±0.18 shift (was ±0.20)

        return y + shift * weight;
    }

    /**
     * Gentle highlight shoulder: compresses the top of the tone curve to
     * match camera firmware HIGH_QUALITY processing.  Without this, the
     * pure sRGB gamma produces brighter highlights than the camera's
     * built-in tone mapping, causing a visible brightness jump when
     * switching from HIGH_QUALITY to CONTRAST_CURVE mode.
     *
     * <p>Quadratic roll-off starting at y=0.78, compressing up to 5%
     * at the very top (y=1.0 → ~0.95).
     */
    private static float applyHighlightShoulder(float y) {
        if (y > 0.78f) {
            float t = (y - 0.78f) / 0.22f;
            y -= t * t * 0.05f;
        }
        return y;
    }

    /**
     * GLSL-style smoothstep for smooth blending between regions.
     */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }
}
