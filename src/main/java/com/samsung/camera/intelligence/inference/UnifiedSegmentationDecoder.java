package com.samsung.camera.intelligence.inference;

import com.samsung.camera.intelligence.recommendation.DefectLocalizer;

import java.util.List;

/** Decode combined CLIP-B16+SegNeXt logits into masks and defect regions. */
public class UnifiedSegmentationDecoder {

    private static final DefectLocalizer.DefectType[] DEFECT_TYPES = new DefectLocalizer.DefectType[] {
            DefectLocalizer.DefectType.SHADOW,
            DefectLocalizer.DefectType.REFLECTION,
            DefectLocalizer.DefectType.FLARE,
            DefectLocalizer.DefectType.MOIRE,
            DefectLocalizer.DefectType.BACKGROUND_PEOPLE
    };

    private final SegmentationRunner bboxHelper;

    public UnifiedSegmentationDecoder() {
        this.bboxHelper = null;
    }

    public DefectLocalizer.LocalizationResult decode(float[] logits, int height, int width,
                                                     int classes, float threshold,
                                                     float minRegionRatio,
                                                     float maxRegionRatio) {
        DefectLocalizer.LocalizationResult result = new DefectLocalizer.LocalizationResult();
        if (logits == null || classes <= 0 || height <= 0 || width <= 0) {
            return result;
        }
        int expected = height * width * classes;
        if (logits.length < expected) {
            return result;
        }

        // Per-class degeneracy guard: if more than this fraction of pixels
        // are positive after thresholding, the head almost certainly mis-fired
        // (e.g. it returned a near-uniform high-confidence map). Treat as
        // "no useful localization" rather than painting the entire image.
        final float maxPositiveFraction = 0.65f;
        final int totalPixels = height * width;

        for (int c = 0; c < Math.min(classes, DEFECT_TYPES.length); c++) {
            float[][] rawProb = new float[height][width];
            float[][] binMask = new float[height][width];
            float maxProb = 0f;
            int positives = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = ((y * width) + x) * classes + c;
                    float prob = sigmoid(logits[idx]);
                    rawProb[y][x] = prob;
                    if (prob >= threshold) {
                        binMask[y][x] = 1f;
                        positives++;
                    }
                    if (prob > maxProb) maxProb = prob;
                }
            }
            if (maxProb < threshold || positives == 0) {
                continue;
            }
            float positiveFraction = (float) positives / (float) totalPixels;
            if (positiveFraction > maxPositiveFraction) {
                // Degenerate: the head is "lit up" almost everywhere — skip
                // rendering so the UI doesn't tint the whole image.
                continue;
            }
            DefectLocalizer.DefectType type = DEFECT_TYPES[c];
            // Store the BINARIZED mask so downstream renderers paint only
            // active pixels (not faint global probability shading).
            result.masks.put(type.value, binMask);
            List<DefectLocalizer.DefectRegion> regions = maskToBoundingBoxes(
                    rawProb, type, threshold, minRegionRatio, maxRegionRatio);
            result.regions.addAll(regions);
            result.detectorsRun.add("UnifiedSegNeXt:" + type.value);
        }
        return result;
    }

    private static List<DefectLocalizer.DefectRegion> maskToBoundingBoxes(
            float[][] mask, DefectLocalizer.DefectType type, float threshold,
            float minRegionRatio, float maxRegionRatio) {
        // Reuse the Android-friendly connected-component approximation in SegmentationRunner.
        SegmentationRunner helper = new SegmentationRunner(null, "combined", 1, 1);
        return helper.maskToBoundingBoxes(mask, type, threshold, minRegionRatio, maxRegionRatio);
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }
}
