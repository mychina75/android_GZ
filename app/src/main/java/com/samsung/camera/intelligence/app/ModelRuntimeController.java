package com.samsung.camera.intelligence.app;

import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.samsung.camera.intelligence.CameraIntelligenceManager;
import com.samsung.camera.intelligence.MasterMatchBootstrap;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class ModelRuntimeController {

    private final AppCompatActivity activity;
    private final CameraIntelligenceManager intelligenceManager;
    private final TextView modelRuntimeHintText;
    private final Consumer<String> statusSink;
    private final Runnable capabilitySummaryUpdater;

    private String activeModelAssetPath = "N/A";
    private String activeBackbone = "N/A";
    private boolean masterMatchEnabled = false;

    public ModelRuntimeController(
            AppCompatActivity activity,
            CameraIntelligenceManager intelligenceManager,
            TextView modelRuntimeHintText,
            Consumer<String> statusSink,
            Runnable capabilitySummaryUpdater
    ) {
        this.activity = activity;
        this.intelligenceManager = intelligenceManager;
        this.modelRuntimeHintText = modelRuntimeHintText;
        this.statusSink = statusSink;
        this.capabilitySummaryUpdater = capabilitySummaryUpdater;
    }

    public void showModelSelectionDialog() {
        List<String> options = ModelAssetSelector.getSelectableBackbones();
        String preferred = ModelAssetSelector.getPreferredBackbone(activity);

        int checkedIndex = options.indexOf(preferred);
        if (checkedIndex < 0) {
            checkedIndex = 0;
        }

        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            labels[i] = ModelAssetSelector.toDisplayNameWithAvailability(activity, options.get(i));
        }

        final int[] selectedIndex = new int[]{checkedIndex};
        String message = activity.getString(R.string.current_model_prefix, getCurrentModelSummary())
                + "\n"
                + activity.getString(R.string.external_model_override_hint);

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.select_model_backbone_title))
                .setMessage(message)
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> selectedIndex[0] = which)
                .setPositiveButton(R.string.apply_model_selection, (dialog, which) -> {
                    String selected = options.get(selectedIndex[0]);
                    if (!ModelAssetSelector.isBackboneAvailable(activity, selected)) {
                        statusSink.accept("Model \"" + ModelAssetSelector.toDisplayName(selected)
                                + "\" is not installed. Push the .tflite file to the device first.");
                        return;
                    }
                    boolean loaded = applyBackboneSelection(selected);
                    if (loaded) {
                        statusSink.accept(activity.getString(
                                R.string.model_switched_toast,
                                getCurrentModelSummary()
                        ));
                    } else {
                        statusSink.accept(activity.getString(R.string.model_switch_failed));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public boolean applyBackboneSelection(String selectedBackbone) {
        String previousPreferred = ModelAssetSelector.getPreferredBackbone(activity);
        if (Objects.equals(selectedBackbone, previousPreferred)) {
            return true;
        }
        ModelAssetSelector.setPreferredBackbone(activity, selectedBackbone);
        if (reloadModel(false)) {
            return true;
        }

        ModelAssetSelector.setPreferredBackbone(activity, previousPreferred);
        if (!Objects.equals(previousPreferred, selectedBackbone)) {
            if (reloadModel(false)) {
                statusSink.accept("Model switch rolled back to "
                        + ModelAssetSelector.toDisplayName(previousPreferred));
            } else {
                recoverToSafeDefault(previousPreferred);
            }
        }
        return false;
    }

    public boolean reloadModel() {
        return reloadModel(true);
    }

    private boolean reloadModel(boolean allowFallback) {
        intelligenceManager.release();
        ModelAssetSelector.LoadResult loadResult = ModelAssetSelector.initialize(intelligenceManager, activity, false);
        if (!loadResult.success) {
            String preferredBackbone = ModelAssetSelector.getPreferredBackbone(activity);
            if (allowFallback) {
                String fallbackBackbone = safeFallbackBackbone(preferredBackbone);
                if (!Objects.equals(fallbackBackbone, preferredBackbone)) {
                    statusSink.accept("Model "
                            + ModelAssetSelector.toDisplayName(preferredBackbone)
                            + " failed to load. Falling back to "
                            + ModelAssetSelector.toDisplayName(fallbackBackbone));
                    ModelAssetSelector.setPreferredBackbone(activity, fallbackBackbone);
                    return reloadModel(false);
                }
            }
            activeModelAssetPath = "none";
            activeBackbone = "none";
            masterMatchEnabled = false;
            updateModelRuntimeHint();
            capabilitySummaryUpdater.run();
            String detail = "Tried: " + loadResult.attempted;
            if (loadResult.lastError != null) {
                detail += " | Error: " + loadResult.lastError;
            }
            statusSink.accept(detail);
            statusSink.accept(activity.getString(R.string.model_load_failed));
            return false;
        }

        activeModelAssetPath = loadResult.assetPath;
        activeBackbone = loadResult.backbone;
    String masterMatchEmbeddings = ModelAssetSelector.getMasterMatchEmbeddingsAsset(activeBackbone);
    String masterMatchRecords = ModelAssetSelector.getMasterMatchRecordsAsset(activeBackbone);
    masterMatchEnabled = MasterMatchBootstrap.configure(
        activity,
        intelligenceManager,
        masterMatchEmbeddings,
        masterMatchRecords
    );
        updateModelRuntimeHint();
        capabilitySummaryUpdater.run();
        statusSink.accept(activity.getString(R.string.model_loaded_status, getCurrentModelSummary())
            + " | MasterMatch: " + MasterMatchBootstrap.getLastStatusSummary());
        return true;
    }

    private void recoverToSafeDefault(String failingBackbone) {
        String fallbackBackbone = safeFallbackBackbone(failingBackbone);
        if (Objects.equals(fallbackBackbone, failingBackbone)) {
            return;
        }
        ModelAssetSelector.setPreferredBackbone(activity, fallbackBackbone);
        reloadModel(false);
    }

    private String safeFallbackBackbone(String failingBackbone) {
        if (activeBackbone != null
                && !activeBackbone.trim().isEmpty()
                && !"none".equalsIgnoreCase(activeBackbone)
                && !"n/a".equalsIgnoreCase(activeBackbone)
                && ModelAssetSelector.isBackboneAvailable(activity, activeBackbone)) {
            return activeBackbone;
        }
        if (!Objects.equals(failingBackbone, ModelAssetSelector.DEFAULT_BACKBONE)
                && ModelAssetSelector.isBackboneAvailable(activity, ModelAssetSelector.DEFAULT_BACKBONE)) {
            return ModelAssetSelector.DEFAULT_BACKBONE;
        }
        return ModelAssetSelector.BACKBONE_AUTO;
    }

    public String getCurrentModelSummary() {
        return ModelAssetSelector.toDisplayName(activeBackbone) + " @ " + activeModelAssetPath;
    }

    public String getActiveBackbone() {
        return activeBackbone;
    }

    public boolean isMasterMatchEnabled() {
        return masterMatchEnabled;
    }

    public void release() {
        intelligenceManager.release();
    }

    private void updateModelRuntimeHint() {
        if (modelRuntimeHintText == null) {
            return;
        }
        String modelPath = activeModelAssetPath == null ? "" : activeModelAssetPath.trim();
        if (modelPath.isEmpty() || "N/A".equalsIgnoreCase(modelPath)) {
            modelRuntimeHintText.setText(R.string.model_runtime_hint_idle);
            return;
        }
        if ("none".equalsIgnoreCase(modelPath)) {
            modelRuntimeHintText.setText(R.string.model_runtime_hint_missing);
            return;
        }

        String sourceLabel = classifyActiveModelSource(modelPath);
        String fileName = new File(modelPath).getName();
        if (TextUtils.isEmpty(fileName)) {
            fileName = modelPath;
        }
        String backboneLabel = ModelAssetSelector.toDisplayName(activeBackbone);
        String preferredLabel = ModelAssetSelector.toDisplayName(ModelAssetSelector.getPreferredBackbone(activity));
        String runtimeLine = activity.getString(
                R.string.model_runtime_hint_format,
                sourceLabel,
                fileName,
                backboneLabel
        );
        modelRuntimeHintText.setText(runtimeLine
            + "\nPreferred: " + preferredLabel
            + "\nMasterMatch: " + MasterMatchBootstrap.getLastStatusSummary());
    }

    private String classifyActiveModelSource(String modelPath) {
        String normalizedPath = modelPath.replace('\\', '/').toLowerCase(Locale.US);
        if (!normalizedPath.contains(":/") && !normalizedPath.startsWith("/")) {
            return activity.getString(R.string.model_source_asset);
        }

        List<String> externalDirs = ModelAssetSelector.getExternalModelDirectories(activity);
        for (String dir : externalDirs) {
            if (dir == null) {
                continue;
            }
            String normalizedDir = dir.replace('\\', '/').toLowerCase(Locale.US);
            if (!normalizedPath.startsWith(normalizedDir)) {
                continue;
            }
            if (normalizedDir.contains("/download/")
                    || normalizedDir.endsWith("/download/intelligent_camera/models")) {
                return activity.getString(R.string.model_source_download);
            }
            if (normalizedDir.contains("/android/data/")) {
                return activity.getString(R.string.model_source_app_external);
            }
            return activity.getString(R.string.model_source_unknown);
        }
        return activity.getString(R.string.model_source_unknown);
    }
}
