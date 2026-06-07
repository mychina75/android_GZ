package com.samsung.camera.intelligence.app.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.samsung.camera.intelligence.app.ui.GuidanceOverlayView;
import com.samsung.camera.intelligence.config.Settings.CompositionGuidanceSettings;

/**
 * Phase 6 — When the framing template enters the LOCKED state and the user
 * has enabled "Auto-Zoom on Lock" in Settings, this controller animates
 * the camera zoom ratio toward the value needed to fully fill the
 * recommended crop. Never zooms out, and is cancelled on unlock.
 */
public class AutoZoomController implements GuidanceOverlayView.MatchLockListener {
    private static final String TAG = "AutoZoomController";

    private final CameraController cameraController;
    private final CompositionGuidanceSettings settings;
    @Nullable private final GuidanceOverlayView overlayView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private ValueAnimator currentAnim;

    public AutoZoomController(CameraController cameraController,
                              CompositionGuidanceSettings settings) {
        this(cameraController, settings, null);
    }

    public AutoZoomController(CameraController cameraController,
                              CompositionGuidanceSettings settings,
                              @Nullable GuidanceOverlayView overlayView) {
        this.cameraController = cameraController;
        this.settings = settings;
        this.overlayView = overlayView;
    }

    @Override
    public void onMatchLockChanged(boolean locked, @Nullable float[] lockedCropNorm) {
        mainHandler.post(() -> {
            cancelAnim();
            if (!locked) {
                publishZoomState(false, 1f);
                return;
            }
            if (settings == null || !settings.isAutoZoomOnLock()) return;
            if (cameraController == null) return;
            if (lockedCropNorm == null || lockedCropNorm.length < 4) return;

            float maxCropDim = Math.max(lockedCropNorm[2], lockedCropNorm[3]);
            if (maxCropDim < 0.05f || maxCropDim >= 0.95f) return;

            float current = cameraController.getCurrentZoom();
            float deviceMax = cameraController.getMaxZoom();
            float target = Math.min(deviceMax, Math.max(current, current * (1f / maxCropDim)));
            target = Math.min(target, 5.0f);
            if (target <= current * 1.10f) {
                publishZoomState(false, target);
                return;
            }

            Log.i(TAG, "AutoZoom: " + current + " -> " + target);
            currentAnim = ValueAnimator.ofFloat(current, target);
            currentAnim.setDuration(600L);
            final float targetRatio = target;
            publishZoomState(true, targetRatio);
            currentAnim.addUpdateListener(a -> {
                Float v = (Float) a.getAnimatedValue();
                if (v != null) {
                    cameraController.setZoom(v);
                    publishZoomState(true, targetRatio, v);
                }
            });
            currentAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    publishZoomState(false, targetRatio);
                    if (currentAnim == animation) currentAnim = null;
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    publishZoomState(false, targetRatio);
                }
            });
            currentAnim.start();
        });
    }

    private void publishZoomState(boolean active, float targetRatio) {
        publishZoomState(active, targetRatio,
                cameraController == null ? 1f : cameraController.getCurrentZoom());
    }

    private void publishZoomState(boolean active, float targetRatio, float currentRatio) {
        if (overlayView != null) {
            overlayView.setAutoZoomState(currentRatio, targetRatio, active);
        }
    }

    private void cancelAnim() {
        if (currentAnim != null) {
            currentAnim.cancel();
            currentAnim = null;
        }
    }
}
