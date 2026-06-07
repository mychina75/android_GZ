package com.samsung.camera.intelligence.guidance;

import android.util.Log;

import com.samsung.camera.intelligence.recommendation.ExposureMapper;
import com.samsung.camera.intelligence.trigger.NativeBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 4 — Aesthetic Transfer Guide.
 *
 * Searches a vector index of professional photographs to find the master work
 * most similar to the current viewfinder preview, then maps the master's EXIF
 * parameters to equivalent phone Pro Mode settings and emits a
 * {@link MasterMatchOverlay} for the Android renderer.
 *
 * On Android, FAISS is not available — this implementation uses a simple
 * brute-force cosine-similarity search over a pre-loaded in-memory index.
 * For production, consider using a native FAISS-lite or ANN library.
 *
 * Features:
 *   - Configurable minimum similarity threshold
 *   - Cooldown logic (re-query only when scene changes or after N seconds)
 *   - Scene-type filtering for relevance
 *   - EV-equivalent exposure mapping via {@link ExposureMapper}
 *
 * Ported from Python master_match_guide.py
 */
public class MasterMatchGuide {

    private static final String TAG = "MasterMatchGuide";

    // ---- Config ----

    private boolean enabled = true;
    private float minSimilarity = 0.30f;
    private float compatibilityFallbackSimilarity = 0.00f;
    private int topK = 3;
    private float cooldownMs = 5000f; // 5 seconds in ms
    private boolean filterByScene = true;

    // Hysteresis: the currently applied top-1 photo gets a similarity bonus so
    // that minor embedding drift caused by parameter application does not
    // immediately switch to a different master photo.
    private float stickyBias = 0.10f;
    // Even when the scene type changes, enforce at least this cooldown (ms)
    // to let the camera preview settle after parameter application.
    private long minCooldownOnSceneChangeMs = 2000;

    // ---- State ----

    private long lastQueryTimeMs = 0;
    private String lastSceneType = "";
    private List<MasterMatchOverlay> cachedOverlays = new ArrayList<>();
    private String appliedPhotoId = null; // ID of the currently applied master photo

    // Vector index data (loaded externally)
    private float[][] indexEmbeddings = null; // [N][D] L2-normalized embeddings
    private float[] flatIndexEmbeddings = null; // [N * D] flattened view for JNI search
    private int indexEmbeddingDim = 0;
    private List<MasterRecord> indexRecords = null;

    // Exposure mapper (lazy)
    private ExposureMapper exposureMapper = null;
    private final String deviceModel;

    public MasterMatchGuide() {
        this(null);
    }

    /**
     * Optional constructor for model-aware exposure mapping profile selection.
     */
    public MasterMatchGuide(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    // ---- Config setters ----

    public void setEnabled(boolean v) { this.enabled = v; }
    public void setMinSimilarity(float v) { this.minSimilarity = v; }
    public void setCompatibilityFallbackSimilarity(float v) { this.compatibilityFallbackSimilarity = v; }
    public void setTopK(int v) { this.topK = v; }
    public void setCooldownMs(float v) { this.cooldownMs = v; }
    public void setFilterByScene(boolean v) { this.filterByScene = v; }
    public void setStickyBias(float v) { this.stickyBias = v; }
    public void setMinCooldownOnSceneChangeMs(long v) { this.minCooldownOnSceneChangeMs = v; }

    /** Called by the UI after applying a master photo's parameters to the camera. */
    public void setAppliedPhotoId(String photoId) { this.appliedPhotoId = photoId; }

    /**
     * Load the master photo index into memory.
     *
     * @param embeddings [N][D] L2-normalized feature vectors
     * @param records Metadata for each photo
     */
    public void loadIndex(float[][] embeddings, List<MasterRecord> records) {
        this.indexEmbeddings = embeddings;
        this.indexRecords = records;
        this.flatIndexEmbeddings = null;
        this.indexEmbeddingDim = 0;
        if (embeddings == null || embeddings.length == 0 || embeddings[0] == null) {
            return;
        }
        int dim = embeddings[0].length;
        float[] flat = new float[embeddings.length * dim];
        for (int i = 0; i < embeddings.length; i++) {
            float[] row = embeddings[i];
            if (row == null || row.length != dim) {
                flatIndexEmbeddings = null;
                indexEmbeddingDim = 0;
                return;
            }
            System.arraycopy(row, 0, flat, i * dim, dim);
        }
        flatIndexEmbeddings = flat;
        indexEmbeddingDim = dim;
    }

    // ---- Public API ----

    /**
     * Generate master-match overlays based on the current frame analysis.
     * Returns up to three {@link MasterMatchOverlay} instances ranked by similarity.
     */
    // public List<MasterMatchOverlay> evaluate(FrameAnalysis analysis) {
    //     if (!enabled) return new ArrayList<>();

    //     // Need an embedding to search
    //     float[] embedding = analysis.getFeatureEmbedding();
    //     if (embedding == null || embedding.length == 0) {
    //         return new ArrayList<>();
    //     }

    //     // Check cooldown
    //     long now = System.currentTimeMillis();
    //     boolean sceneChanged = !analysis.getSceneType().equals(lastSceneType);
    //     long elapsed = now - lastQueryTimeMs;
    //     if (!sceneChanged && elapsed < (long) cooldownMs) {
    //         return new ArrayList<>(cachedOverlays);
    //     }

    //     // Check index availability
    //     if (indexEmbeddings == null || indexRecords == null || indexRecords.isEmpty()) {
    //         return new ArrayList<>();
    //     }

    //     // Search with optional scene filter
    //     String sceneFilter = filterByScene ? analysis.getSceneType() : null;
    //     List<SearchResult> results = search(embedding, topK, sceneFilter);

    //     // Fall back to unfiltered search if no scene matches
    //     if (results.isEmpty() && sceneFilter != null) {
    //         results = search(embedding, topK, null);
    //     }

    //     // Filter by similarity threshold
    //     List<SearchResult> filtered = new ArrayList<>();
    //     for (SearchResult r : results) {
    //         if (r.similarity >= minSimilarity) {
    //             filtered.add(r);
    //         }
    //     }

    //     if (filtered.isEmpty()) {
    //         cachedOverlays = new ArrayList<>();
    //         lastQueryTimeMs = now;
    //         lastSceneType = analysis.getSceneType();
    //         return new ArrayList<>();
    //     }
    public List<MasterMatchOverlay> evaluate(FrameAnalysis analysis) {
        if (!enabled) {
            Log.w(TAG, "[DIAG] evaluate: SKIP — disabled");
            return new ArrayList<>();
        }

        // Need an embedding to search
        float[] embedding = analysis.getFeatureEmbedding();
        if (embedding == null || embedding.length == 0) {
            Log.w(TAG, "[DIAG] evaluate: SKIP — embedding "
                    + (embedding == null ? "null" : "empty(len=" + embedding.length + ")"));
            return new ArrayList<>();
        }

        // Check cooldown — enforce a minimum even when scene type changes to
        // prevent the feedback loop where applied parameters change the scene
        // classification, bypassing the cooldown and triggering a new search.
        long now = System.currentTimeMillis();
        boolean sceneChanged = !analysis.getSceneType().equals(lastSceneType);
        long elapsed = now - lastQueryTimeMs;
        long effectiveCooldown = sceneChanged
                ? minCooldownOnSceneChangeMs
                : (long) cooldownMs;
        if (elapsed < effectiveCooldown) {
            Log.w(TAG, "[DIAG] evaluate: cooldown cached=" + cachedOverlays.size()
                    + " elapsed=" + elapsed + "ms (limit=" + effectiveCooldown
                    + ", sceneChanged=" + sceneChanged + ")");
            return new ArrayList<>(cachedOverlays);
        }

        // Check index availability
        if (indexEmbeddings == null || indexRecords == null || indexRecords.isEmpty()) {
            Log.w(TAG, "[DIAG] evaluate: SKIP — index not loaded"
                    + " emb=" + (indexEmbeddings == null ? "null" : String.valueOf(indexEmbeddings.length))
                    + " rec=" + (indexRecords == null ? "null" : String.valueOf(indexRecords.size())));
            return new ArrayList<>();
        }

        // Search with optional scene filter
        String sceneFilter = filterByScene ? analysis.getSceneType() : null;
        List<SearchResult> results = search(embedding, topK, sceneFilter);
        Log.w(TAG, "[DIAG] search scene=" + sceneFilter + " -> " + results.size() + " results");

        // Fall back to unfiltered search if no scene matches
        if (results.isEmpty() && sceneFilter != null) {
            results = search(embedding, topK, null);
            Log.w(TAG, "[DIAG] fallback unfiltered -> " + results.size() + " results");
        }

        // Apply sticky bias: boost the currently applied photo's similarity
        // so minor embedding drift doesn't cause oscillation.
        if (appliedPhotoId != null && stickyBias > 0f) {
            for (SearchResult r : results) {
                if (appliedPhotoId.equals(r.record.photoId)) {
                    r.similarity += stickyBias;
                    break;
                }
            }
            // Re-sort after bias adjustment
            results.sort((a, b) -> Float.compare(b.similarity, a.similarity));
        }

        // Filter by similarity threshold
        List<SearchResult> filtered = new ArrayList<>();
        float topSim = results.isEmpty() ? 0f : results.get(0).similarity;
        for (SearchResult r : results) {
            if (r.similarity >= minSimilarity) {
                filtered.add(r);
            }
        }
        Log.w(TAG, "[DIAG] threshold=" + minSimilarity + " topSim=" + topSim
                + " passed=" + filtered.size() + "/" + results.size()
                + " appliedId=" + appliedPhotoId);

        if (filtered.isEmpty()) {
            for (SearchResult r : results) {
                if (r.similarity >= compatibilityFallbackSimilarity) {
                    filtered.add(r);
                }
            }
            Log.w(TAG, "[DIAG] compatibility fallback threshold="
                    + compatibilityFallbackSimilarity + " passed=" + filtered.size()
                    + "/" + results.size()
                    + " (likely cross-backbone index/query embedding space)");
            if (filtered.isEmpty()) {
                cachedOverlays = new ArrayList<>();
                lastQueryTimeMs = now;
                lastSceneType = analysis.getSceneType();
                Log.w(TAG, "[DIAG] evaluate: EMPTY — all below threshold");
                return new ArrayList<>();
            }
        }
        
        // Build overlays for up to top-3 matches
        cachedOverlays = new ArrayList<>();
        int limit = Math.min(3, filtered.size());
        for (int rank = 0; rank < limit; rank++) {
            SearchResult match = filtered.get(rank);
            MasterRecord record = match.record;

            // Map EXIF → phone Pro Mode parameters
            Map<String, Object> proParams = mapExifToProMode(
                    record.exif,
                    analysis.getNoiseLevel(),
                    analysis.getLightingCondition(),
                    analysis.getMotionType()
            );

            // Carry camera identity (cluster_id + label) into proModeParams so the
            // app side can pick the right baked LUT.  These keys are silently
            // ignored by older overlay consumers.
            if (record.cameraClusterId != null) {
                proParams.put("camera_cluster_id", record.cameraClusterId);
            }
            if (!record.cameraBrand.isEmpty()) {
                proParams.put("camera_brand", record.cameraBrand);
            }
            if (!record.cameraMake.isEmpty()) {
                proParams.put("camera_make", record.cameraMake);
            }
            if (!record.cameraModel.isEmpty()) {
                proParams.put("camera_model", record.cameraModel);
            }
            // camera_label is computed downstream (CameraStylePresetManager.labelOf)
            // from cluster_id, since labels live in assets/camera_luts/manifest.json.

            MasterMatchOverlay overlay = new MasterMatchOverlay(
                    record.photographerName,
                    record.title,
                    record.photoId,
                    match.similarity,
                    new ArrayList<>(record.styleTags),
                    proParams,
                    record.thumbnailUrl
            );
            overlay.setMessage("Style: " + record.title + " by " + record.photographerName);
            cachedOverlays.add(overlay);
        }

        lastQueryTimeMs = now;
        lastSceneType = analysis.getSceneType();
        return new ArrayList<>(cachedOverlays);
    }

    /**
     * Reset cooldown and cached overlays.
     */
    public void reset() {
        lastQueryTimeMs = 0;
        lastSceneType = "";
        cachedOverlays = new ArrayList<>();
        appliedPhotoId = null;
    }

    // ---- Internal: brute-force cosine similarity search ----

    private List<SearchResult> search(float[] query, int k, String sceneFilter) {
        if (NativeBridge.isAvailable() && flatIndexEmbeddings != null
                && query != null && query.length == indexEmbeddingDim) {
            try {
                int[] candidateIndices = buildCandidateIndices(sceneFilter);
                if (candidateIndices.length == 0) {
                    return new ArrayList<>();
                }
                float[] packed = NativeBridge.masterMatchTopK(
                        query,
                        flatIndexEmbeddings,
                        indexEmbeddings.length,
                        indexEmbeddingDim,
                        candidateIndices,
                        k);
                if (packed != null && packed.length >= 2) {
                    List<SearchResult> out = new ArrayList<>();
                    for (int i = 0; i + 1 < packed.length; i += 2) {
                        int idx = Math.round(packed[i]);
                        if (idx < 0 || idx >= indexRecords.size()) continue;
                        out.add(new SearchResult(packed[i + 1], indexRecords.get(idx)));
                    }
                    return out;
                }
            } catch (Throwable t) {
                Log.w(TAG, "native master-match search failed, using Java fallback", t);
            }
        }
        List<SearchResult> candidates = new ArrayList<>();

        for (int i = 0; i < indexEmbeddings.length; i++) {
            MasterRecord record = indexRecords.get(i);
            // Scene filter
            if (sceneFilter != null && !sceneFilter.equals(record.sceneType)) {
                continue;
            }
            float sim = cosineSimilarity(query, indexEmbeddings[i]);
            candidates.add(new SearchResult(sim, record));
        }

        // Sort descending by similarity
        candidates.sort((a, b) -> Float.compare(b.similarity, a.similarity));

        // Take top-k
        if (candidates.size() > k) {
            return new ArrayList<>(candidates.subList(0, k));
        }
        return candidates;
    }

    private int[] buildCandidateIndices(String sceneFilter) {
        if (indexEmbeddings == null || indexRecords == null || indexRecords.isEmpty()) {
            return new int[0];
        }
        if (sceneFilter == null) {
            int[] all = new int[indexEmbeddings.length];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }
        int[] tmp = new int[indexEmbeddings.length];
        int n = 0;
        for (int i = 0; i < indexEmbeddings.length; i++) {
            MasterRecord record = indexRecords.get(i);
            if (sceneFilter.equals(record.sceneType)) {
                tmp[n++] = i;
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom > 0 ? dot / denom : 0f;
    }

    private Map<String, Object> mapExifToProMode(
            Map<String, Object> exif,
            float currentNoise,
            String currentLighting,
            String currentMotion) {
        if (exposureMapper == null) {
            exposureMapper = deviceModel != null ? new ExposureMapper(deviceModel) : new ExposureMapper();
        }
        try {
            return exposureMapper.mapToPhone(exif, currentNoise, currentLighting, currentMotion);
        } catch (Exception e) {
            Log.w(TAG, "ExposureMapper failed, returning raw EXIF", e);
            return exif != null ? exif : new HashMap<>();
        }
    }

    // ---- Data classes ----

    /**
     * Metadata record for a master photograph in the index.
     */
    public static class MasterRecord {
        public final String photoId;
        public final String title;
        public final String photographerName;
        public final String sceneType;
        public final List<String> styleTags;
        public final Map<String, Object> exif;
        public final String thumbnailUrl;
        /** Camera identity (format_version >= 3). May be empty / null. */
        public final String cameraMake;
        public final String cameraModel;
        public final String cameraBrand;
        public final Integer cameraClusterId;

        /** Backward-compatible constructor (no camera identity). */
        public MasterRecord(String photoId, String title, String photographerName,
                            String sceneType, List<String> styleTags,
                            Map<String, Object> exif, String thumbnailUrl) {
            this(photoId, title, photographerName, sceneType, styleTags, exif, thumbnailUrl,
                    "", "", "", null);
        }

        public MasterRecord(String photoId, String title, String photographerName,
                            String sceneType, List<String> styleTags,
                            Map<String, Object> exif, String thumbnailUrl,
                            String cameraMake, String cameraModel, String cameraBrand,
                            Integer cameraClusterId) {
            this.photoId = photoId;
            this.title = title;
            this.photographerName = photographerName;
            this.sceneType = sceneType;
            this.styleTags = styleTags != null ? styleTags : new ArrayList<>();
            this.exif = exif != null ? exif : new HashMap<>();
            this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : "";
            this.cameraMake = cameraMake != null ? cameraMake : "";
            this.cameraModel = cameraModel != null ? cameraModel : "";
            this.cameraBrand = cameraBrand != null ? cameraBrand : "";
            this.cameraClusterId = cameraClusterId;
        }
    }

    /**
     * Internal search result pairing similarity score with a record.
     */
    private static class SearchResult {
        float similarity; // mutable for sticky-bias adjustment
        final MasterRecord record;

        SearchResult(float similarity, MasterRecord record) {
            this.similarity = similarity;
            this.record = record;
        }
    }
}
