package com.samsung.camera.intelligence.trigger;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L1 external learned head: a single (11 x D) float matrix + bias that
 * runs in {@code <50 KB}, applied as
 * {@code sigmoid( W · x + b )} per trigger.
 *
 * <p>Asset format (binary, little-endian):</p>
 * <pre>
 *   magic  : 4 bytes  ASCII "THD1"
 *   numTrig: int32    (== 11)
 *   D      : int32    (feature dimension)
 *   W      : float32 * numTrig * D    (row-major)
 *   b      : float32 * numTrig
 *   thr    : float32 * numTrig        (per-trigger best threshold)
 *   keysLen: int32
 *   keys   : UTF-8    (newline-separated D feature names)
 *   useFlg : int32    (1 bit per trigger; 1 = use L1, 0 = fall back to L0)
 * </pre>
 *
 * <p>Build the asset from the trained .npz on the Python side via the
 * helper {@code recommendation/triggers/export_android_head.py} (run once
 * after training).</p>
 *
 * <p>Lookup is by string key so the order in which signals + embedding
 * dimensions are produced by {@link TriggerSignals} (and the camera
 * pipeline) is decoupled from the asset's column order.</p>
 */
public final class TriggerExternalHead {

    private static final String TAG = "TriggerExternalHead";
    private static final String DEFAULT_ASSET = "trigger_external_head.bin";

    private final float[][] w;          // [numTrig][D]
    private final float[] bias;         // [numTrig]
    private final float[] threshold;    // [numTrig]
    private final boolean[] useL1;      // [numTrig]
    private final String[] featureKeys; // [D]
    private final int dim;
    private final float maxAbsWeight;
    private final float maxAbsBias;

    public TriggerExternalHead(float[][] w, float[] bias, float[] threshold,
                                boolean[] useL1, String[] featureKeys) {
        this.w = w;
        this.bias = bias;
        this.threshold = threshold;
        this.useL1 = useL1;
        this.featureKeys = featureKeys;
        this.dim = featureKeys.length;
        float maxW = 0f;
        for (float[] row : w) {
            for (float value : row) {
                maxW = Math.max(maxW, Math.abs(value));
            }
        }
        float maxB = 0f;
        for (float value : bias) {
            maxB = Math.max(maxB, Math.abs(value));
        }
        this.maxAbsWeight = maxW;
        this.maxAbsBias = maxB;
    }

    public int featureDim() { return dim; }
    public String[] featureKeys() { return featureKeys; }
    public float threshold(int triggerIdx) { return threshold[triggerIdx]; }
    public boolean useL1(int triggerIdx) { return useL1[triggerIdx]; }
    public float maxAbsWeight() { return maxAbsWeight; }
    public float maxAbsBias() { return maxAbsBias; }

    public boolean isNumericallyHealthyForPreview() {
        return maxAbsWeight <= 1000f && maxAbsBias <= 1000f;
    }

    public static TriggerExternalHead fromAsset(Context ctx) throws IOException {
        return fromAsset(ctx, DEFAULT_ASSET);
    }

    public static TriggerExternalHead fromAssetForBackbone(Context ctx, String backbone) throws IOException {
        String assetName = "trigger/" + canonicalBackbone(backbone) + "/trigger_external_head.bin";
        if (assetExists(ctx, assetName)) {
            return fromAsset(ctx, assetName);
        }
        Log.w(TAG, "backbone trigger head missing: " + assetName + ", falling back to " + DEFAULT_ASSET);
        return fromAsset(ctx, DEFAULT_ASSET);
    }

    public static TriggerExternalHead fromAsset(Context ctx, String assetName) throws IOException {
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(assetName)) {
            return fromStream(is);
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

    public static TriggerExternalHead fromStream(InputStream raw) throws IOException {
        byte[] all;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = raw.read(buf)) > 0) baos.write(buf, 0, n);
            all = baos.toByteArray();
        }
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        if (!Arrays.equals(magic, new byte[] {'T','H','D','1'})) {
            throw new IOException("bad magic, expected THD1");
        }
        int numTrig = bb.getInt();
        int D = bb.getInt();
        if (numTrig != TriggerNames.COUNT) {
            throw new IOException("numTrig=" + numTrig + " but expected " + TriggerNames.COUNT);
        }
        float[][] w = new float[numTrig][D];
        for (int i = 0; i < numTrig; i++) {
            for (int j = 0; j < D; j++) {
                w[i][j] = bb.getFloat();
            }
        }
        float[] bias = new float[numTrig];
        for (int i = 0; i < numTrig; i++) bias[i] = bb.getFloat();
        float[] thr = new float[numTrig];
        for (int i = 0; i < numTrig; i++) thr[i] = bb.getFloat();
        int keysLen = bb.getInt();
        byte[] keysBytes = new byte[keysLen];
        bb.get(keysBytes);
        String[] keys = new String(keysBytes, StandardCharsets.UTF_8).split("\n");
        if (keys.length != D) {
            throw new IOException("feature key count=" + keys.length + " != D=" + D);
        }
        int flags = bb.getInt();
        boolean[] useL1 = new boolean[numTrig];
        for (int i = 0; i < numTrig; i++) useL1[i] = (flags & (1 << i)) != 0;
        return new TriggerExternalHead(w, bias, thr, useL1, keys);
    }

    /**
     * Score 11 triggers given a signals map and an optional embedding vector.
     * Embedding dims map to feature keys named {@code emb_0..emb_(K-1)};
     * if the asset was trained without embedding (D == signals.size only),
     * the embedding parameter is ignored.
     */
    public Map<String, Float> score(Map<String, Float> signals, float[] embedding) {
        float[] x = buildFeatureVector(signals, embedding);
        Map<String, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < TriggerNames.COUNT; i++) {
            float z = bias[i];
            float[] row = w[i];
            for (int j = 0; j < dim; j++) {
                z += row[j] * x[j];
            }
            out.put(TriggerNames.ALL.get(i), sigmoid(z));
        }
        return out;
    }

    /** Build the dense feature vector in the column order baked into the asset. */
    public float[] buildFeatureVector(Map<String, Float> signals, float[] embedding) {
        float[] x = new float[dim];
        for (int j = 0; j < dim; j++) {
            String k = featureKeys[j];
            if (k.startsWith("emb_")) {
                if (embedding == null) continue;
                int idx;
                try {
                    idx = Integer.parseInt(k.substring(4));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (idx >= 0 && idx < embedding.length) {
                    x[j] = embedding[idx];
                }
            } else {
                Float v = signals.get(k);
                if (v != null) x[j] = v;
            }
        }
        return x;
    }

    /**
     * Hybrid scoring helper: per trigger, take L1 score if the asset's
     * {@code useL1} bit is set, otherwise fall back to the supplied L0 map.
     */
    public Map<String, Float> hybrid(Map<String, Float> l0Scores,
                                      Map<String, Float> signals,
                                      float[] embedding) {
        Map<String, Float> l1Scores = score(signals, embedding);
        Map<String, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < TriggerNames.COUNT; i++) {
            String name = TriggerNames.ALL.get(i);
            out.put(name, useL1[i]
                    ? l1Scores.getOrDefault(name, 0f)
                    : l0Scores.getOrDefault(name, 0f));
        }
        return out;
    }

    private static float sigmoid(float x) {
        if (x >= 0f) return (float) (1.0 / (1.0 + Math.exp(-x)));
        double z = Math.exp(x);
        return (float) (z / (1.0 + z));
    }
}
