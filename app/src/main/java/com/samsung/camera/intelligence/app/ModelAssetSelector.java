package com.samsung.camera.intelligence.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Environment;
import android.util.Log;

import com.samsung.camera.intelligence.CameraIntelligenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ModelAssetSelector {

    private static final String TAG = "ModelAssetSelector";
    private static final String PREFS = "camera_runtime_prefs";
    // Bumped to v11 (May 2026) so existing installs migrate to the FastViT
    // Round-3 demo default and its dedicated MasterMatch bundle.
    private static final String KEY_BACKBONE = "preferred_backbone_v11";
    private static final String LEGACY_KEY_BACKBONE = "preferred_backbone";
    private static final String V2_KEY_BACKBONE = "preferred_backbone_v2";
    private static final String V3_KEY_BACKBONE = "preferred_backbone_v3";
    private static final String V4_KEY_BACKBONE = "preferred_backbone_v4";
    private static final String V5_KEY_BACKBONE = "preferred_backbone_v5";
    private static final String V6_KEY_BACKBONE = "preferred_backbone_v6";
    private static final String V7_KEY_BACKBONE = "preferred_backbone_v7";
    private static final String V8_KEY_BACKBONE = "preferred_backbone_v8";
    private static final String V9_KEY_BACKBONE = "preferred_backbone_v9";
    private static final String V10_KEY_BACKBONE = "preferred_backbone_v10";
    public static final String BACKBONE_AUTO = "";

    // Canonical backbone names match the Python trigger-head export folders.
    // Only FastViT Round-3 and CLIP B/32 remain selectable in the demo APK.
    // External files with the same backbone names can still override them.
    public static final String MOBILENETV3 = "mobilenetv3";
    public static final String CLIP_VIT_B32 = "clip_vit_b32";
    public static final String CLIP_VIT_B16 = "clip_vit_b16";
        public static final String VJEPA2_VIT_B16_224 = "vjepa2_vit_b16_224";
        public static final String FASTVIT_SA36_ROUND3 = "fastvit_sa36_round3";

        private static final String[] FASTVIT_ENCODER_FILE_NAMES = new String[] {
            "fastvit_sa36_encoder.onnx"
        };
        private static final String[] FASTVIT_HEADS_FILE_NAMES = new String[] {
            "multi_task_heads.onnx"
        };

        private static final String[] VJEPA_ENCODER_FILE_NAMES = new String[] {
            "vjepa2_vit_b16_224_encoder.tflite"
        };
        private static final String[] VJEPA_HEADS_FILE_NAMES = new String[] {
            "vjepa2_vit_b16_224_heads.tflite",
            "vjepa2_vit_b16_224_heads_fixed_tflite/vjepa2_vit_b16_224_heads_fixed_float32.tflite",
            "vjepa2_vit_b16_224_heads_fixed_tflite/vjepa2_vit_b16_224_heads_fixed_float16.tflite"
        };
        private static final String[] VJEPA_TRIGGER_HEADS_FILE_NAMES = new String[] {
            "vjepa_trigger_heads_int8.tflite",
            "vjepa_trigger_heads.tflite",
            "vjepa_trigger_heads_fixed_tflite/vjepa_trigger_heads_fixed_float32.tflite",
            "vjepa_trigger_heads_fixed_tflite/vjepa_trigger_heads_fixed_float16.tflite"
        };
        private static final String[] VJEPA_TRIGGER_CONTRACT_FILE_NAMES = new String[] {
            "trigger_split_contract.json"
        };
        private static final String VJEPA_TRIGGER_CONTRACT_ASSET =
            "trigger/vjepa2_vit_b16_224/trigger_split_contract.json";
    private static final String VJEPA_MASTER_MATCH_ASSET_DIR = "master_match_vjepa2_vit_b16_224";
        private static final String FASTVIT_MASTER_MATCH_ASSET_DIR = "master_match_fastvit_sa36_round3";

        public static final String DEFAULT_BACKBONE = FASTVIT_SA36_ROUND3;

        // Selectable backbones for the simplified demo UI.
    private static final List<String> BACKBONE_ORDER = Arrays.asList(
            FASTVIT_SA36_ROUND3,
            CLIP_VIT_B32
    );

    private ModelAssetSelector() {
    }

    public static List<String> getSelectableBackbones() {
        return new ArrayList<>(BACKBONE_ORDER);
    }

    public static List<String> getAvailableSelectableBackbones(Context context) {
        List<String> options = new ArrayList<>();
        for (String backbone : BACKBONE_ORDER) {
            if (isBackboneAvailable(context, backbone)) {
                options.add(backbone);
            }
        }
        return options;
    }

    public static boolean isBackboneAvailable(Context context, String backbone) {
        if (backbone == null || backbone.trim().isEmpty()) {
            return true;
        }
        if (VJEPA2_VIT_B16_224.equals(backbone)) {
            return hasVjepaAssetPair(context) || hasVjepaExternalPair(context);
        }
        if (FASTVIT_SA36_ROUND3.equals(backbone)) {
            return hasFastvitRound3AssetPair(context) || hasFastvitRound3ExternalPair(context);
        }
        return hasBackboneAsset(context, backbone) || hasExternalBackbone(context, backbone);
    }

    private static boolean hasExternalBackbone(Context context, String backbone) {
        if (VJEPA2_VIT_B16_224.equals(backbone)) {
            return hasVjepaExternalPair(context);
        }
        if (FASTVIT_SA36_ROUND3.equals(backbone)) {
            return hasFastvitRound3ExternalPair(context);
        }
        for (String dir : getExternalModelDirectories(context)) {
            for (String fileName : candidateFileNamesForBackbone(backbone)) {
                File f = new File(dir, fileName);
                if (f.exists() && f.isFile()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String getPreferredBackbone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Migrate-and-clear: wipe any persisted prior-version preference so the
        // new FastViT Round-3 default is used until the user explicitly re-selects
        // a model in Settings.
        if (prefs.contains(LEGACY_KEY_BACKBONE) || prefs.contains(V2_KEY_BACKBONE)
                || prefs.contains(V3_KEY_BACKBONE) || prefs.contains(V4_KEY_BACKBONE)
                || prefs.contains(V5_KEY_BACKBONE) || prefs.contains(V6_KEY_BACKBONE)
            || prefs.contains(V7_KEY_BACKBONE) || prefs.contains(V8_KEY_BACKBONE)
            || prefs.contains(V9_KEY_BACKBONE) || prefs.contains(V10_KEY_BACKBONE)) {
            prefs.edit()
                    .remove(LEGACY_KEY_BACKBONE)
                    .remove(V2_KEY_BACKBONE)
                    .remove(V3_KEY_BACKBONE)
                    .remove(V4_KEY_BACKBONE)
                    .remove(V5_KEY_BACKBONE)
                    .remove(V6_KEY_BACKBONE)
                    .remove(V7_KEY_BACKBONE)
                    .remove(V8_KEY_BACKBONE)
                    .remove(V9_KEY_BACKBONE)
                    .remove(V10_KEY_BACKBONE)
                    .apply();
        }
        String value = prefs.getString(KEY_BACKBONE, DEFAULT_BACKBONE);
        if (value == null) {
            return DEFAULT_BACKBONE;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return DEFAULT_BACKBONE;
        }
        return BACKBONE_ORDER.contains(normalized) ? normalized : DEFAULT_BACKBONE;
    }

    public static void setPreferredBackbone(Context context, String backbone) {
        String normalized = backbone == null ? DEFAULT_BACKBONE : backbone.trim();
        if (normalized.isEmpty() || !BACKBONE_ORDER.contains(normalized)) {
            normalized = DEFAULT_BACKBONE;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(LEGACY_KEY_BACKBONE)
                .remove(V2_KEY_BACKBONE)
                .remove(V3_KEY_BACKBONE)
                .remove(V4_KEY_BACKBONE)
                .remove(V5_KEY_BACKBONE)
                .remove(V6_KEY_BACKBONE)
                .remove(V7_KEY_BACKBONE)
                .remove(V8_KEY_BACKBONE)
                .remove(V9_KEY_BACKBONE)
                .remove(V10_KEY_BACKBONE)
                .putString(KEY_BACKBONE, normalized)
                .commit();
    }

    public static String toDisplayName(String backbone) {
        if (backbone == null || backbone.trim().isEmpty()) {
            return "FastViT-SA36 Round-3 ONNX split (default)";
        }
        if (CLIP_VIT_B32.equals(backbone)) {
            return "CLIP ViT-B/32 (bundled)";
        }
        if (CLIP_VIT_B16.equals(backbone)) {
            return "CLIP ViT-B/16 (bundled)";
        }
        if (VJEPA2_VIT_B16_224.equals(backbone)) {
            return "V-JEPA2 ViT-B/16 split (experimental)";
        }
        if (FASTVIT_SA36_ROUND3.equals(backbone)) {
            return "FastViT-SA36 Round-3 ONNX split (default)";
        }
        if (MOBILENETV3.equals(backbone)) {
            return "MobileNetV3-Large (bundled, lightweight)";
        }
        return backbone;
    }

    public static String getMasterMatchEmbeddingsAsset(String backbone) {
        if (FASTVIT_SA36_ROUND3.equals(backbone)) {
            return FASTVIT_MASTER_MATCH_ASSET_DIR + "/master_match_embeddings.json";
        }
        if (VJEPA2_VIT_B16_224.equals(backbone)) {
            return VJEPA_MASTER_MATCH_ASSET_DIR + "/master_match_embeddings.json";
        }
        return CameraIntelligenceManager.DEFAULT_MASTER_MATCH_EMBEDDINGS_ASSET;
    }

    public static String getMasterMatchRecordsAsset(String backbone) {
        if (FASTVIT_SA36_ROUND3.equals(backbone)) {
            return FASTVIT_MASTER_MATCH_ASSET_DIR + "/master_match_records.json";
        }
        if (VJEPA2_VIT_B16_224.equals(backbone)) {
            return VJEPA_MASTER_MATCH_ASSET_DIR + "/master_match_records.json";
        }
        return CameraIntelligenceManager.DEFAULT_MASTER_MATCH_RECORDS_ASSET;
    }

    public static String toDisplayNameWithAvailability(Context context, String backbone) {
        String display = toDisplayName(backbone);
        if (!isBackboneAvailable(context, backbone)) {
            return display + " (not installed)";
        }
        return display;
    }

    public static LoadResult initialize(CameraIntelligenceManager manager, Context context, boolean useGpu) {
        String preferredBackbone = getPreferredBackbone(context);
        if (FASTVIT_SA36_ROUND3.equals(preferredBackbone)) {
            return initializeFastvitRound3Split(manager, context, useGpu);
        }
        if (VJEPA2_VIT_B16_224.equals(preferredBackbone)) {
            return initializeVjepaSplit(manager, context, useGpu);
        }

        // First try external model directories so users can push/replace tflite files directly on device.
        List<String> externalCandidates = buildExternalCandidates(context);
        List<String> attempted = new ArrayList<>();
        String lastError = null;
        for (String modelFilePath : externalCandidates) {
            File f = new File(modelFilePath);
            if (!f.exists() || !f.isFile()) {
                continue;
            }
            attempted.add(modelFilePath);
            lastError = manager.initializeWithError(modelFilePath, useGpu);
            if (manager.isInitialized()) {
                String probeErr = manager.probeInference();
                if (probeErr != null) {
                    Log.w(TAG, "Probe failed for file: " + modelFilePath + " err=" + probeErr);
                    lastError = probeErr;
                    manager.release();
                    continue;
                }
                String backbone = inferBackbone(modelFilePath);
                manager.setBackboneNormalization(backbone);
                Log.i(TAG, "Loaded model from file: " + modelFilePath + " backbone=" + backbone);
                return new LoadResult(true, modelFilePath, backbone, attempted, null);
            }
            Log.w(TAG, "Failed file candidate: " + modelFilePath + " err=" + lastError);
        }

        List<String> candidates = buildCandidates(context);

        for (String modelPath : candidates) {
            if (!assetExists(context, modelPath)) {
                continue;
            }
            attempted.add(modelPath);
            lastError = manager.initializeWithError(context, modelPath, useGpu);
            if (manager.isInitialized()) {
                String probeErr = manager.probeInference();
                if (probeErr != null) {
                    Log.w(TAG, "Probe failed for asset: " + modelPath + " err=" + probeErr);
                    lastError = probeErr;
                    manager.release();
                    continue;
                }
                String backbone = inferBackbone(modelPath);
                manager.setBackboneNormalization(backbone);
                Log.i(TAG, "Loaded model from asset: " + modelPath + " backbone=" + backbone);
                return new LoadResult(true, modelPath, backbone, attempted, null);
            }
            Log.w(TAG, "Failed asset candidate: " + modelPath + " err=" + lastError);
        }

        return new LoadResult(false, null, null, attempted, lastError);
    }

    private static List<String> buildCandidates(Context context) {
        String preferredBackbone = getPreferredBackbone(context);

        Set<String> ordered = new LinkedHashSet<>();

        // Explicit user selection is strict: never fall back to another backbone,
        // because that makes Settings appear to ignore the selected model.
        if (preferredBackbone != null && !preferredBackbone.trim().isEmpty()) {
            addBackboneAssetCandidates(ordered, preferredBackbone.trim());
            return new ArrayList<>(ordered);
        }

        // Legacy empty preference falls back to the current demo default.
        addBackboneAssetCandidates(ordered, DEFAULT_BACKBONE);

        // 4. Generic catch-all filenames (kept for backwards compatibility with
        //    older external pushes).
        ordered.add("models/intelligent_camera.tflite");
        ordered.add("intelligent_camera.tflite");

        return new ArrayList<>(ordered);
    }

    private static List<String> buildExternalCandidates(Context context) {
        String preferredBackbone = getPreferredBackbone(context);

        Set<String> ordered = new LinkedHashSet<>();
        for (String dir : getExternalModelDirectories(context)) {
            if (preferredBackbone != null && !preferredBackbone.trim().isEmpty()) {
                addBackboneFileCandidates(ordered, dir, preferredBackbone.trim());
            } else {
                // AUTO mode only allows an external copy of the current default to override
                // the bundled default. Stale generic files must not hijack startup.
                addBackboneFileCandidates(ordered, dir, DEFAULT_BACKBONE);
            }
        }

        return new ArrayList<>(ordered);
    }

    public static List<String> getExternalModelDirectories(Context context) {
        List<String> dirs = new ArrayList<>();

        File appExternal = context.getExternalFilesDir("models");
        if (appExternal != null) {
            dirs.add(appExternal.getAbsolutePath());
        }

        File downloadRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadRoot != null) {
            dirs.add(new File(downloadRoot, "intelligent_camera/models").getAbsolutePath());
        }

        return dirs;
    }

    private static boolean assetExists(Context context, String path) {
        try {
            AssetManager am = context.getAssets();
            am.open(path).close();
            Log.d(TAG, "Asset exists: " + path);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Asset not found: " + path + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private static boolean hasBackboneAsset(Context context, String backbone) {
        if (VJEPA2_VIT_B16_224.equals(backbone)) {
            return hasVjepaAssetPair(context);
        }
        if (FASTVIT_SA36_ROUND3.equals(backbone)) {
            return hasFastvitRound3AssetPair(context);
        }
        for (String fileName : candidateFileNamesForBackbone(backbone)) {
            if (assetExists(context, "models/" + fileName) || assetExists(context, fileName)) {
                return true;
            }
        }
        return false;
    }

    private static void addBackboneAssetCandidates(Set<String> ordered, String backbone) {
        for (String fileName : candidateFileNamesForBackbone(backbone)) {
            ordered.add("models/" + fileName);
            ordered.add(fileName);
        }
    }

    private static void addBackboneFileCandidates(Set<String> ordered, String dir, String backbone) {
        for (String fileName : candidateFileNamesForBackbone(backbone)) {
            ordered.add(dir + "/" + fileName);
        }
    }

    private static List<String> candidateFileNamesForBackbone(String backbone) {
        List<String> names = new ArrayList<>();
        if (MOBILENETV3.equals(backbone)) {
            names.add("mobilenetv3.tflite");
            names.add("mobilenetv3_dynamic_range.tflite");
            names.add("mobilenetv3_fixed_float16.tflite");
            return names;
        }
        if (CLIP_VIT_B32.equals(backbone)) {
            names.add("clip_vit_b32_220Kshadow_v5_dynamic_range.tflite");
            names.add("clip_vit_b32_220Kshadow_dynamic_range.tflite");
            names.add("clip_vit_b32_dynamic_range.tflite");
            names.add("clip_vit_b32.tflite");
            return names;
        }
        if (CLIP_VIT_B16.equals(backbone)) {
            names.add("clip_vit_b16_220Kshadow_v5_dynamic_range.tflite");
            names.add("clip_vit_b16_220Kshadow_dynamic_range.tflite");
            names.add("clip_vit_b16_dynamic_range.tflite");
            names.add("clip_vit_b16.tflite");
            return names;
        }
        if (VJEPA2_VIT_B16_224.equals(backbone)) {
            names.addAll(Arrays.asList(VJEPA_ENCODER_FILE_NAMES));
            names.addAll(Arrays.asList(VJEPA_HEADS_FILE_NAMES));
            return names;
        }
        if (FASTVIT_SA36_ROUND3.equals(backbone)) {
            names.addAll(Arrays.asList(FASTVIT_ENCODER_FILE_NAMES));
            names.addAll(Arrays.asList(FASTVIT_HEADS_FILE_NAMES));
            return names;
        }
        names.add(backbone + "_dynamic_range.tflite");
        names.add(backbone + ".tflite");
        return names;
    }

    private static LoadResult initializeFastvitRound3Split(CameraIntelligenceManager manager,
                                                           Context context,
                                                           boolean useGpu) {
        List<String> attempted = new ArrayList<>();
        String lastError = null;

        for (String dir : getExternalModelDirectories(context)) {
            for (String encoderFileName : FASTVIT_ENCODER_FILE_NAMES) {
                for (String headsFileName : FASTVIT_HEADS_FILE_NAMES) {
                    File encoderFile = new File(dir, encoderFileName);
                    File headsFile = new File(dir, headsFileName);
                    if (!encoderFile.exists() || !headsFile.exists()) {
                        continue;
                    }
                    String pairLabel = encoderFile.getAbsolutePath() + " + " + headsFile.getAbsolutePath();
                    attempted.add(pairLabel);
                    lastError = manager.initializeOnnxSplitWithError(
                            encoderFile.getAbsolutePath(),
                            headsFile.getAbsolutePath(),
                            useGpu);
                    if (manager.isInitialized()) {
                        String probeErr = manager.probeInference();
                        if (probeErr != null) {
                            Log.w(TAG, "FastViT Round-3 probe failed for files: " + pairLabel + " err=" + probeErr);
                            lastError = probeErr;
                            manager.release();
                            continue;
                        }
                        manager.setBackboneNormalization(FASTVIT_SA36_ROUND3);
                        Log.i(TAG, "Loaded FastViT Round-3 ONNX pair from files: " + pairLabel);
                        return new LoadResult(true, pairLabel, FASTVIT_SA36_ROUND3, attempted, null);
                    }
                    Log.w(TAG, "Failed FastViT Round-3 file candidate: " + pairLabel + " err=" + lastError);
                }
            }
        }

        for (String encoderFileName : FASTVIT_ENCODER_FILE_NAMES) {
            for (String headsFileName : FASTVIT_HEADS_FILE_NAMES) {
                String encoderAsset = "models/" + encoderFileName;
                String headsAsset = "models/" + headsFileName;
                if (!assetExists(context, encoderAsset) || !assetExists(context, headsAsset)) {
                    continue;
                }
                String pairLabel = encoderAsset + " + " + headsAsset;
                attempted.add(pairLabel);
                lastError = manager.initializeOnnxSplitWithError(context, encoderAsset, headsAsset, useGpu);
                if (manager.isInitialized()) {
                    String probeErr = manager.probeInference();
                    if (probeErr != null) {
                        Log.w(TAG, "FastViT Round-3 probe failed for assets: " + pairLabel + " err=" + probeErr);
                        lastError = probeErr;
                        manager.release();
                        continue;
                    }
                    manager.setBackboneNormalization(FASTVIT_SA36_ROUND3);
                    Log.i(TAG, "Loaded FastViT Round-3 ONNX pair from assets: " + pairLabel);
                    return new LoadResult(true, pairLabel, FASTVIT_SA36_ROUND3, attempted, null);
                }
                Log.w(TAG, "Failed FastViT Round-3 asset candidate: " + pairLabel + " err=" + lastError);
            }
        }

        return new LoadResult(false, null, FASTVIT_SA36_ROUND3, attempted, lastError);
    }

    private static boolean hasFastvitRound3AssetPair(Context context) {
        for (String encoderFileName : FASTVIT_ENCODER_FILE_NAMES) {
            for (String headsFileName : FASTVIT_HEADS_FILE_NAMES) {
                if (assetExists(context, "models/" + encoderFileName)
                        && assetExists(context, "models/" + headsFileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasFastvitRound3ExternalPair(Context context) {
        for (String dir : getExternalModelDirectories(context)) {
            for (String encoderFileName : FASTVIT_ENCODER_FILE_NAMES) {
                for (String headsFileName : FASTVIT_HEADS_FILE_NAMES) {
                    File encoderFile = new File(dir, encoderFileName);
                    File headsFile = new File(dir, headsFileName);
                    if (encoderFile.exists() && headsFile.exists()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static LoadResult initializeVjepaSplit(CameraIntelligenceManager manager,
                                                   Context context,
                                                   boolean useGpu) {
        List<String> attempted = new ArrayList<>();
        String lastError = null;

        for (String dir : getExternalModelDirectories(context)) {
            for (String encoderFileName : VJEPA_ENCODER_FILE_NAMES) {
                for (String headsFileName : VJEPA_HEADS_FILE_NAMES) {
                    File encoderFile = new File(dir, encoderFileName);
                    File headsFile = new File(dir, headsFileName);
                    if (!encoderFile.exists() || !headsFile.exists()) {
                        continue;
                    }
                    String pairLabel = encoderFile.getAbsolutePath() + " + " + headsFile.getAbsolutePath();
                    attempted.add(pairLabel);
                    lastError = manager.initializeSplitWithError(
                            encoderFile.getAbsolutePath(),
                            headsFile.getAbsolutePath(),
                            useGpu);
                    if (manager.isInitialized()) {
                        String probeErr = manager.probeInference();
                        if (probeErr != null) {
                            Log.w(TAG, "Split V-JEPA probe failed for files: " + pairLabel + " err=" + probeErr);
                            lastError = probeErr;
                            manager.release();
                            continue;
                        }
                        manager.setBackboneNormalization(VJEPA2_VIT_B16_224);
                        attachVjepaDirectTriggerFromFiles(manager, encoderFile.getParentFile());
                        Log.i(TAG, "Loaded split V-JEPA from files: " + pairLabel);
                        return new LoadResult(true, pairLabel, VJEPA2_VIT_B16_224, attempted, null);
                    }
                    Log.w(TAG, "Failed split V-JEPA file candidate: " + pairLabel + " err=" + lastError);
                }
            }
        }

        for (String encoderFileName : VJEPA_ENCODER_FILE_NAMES) {
            for (String headsFileName : VJEPA_HEADS_FILE_NAMES) {
                String encoderAsset = "models/" + encoderFileName;
                String headsAsset = "models/" + headsFileName;
                if (!assetExists(context, encoderAsset) || !assetExists(context, headsAsset)) {
                    continue;
                }
                String pairLabel = encoderAsset + " + " + headsAsset;
                attempted.add(pairLabel);
                lastError = manager.initializeSplitWithError(context, encoderAsset, headsAsset, useGpu);
                if (manager.isInitialized()) {
                    String probeErr = manager.probeInference();
                    if (probeErr != null) {
                        Log.w(TAG, "Split V-JEPA probe failed for assets: " + pairLabel + " err=" + probeErr);
                        lastError = probeErr;
                        manager.release();
                        continue;
                    }
                    manager.setBackboneNormalization(VJEPA2_VIT_B16_224);
                    attachVjepaDirectTriggerFromAssets(manager, context);
                    Log.i(TAG, "Loaded split V-JEPA from assets: " + pairLabel);
                    return new LoadResult(true, pairLabel, VJEPA2_VIT_B16_224, attempted, null);
                }
                Log.w(TAG, "Failed split V-JEPA asset candidate: " + pairLabel + " err=" + lastError);
            }
        }

        return new LoadResult(false, null, VJEPA2_VIT_B16_224, attempted, lastError);
    }

    private static boolean hasVjepaAssetPair(Context context) {
        for (String encoderFileName : VJEPA_ENCODER_FILE_NAMES) {
            for (String headsFileName : VJEPA_HEADS_FILE_NAMES) {
                if (assetExists(context, "models/" + encoderFileName)
                        && assetExists(context, "models/" + headsFileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void attachVjepaDirectTriggerFromFiles(CameraIntelligenceManager manager, File directory) {
        if (directory == null) {
            return;
        }
        for (String headsFileName : VJEPA_TRIGGER_HEADS_FILE_NAMES) {
            File headsFile = new File(directory, headsFileName);
            if (!headsFile.exists() || !headsFile.isFile()) {
                continue;
            }
            for (String contractFileName : VJEPA_TRIGGER_CONTRACT_FILE_NAMES) {
                File contractFile = new File(directory, contractFileName);
                if (!contractFile.exists() || !contractFile.isFile()) {
                    continue;
                }
                String err = manager.attachTriggerSplitWithError(
                        headsFile.getAbsolutePath(),
                        contractFile.getAbsolutePath());
                if (err == null) {
                    Log.i(TAG, "Attached direct trigger split from files: "
                            + headsFile.getAbsolutePath() + " + " + contractFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Direct trigger split unavailable for files: "
                            + headsFile.getAbsolutePath() + " err=" + err);
                }
                return;
            }
        }
        Log.i(TAG, "No direct trigger split file pair found alongside V-JEPA scene split");
    }

    private static void attachVjepaDirectTriggerFromAssets(CameraIntelligenceManager manager, Context context) {
        if (!assetExists(context, VJEPA_TRIGGER_CONTRACT_ASSET)) {
            Log.i(TAG, "Direct trigger split contract asset not bundled: " + VJEPA_TRIGGER_CONTRACT_ASSET);
            return;
        }
        for (String headsFileName : VJEPA_TRIGGER_HEADS_FILE_NAMES) {
            String headsAsset = "models/" + headsFileName;
            if (!assetExists(context, headsAsset)) {
                continue;
            }
            String err = manager.attachTriggerSplitWithError(context, headsAsset, VJEPA_TRIGGER_CONTRACT_ASSET);
            if (err == null) {
                Log.i(TAG, "Attached direct trigger split from assets: " + headsAsset);
            } else {
                Log.w(TAG, "Direct trigger split unavailable for asset: " + headsAsset + " err=" + err);
            }
            return;
        }
        Log.i(TAG, "No bundled direct trigger heads asset found for V-JEPA");
    }

    private static boolean hasVjepaExternalPair(Context context) {
        for (String dir : getExternalModelDirectories(context)) {
            for (String encoderFileName : VJEPA_ENCODER_FILE_NAMES) {
                for (String headsFileName : VJEPA_HEADS_FILE_NAMES) {
                    File encoderFile = new File(dir, encoderFileName);
                    File headsFile = new File(dir, headsFileName);
                    if (encoderFile.exists() && headsFile.exists()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String inferBackbone(String modelPath) {
        if (modelPath == null) {
            return "unknown";
        }
        String lower = modelPath.toLowerCase(Locale.US);
        if (lower.contains("fastvit_sa36")) {
            return FASTVIT_SA36_ROUND3;
        }
        // Match longest backbone names first so a specific backbone wins over
        // shorter substrings in versioned filenames.
        List<String> sorted = new ArrayList<>(BACKBONE_ORDER);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String backbone : sorted) {
            if (lower.contains(backbone.toLowerCase(Locale.US))) {
                return backbone;
            }
        }
        return "generic";
    }

    public static final class LoadResult {
        public final boolean success;
        public final String assetPath;
        public final String backbone;
        public final List<String> attempted;
        public final String lastError;

        public LoadResult(boolean success, String assetPath, String backbone,
                          List<String> attempted, String lastError) {
            this.success = success;
            this.assetPath = assetPath;
            this.backbone = backbone;
            this.attempted = attempted;
            this.lastError = lastError;
        }
    }
}
