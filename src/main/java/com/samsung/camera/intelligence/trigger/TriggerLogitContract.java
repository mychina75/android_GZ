package com.samsung.camera.intelligence.trigger;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contract for the dedicated 11-logit trigger model exported by
 * scripts/export_trigger_split_tflite.py.
 */
public final class TriggerLogitContract {

    private static final String TAG = "TriggerLogitContract";
    private static final String DEFAULT_ASSET = "trigger/vjepa2_vit_b16_224/trigger_split_contract.json";

    private final String[] labelNames;
    private final float[] thresholds;
    private final int bestEpoch;
    private final float macroF1Calibrated;
    private final float macroF1Default;

    public TriggerLogitContract(
            String[] labelNames,
            float[] thresholds,
            int bestEpoch,
            float macroF1Calibrated,
            float macroF1Default) {
        this.labelNames = labelNames;
        this.thresholds = thresholds;
        this.bestEpoch = bestEpoch;
        this.macroF1Calibrated = macroF1Calibrated;
        this.macroF1Default = macroF1Default;
    }

    public static TriggerLogitContract fromAssetForBackbone(Context ctx, String backbone) throws IOException {
        String assetName = "trigger/" + canonicalBackbone(backbone) + "/trigger_split_contract.json";
        if (assetExists(ctx, assetName)) {
            return fromAsset(ctx, assetName);
        }
        Log.w(TAG, "trigger split contract missing: " + assetName + ", falling back to " + DEFAULT_ASSET);
        return fromAsset(ctx, DEFAULT_ASSET);
    }

    public static TriggerLogitContract fromAsset(Context ctx, String assetName) throws IOException {
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(assetName)) {
            return fromJson(readAll(is));
        }
    }

    public static TriggerLogitContract fromFile(String path) throws IOException {
        try (InputStream is = new FileInputStream(path)) {
            return fromJson(readAll(is));
        }
    }

    public static TriggerLogitContract fromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject heads = root.getJSONObject("heads");
            JSONArray labels = heads.getJSONArray("label_names");
            JSONObject thresholdObject = heads.getJSONObject("thresholds");

            String[] labelNames = new String[labels.length()];
            float[] thresholds = new float[labels.length()];
            for (int i = 0; i < labels.length(); i++) {
                String label = labels.getString(i);
                labelNames[i] = label;
                thresholds[i] = (float) thresholdObject.optDouble(label, 0.5);
            }

            return new TriggerLogitContract(
                    labelNames,
                    thresholds,
                    heads.optInt("best_epoch", -1),
                    (float) heads.optDouble("macro_f1_calibrated", Double.NaN),
                    (float) heads.optDouble("macro_f1_default", Double.NaN));
        } catch (Exception e) {
            throw new RuntimeException("failed to parse trigger_split_contract.json", e);
        }
    }

    public boolean matchesLogits(float[] logits) {
        return logits != null && logits.length == labelNames.length;
    }

    public Map<String, Float> scoreLogits(float[] logits) {
        LinkedHashMap<String, Float> out = new LinkedHashMap<>();
        if (logits == null) {
            return out;
        }
        int n = Math.min(logits.length, labelNames.length);
        for (int i = 0; i < n; i++) {
            out.put(labelNames[i], sigmoid(logits[i]));
        }
        return out;
    }

    public Map<String, Float> thresholdMap() {
        LinkedHashMap<String, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < labelNames.length; i++) {
            out.put(labelNames[i], thresholds[i]);
        }
        return out;
    }

    public String[] labelNames() {
        return Arrays.copyOf(labelNames, labelNames.length);
    }

    public float threshold(int index) {
        return thresholds[index];
    }

    public int bestEpoch() {
        return bestEpoch;
    }

    public float macroF1Calibrated() {
        return macroF1Calibrated;
    }

    public float macroF1Default() {
        return macroF1Default;
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
            return "vjepa2_vit_b16_224";
        }
        String lower = backbone.trim().toLowerCase();
        if (lower.contains("vjepa2_vit_b16_224") || lower.contains("vjepa2_vit_b16")) {
            return "vjepa2_vit_b16_224";
        }
        return lower;
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
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
