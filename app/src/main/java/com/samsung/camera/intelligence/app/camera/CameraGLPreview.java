package com.samsung.camera.intelligence.app.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;

import androidx.camera.core.Preview;

/**
 * Custom GLSurfaceView that renders the camera preview with a GPU-based
 * 3D LUT (Look-Up Table) for real-time tone/color grading.
 *
 * <p>Replaces the standard CameraX {@code PreviewView}.  Provides a
 * {@link Preview.SurfaceProvider} so CameraX delivers frames as an
 * OpenGL External (OES) texture.  The internal {@link CameraGLRenderer}
 * renders each frame through a fragment shader that applies the LUT.
 *
 * <p><b>Why this exists:</b> Camera2's TONEMAP_MODE_CONTRAST_CURVE
 * applies tone mapping at the ISP level, which affects both the display
 * preview AND the ImageAnalysis stream.  When the AI model analyses the
 * already-tone-mapped preview, it causes a feedback loop (progressive
 * brightening).  By rendering the tone adjustment purely in the GPU
 * display path, the analysis stream remains unmodified.
 */
public class CameraGLPreview extends GLSurfaceView {

    private static final String TAG = "CameraGLPreview";
    private final CameraGLRenderer renderer;

    public CameraGLPreview(Context context) {
        this(context, null);
    }

    public CameraGLPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new CameraGLRenderer();
        renderer.setGlView(this);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        Log.i(TAG, "CameraGLPreview initialized (GLES 2.0, RENDERMODE_WHEN_DIRTY)");
    }

    /**
     * Returns a {@link Preview.SurfaceProvider} for CameraX.
     * Use this instead of {@code PreviewView.getSurfaceProvider()}.
     */
    public Preview.SurfaceProvider getSurfaceProvider() {
        return request -> queueEvent(() -> renderer.handleSurfaceRequest(request));
    }

    /**
     * Register a listener that the renderer will fire (on the main thread)
     * whenever it needs CameraX use cases re-bound — e.g. after EGL context
     * loss or a hard preview stall.  This is required to recover from the
     * preview-freeze scenario where CameraX still believes its preview
     * Surface is valid even after the GL context (and therefore the OES
     * texture / SurfaceTexture) has been destroyed and recreated.
     */
    public void setSurfaceRebindListener(CameraGLRenderer.SurfaceRebindListener listener) {
        renderer.setSurfaceRebindListener(listener);
    }

    /**
     * Upload a 3D LUT bitmap to the GPU for real-time tone grading.
     * The bitmap must be in the standard atlas format:
     * (LUT_SIZE * LUT_SIZE) × LUT_SIZE, ARGB_8888.
     *
     * @param lutBitmap the LUT atlas bitmap (ownership transferred — will be recycled)
     */
    public void updateLut(Bitmap lutBitmap) {
        renderer.updateLut(lutBitmap);
    }

    /**
     * Convenience: generate and upload a LUT from tone parameters.
     * Thread-safe — can be called from any thread.
     *
     * @param contrast   -100..+100
     * @param highlights -100..+100
     * @param shadows    -100..+100
     * @param saturation -100..+100
     */
    public void updateLutFromToneParams(float contrast, float highlights,
                                        float shadows, float saturation) {
        Bitmap lut = LutToneMapper.generateLutBitmap(contrast, highlights, shadows, saturation);
        renderer.updateLut(lut);
    }

    /**
     * Convenience: generate and upload a LUT from tone + color style parameters.
     * Thread-safe — can be called from any thread.
     *
     * @param contrast        -100..+100
     * @param highlights      -100..+100
     * @param shadows         -100..+100
     * @param saturation      -100..+100
     * @param highlightWarmth -100..+100
     * @param shadowTint      -100..+100
     */
    public void updateLutFromToneParams(float contrast, float highlights,
                                        float shadows, float saturation,
                                        float highlightWarmth, float shadowTint) {
        Bitmap lut = LutToneMapper.generateLutBitmap(contrast, highlights, shadows, saturation,
                highlightWarmth, shadowTint);
        renderer.updateLut(lut);
    }

    /**
     * Disable the LUT — show the raw camera output.
     */
    public void clearLut() {
        renderer.clearLut();
    }

    /**
     * Return a copy of the current active LUT bitmap, or {@code null} if
     * no LUT is active.  Used for CPU-side capture tone grading.
     */
    public Bitmap getCurrentLutBitmap() {
        return renderer.getCurrentLutBitmap();
    }

    /**
     * Get the current preview viewport width.
     */
    public int getPreviewViewWidth() {
        return renderer.getViewWidth();
    }

    /**
     * Get the current preview viewport height.
     */
    public int getPreviewViewHeight() {
        return renderer.getViewHeight();
    }

    /**
     * Set T2 shader enhancement mode and parameters. Thread-safe.
     * @param mode 0=none, 1=faceLift+HDR, 2=GND, 3=starSky
     * @param params mode-specific parameter array (or null for mode 0)
     */
    public void updateEnhancement(int mode, float[] params) {
        renderer.updateEnhancement(mode, params);
    }

    /**
     * Set T3 USM (Unsharp Mask) parameters. Thread-safe.
     */
    public void updateUsm(boolean enabled, float radius, float strength) {
        renderer.updateUsm(enabled, radius, strength);
    }

    /**
     * Set global crop/zoom (1.0 = none) and brightness multiplier (1.0 = none).
     * Used by Portrait mode (1.5x crop + slight brightness). Thread-safe.
     */
    public void updateZoomBrightness(float zoom, float brightness) {
        renderer.updateZoomBrightness(zoom, brightness);
    }

    /**
     * Clear all scene enhancements (T2 shader + T3 USM). Thread-safe.
     */
    public void clearEnhancements() {
        renderer.clearEnhancements();
    }

    /** Upload a portrait segmentation mask for mode-preview background blur. */
    public void updatePortraitMask(Bitmap maskBitmap) {
        renderer.updatePortraitMask(maskBitmap);
    }

    /** Clear any portrait segmentation mask currently bound in the renderer. */
    public void clearPortraitMask() {
        renderer.clearPortraitMask();
    }

    @Override
    protected void onDetachedFromWindow() {
        // Release the renderer's background frame-callback thread and
        // watchdog so they don't leak when the view is removed.  GLSurfaceView
        // itself tears down the GL thread here.
        try {
            renderer.release();
        } catch (Throwable t) {
            Log.w(TAG, "renderer.release() failed", t);
        }
        super.onDetachedFromWindow();
    }
}
