package com.samsung.camera.intelligence.trigger;

import java.util.HashMap;
import java.util.Map;

/**
 * Collapse the raw {@code Map<String, float[]>} returned by
 * {@code TFLiteInferenceEngine} into a fixed-name signals map consumed by
 * {@link TriggerScorer} (L0 rules) and {@link TriggerExternalHead} (L1).
 *
 * <p>Mirror of {@code recommendation/triggers/feature_vector.py}.  The key
 * set MUST stay aligned so that the same rule weights can run on both
 * sides.  Signal keys not derivable from a given model output remain at 0.</p>
 */
public final class TriggerSignals {

    /** Canonical 14 composition-issue names (multi-label sigmoid head). */
    public static final String[] COMPOSITION_ISSUE_NAMES = new String[] {
            "none",
            "subject_off_center",
            "poor_rule_of_thirds",
            "unbalanced",
            "distracting_elements",
            "too_much_headroom",
            "insufficient_headroom",
            "poor_framing",
            "horizon_not_level",
            "cluttered_background",
            "awkward_cropping",
            "needs_recomposition",
            "subject_too_small",
            "subject_cut_off",
    };

    /** Heads that are categorical (collapse to argmax id + max prob). */
    private static final String[][] CATEGORICAL_HEADS = new String[][] {
            {"scene_type",      "scene_type"},
            {"lighting",        "lighting"},
            {"motion",          "motion"},
            {"subject",         "subject"},
            {"speed_logits",    "speed"},
            {"direction_logits","direction"},
    };

        private static final String[] ROUND3_BINARY_HEADS = new String[] {
            "has_face",
            "has_text",
            "has_background_people",
            "has_moire",
            "is_repeating_motion",
            "is_repeating_motion_flow",
            "business_card",
            "wifi_credential",
            "finger_cover",
            "motion_blur_present",
            "text_region_prob",
            // Round-3.1: appended at END (group-selfie head for ⑦ vs ②).
            "is_group_selfie",
        };

        private static final String[] ROUND3_SCALAR_HEADS = new String[] {
            "face_count",
            "subject_count",
            "flow_magnitude",
            "blur_level",
            "brightness_value",
            "sharpness",
            "sharpness_quality",
            "contrast_quality",
            "overall_quality",
            "scene_depth_layers",
            "visual_complexity",
            "text_region_patch_count",
            "speed_logits",
            "direction_logits",
            "motion_logits_flow",
            "motion_pred_flow",
        };

    /** Scalar (binary or regression) heads passed through (or sigmoid-ed). */
    private static final String[] SCALAR_HEADS = new String[] {
            "contrast", "sharpness", "noise",
            "composition_score", "needs_composition_edit",
            "has_face", "has_text", "is_tilted",
            "has_shadow", "has_reflection", "has_background_people",
            "has_flare", "is_repeating_motion", "face_count",
            "flow_magnitude", "is_repeating_motion_flow",
            "contrast_quality", "sharpness_quality", "noise_quality", "overall_quality",
            "tilt_angle",
            "has_symmetry", "has_diagonal_lines", "has_leading_lines",
            "subject_fill_ratio", "subject_count", "visual_complexity",
            "scene_depth_layers",
            "brightness_value", "blur_level", "has_moire",
    };

    /** Keys whose scalar value should be sigmoid'd if it looks like a logit. */
    private static final java.util.Set<String> BINARY_HEADS;
    static {
        BINARY_HEADS = new java.util.HashSet<>();
        for (String s : new String[] {
                "needs_composition_edit", "is_tilted",
                "is_repeating_motion", "is_repeating_motion_flow",
                "has_face", "has_text", "has_shadow", "has_reflection",
                "has_background_people", "has_flare",
                "has_symmetry", "has_diagonal_lines", "has_leading_lines",
                "has_moire",
        }) {
            BINARY_HEADS.add(s);
        }
    }

    private TriggerSignals() {}

    // --------------------------------------------------------------
    // Public API
    // --------------------------------------------------------------

    /** Extract a flat signal map from raw model outputs. */
    public static Map<String, Float> extract(Map<String, float[]> outputs) {
        Map<String, Float> sig = new HashMap<>();

        addRound3PackedHeads(sig, outputs);

        // Categorical: argmax id + max softmax prob
        for (String[] pair : CATEGORICAL_HEADS) {
            float[] arr = outputs.get(pair[0]);
            if (arr == null || arr.length == 0) continue;
            if (arr.length == 1 && ("speed_logits".equals(pair[0]) || "direction_logits".equals(pair[0]))) {
                sig.put(pair[0], arr[0]);
                continue;
            }
            float[] probs = maybeSoftmax(arr);
            int idx = argmax(probs);
            sig.put(pair[1] + "_id", (float) idx);
            sig.put(pair[1] + "_prob", probs[idx]);
            if ("scene_type".equals(pair[0])) {
                sig.put("scene_id", (float) idx);
                sig.put("scene_prob", probs[idx]);
            }
        }

        // Composition issues (13 or 14 dims, multi-label sigmoid)
        float[] ci = outputs.get("composition_issues");
        if (ci != null && ci.length > 0) {
            float[] ciProb = looksLikeProbs(ci) ? ci.clone() : sigmoidVec(ci);
            int n = COMPOSITION_ISSUE_NAMES.length;
            int offset = Math.max(0, n - ciProb.length);
            for (int i = 0; i < ciProb.length && (i + offset) < n; i++) {
                sig.put("issue_" + COMPOSITION_ISSUE_NAMES[i + offset], ciProb[i]);
            }
        }
        // Default any missing issue keys to 0
        for (String iname : COMPOSITION_ISSUE_NAMES) {
            sig.putIfAbsent("issue_" + iname, 0f);
        }

        // Scalar heads
        for (String k : SCALAR_HEADS) {
            float[] arr = outputs.get(k);
            if (arr == null || arr.length == 0) continue;
            float v = arr[0];
            if (BINARY_HEADS.contains(k)) {
                v = maybeSigmoid(v);
            }
            sig.put(k, v);
        }

        if (sig.containsKey("business_card")) {
            sig.put("document_card_prob", sig.get("business_card"));
        }

        // subject_center
        float[] sc = outputs.get("subject_center");
        if (sc != null && sc.length >= 2) {
            sig.put("subject_cx", sc[0]);
            sig.put("subject_cy", sc[1]);
        }

        // subject_bbox
        float[] bb = outputs.get("subject_bbox");
        if (bb != null && bb.length >= 4) {
            float bx = bb[0], by = bb[1], bw = bb[2], bh = bb[3];
            sig.put("bbox_x", bx);
            sig.put("bbox_y", by);
            sig.put("bbox_w", bw);
            sig.put("bbox_h", bh);
            sig.put("bbox_area", Math.max(0f, bw) * Math.max(0f, bh));
            float edge = Math.min(Math.min(bx, by), Math.min(1f - (bx + bw), 1f - (by + bh)));
            sig.put("bbox_edge_dist", Math.max(0f, edge));
        }

        // suggested_crop
        float[] cr = outputs.get("suggested_crop");
        if (cr != null && cr.length >= 4) {
            sig.put("crop_x", cr[0]);
            sig.put("crop_y", cr[1]);
            sig.put("crop_w", cr[2]);
            sig.put("crop_h", cr[3]);
        }

        return sig;
    }

    private static void addRound3PackedHeads(Map<String, Float> sig, Map<String, float[]> outputs) {
        float[] binary = outputs.get("binary_logits");
        if (binary != null && binary.length > 0) {
            int n = Math.min(binary.length, ROUND3_BINARY_HEADS.length);
            for (int i = 0; i < n; i++) {
                sig.put(ROUND3_BINARY_HEADS[i], maybeSigmoid(binary[i]));
            }
        }

        float[] scalars = outputs.get("scalars");
        if (scalars != null && scalars.length > 0) {
            int n = Math.min(scalars.length, ROUND3_SCALAR_HEADS.length);
            for (int i = 0; i < n; i++) {
                sig.put(ROUND3_SCALAR_HEADS[i], scalars[i]);
            }
        }
    }

    // --------------------------------------------------------------
    // Numerical helpers
    // --------------------------------------------------------------

    private static boolean looksLikeProbs(float[] arr) {
        float sum = 0f;
        for (float v : arr) {
            if (v < 0f || v > 1f) return false;
            sum += v;
        }
        // multi-label vs softmax distinction not needed here; we only check 0/1 range
        return sum > 0f;
    }

    private static float[] maybeSoftmax(float[] arr) {
        // If values already look like a probability simplex, pass through.
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY, sum = 0f;
        for (float v : arr) { if (v < min) min = v; if (v > max) max = v; sum += v; }
        if (min >= 0f && max <= 1f && Math.abs(sum - 1f) < 0.1f) {
            return arr;
        }
        float[] out = new float[arr.length];
        float m = max;
        float es = 0f;
        for (int i = 0; i < arr.length; i++) {
            out[i] = (float) Math.exp(arr[i] - m);
            es += out[i];
        }
        if (es <= 0f) {
            float u = 1f / arr.length;
            for (int i = 0; i < arr.length; i++) out[i] = u;
            return out;
        }
        for (int i = 0; i < arr.length; i++) out[i] /= es;
        return out;
    }

    private static int argmax(float[] arr) {
        int idx = 0;
        float best = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > best) { best = arr[i]; idx = i; }
        }
        return idx;
    }

    private static float maybeSigmoid(float v) {
        if (v >= 0f && v <= 1f) return v;
        return (float) (1.0 / (1.0 + Math.exp(-v)));
    }

    private static float[] sigmoidVec(float[] arr) {
        float[] out = new float[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = (float) (1.0 / (1.0 + Math.exp(-arr[i])));
        }
        return out;
    }
}
