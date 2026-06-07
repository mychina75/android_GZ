package com.samsung.camera.intelligence.app.camera;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.samsung.camera.intelligence.trigger.NativeBridge;

/**
 * Generates a 3D Look-Up Table (LUT) from tone parameters for GPU-based
 * color grading.  The LUT is stored as a 2D texture atlas where blue slices
 * are arranged along the x-axis:
 *
 * <pre>
 *   Texture size: (LUT_SIZE * LUT_SIZE) × LUT_SIZE   (e.g. 1089 × 33)
 *   Pixel at (x, y):
 *     blue  = x / LUT_SIZE
 *     red   = x % LUT_SIZE
 *     green = y
 * </pre>
 *
 * <p><b>IMPORTANT:</b> Unlike {@code ToneCurveMapper} (designed for Camera2
 * {@code TONEMAP_MODE_CONTRAST_CURVE} which replaces the ISP gamma), this
 * class generates curves for <b>already gamma-encoded</b> input.  The camera
 * ISP runs {@code TONEMAP_MODE_HIGH_QUALITY} and delivers sRGB frames to the
 * GL surface.  The LUT must start from identity (y=x) and apply only the
 * contrast/highlights/shadows/saturation <i>deltas</i>.
 */
public final class LutToneMapper {

    private static final String TAG = "LutToneMapper";

    /** LUT resolution per axis.  33 gives excellent quality at ~140 KB. */
    public static final int LUT_SIZE = 33;

    private LutToneMapper() {}

    /**
     * Generate a 3D LUT bitmap from tone parameters.
     *
     * <p>The input pixels are already in sRGB gamma space (from the ISP's
     * HIGH_QUALITY tone mapping).  The curve starts from identity (y=x) and
     * applies only the requested adjustments.
     *
     * @param contrast   -100..+100, 0 = neutral
     * @param highlights -100..+100, 0 = neutral
     * @param shadows    -100..+100, 0 = neutral
     * @param saturation -100..+100, 0 = neutral
     * @return Bitmap of size (LUT_SIZE*LUT_SIZE) × LUT_SIZE in ARGB_8888
     */
    public static Bitmap generateLutBitmap(float contrast, float highlights,
                                           float shadows, float saturation) {
        return generateLutBitmap(contrast, highlights, shadows, saturation, 0f, 0f);
    }

    /**
     * Generate a 3D LUT bitmap from tone + color style parameters.
     *
     * <p>Extends the 4-param version with highlight warmth (warm/cool shift
     * in bright regions) and shadow tint (hue shift in dark regions).
     *
     * @param contrast        -100..+100, 0 = neutral
     * @param highlights      -100..+100, 0 = neutral
     * @param shadows         -100..+100, 0 = neutral
     * @param saturation      -100..+100, 0 = neutral
     * @param highlightWarmth -100..+100, positive = warmer highlights
     * @param shadowTint      -100..+100, positive = warmer shadows
     * @return Bitmap of size (LUT_SIZE*LUT_SIZE) × LUT_SIZE in ARGB_8888
     */
    public static Bitmap generateLutBitmap(float contrast, float highlights,
                                           float shadows, float saturation,
                                           float highlightWarmth, float shadowTint) {
        if (NativeBridge.isAvailable()) {
            try {
                int width = LUT_SIZE * LUT_SIZE;
                int height = LUT_SIZE;
                int[] pixels = NativeBridge.generateLut(
                        contrast, highlights, shadows, saturation, highlightWarmth, shadowTint);
                if (pixels != null && pixels.length == width * height) {
                    return bitmapFromPixels(pixels, width, height);
                }
            } catch (Throwable t) {
                Log.w(TAG, "native LUT generation failed, using Java fallback", t);
            }
        }
        float[] tone = dampenExtremeToneSpread(contrast, highlights, shadows);
        contrast = tone[0];
        highlights = tone[1];
        shadows = tone[2];

        int n = LUT_SIZE;
        int width = n * n;   // 1089
        int height = n;      // 33

        // Build tone curve (same for all channels — avoids colour cast)
        float[] toneLut = buildDisplayCurve(n, contrast, highlights, shadows);

        // Saturation factor (applied as cross-channel deviation from luminance)
        float satFactor = 1.0f;
        boolean applySaturation = Math.abs(saturation) > 1f;
        if (applySaturation) {
            satFactor = 1.0f + saturation / 100.0f;
            satFactor = Math.max(0.5f, Math.min(1.5f, satFactor));
        }

        // Fill the 2D atlas pixel array
        // Warmth/tint strength: at ±100 the max channel shift is ±0.08
        float warmthStr = highlightWarmth / 100.0f * 0.08f;
        float tintStr   = shadowTint      / 100.0f * 0.08f;
        boolean applyColorShift = Math.abs(highlightWarmth) > 1f || Math.abs(shadowTint) > 1f;

        int[] pixels = new int[width * height];
        for (int gy = 0; gy < n; gy++) {
            float gBase = toneLut[gy];
            for (int bSlice = 0; bSlice < n; bSlice++) {
                float bBase = toneLut[bSlice];
                int xBase = bSlice * n;
                for (int rx = 0; rx < n; rx++) {
                    float rBase = toneLut[rx];

                    float rVal = rBase;
                    float gVal = gBase;
                    float bVal = bBase;

                    // Saturation: scale each channel's deviation from luminance.
                    // This preserves neutral grays and pushes chromatic pixels
                    // outward without introducing a colour cast.
                    if (applySaturation) {
                        float lum = 0.2126f * rBase + 0.7152f * gBase + 0.0722f * bBase;
                        rVal = lum + (rBase - lum) * satFactor;
                        gVal = lum + (gBase - lum) * satFactor;
                        bVal = lum + (bBase - lum) * satFactor;
                    }

                    if (applyColorShift) {
                        // Luminance approximation (BT.709)
                        float lum = 0.2126f * rVal + 0.7152f * gVal + 0.0722f * bVal;

                        // Highlight warmth: bright pixels (lum > 0.5) → R↑ B↓
                        if (Math.abs(highlightWarmth) > 1f) {
                            float hWeight = smoothstep(0.4f, 0.8f, lum);
                            rVal += warmthStr * hWeight;
                            bVal -= warmthStr * hWeight;
                        }

                        // Shadow tint: dark pixels (lum < 0.5) → warm(R↑ B↓) or cool(R↓ B↑)
                        if (Math.abs(shadowTint) > 1f) {
                            float sWeight = 1.0f - smoothstep(0.2f, 0.5f, lum);
                            rVal += tintStr * sWeight;
                            bVal -= tintStr * sWeight;
                        }
                    }

                    rVal = Math.max(0f, Math.min(1f, rVal));
                    gVal = Math.max(0f, Math.min(1f, gVal));
                    bVal = Math.max(0f, Math.min(1f, bVal));

                    pixels[gy * width + xBase + rx] = Color.argb(255, toByte(rVal), toByte(gVal), toByte(bVal));
                }
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /**
     * Keep high shadow-lift + highlight-compression combinations from turning
     * indoor/dark scenes into a flat haze. The same normalization is used for
     * plain tone LUTs and composed Film Simulation LUTs so both preview paths
     * respond consistently to a Mimic card's tone metadata.
     */
    public static float[] dampenExtremeToneSpread(float contrast, float highlights, float shadows) {
        float combinedSpread = shadows - highlights;
        if (combinedSpread > 45f) {
            float excess = (combinedSpread - 45f) * 0.5f;
            shadows -= excess;
            highlights += excess;
        }
        return new float[]{contrast, highlights, shadows};
    }

    /**
     * Build a display-path tone curve for already-gamma-encoded input.
     * Starts from identity (y=x) and applies only the deltas.
     */
    private static float[] buildDisplayCurve(int n, float contrast,
                                             float highlights, float shadows) {
        float[] lut = new float[n];
        for (int i = 0; i < n; i++) {
            float x = (float) i / (n - 1);   // 0.0 .. 1.0 (already sRGB)

            // Start from identity
            float y = x;

            // Contrast: S-curve in gamma space (pivot at midpoint 0.5)
            y = applyDisplayContrast(y, contrast);

            // Highlights: boost/compress upper tones
            y = applyDisplayHighlights(y, highlights);

            // Shadows: lift/crush lower tones
            y = applyDisplayShadows(y, shadows);

            lut[i] = Math.max(0f, Math.min(1f, y));
        }

        // Enforce monotonicity
        for (int i = 1; i < n; i++) {
            if (lut[i] < lut[i - 1]) {
                lut[i] = lut[i - 1];
            }
        }
        return lut;
    }

    /**
     * Contrast S-curve for gamma-encoded input.
     * Pivots around 0.5 (perceptual midpoint in sRGB).
     * Positive → steeper midtones; negative → flatter.
     */
    private static float applyDisplayContrast(float y, float contrast) {
        if (Math.abs(contrast) < 1f) return y;
        // Strength: at ±100 the multiplier reaches ±0.7
        float strength = contrast / 100.0f * 0.7f;
        // Sine-based S-curve centered at 0.5
        float s = (float) (0.5f * Math.sin(Math.PI * (y - 0.5f)));
        return y + strength * s;
    }

    /**
     * Highlights adjustment for gamma-encoded input.
     * Affects upper tones (x > ~0.35) with smooth weight.
     */
    private static float applyDisplayHighlights(float y, float highlights) {
        if (Math.abs(highlights) < 1f) return y;
        float weight = smoothstep(0.35f, 0.75f, y);
        float shift = highlights / 100.0f * 0.30f;  // max ±0.30
        return y + shift * weight;
    }

    /**
     * Shadows adjustment for gamma-encoded input.
     * Affects lower tones (x < ~0.55) with smooth weight.
     */
    private static float applyDisplayShadows(float y, float shadows) {
        if (Math.abs(shadows) < 1f) return y;
        float weight = 1.0f - smoothstep(0.20f, 0.55f, y);
        float shift = shadows / 100.0f * 0.25f;  // max ±0.25
        return y + shift * weight;
    }



    /**
     * Generate an identity (pass-through) LUT — no tone modification.
     */
    public static Bitmap identityLutBitmap() {
        if (NativeBridge.isAvailable()) {
            try {
                int width = LUT_SIZE * LUT_SIZE;
                int height = LUT_SIZE;
                int[] pixels = NativeBridge.identityLut();
                if (pixels != null && pixels.length == width * height) {
                    return bitmapFromPixels(pixels, width, height);
                }
            } catch (Throwable t) {
                Log.w(TAG, "native identity LUT failed, using Java fallback", t);
            }
        }
        int n = LUT_SIZE;
        int width = n * n;
        int height = n;
        int[] pixels = new int[width * height];

        for (int gy = 0; gy < n; gy++) {
            int gByte = Math.round(gy * 255f / (n - 1));
            for (int bSlice = 0; bSlice < n; bSlice++) {
                int bByte = Math.round(bSlice * 255f / (n - 1));
                int xBase = bSlice * n;
                for (int rx = 0; rx < n; rx++) {
                    int rByte = Math.round(rx * 255f / (n - 1));
                    pixels[gy * width + xBase + rx] = Color.argb(255, rByte, gByte, bByte);
                }
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /**
     * Apply a 3D LUT bitmap to an image bitmap (CPU-side).
     *
     * <p>This replicates the GPU fragment shader's {@code lutLookup()} on the
     * CPU so that captured photos can receive the same tone grading that is
     * visible in the GL preview.
     *
     * @param image      the source image (not modified)
     * @param lutBitmap  the LUT atlas bitmap (LUT_SIZE*LUT_SIZE × LUT_SIZE, ARGB_8888)
     * @return a new Bitmap with the LUT applied
     */
    public static Bitmap applyLutToBitmap(Bitmap image, Bitmap lutBitmap) {
        if (NativeBridge.isAvailable() && image != null && lutBitmap != null) {
            try {
                int w = image.getWidth();
                int h = image.getHeight();
                int lutWidth = lutBitmap.getWidth();
                int lutHeight = lutBitmap.getHeight();
                int[] srcPixels = new int[w * h];
                int[] lutPixels = new int[lutWidth * lutHeight];
                image.getPixels(srcPixels, 0, w, 0, 0, w, h);
                lutBitmap.getPixels(lutPixels, 0, lutWidth, 0, 0, lutWidth, lutHeight);
                int[] out = NativeBridge.applyLut(srcPixels, w, h, lutPixels, lutWidth, lutHeight);
                if (out != null && out.length == srcPixels.length) {
                    return bitmapFromPixels(out, w, h);
                }
            } catch (Throwable t) {
                Log.w(TAG, "native applyLut failed, using Java fallback", t);
            }
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int n = LUT_SIZE;
        int lutWidth = n * n;

        // Read LUT pixels once
        int[] lutPixels = new int[lutWidth * n];
        lutBitmap.getPixels(lutPixels, 0, lutWidth, 0, 0, lutWidth, n);

        // Process image in scanline batches for cache efficiency
        int[] srcPixels = new int[w * h];
        image.getPixels(srcPixels, 0, w, 0, 0, w, h);
        int[] dstPixels = new int[w * h];

        float sizeM1 = n - 1f;

        for (int i = 0; i < srcPixels.length; i++) {
            int px = srcPixels[i];
            int a = (px >>> 24) & 0xFF;
            float r = ((px >> 16) & 0xFF) / 255f;
            float g = ((px >> 8) & 0xFF) / 255f;
            float b = (px & 0xFF) / 255f;

            // Trilinear LUT lookup (mirrors the GLSL lutLookup)
            float blueIndex = b * sizeM1;
            int bFloor = (int) blueIndex;
            int bCeil = Math.min(bFloor + 1, n - 1);
            float bFrac = blueIndex - bFloor;

            int rx = Math.min(Math.round(r * sizeM1), n - 1);
            int gy = Math.min(Math.round(g * sizeM1), n - 1);

            // Sample floor blue slice
            int idx0 = gy * lutWidth + bFloor * n + rx;
            int s0 = lutPixels[idx0];
            float r0 = ((s0 >> 16) & 0xFF) / 255f;
            float g0 = ((s0 >> 8) & 0xFF) / 255f;
            float b0 = (s0 & 0xFF) / 255f;

            // Sample ceil blue slice
            int idx1 = gy * lutWidth + bCeil * n + rx;
            int s1 = lutPixels[idx1];
            float r1 = ((s1 >> 16) & 0xFF) / 255f;
            float g1 = ((s1 >> 8) & 0xFF) / 255f;
            float b1 = (s1 & 0xFF) / 255f;

            // Interpolate
            int outR = toByte(r0 + (r1 - r0) * bFrac);
            int outG = toByte(g0 + (g1 - g0) * bFrac);
            int outB = toByte(b0 + (b1 - b0) * bFrac);

            dstPixels[i] = (a << 24) | (outR << 16) | (outG << 8) | outB;
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(dstPixels, 0, w, 0, 0, w, h);
        return result;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static int toByte(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    private static Bitmap bitmapFromPixels(int[] pixels, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
