package com.samsung.camera.intelligence.trigger;

import android.graphics.Bitmap;

import java.util.Map;

/**
 * CV proxy for background defocus / bokeh strength.
 *
 * <p>The model emits no background-blur output, so we estimate it from the
 * preview frame.  We compute the variance of the Laplacian (a standard
 * sharpness measure) separately for the subject bounding box and for the
 * background (everything outside it):</p>
 * <ul>
 *   <li>{@code bg_sharp_ratio = varLap(bg) / max(varLap(subject), eps)} —
 *       higher means the background is as crisp as the subject (no bokeh).</li>
 *   <li>{@code bg_blur_strength = clip01(1 - bg_sharp_ratio)} — higher means
 *       the background is strongly defocused relative to the subject.</li>
 * </ul>
 *
 * <p>These feed the {@code tele_portrait} gate so we stop suggesting a
 * portrait blur when the optics already produced one.  Sampled on a coarse
 * grid for speed.</p>
 */
public final class BackgroundBokehStats {

    /** Coarse luma grid resolution used for the Laplacian. */
    private static final int GRID = 48;
    private static final float EPS = 1e-3f;

    private final float[] luma = new float[GRID * GRID];
    private final boolean[] isSubject = new boolean[GRID * GRID];

    public static final class Result {
        public final float bgSharpRatio;
        public final float bgBlurStrength;
        Result(float ratio, float strength) {
            this.bgSharpRatio = ratio;
            this.bgBlurStrength = strength;
        }
    }

    public BackgroundBokehStats() {
    }

    /**
     * @param frame preview bitmap (ARGB_8888)
     * @param bx,by,bw,bh normalized subject bbox (top-left + size, 0..1)
     */
    public Result analyze(Bitmap frame, float bx, float by, float bw, float bh) {
        if (frame == null || frame.getWidth() < GRID || frame.getHeight() < GRID) {
            return new Result(1f, 0f);
        }
        final int W = frame.getWidth();
        final int H = frame.getHeight();

        // Sample luma onto the GRID x GRID lattice and mark subject cells.
        float sx0 = clamp01(bx), sy0 = clamp01(by);
        float sx1 = clamp01(bx + bw), sy1 = clamp01(by + bh);
        for (int gy = 0; gy < GRID; gy++) {
            int py = (int) (((gy + 0.5f) / GRID) * H);
            if (py >= H) py = H - 1;
            for (int gx = 0; gx < GRID; gx++) {
                int px = sampleX(gx, W);
                int c = frame.getPixel(px, py);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                float y = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                int idx = gy * GRID + gx;
                luma[idx] = y;
                float fx = (gx + 0.5f) / GRID;
                float fy = (gy + 0.5f) / GRID;
                isSubject[idx] = (fx >= sx0 && fx <= sx1 && fy >= sy0 && fy <= sy1)
                        && (sx1 > sx0) && (sy1 > sy0);
            }
        }

        // Variance of the 4-neighbour Laplacian, split by region.
        double subjSum = 0, subjSumSq = 0;
        int subjN = 0;
        double bgSum = 0, bgSumSq = 0;
        int bgN = 0;
        for (int gy = 1; gy < GRID - 1; gy++) {
            for (int gx = 1; gx < GRID - 1; gx++) {
                int idx = gy * GRID + gx;
                float lap = 4f * luma[idx]
                        - luma[idx - 1] - luma[idx + 1]
                        - luma[idx - GRID] - luma[idx + GRID];
                if (isSubject[idx]) {
                    subjSum += lap; subjSumSq += (double) lap * lap; subjN++;
                } else {
                    bgSum += lap; bgSumSq += (double) lap * lap; bgN++;
                }
            }
        }
        float subjVar = variance(subjSum, subjSumSq, subjN);
        float bgVar = variance(bgSum, bgSumSq, bgN);

        if (subjN == 0 || bgN == 0) {
            return new Result(1f, 0f);
        }
        float ratio = bgVar / Math.max(subjVar, EPS);
        float strength = clamp01(1f - ratio);
        return new Result(ratio, strength);
    }

    /** Convenience: read bbox from signals, analyze, and inject both signals. */
    public void injectInto(Bitmap frame, Map<String, Float> signals) {
        float bx = get(signals, "bbox_x", 0.25f);
        float by = get(signals, "bbox_y", 0.25f);
        float bw = get(signals, "bbox_w", 0.5f);
        float bh = get(signals, "bbox_h", 0.5f);
        Result r = analyze(frame, bx, by, bw, bh);
        signals.put("bg_sharp_ratio", r.bgSharpRatio);
        signals.put("bg_blur_strength", r.bgBlurStrength);
    }

    private static float variance(double sum, double sumSq, int n) {
        if (n <= 0) return 0f;
        double mean = sum / n;
        double var = sumSq / n - mean * mean;
        return (float) Math.max(0.0, var);
    }

    private static int sampleX(int gx, int w) {
        int px = (int) (((gx + 0.5f) / GRID) * w);
        return px >= w ? w - 1 : px;
    }

    private static float get(Map<String, Float> m, String k, float def) {
        Float v = m == null ? null : m.get(k);
        return v == null ? def : v;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
