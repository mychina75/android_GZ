package com.samsung.camera.intelligence.app.camera;

import android.graphics.Bitmap;
import android.util.Log;

import com.samsung.camera.intelligence.trigger.NativeBridge;

/**
 * Compose a base camera-style LUT with a per-photo tone overlay into a single
 * 3D LUT bitmap that can be uploaded to the GPU.
 *
 * <p>The base LUT (e.g. from {@link CameraStylePresetManager}) encodes only the
 * cluster's color signature (saturation / highlight warmth / shadow tint).  The
 * overlay applies the photo-specific tone parameters
 * (contrast / highlights / shadows / saturation / highlight warmth / shadow
 * tint) on top of the base LUT's output.
 *
 * <p>Algorithm is identical to {@link LutToneMapper#generateLutBitmap} so that
 * <code>compose(baseLut, c, h, s, sat, w, t)(input)</code> ≈
 * <code>applyToneOverlay(baseLut(input), c, h, s, sat, w, t)</code> within the
 * trilinear interpolation budget (PSNR ≥ 45 dB on synthetic test panels).
 */
public final class LutComposer {

    private static final String TAG = "LutComposer";

    private static final int N = LutToneMapper.LUT_SIZE;       // 33
    private static final int WIDTH = N * N;                    // 1089

    private LutComposer() {}

    /**
     * Compose a base LUT with a tone overlay.
     *
     * @param baseLut    base LUT bitmap, must be {@code WIDTH × N}, ARGB_8888.
     *                   Not modified; ownership remains with the caller.
     * @param contrast        -100..+100
     * @param highlights      -100..+100
     * @param shadows         -100..+100
     * @param saturation      -100..+100
     * @param highlightWarmth -100..+100
     * @param shadowTint      -100..+100
     * @return a new ARGB_8888 bitmap of the same size with the overlay applied
     */
    public static Bitmap compose(Bitmap baseLut,
                                 float contrast, float highlights, float shadows,
                                 float saturation, float highlightWarmth, float shadowTint) {
        return compose(baseLut, contrast, highlights, shadows, saturation,
                highlightWarmth, shadowTint, 0f, null);
    }

    /**
     * Compose a base LUT with a tone overlay, an optional global brightness
     * lift, and optional 3-zone split-toning.
     *
     * <p>The extra parameters extend the 6-param overlay so a Mimic card can
     * reproduce the reference image's absolute brightness/atmosphere (via the
     * global lift, baked straight into the LUT so it carries to both preview
     * and saved JPEG) and richer per-band colour casts (split-toning) that a
     * single global warmth/tint pair cannot represent.
     *
     * <p>When {@code brightness == 0} and {@code splitTone} is {@code null} (or
     * all-zero) the call is byte-for-byte equivalent to the 6-param overload and
     * is dispatched to the native compose path; otherwise a pure-Java extended
     * path is used.  This keeps the existing native fast-path untouched.
     *
     * @param brightness -100..+100 global lift (positive = brighter); applied
     *                   as a shadow/midtone-weighted lift so highlights are not
     *                   blown.  0 = no-op.
     * @param splitTone  optional 6-element array
     *                   {shadowWc, shadowGm, midWc, midGm, highWc, highGm},
     *                   each -100..+100.  {@code null} = no split-toning.
     */
    public static Bitmap compose(Bitmap baseLut,
                                 float contrast, float highlights, float shadows,
                                 float saturation, float highlightWarmth, float shadowTint,
                                 float brightness, float[] splitTone) {
        if (baseLut == null) return null;
        boolean hasBrightness = Math.abs(brightness) > 1f;
        boolean hasSplit = splitToneActive(splitTone);

        if (!hasBrightness && !hasSplit && NativeBridge.isAvailable()) {
            try {
                int w = baseLut.getWidth();
                int h = baseLut.getHeight();
                int[] src = new int[w * h];
                baseLut.getPixels(src, 0, w, 0, 0, w, h);
                int[] dst = NativeBridge.composeLut(src, w, h,
                        contrast, highlights, shadows, saturation, highlightWarmth, shadowTint);
                if (dst != null && dst.length == src.length) {
                    Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    out.setPixels(dst, 0, w, 0, 0, w, h);
                    return out;
                }
            } catch (Throwable t) {
                Log.w(TAG, "native LUT compose failed, using Java fallback", t);
            }
        }
        float[] tone = LutToneMapper.dampenExtremeToneSpread(contrast, highlights, shadows);
        contrast = tone[0];
        highlights = tone[1];
        shadows = tone[2];

        int n = N;
        int w = WIDTH;
        int h = n;
        if (baseLut.getWidth() != w || baseLut.getHeight() != h) {
            throw new IllegalArgumentException("Base LUT must be " + w + "×" + h);
        }

        // Read base pixels
        int[] src = new int[w * h];
        baseLut.getPixels(src, 0, w, 0, 0, w, h);

        // Build the per-channel display curve once (same for all pixels).
        // The global brightness lift is folded into the same 1-D curve so it is
        // baked straight into the LUT (carries to preview AND saved JPEG).
        float[] toneLut = buildDisplayCurve(n, contrast, highlights, shadows);
        if (hasBrightness) {
            applyBrightnessLift(toneLut, brightness);
        }

        boolean applySat = Math.abs(saturation) > 1f;
        float satFactor = applySat ? clamp(1f + saturation / 100f, 0.5f, 1.5f) : 1f;

        boolean applyShift = Math.abs(highlightWarmth) > 1f || Math.abs(shadowTint) > 1f;
        float warmthStr = highlightWarmth / 100f * 0.08f;
        float tintStr = shadowTint / 100f * 0.08f;

        // 3-zone split-tone strengths (per-band warm-cool / green-magenta).
        // At ±100 a band contributes ±0.06 of channel separation.
        float[] st = hasSplit ? splitTone : null;

        int[] dst = new int[w * h];
        for (int i = 0; i < src.length; i++) {
            int px = src[i];
            int a = (px >>> 24) & 0xFF;
            float r = ((px >> 16) & 0xFF) / 255f;
            float g = ((px >> 8) & 0xFF) / 255f;
            float b = (px & 0xFF) / 255f;

            // 1) Tone curve (using base LUT entry as the "input" being adjusted).
            //    We sample toneLut by quantising the base value to LUT bin —
            //    cheap and good enough since LUT is dense (33 bins).
            r = sampleToneLut(toneLut, r);
            g = sampleToneLut(toneLut, g);
            b = sampleToneLut(toneLut, b);

            // 2) Saturation
            if (applySat) {
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = lum + (r - lum) * satFactor;
                g = lum + (g - lum) * satFactor;
                b = lum + (b - lum) * satFactor;
            }

            // 3) Highlight warmth + shadow tint
            if (applyShift) {
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                if (Math.abs(highlightWarmth) > 1f) {
                    float weight = smoothstep(0.4f, 0.8f, lum);
                    r += warmthStr * weight;
                    b -= warmthStr * weight;
                }
                if (Math.abs(shadowTint) > 1f) {
                    float weight = 1f - smoothstep(0.2f, 0.5f, lum);
                    r += tintStr * weight;
                    b -= tintStr * weight;
                }
            }

            // 4) 3-zone split-toning (shadows / midtones / highlights casts)
            if (st != null) {
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float wShadow = 1f - smoothstep(0.15f, 0.5f, lum);
                float wHigh = smoothstep(0.5f, 0.85f, lum);
                float wMid = 1f - wShadow - wHigh;
                if (wMid < 0f) wMid = 0f;
                float wc = st[0] * wShadow + st[2] * wMid + st[4] * wHigh;
                float gm = st[1] * wShadow + st[3] * wMid + st[5] * wHigh;
                float wcStr = wc / 100f * 0.06f;   // R↑/B↓ = warm
                float gmStr = gm / 100f * 0.06f;   // G↑ = green
                r += wcStr;
                b -= wcStr;
                g += gmStr;
                r -= gmStr * 0.5f;
                b -= gmStr * 0.5f;
            }

            r = clamp(r, 0f, 1f);
            g = clamp(g, 0f, 1f);
            b = clamp(b, 0f, 1f);
            dst[i] = (a << 24) | (toByte(r) << 16) | (toByte(g) << 8) | toByte(b);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    /** True when any split-tone band carries a non-trivial cast. */
    private static boolean splitToneActive(float[] st) {
        if (st == null || st.length < 6) return false;
        for (int i = 0; i < 6; i++) {
            if (Math.abs(st[i]) > 1f) return true;
        }
        return false;
    }

    /**
     * Fold a global brightness lift into an existing 1-D tone curve, in place.
     *
     * <p>Positive {@code brightness} lifts shadows and midtones strongly while
     * tapering toward the highlights so bright regions are not blown; negative
     * darkens symmetrically.  Monotonicity is preserved.  At ±100 the peak
     * midtone shift is ±0.22.
     */
    static void applyBrightnessLift(float[] lut, float brightness) {
        float amount = clamp(brightness / 100f, -1f, 1f) * 0.22f;
        int n = lut.length;
        for (int i = 0; i < n; i++) {
            float y = lut[i];
            // Shadow/midtone-weighted gain: strongest near 0.0, fades out by
            // ~0.85 so highlights are largely preserved.
            float weight = 1f - smoothstep(0.0f, 0.85f, y);
            lut[i] = clamp(y + amount * weight, 0f, 1f);
        }
        for (int i = 1; i < n; i++) {
            if (lut[i] < lut[i - 1]) lut[i] = lut[i - 1];
        }
    }

    /** Linear-interpolated sample of a 1-D LUT keyed in [0, 1]. */
    private static float sampleToneLut(float[] lut, float x) {
        int n = lut.length;
        float idx = clamp(x, 0f, 1f) * (n - 1);
        int i0 = (int) idx;
        int i1 = Math.min(i0 + 1, n - 1);
        float f = idx - i0;
        return lut[i0] * (1f - f) + lut[i1] * f;
    }

    /** Build the same display tone curve as {@link LutToneMapper}. */
    private static float[] buildDisplayCurve(int n, float contrast,
                                             float highlights, float shadows) {
        float[] lut = new float[n];
        for (int i = 0; i < n; i++) {
            float x = (float) i / (n - 1);
            float y = x;
            // Contrast
            if (Math.abs(contrast) >= 1f) {
                float strength = contrast / 100f * 0.7f;
                float s = (float) (0.5 * Math.sin(Math.PI * (y - 0.5)));
                y = y + strength * s;
            }
            // Highlights
            if (Math.abs(highlights) >= 1f) {
                float weight = smoothstep(0.35f, 0.75f, y);
                y = y + (highlights / 100f * 0.30f) * weight;
            }
            // Shadows
            if (Math.abs(shadows) >= 1f) {
                float weight = 1f - smoothstep(0.20f, 0.55f, y);
                y = y + (shadows / 100f * 0.25f) * weight;
            }
            lut[i] = clamp(y, 0f, 1f);
        }
        // Monotonicity
        for (int i = 1; i < n; i++) {
            if (lut[i] < lut[i - 1]) lut[i] = lut[i - 1];
        }
        return lut;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int toByte(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }
}
