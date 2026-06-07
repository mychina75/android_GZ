package com.samsung.camera.intelligence.trigger;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.Map;

/** Lightweight raw-frame estimate for small text/card/sign regions. */
public final class TextRegionStats {
    private static final int PATCH = 48;
    private static final int COLS = 7;
    private static final int ROWS = 11;

    private final int[] patchBuf = new int[PATCH * PATCH];

    public static final class Result {
        public final float textRegionProbability;
        public final float documentCardProbability;
        public final int candidatePatches;
        public final float maxPatchScore;
        public final float centerBiasScore;

        Result(float textRegionProbability, float documentCardProbability,
               int candidatePatches, float maxPatchScore, float centerBiasScore) {
            this.textRegionProbability = textRegionProbability;
            this.documentCardProbability = documentCardProbability;
            this.candidatePatches = candidatePatches;
            this.maxPatchScore = maxPatchScore;
            this.centerBiasScore = centerBiasScore;
        }
    }

    public Result analyze(Bitmap frame) {
        if (frame == null || frame.getWidth() < PATCH * 3 || frame.getHeight() < PATCH * 3) {
            return new Result(0f, 0f, 0, 0f, 0f);
        }
        int width = frame.getWidth();
        int height = frame.getHeight();
        int candidates = 0;
        float sumScore = 0f;
        float maxScore = 0f;
        float centerScore = 0f;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int x = Math.round(col * (width - PATCH) / (float) (COLS - 1));
                int y = Math.round(row * (height - PATCH) / (float) (ROWS - 1));
                float score = scorePatch(frame, new Rect(x, y, x + PATCH, y + PATCH));
                if (score <= 0f) {
                    continue;
                }
                candidates++;
                sumScore += score;
                maxScore = Math.max(maxScore, score);
                float normX = (x + PATCH * 0.5f) / width;
                float normY = (y + PATCH * 0.5f) / height;
                float centerWeight = Math.max(0f, 1f - 1.6f * distanceFromCenter(normX, normY));
                centerScore += score * centerWeight;
            }
        }
        float countScore = Math.min(1f, candidates / 4f);
        float textProb = clamp01(0.20f * countScore + 0.50f * maxScore + 0.08f * sumScore);
        float cardProb = clamp01(0.15f * countScore + 0.45f * maxScore + 0.15f * centerScore);
        return new Result(textProb, cardProb, candidates, maxScore, centerScore);
    }

    public void injectInto(Bitmap frame, Map<String, Float> signals) {
        Result result = analyze(frame);
        signals.put("text_region_prob", result.textRegionProbability);
        signals.put("document_card_prob", result.documentCardProbability);
        signals.put("text_region_patch_count", (float) result.candidatePatches);
        signals.put("text_region_max_score", result.maxPatchScore);
        signals.put("text_region_center_score", result.centerBiasScore);
    }

    /** Same as {@link #injectInto(Bitmap, Map)} but reusing a precomputed result. */
    public static void injectInto(Map<String, Float> signals, Result result) {
        if (result == null || signals == null) return;
        signals.put("text_region_prob", result.textRegionProbability);
        signals.put("document_card_prob", result.documentCardProbability);
        signals.put("text_region_patch_count", (float) result.candidatePatches);
        signals.put("text_region_max_score", result.maxPatchScore);
        signals.put("text_region_center_score", result.centerBiasScore);
    }

    private float scorePatch(Bitmap frame, Rect rect) {
        frame.getPixels(patchBuf, 0, PATCH, rect.left, rect.top, PATCH, PATCH);
        float[] luma = new float[PATCH * PATCH];
        float sum = 0f;
        float sumSq = 0f;
        int darkPixels = 0;
        int brightPixels = 0;
        int yellowishPixels = 0;
        for (int index = 0; index < luma.length; index++) {
            int pixel = patchBuf[index];
            int red = (pixel >> 16) & 0xFF;
            int green = (pixel >> 8) & 0xFF;
            int blue = pixel & 0xFF;
            float value = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f;
            luma[index] = value;
            sum += value;
            sumSq += value * value;
            if (value < 0.30f) darkPixels++;
            if (value > 0.58f) brightPixels++;
            if (red > 135 && green > 110 && blue < red * 0.85f && blue < green * 0.95f) {
                yellowishPixels++;
            }
        }
        float pixels = luma.length;
        float mean = sum / pixels;
        float variance = Math.max(0f, sumSq / pixels - mean * mean);
        float std = (float) Math.sqrt(variance);
        float darkRatio = darkPixels / pixels;
        float brightRatio = brightPixels / pixels;
        float yellowRatio = yellowishPixels / pixels;

        float edgeSum = 0f;
        int strongEdges = 0;
        int edgeCount = 0;
        for (int y = 1; y < PATCH - 1; y++) {
            for (int x = 1; x < PATCH - 1; x++) {
                int center = y * PATCH + x;
                float gx = Math.abs(luma[center + 1] - luma[center - 1]);
                float gy = Math.abs(luma[center + PATCH] - luma[center - PATCH]);
                float edge = gx + gy;
                edgeSum += edge;
                if (edge > 0.18f) strongEdges++;
                edgeCount++;
            }
        }
        float edgeDensity = strongEdges / Math.max(1f, (float) edgeCount);
        float edgeMean = edgeSum / Math.max(1f, (float) edgeCount);
        boolean brightPanel = brightRatio > 0.28f || yellowRatio > 0.20f || mean > 0.48f;
        boolean darkStrokes = darkRatio > 0.03f && darkRatio < 0.62f;
        boolean textEdges = edgeDensity > 0.035f || edgeMean > 0.052f || std > 0.16f;
        if (!brightPanel || !darkStrokes || !textEdges) {
            return 0f;
        }
        float panelScore = clamp01((brightRatio + yellowRatio * 0.8f - 0.20f) / 0.55f);
        float strokeScore = clamp01((darkRatio - 0.02f) / 0.22f);
        float edgeScore = clamp01((Math.max(edgeDensity * 2.0f, edgeMean) - 0.035f) / 0.20f);
        return clamp01(0.30f * panelScore + 0.35f * strokeScore + 0.35f * edgeScore);
    }

    private static float distanceFromCenter(float x, float y) {
        float dx = x - 0.5f;
        float dy = y - 0.5f;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
