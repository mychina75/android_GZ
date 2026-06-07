package com.samsung.camera.intelligence.trigger;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L0 rule-based trigger scorer for Android.
 *
 * <p>Mirrors {@code recommendation/triggers/trigger_scorer.py}.  Rules are
 * loaded from a JSON asset (compiled from the YAML on the Python side via
 * {@code python -c "import yaml,json; print(json.dumps(yaml.safe_load(open('recommendation/triggers/trigger_rules.yaml'))))"})
 * and dropped into {@code app/src/main/assets/trigger_rules.json}.</p>
 *
 * <p>Output: {@code Map<String, Float>} of trigger name → probability.</p>
 */
public final class TriggerScorer {

    private static final String TAG = "TriggerScorer";
    private static final String DEFAULT_ASSET = "trigger_rules.json";

    /** A single rule term. */
    private static final class Term {
        final String type;
        final float weight;
        final String key;
        final int classId;
        final float thr;
        final float low;
        final float high;
        final float dflt;

        Term(String type, float weight, String key, int classId,
             float thr, float low, float high, float dflt) {
            this.type = type;
            this.weight = weight;
            this.key = key;
            this.classId = classId;
            this.thr = thr;
            this.low = low;
            this.high = high;
            this.dflt = dflt;
        }
    }

    private static final class Rule {
        final String name;
        final float bias;
        final List<Term> terms;
        Rule(String name, float bias, List<Term> terms) {
            this.name = name;
            this.bias = bias;
            this.terms = terms;
        }
    }

    private final Map<String, Rule> rules;
    private JSONObject arbitrationConfig;
    private JSONObject hysteresisConfig;

    public TriggerScorer(Map<String, Rule> rules) {
        this.rules = rules;
    }

    /** Mutual-exclusion arbiter built from the {@code __arbitration__} block. */
    public TriggerArbiter arbiter() {
        return TriggerArbiter.fromConfig(arbitrationConfig);
    }

    /** Raw {@code __hysteresis__} config (may be null). */
    public JSONObject hysteresisConfig() {
        return hysteresisConfig;
    }

    public static TriggerScorer fromAsset(Context ctx) throws IOException {
        return fromAsset(ctx, DEFAULT_ASSET);
    }

    public static TriggerScorer fromAssetForBackbone(Context ctx, String backbone) throws IOException {
        String assetName = "trigger/" + canonicalBackbone(backbone) + "/trigger_rules.json";
        if (assetExists(ctx, assetName)) {
            return fromAsset(ctx, assetName);
        }
        Log.w(TAG, "backbone trigger rules missing: " + assetName + ", falling back to " + DEFAULT_ASSET);
        return fromAsset(ctx, DEFAULT_ASSET);
    }

    public static TriggerScorer fromAsset(Context ctx, String assetName) throws IOException {
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(assetName)) {
            return fromJson(readAll(is));
        }
    }

    private static boolean assetExists(Context ctx, String assetName) {
        try (InputStream ignored = ctx.getAssets().open(assetName)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String canonicalBackbone(String backbone) {
        if (backbone == null) {
            return "clip_vit_b32";
        }
        String lower = backbone.trim().toLowerCase();
        if (lower.contains("clip_vit_b16")) {
            return "clip_vit_b16";
        }
        if (lower.contains("clip_vit_b32")) {
            return "clip_vit_b32";
        }
        if (lower.contains("fastvit_sa36")) {
            return "fastvit_sa36_round3";
        }
        if (lower.contains("mobilenetv3")) {
            return "mobilenetv3";
        }
        return "clip_vit_b32";
    }

    public static TriggerScorer fromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            Map<String, Rule> rules = new LinkedHashMap<>();
            for (String name : TriggerNames.ALL) {
                JSONObject entry = root.optJSONObject(name);
                if (entry == null) {
                    Log.w(TAG, "no rule for trigger=" + name + ", using bias=-3");
                    rules.put(name, new Rule(name, -3f, new ArrayList<>()));
                    continue;
                }
                float bias = (float) entry.optDouble("bias", -3.0);
                JSONArray arr = entry.optJSONArray("terms");
                List<Term> terms = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject t = arr.getJSONObject(i);
                        terms.add(new Term(
                                t.optString("type", "prob"),
                                (float) t.optDouble("weight", 0.0),
                                t.optString("key", null),
                                t.optInt("class_id", -1),
                                (float) t.optDouble("thr", 0.5),
                                (float) t.optDouble("low", 0.0),
                                (float) t.optDouble("high", 1.0),
                                (float) t.optDouble("default", 0.0)
                        ));
                    }
                }
                rules.put(name, new Rule(name, bias, terms));
            }
            TriggerScorer scorer = new TriggerScorer(rules);
            scorer.arbitrationConfig = root.optJSONObject("__arbitration__");
            scorer.hysteresisConfig = root.optJSONObject("__hysteresis__");
            return scorer;
        } catch (Exception e) {
            throw new RuntimeException("failed to parse trigger_rules.json", e);
        }
    }

    /** Score one frame.  Returns map preserving canonical trigger order. */
    public Map<String, Float> score(Map<String, Float> signals) {
        if (NativeBridge.isAvailable()) {
            Map<String, Float> nativeOut = scoreNative(signals);
            if (nativeOut != null) return nativeOut;
        }
        return scoreJava(signals);
    }

    /**
     * Delegate scoring to the native engine (compiled rules). Returns null on
     * any marshalling/JNI failure so the caller can fall back to Java.
     */
    private Map<String, Float> scoreNative(Map<String, Float> signals) {
        try {
            int n = signals.size();
            String[] keys = new String[n];
            float[] vals = new float[n];
            int i = 0;
            for (Map.Entry<String, Float> e : signals.entrySet()) {
                keys[i] = e.getKey();
                vals[i] = e.getValue() == null ? 0f : e.getValue();
                i++;
            }
            float[] scores = NativeBridge.score(keys, vals);
            if (scores == null || scores.length != TriggerNames.ALL.size()) return null;
            Map<String, Float> out = new LinkedHashMap<>();
            for (int k = 0; k < TriggerNames.ALL.size(); k++) {
                out.put(TriggerNames.ALL.get(k), scores[k]);
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "native score failed, falling back to Java: " + t);
            return null;
        }
    }

    /** Pure-Java scoring (fallback when native is unavailable). */
    private Map<String, Float> scoreJava(Map<String, Float> signals) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (String name : TriggerNames.ALL) {
            Rule r = rules.get(name);
            if (r == null) { out.put(name, 0f); continue; }
            float z = r.bias;
            for (Term t : r.terms) {
                if ("gate".equals(t.type)) continue;
                z += t.weight * evalTerm(t, signals);
            }
            out.put(name, applyGates(sigmoid(z), r, signals));
        }
        return out;
    }

    /** Convenience: extract signals from raw model outputs and score. */
    public Map<String, Float> scoreOutputs(Map<String, float[]> modelOutputs) {
        return score(TriggerSignals.extract(modelOutputs));
    }

    // --------------------------------------------------------------
    // Term evaluator (matches Python _eval_term)
    // --------------------------------------------------------------

    private static float evalTerm(Term t, Map<String, Float> sig) {
        switch (t.type) {
            case "gate": {
                // Necessary-condition gate; leak applied in applyGates().
                float v = get(sig, t.key, t.dflt);
                return v >= t.thr ? 1f : 0f;
            }
            case "prob":
                return clip01(get(sig, t.key, 0f));
            case "inv":
                return clip01(1f - get(sig, t.key, 0f));
            case "eq": {
                int actual = (int) get(sig, t.key + "_id", -1f);
                float p = get(sig, t.key + "_prob", 0f);
                return actual == t.classId ? p : 0f;
            }
            case "range": {
                float v = get(sig, t.key, 0f);
                return (v >= t.low && v <= t.high) ? 1f : 0f;
            }
            case "gt": {
                float v = get(sig, t.key, 0f);
                if (v >= t.thr) return 1f;
                return Math.max(0f, v) / Math.max(t.thr, 1e-6f);
            }
            case "lt": {
                float v = get(sig, t.key, 0f);
                return v <= t.thr ? 1f : 0f;
            }
            case "linear": {
                float v = get(sig, t.key, 0f);
                if (t.high == t.low) return 0f;
                return clip01((v - t.low) / (t.high - t.low));
            }
            default:
                Log.w(TAG, "unknown term type: " + t.type);
                return 0f;
        }
    }

    private static float get(Map<String, Float> m, String k, float def) {
        Float v = m.get(k);
        return v == null ? def : v;
    }

    /**
     * Apply multiplicative necessary-condition gates.  For each {@code gate}
     * term, if {@code signals[key] < thr} the score is multiplied by the leak
     * {@code weight} (0 = hard gate).  Mirrors Python {@code _apply_gates}.
     */
    private static float applyGates(float score, Rule r, Map<String, Float> sig) {
        for (Term t : r.terms) {
            if (!"gate".equals(t.type)) continue;
            float v = get(sig, t.key, t.dflt);
            if (v < t.thr) {
                score *= t.weight;
            }
        }
        return score;
    }

    private static float clip01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static float sigmoid(float x) {
        if (x >= 0f) {
            return (float) (1.0 / (1.0 + Math.exp(-x)));
        }
        double z = Math.exp(x);
        return (float) (z / (1.0 + z));
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
