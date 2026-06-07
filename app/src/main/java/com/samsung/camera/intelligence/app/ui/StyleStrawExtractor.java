package com.samsung.camera.intelligence.app.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;

import com.samsung.camera.intelligence.trigger.NativeBridge;

import java.util.HashMap;
import java.util.Map;

/**
 * On-device tone parameter extraction from a gallery photo bitmap.
 *
 * Ports the algorithm from {@code prepare_master_assets_0414.py::extract_tone_and_color()}
 * to Java.  Input: a pre-scaled bitmap (~256 px).  Output: 6 tone parameters
 * in [-100, +100] matching the keys consumed by {@code LutToneMapper}.
 *
 * <p>Extracted parameters:
 * <ul>
 *   <li>{@code contrast} — luminance std dev vs neutral baseline</li>
 *   <li>{@code highlights} — mean luminance of bright pixels vs neutral</li>
 *   <li>{@code shadows} — mean luminance of dark pixels vs neutral</li>
 *   <li>{@code saturation} — mean HSL saturation vs neutral</li>
 *   <li>{@code highlight_warmth} — hue shift in bright regions</li>
 *   <li>{@code shadow_tint} — hue shift in dark regions</li>
 * </ul>
 */
public final class StyleStrawExtractor {

    // Neutral baselines (calibrated — must match Python extract_tone_params.py)
    private static final float NEUTRAL_CONTRAST_STD = 0.18f;
    private static final float NEUTRAL_HIGHLIGHTS_MEAN = 0.72f;
    private static final float NEUTRAL_SHADOWS_MEAN = 0.28f;
    private static final float NEUTRAL_SATURATION = 0.35f;
    private static final float NEUTRAL_WARMTH_DEG = 30.0f;   // orange-ish neutral
    private static final float NEUTRAL_SHADOW_HUE_DEG = 220.0f; // slightly blue neutral

    private StyleStrawExtractor() {}

    /**
     * Extract 6 tone parameters from a pre-scaled bitmap.
     *
     * @param bitmap scaled to ~256 px for performance (must be non-null)
     * @return map with keys: contrast, highlights, shadows, saturation,
     *         highlight_warmth, shadow_tint — all Float in [-100, +100]
     */
    public static Map<String, Object> extractToneParams(Bitmap bitmap) {
        Bitmap readableBitmap = ensureReadableBitmap(bitmap);
        int width = readableBitmap.getWidth();
        int height = readableBitmap.getHeight();
        int count = width * height;
        int[] pixels = new int[count];
        readableBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        if (NativeBridge.isAvailable()) {
            try {
                float[] nativeParams = NativeBridge.extractToneParams(pixels, width, height);
                if (nativeParams != null && nativeParams.length >= 6) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("contrast", nativeParams[0]);
                    result.put("highlights", nativeParams[1]);
                    result.put("shadows", nativeParams[2]);
                    result.put("saturation", nativeParams[3]);
                    result.put("highlight_warmth", nativeParams[4]);
                    result.put("shadow_tint", nativeParams[5]);
                    return result;
                }
            } catch (Throwable t) {
                Log.w("StyleStraw", "native tone extraction failed, using Java fallback", t);
            }
        }

        // Pre-allocate per-pixel arrays
        float[] luma = new float[count];
        float[] sat = new float[count];
        float[] hue = new float[count];

        // Single pass: compute luminance, HSL saturation, and hue for every pixel
        for (int i = 0; i < count; i++) {
            int pixel = pixels[i];
            float r = Color.red(pixel) / 255.0f;
            float g = Color.green(pixel) / 255.0f;
            float b = Color.blue(pixel) / 255.0f;

            // BT.709 luminance
            float L = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            luma[i] = L;

            // Min / max for HSL saturation and hue
            float cmax = Math.max(r, Math.max(g, b));
            float cmin = Math.min(r, Math.min(g, b));
            float delta = cmax - cmin;

            // HSL saturation: delta / (1 - |2L-1|)
            float midL = (cmax + cmin) * 0.5f;
            float denom = 1.0f - Math.abs(2.0f * midL - 1.0f);
            sat[i] = (denom > 1e-6f) ? Math.min(delta / denom, 1.0f) : 0.0f;

            // Hue (degrees 0..360)
            if (delta < 1e-7f) {
                hue[i] = 0.0f;
            } else if (cmax == r) {
                hue[i] = 60.0f * (((g - b) / delta) % 6.0f);
            } else if (cmax == g) {
                hue[i] = 60.0f * ((b - r) / delta + 2.0f);
            } else {
                hue[i] = 60.0f * ((r - g) / delta + 4.0f);
            }
            hue[i] = ((hue[i] % 360.0f) + 360.0f) % 360.0f;
        }

        // ── Contrast: std dev of luminance ──────────────────────────
        float lumaSum = 0.0f;
        for (float v : luma) lumaSum += v;
        float lumaMean = lumaSum / count;

        float lumaVarSum = 0.0f;
        for (float v : luma) {
            float d = v - lumaMean;
            lumaVarSum += d * d;
        }
        float lumaStd = (float) Math.sqrt(lumaVarSum / count);
        float contrast = clamp100((lumaStd - NEUTRAL_CONTRAST_STD) / NEUTRAL_CONTRAST_STD * 100.0f);

        // ── Highlights: mean luminance of pixels >= 0.5 ─────────────
        float hiSum = 0.0f;
        int hiCount = 0;
        for (float v : luma) {
            if (v >= 0.5f) { hiSum += v; hiCount++; }
        }
        float hiMean = hiCount > 0 ? hiSum / hiCount : 0.5f;
        float highlights = clamp100((hiMean - NEUTRAL_HIGHLIGHTS_MEAN) / NEUTRAL_HIGHLIGHTS_MEAN * 100.0f);

        // ── Shadows: mean luminance of pixels < 0.5 ─────────────────
        float loSum = 0.0f;
        int loCount = 0;
        for (float v : luma) {
            if (v < 0.5f) { loSum += v; loCount++; }
        }
        float loMean = loCount > 0 ? loSum / loCount : 0.5f;
        float shadows = clamp100((loMean - NEUTRAL_SHADOWS_MEAN) / NEUTRAL_SHADOWS_MEAN * 100.0f);

        // ── Saturation: mean HSL saturation ─────────────────────────
        float satSum = 0.0f;
        for (float v : sat) satSum += v;
        float satMean = satSum / count;
        float saturation = clamp100((satMean - NEUTRAL_SATURATION) / NEUTRAL_SATURATION * 100.0f);

        // ── Highlight warmth: circular mean of hues (luma >= 0.6, sat > 0.05) ──
        float highlightWarmth = 0.0f;
        {
            double sinSum = 0.0, cosSum = 0.0;
            int n = 0;
            for (int i = 0; i < count; i++) {
                if (luma[i] >= 0.6f && sat[i] > 0.05f) {
                    double rad = Math.toRadians(hue[i]);
                    sinSum += Math.sin(rad);
                    cosSum += Math.cos(rad);
                    n++;
                }
            }
            if (n > 50) {
                double meanHue = Math.toDegrees(Math.atan2(sinSum / n, cosSum / n));
                meanHue = ((meanHue % 360.0) + 360.0) % 360.0;
                double hueDiff = ((meanHue - NEUTRAL_WARMTH_DEG + 180.0) % 360.0) - 180.0;
                // Invert: positive = warmer (match Python)
                highlightWarmth = clamp100((float) (-hueDiff * 1.5));
            }
        }

        // ── Shadow tint: circular mean of hues (luma < 0.4, sat > 0.05) ────
        float shadowTint = 0.0f;
        {
            double sinSum = 0.0, cosSum = 0.0;
            int n = 0;
            for (int i = 0; i < count; i++) {
                if (luma[i] < 0.4f && sat[i] > 0.05f) {
                    double rad = Math.toRadians(hue[i]);
                    sinSum += Math.sin(rad);
                    cosSum += Math.cos(rad);
                    n++;
                }
            }
            if (n > 50) {
                double meanHue = Math.toDegrees(Math.atan2(sinSum / n, cosSum / n));
                meanHue = ((meanHue % 360.0) + 360.0) % 360.0;
                double hueDiff = ((meanHue - NEUTRAL_SHADOW_HUE_DEG + 180.0) % 360.0) - 180.0;
                shadowTint = clamp100((float) (hueDiff * 1.5));
            }
        }

        // Build result map with same keys as MasterMatchOverlay.proModeParams
        Map<String, Object> result = new HashMap<>();
        result.put("contrast", round1(contrast));
        result.put("highlights", round1(highlights));
        result.put("shadows", round1(shadows));
        result.put("saturation", round1(saturation));
        result.put("highlight_warmth", round1(highlightWarmth));
        result.put("shadow_tint", round1(shadowTint));
        return result;
    }

    /**
     * Return a software ARGB_8888 bitmap that supports {@code getPixels()}.
     *
     * <p>On Android 8.0+ a {@link Bitmap} returned by the gallery picker may be
     * hardware-backed ({@code Bitmap.Config.HARDWARE}).  Such bitmaps throw on
     * {@code getPixels()} and cannot be drawn onto a software {@link Canvas},
     * so the only safe conversion path is {@link Bitmap#copy(Bitmap.Config, boolean)}
     * which delegates to the GPU for the hardware->software readback.
     */
    public static Bitmap ensureReadableBitmap(Bitmap source) {
        if (source == null) {
            throw new IllegalArgumentException("bitmap == null");
        }
        Bitmap.Config config = source.getConfig();
        if (config == Bitmap.Config.ARGB_8888) {
            try {
                int[] probe = new int[1];
                source.getPixels(probe, 0, 1, 0, 0, 1, 1);
                return source;
            } catch (RuntimeException ignored) {
                // Fall through to copy().
            }
        }

        // copy() is the only API that can safely convert a HARDWARE bitmap to
        // a software bitmap.  Canvas.drawBitmap() on a software canvas throws
        // IllegalStateException for hardware sources.
        try {
            Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
            if (copy != null) {
                return copy;
            }
        } catch (Throwable t) {
            Log.w("StyleStraw", "Bitmap.copy(ARGB_8888) failed, falling back to Canvas", t);
        }

        // Last resort: try Canvas (works for non-hardware sources only).
        Bitmap fallback = Bitmap.createBitmap(
                Math.max(1, source.getWidth()),
                Math.max(1, source.getHeight()),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fallback);
        canvas.drawBitmap(source, 0.0f, 0.0f, null);
        return fallback;
    }

    private static float clamp100(float v) {
        return Math.max(-100.0f, Math.min(100.0f, v));
    }

    private static float round1(float v) {
        return Math.round(v * 10.0f) / 10.0f;
    }
}
