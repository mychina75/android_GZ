package com.samsung.camera.intelligence.guidance;

import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase A — chooses one {@link CompositionTemplate} per frame from the
 * scene type + multi-task model heads. Carries per-template hysteresis
 * state so the visible sketch does not flicker frame-to-frame.
 *
 * Priority order (first matching trigger wins):
 *   1. SYMMETRY_VERTICAL      — has_symmetry + architectural scene
 *   2. HORIZON_THIRDS         — landscape-ish scene without salient subject
 *   3. GOLDEN_SPIRAL          — architecture/portrait + symmetry
 *   4. LEADING_LINES_X        — has_leading_lines
 *   5. DIAGONAL               — has_diagonal_lines
 *   6. CENTERED               — product/food/document/macro/flower/pet,
 *                                or single dominant face
 *   7. RULE_OF_THIRDS         — fallback default
 *
 * The chosen template only switches when a different candidate "wins"
 * for at least {@link #SWITCH_HOLD_FRAMES} consecutive frames.
 */
public final class TemplateChooser {

    private static final String TAG = "TemplateChooser";
    private static final int SWITCH_HOLD_FRAMES = 8;

    private static final float[][] ROT_POINTS = {
        {1f / 3f, 1f / 3f}, {2f / 3f, 1f / 3f},
        {1f / 3f, 2f / 3f}, {2f / 3f, 2f / 3f},
    };

    private static final Set<String> ARCHITECTURE_SCENES = new HashSet<>(Arrays.asList(
        "architecture", "architecture_exterior", "architecture_interior", "cityscape"
    ));
    private static final Set<String> LANDSCAPE_SCENES = new HashSet<>(Arrays.asList(
        "landscape", "panoramic", "sunset_sunrise", "waterfall", "night_cityscape"
    ));
    private static final Set<String> CENTERED_SCENES = new HashSet<>(Arrays.asList(
        "product", "food", "document", "macro", "flower", "pet"
    ));
    private static final Set<String> PORTRAIT_SCENES = new HashSet<>(Arrays.asList(
        "portrait", "selfie", "night_portrait", "backlit_portrait"
    ));

    // Hysteresis state.
    private CompositionTemplate.Type currentType = CompositionTemplate.Type.RULE_OF_THIRDS;
    private CompositionTemplate.Type pendingType = null;
    private int pendingFrames = 0;

    public CompositionTemplate choose(FrameAnalysis analysis,
                                      float[] subjectCenter,
                                      float[] subjectSize,
                                      float[] crop) {
        CompositionTemplate.Type candidate = pickByPriority(analysis, subjectCenter);

        // Hysteresis: only switch once a different candidate has been
        // dominant for SWITCH_HOLD_FRAMES consecutive frames.
        if (candidate == currentType) {
            pendingType = null;
            pendingFrames = 0;
        } else if (candidate == pendingType) {
            pendingFrames++;
            if (pendingFrames >= SWITCH_HOLD_FRAMES) {
                Log.i(TAG, "Switching template " + currentType + " -> " + candidate);
                currentType = candidate;
                pendingType = null;
                pendingFrames = 0;
            }
        } else {
            pendingType = candidate;
            pendingFrames = 1;
        }

        return materialize(currentType, analysis, subjectCenter, subjectSize, crop);
    }

    private CompositionTemplate.Type pickByPriority(FrameAnalysis a, float[] subjectCenter) {
        String scene = a.getSceneType() == null ? "" : a.getSceneType();
        boolean hasSubject = subjectCenter != null;
        float fillRatio = a.getSubjectFillRatio();

        // 1. Symmetry vertical — strong architectural symmetry signal.
        if (a.isHasSymmetry() && ARCHITECTURE_SCENES.contains(scene)) {
            return CompositionTemplate.Type.SYMMETRY_VERTICAL;
        }
        // 2. Horizon thirds — landscape scenes without a salient subject.
        if (LANDSCAPE_SCENES.contains(scene) && (!hasSubject || fillRatio < 0.10f)) {
            return CompositionTemplate.Type.HORIZON_THIRDS;
        }
        // 3. Golden spiral — symmetry hint + architecture/portrait.
        if (a.isHasSymmetry() && (ARCHITECTURE_SCENES.contains(scene)
                || PORTRAIT_SCENES.contains(scene))) {
            return CompositionTemplate.Type.GOLDEN_SPIRAL;
        }
        // 4. Leading lines.
        if (a.isHasLeadingLines()) {
            return CompositionTemplate.Type.LEADING_LINES_X;
        }
        // 5. Diagonal.
        if (a.isHasDiagonalLines()) {
            return CompositionTemplate.Type.DIAGONAL;
        }
        // 6. Centered — product/food/macro, or one dominant face.
        if (CENTERED_SCENES.contains(scene)
                || (a.getFaceCount() == 1 && fillRatio > 0.40f)) {
            return CompositionTemplate.Type.CENTERED;
        }
        // 7. Default: rule of thirds.
        return CompositionTemplate.Type.RULE_OF_THIRDS;
    }

    private CompositionTemplate materialize(CompositionTemplate.Type t,
                                            FrameAnalysis a,
                                            float[] subjectCenter,
                                            float[] subjectSize,
                                            float[] crop) {
        switch (t) {
            case SYMMETRY_VERTICAL: {
                float ax = 0.5f, ay = subjectCenter != null ? subjectCenter[1] : 0.5f;
                return new CompositionTemplate(t, new float[]{ax, ay},
                        new float[]{0.5f}, "Symmetry");
            }
            case SYMMETRY_HORIZONTAL: {
                float ax = subjectCenter != null ? subjectCenter[0] : 0.5f;
                return new CompositionTemplate(t, new float[]{ax, 0.5f},
                        new float[]{0.5f}, "Symmetry");
            }
            case HORIZON_THIRDS: {
                // Default lower third (sky-heavy). Heuristic: if subject is in upper
                // half pick upper third instead.
                float horizonY = 0.667f;
                float ax = 0.5f;
                if (subjectCenter != null) {
                    ax = subjectCenter[0];
                    if (subjectCenter[1] < 0.5f) horizonY = 0.333f;
                }
                return new CompositionTemplate(t, new float[]{ax, horizonY},
                        new float[]{horizonY}, "Horizon");
            }
            case LEADING_LINES_X: {
                // Anchor on the suggested-crop power-point closest to the
                // subject. No fake cross-frame X lines (they did not provide
                // useful guidance per user feedback).
                float[] pp = nearestPowerPointInCrop(subjectCenter, crop);
                return new CompositionTemplate(t, new float[]{pp[0], pp[1]},
                        new float[0], "Leading Lines");
            }
            case DIAGONAL: {
                float[] pp = nearestPowerPointInCrop(subjectCenter, crop);
                return new CompositionTemplate(t, new float[]{pp[0], pp[1]},
                        new float[0], "Diagonal");
            }
            case GOLDEN_SPIRAL: {
                // Phi spiral focal point ≈ (0.382, 0.382). Pick orientation by
                // which corner the subject is closest to.
                float ax = 0.382f, ay = 0.382f;
                int orient = 0;
                if (subjectCenter != null) {
                    boolean right = subjectCenter[0] > 0.5f;
                    boolean bottom = subjectCenter[1] > 0.5f;
                    if (right && !bottom) { orient = 1; ax = 0.618f; ay = 0.382f; }
                    else if (right && bottom) { orient = 2; ax = 0.618f; ay = 0.618f; }
                    else if (!right && bottom) { orient = 3; ax = 0.382f; ay = 0.618f; }
                }
                return new CompositionTemplate(t, new float[]{ax, ay},
                        new float[]{0f, 0f, 1f, 1f, orient}, "Golden Spiral");
            }
            case CENTERED: {
                return new CompositionTemplate(t, new float[]{0.5f, 0.5f},
                        new float[0], "Centered");
            }
            case FRAME_WITHIN_FRAME: {
                float ix = crop != null ? crop[0] : 0.1f;
                float iy = crop != null ? crop[1] : 0.1f;
                float iw = crop != null ? crop[2] : 0.8f;
                float ih = crop != null ? crop[3] : 0.8f;
                float cx = ix + iw * 0.5f, cy = iy + ih * 0.5f;
                return new CompositionTemplate(t, new float[]{cx, cy},
                        new float[]{ix, iy, iw, ih}, "Frame in Frame");
            }
            case RULE_OF_THIRDS:
            default: {
                // Power-point of the SUGGESTED CROP (not the full frame).
                // This is what users care about — "place the subject at the
                // recommended composition's power-point" — instead of
                // snapping to the static full-frame grid intersection.
                float[] pp = nearestPowerPointInCrop(subjectCenter, crop);
                return new CompositionTemplate(CompositionTemplate.Type.RULE_OF_THIRDS,
                        new float[]{pp[0], pp[1]},
                        new float[]{pp[0], pp[1]},
                        "Rule of Thirds");
            }
        }
    }

    private static float[] nearestPowerPoint(float[] c) {
        float best = Float.MAX_VALUE;
        float[] out = ROT_POINTS[0];
        for (float[] pp : ROT_POINTS) {
            float d = (pp[0] - c[0]) * (pp[0] - c[0]) + (pp[1] - c[1]) * (pp[1] - c[1]);
            if (d < best) { best = d; out = pp; }
        }
        return out;
    }

    /**
     * Returns the rule-of-thirds power-point of the suggested CROP rectangle
     * (mapped back into full-frame normalized coordinates) closest to the
     * subject. When no subject is detected, returns the lower-right power-point
     * of the crop (a safe default for portrait/landscape).
     */
    private static float[] nearestPowerPointInCrop(float[] subjectCenter, float[] crop) {
        if (crop == null || crop.length < 4) {
            float[] f = (subjectCenter != null) ? nearestPowerPoint(subjectCenter)
                                                : new float[]{2f / 3f, 1f / 3f};
            return new float[]{f[0], f[1]};
        }
        if (subjectCenter == null) {
            return new float[]{crop[0] + crop[2] * 2f / 3f,
                               crop[1] + crop[3] * 1f / 3f};
        }
        float bestDist = Float.MAX_VALUE;
        float bestX = crop[0] + crop[2] * 0.5f;
        float bestY = crop[1] + crop[3] * 0.5f;
        for (float[] pp : ROT_POINTS) {
            float ax = crop[0] + pp[0] * crop[2];
            float ay = crop[1] + pp[1] * crop[3];
            float d = (ax - subjectCenter[0]) * (ax - subjectCenter[0])
                    + (ay - subjectCenter[1]) * (ay - subjectCenter[1]);
            if (d < bestDist) { bestDist = d; bestX = ax; bestY = ay; }
        }
        return new float[]{bestX, bestY};
    }
}
