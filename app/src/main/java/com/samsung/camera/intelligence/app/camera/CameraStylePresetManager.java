package com.samsung.camera.intelligence.app.camera;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads pre-baked camera-style LUTs from assets and serves them by cluster id.
 *
 * <p>Each cluster's LUT is a 1089×33 ARGB_8888 PNG matching the atlas layout
 * produced by {@link LutToneMapper#generateLutBitmap}.  The PNGs are baked
 * offline by {@code scripts/bake_cluster_lut.py} from the K-means cluster
 * centroids of the master photo dataset (mean saturation, highlight warmth,
 * and shadow tint).
 *
 * <p>Assets layout:
 * <pre>
 *   assets/camera_luts/
 *     manifest.json                                        // {k, files: [{cluster_id, label, file}]}
 *     cluster_0_Canon_Full_Frame_Cool_Muted_Lifted.png
 *     cluster_1_...
 *     ...
 * </pre>
 *
 * <p>Total runtime memory cap: 8 LUTs × 1089*33*4 ≈ 1.15 MB.
 */
public class CameraStylePresetManager {

    private static final String TAG = "CameraStylePresetManager";
    private static final String MANIFEST_PATH = "camera_luts/manifest.json";
    private static final String LUT_DIR = "camera_luts";
    private static final int CACHE_SIZE = 8;

    private final AssetManager assets;

    /** Per-cluster filename inside {@link #LUT_DIR}. */
    private final Map<Integer, String> clusterFiles = new HashMap<>();
    /** Per-cluster human-readable label. */
    private final Map<Integer, String> clusterLabels = new HashMap<>();

    /** Decoded LUT bitmap LRU cache. */
    private final LruCache<Integer, Bitmap> cache = new LruCache<>(CACHE_SIZE);

    private boolean loaded = false;

    public CameraStylePresetManager(Context context) {
        this.assets = context.getApplicationContext().getAssets();
        try {
            loadManifest();
        } catch (Exception e) {
            Log.w(TAG, "No camera LUT manifest available — preset manager disabled", e);
        }
    }

    private void loadManifest() throws IOException {
        try (InputStream is = assets.open(MANIFEST_PATH);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            try {
                JSONObject root = new JSONObject(sb.toString());
                JSONArray files = root.optJSONArray("files");
                if (files == null) {
                    Log.w(TAG, "Manifest has no 'files' array");
                    return;
                }
                for (int i = 0; i < files.length(); i++) {
                    JSONObject e = files.getJSONObject(i);
                    int cid = e.getInt("cluster_id");
                    String fname = e.getString("file");
                    String label = e.optString("label", "");
                    clusterFiles.put(cid, fname);
                    clusterLabels.put(cid, label);
                }
                loaded = true;
                Log.i(TAG, "Loaded LUT manifest: " + clusterFiles.size() + " clusters");
            } catch (Exception je) {
                throw new IOException("Failed to parse manifest", je);
            }
        }
    }

    public boolean isAvailable() {
        return loaded && !clusterFiles.isEmpty();
    }

    public int clusterCount() {
        return clusterFiles.size();
    }

    public List<Integer> clusterIds() {
        if (!loaded) return Collections.emptyList();
        return new ArrayList<>(clusterFiles.keySet());
    }

    public String labelOf(int clusterId) {
        return clusterLabels.getOrDefault(clusterId, "");
    }

    /**
     * Load the LUT bitmap for the given cluster id, caching it for reuse.
     * Returns {@code null} if the cluster id is unknown or decoding failed.
     *
     * <p>The returned bitmap MUST NOT be recycled by the caller — it is
     * owned by the cache.  Pass a copy to {@link CameraGLPreview#updateLut}
     * (which expects ownership) by calling {@link Bitmap#copy(Bitmap.Config, boolean)}.
     */
    public Bitmap loadClusterLut(int clusterId) {
        if (!loaded) return null;
        Bitmap cached = cache.get(clusterId);
        if (cached != null && !cached.isRecycled()) return cached;

        String fname = clusterFiles.get(clusterId);
        if (fname == null) {
            Log.w(TAG, "Unknown cluster id: " + clusterId);
            return null;
        }
        try (InputStream is = assets.open(LUT_DIR + "/" + fname)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inScaled = false;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (bmp == null) {
                Log.w(TAG, "Failed to decode LUT bitmap: " + fname);
                return null;
            }
            cache.put(clusterId, bmp);
            return bmp;
        } catch (IOException e) {
            Log.w(TAG, "Failed to load LUT for cluster " + clusterId, e);
            return null;
        }
    }

    /** Drop all cached bitmaps. */
    public void clearCache() {
        cache.evictAll();
    }
}
