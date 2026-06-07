package com.samsung.camera.intelligence;

import android.content.Context;
import android.util.Log;

import com.samsung.camera.intelligence.guidance.MasterMatchAssetLoader;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small end-to-end bootstrap helper for app-side MasterMatch integration.
 *
 * Behavior:
 * 1) Check asset existence.
 * 2) Try loading and enabling MasterMatch.
 * 3) On failure, log and fall back to disabled mode.
 */
public final class MasterMatchBootstrap {

    private static final String TAG = "MasterMatchBootstrap";
    private static final AtomicBoolean PROBE_LOGGED = new AtomicBoolean(false);
    private static volatile String lastStatusSummary = "idle";

    private MasterMatchBootstrap() {}

    /**
     * Configure MasterMatch with default assets.
     *
     * @return true if MasterMatch is enabled, false when fallback disabled is used.
     */
    public static boolean configureDefault(Context context, CameraIntelligenceManager manager) {
        return configure(
                context,
                manager,
                CameraIntelligenceManager.DEFAULT_MASTER_MATCH_EMBEDDINGS_ASSET,
                CameraIntelligenceManager.DEFAULT_MASTER_MATCH_RECORDS_ASSET
        );
    }

    /**
     * Instrumentation-style debug entry.
     *
     * Runs default configuration and prints the enable status only once across
     * the app process lifetime. Useful during first-device bring-up.
     */
    public static boolean configureDefaultWithProbe(Context context, CameraIntelligenceManager manager) {
        boolean enabled = configureDefault(context, manager);
        if (PROBE_LOGGED.compareAndSet(false, true)) {
            Log.w(TAG, "[Probe] MasterMatch enabled=" + enabled);
        }
        return enabled;
    }

    public static String getLastStatusSummary() {
        return lastStatusSummary;
    }

    /**
     * Reset one-time probe state for repeated validation during debug sessions.
     *
     * This method is debug-build only. In release builds it does nothing.
     *
     * @return true when reset is applied; false when ignored (non-debug build).
     */
    public static boolean resetProbeForDebug() {
        if (!isDebugBuild()) {
            Log.w(TAG, "resetProbeForDebug ignored in non-debug build");
            return false;
        }
        PROBE_LOGGED.set(false);
        Log.i(TAG, "[Probe] reset complete");
        return true;
    }

    private static boolean isDebugBuild() {
        try {
            // BuildConfig lives in the app module package, not this shared runtime package.
            Class<?> cls = Class.forName("com.samsung.camera.intelligence.app.BuildConfig");
            return cls.getField("DEBUG").getBoolean(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Configure MasterMatch with explicit asset paths.
     *
     * @return true if MasterMatch is enabled, false when fallback disabled is used.
     */
    public static boolean configure(
            Context context,
            CameraIntelligenceManager manager,
            String embeddingsAssetPath,
            String recordsAssetPath) {
        String embeddingsBinPath = embeddingsAssetPath.replaceFirst("\\.json$", ".bin");
        boolean hasJson = MasterMatchAssetLoader.assetExists(context, embeddingsAssetPath);
        boolean hasBin = MasterMatchAssetLoader.assetExists(context, embeddingsBinPath);
        boolean hasRecords = MasterMatchAssetLoader.assetExists(context, recordsAssetPath);
        lastStatusSummary = "checking assets";
        Log.w(TAG, "[DIAG] configure: json=" + hasJson
                + " bin=" + hasBin
                + " records=" + hasRecords
                + " embPath=" + embeddingsAssetPath
                + " recPath=" + recordsAssetPath);

        if (!MasterMatchAssetLoader.assetsExist(context, embeddingsAssetPath, recordsAssetPath)) {
            lastStatusSummary = "disabled: missing assets (json=" + hasJson
                    + ", bin=" + hasBin + ", records=" + hasRecords + ")";
            Log.w(TAG,
                    "[DIAG] MasterMatch assets missing, fallback disabled. json=" + hasJson
                            + " bin=" + hasBin + " records=" + hasRecords);
            manager.disableMasterMatch();
            return false;
        }

        boolean ok = manager.enableMasterMatchFromAssets(context, embeddingsAssetPath, recordsAssetPath);
        if (!ok) {
            lastStatusSummary = "disabled: " + MasterMatchAssetLoader.getLastLoadError();
            Log.w(TAG, "[DIAG] MasterMatch load failed, fallback to disabled mode");
            manager.disableMasterMatch();
            return false;
        }

        lastStatusSummary = "enabled";
        Log.w(TAG, "[DIAG] MasterMatch enabled successfully");
        return true;
    }
}
