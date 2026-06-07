package com.samsung.camera.intelligence.app.camera;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.camera.core.SurfaceRequest;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 2.0 renderer that displays the camera preview with an optional
 * 3D LUT (look-up table) applied in real time on the GPU.
 *
 * <p>The LUT is a 2D texture atlas of size (N*N × N) where each blue slice
 * is laid out horizontally.  The fragment shader performs trilinear sampling
 * to map each pixel through the LUT with zero CPU overhead.
 *
 * <p>Key advantage over Camera2 TONEMAP_MODE_CONTRAST_CURVE: the LUT is
 * applied ONLY to the display output.  The ImageAnalysis pipeline receives
 * un-tone-mapped frames, completely breaking the feedback loop that caused
 * progressive brightening.
 */
public class CameraGLRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "CameraGLRenderer";

    /**
     * Callback fired (on the main thread) when the renderer needs CameraX
     * to re-bind its use cases so that a fresh {@link SurfaceRequest} is
     * delivered.  This is the definitive fix for the preview-freeze bug:
     * when the EGL context is destroyed and recreated (memory pressure,
     * screen off/on, certain OEM ROM behaviours) the renderer's old
     * {@code SurfaceTexture} becomes invalid, but CameraX still believes
     * its preview output is valid and never issues a new SurfaceRequest
     * on its own — leaving us with {@code surfaceTexture == null} and a
     * permanently black/frozen preview.  By firing this callback the
     * controller can force a rebind, which causes CameraX to call
     * {@link Preview.SurfaceProvider#onSurfaceRequested} again with a
     * fresh request that we can wire up.
     */
    public interface SurfaceRebindListener {
        void onSurfaceRebindRequested();
    }

    // ── Vertex shader ────────────────────────────────────────────────────────
    private static final String VERTEX_SHADER =
            "uniform mat4 uMvpMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMvpMatrix * aPosition;\n" +
            "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    // ── Fragment shader with 3D LUT lookup + T2 enhancements + T3 USM ────────
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uCameraTexture;\n" +
            "uniform sampler2D uLutTexture;\n" +
            "uniform sampler2D uBlurTexture;\n" +
            "uniform sampler2D uMaskTexture;\n" +
            "uniform float uLutSize;\n" +
            "uniform float uLutEnabled;\n" +
            "uniform float uBlurAvailable;\n" +
            "uniform float uMaskEnabled;\n" +
            "// T2 enhancement uniforms\n" +
            "uniform int uEnhanceMode;\n" +
            "uniform vec2 uFaceCenter;\n" +
            "uniform float uFaceLiftRadius;\n" +
            "uniform float uFaceLiftStrength;\n" +
            "uniform float uHdrStrength;\n" +
            "uniform float uGndPosition;\n" +
            "uniform float uGndStrength;\n" +
            "uniform float uStarFloor;\n" +
            "uniform float uStarGain;\n" +
            "uniform float uStarThreshold;\n" +
            "uniform float uNightGamma;\n" +
            "uniform float uNightHighlightCap;\n" +
            "uniform float uNightDenoise;\n" +
            "uniform float uFoodSaturation;\n" +
            "uniform float uFoodWarmR;\n" +
            "uniform float uFoodWarmG;\n" +
            "uniform float uPanoramaBand;\n" +
            "uniform float uPanoramaLineWidth;\n" +
            "uniform vec2 uPortraitCenter;\n" +
            "uniform float uPortraitInnerRadius;\n" +
            "uniform float uPortraitOuterRadius;\n" +
            "uniform float uPortraitBlurStrength;\n" +
            "// T3 USM uniforms\n" +
            "uniform float uUsmEnabled;\n" +
            "uniform float uUsmStrength;\n" +
            "varying vec2 vTexCoord;\n" +
            "\n" +
            "vec3 lutLookup(vec3 color) {\n" +
            "    vec3 c = clamp(color, 0.0, 1.0);\n" +
            "    float size = uLutSize;\n" +
            "    float sizeM1 = size - 1.0;\n" +
            "    float totalWidth = size * size;\n" +
            "    float blueIndex = c.b * sizeM1;\n" +
            "    float bFloor = floor(blueIndex);\n" +
            "    float bCeil  = min(bFloor + 1.0, sizeM1);\n" +
            "    float bFrac  = blueIndex - bFloor;\n" +
            "    float u_r = (c.r * sizeM1 + 0.5) / totalWidth;\n" +
            "    float v_g = (c.g * sizeM1 + 0.5) / size;\n" +
            "    float u0 = bFloor / size + u_r;\n" +
            "    float u1 = bCeil  / size + u_r;\n" +
            "    vec3 s0 = texture2D(uLutTexture, vec2(u0, v_g)).rgb;\n" +
            "    vec3 s1 = texture2D(uLutTexture, vec2(u1, v_g)).rgb;\n" +
            "    return mix(s0, s1, bFrac);\n" +
            "}\n" +
            "\n" +
            "vec3 applySaturationBoost(vec3 color, float amount) {\n" +
            "    float lum = dot(color, vec3(0.299, 0.587, 0.114));\n" +
            "    return mix(vec3(lum), color, amount);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(uCameraTexture, vTexCoord);\n" +
            "    // T3 USM: sharpen raw signal before grading\n" +
            "    if (uUsmEnabled > 0.5) {\n" +
            "        vec3 blurred = texture2D(uBlurTexture, vTexCoord).rgb;\n" +
            "        color.rgb += uUsmStrength * (color.rgb - blurred);\n" +
            "        color.rgb = clamp(color.rgb, 0.0, 1.0);\n" +
            "    }\n" +
            "    // T1 LUT grading\n" +
            "    if (uLutEnabled > 0.5) {\n" +
            "        color.rgb = lutLookup(color.rgb);\n" +
            "    }\n" +
            "    // T2 Mode 1: Backlit Face Lift + HDR\n" +
            "    if (uEnhanceMode == 1) {\n" +
            "        float d = distance(vTexCoord, uFaceCenter);\n" +
            "        float mask = smoothstep(uFaceLiftRadius, 0.0, d);\n" +
            "        float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "        vec3 lifted = color.rgb * (1.0 + uFaceLiftStrength * (1.0 - lum));\n" +
            "        color.rgb = mix(color.rgb, lifted, mask);\n" +
            "        // Global HDR tone compress\n" +
            "        color.rgb = pow(color.rgb, vec3(1.0 - uHdrStrength * 0.3));\n" +
            "    }\n" +
            "    // T2 Mode 2: Digital GND (graduated ND)\n" +
            "    if (uEnhanceMode == 2) {\n" +
            "        float gndMask = smoothstep(uGndPosition - 0.15, uGndPosition + 0.05, vTexCoord.y);\n" +
            "        color.rgb *= mix(1.0, uGndStrength, gndMask);\n" +
            "    }\n" +
            "    // T2 Mode 3: Starry Sky enhancement\n" +
            "    if (uEnhanceMode == 3) {\n" +
            "        color.rgb = max(color.rgb - uStarFloor, 0.0) * uStarGain;\n" +
            "        float peak = max(color.r, max(color.g, color.b));\n" +
            "        color.rgb += step(uStarThreshold, peak) * 0.3;\n" +
            "        color.rgb = clamp(color.rgb, 0.0, 1.0);\n" +
            "    }\n" +
            "    // T2 Mode 4: Night preview brightening + highlight compression + denoise\n" +
            "    if (uEnhanceMode == 4) {\n" +
            "        color.rgb = pow(color.rgb, vec3(1.0 / max(uNightGamma, 0.1)));\n" +
            "        vec3 compressed = min(color.rgb, vec3(uNightHighlightCap));\n" +
            "        color.rgb = mix(color.rgb, compressed, 0.65);\n" +
            "        if (uBlurAvailable > 0.5) {\n" +
            "            vec3 denoised = texture2D(uBlurTexture, vTexCoord).rgb;\n" +
            "            float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "            float shadowWeight = 1.0 - smoothstep(0.25, 0.75, lum);\n" +
            "            color.rgb = mix(color.rgb, denoised, shadowWeight * uNightDenoise);\n" +
            "        }\n" +
            "        color.rgb = clamp(color.rgb, 0.0, 1.0);\n" +
            "    }\n" +
            "    // T2 Mode 5: Food preview warm + saturated\n" +
            "    if (uEnhanceMode == 5) {\n" +
            "        color.rgb = applySaturationBoost(color.rgb, uFoodSaturation);\n" +
            "        color.r = min(1.0, color.r + uFoodWarmR);\n" +
            "        color.g = min(1.0, color.g + uFoodWarmG);\n" +
            "    }\n" +
            "    // T2 Mode 6: Panorama matte + guide line + arrow\n" +
            "    if (uEnhanceMode == 6) {\n" +
            "        if (vTexCoord.y < uPanoramaBand || vTexCoord.y > 1.0 - uPanoramaBand) {\n" +
            "            color.rgb *= 0.18;\n" +
            "        }\n" +
            "        float lineMask = 1.0 - smoothstep(0.0, uPanoramaLineWidth, abs(vTexCoord.y - 0.5));\n" +
            "        float arrowBody = step(0.56, vTexCoord.x) * step(vTexCoord.x, 0.72) * (1.0 - smoothstep(0.0, uPanoramaLineWidth * 1.4, abs(vTexCoord.y - 0.5)));\n" +
            "        float headSpan = max(0.0, 0.82 - vTexCoord.x) * 0.45;\n" +
            "        float arrowHead = step(0.72, vTexCoord.x) * step(abs(vTexCoord.y - 0.5), headSpan);\n" +
            "        float guide = max(lineMask, max(arrowBody, arrowHead));\n" +
            "        color.rgb = mix(color.rgb, vec3(0.16, 0.94, 0.42), clamp(guide, 0.0, 1.0));\n" +
            "    }\n" +
            "    // T2 Mode 7: Portrait preview blur with optional segmentation mask\n" +
            "    if (uEnhanceMode == 7 && uBlurAvailable > 0.5) {\n" +
            "        vec3 blurred = texture2D(uBlurTexture, vTexCoord).rgb;\n" +
            "        float personMask = uMaskEnabled > 0.5 ? texture2D(uMaskTexture, vTexCoord).r : 0.0;\n" +
            "        float radial = smoothstep(uPortraitInnerRadius, uPortraitOuterRadius, distance(vTexCoord, uPortraitCenter));\n" +
            "        float blurMix = (1.0 - personMask) * mix(0.35, 1.0, radial) * uPortraitBlurStrength;\n" +
            "        color.rgb = mix(color.rgb, blurred, clamp(blurMix, 0.0, 1.0));\n" +
            "    }\n" +
            "    gl_FragColor = color;\n" +
            "}\n";

    // ── Blur shaders for FBO-based USM (T3) ─────────────────────────────────
    private static final String BLUR_VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String BLUR_H_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform float uTexelSize;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec3 sum = texture2D(uTexture, vTexCoord).rgb * 0.2270270;\n" +
            "    sum += texture2D(uTexture, vTexCoord + vec2(uTexelSize * 1.3846154, 0.0)).rgb * 0.3162162;\n" +
            "    sum += texture2D(uTexture, vTexCoord - vec2(uTexelSize * 1.3846154, 0.0)).rgb * 0.3162162;\n" +
            "    sum += texture2D(uTexture, vTexCoord + vec2(uTexelSize * 3.2307692, 0.0)).rgb * 0.0702703;\n" +
            "    sum += texture2D(uTexture, vTexCoord - vec2(uTexelSize * 3.2307692, 0.0)).rgb * 0.0702703;\n" +
            "    gl_FragColor = vec4(sum, 1.0);\n" +
            "}\n";

    private static final String BLUR_V_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform float uTexelSize;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec3 sum = texture2D(uTexture, vTexCoord).rgb * 0.2270270;\n" +
            "    sum += texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize * 1.3846154)).rgb * 0.3162162;\n" +
            "    sum += texture2D(uTexture, vTexCoord - vec2(0.0, uTexelSize * 1.3846154)).rgb * 0.3162162;\n" +
            "    sum += texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize * 3.2307692)).rgb * 0.0702703;\n" +
            "    sum += texture2D(uTexture, vTexCoord - vec2(0.0, uTexelSize * 3.2307692)).rgb * 0.0702703;\n" +
            "    gl_FragColor = vec4(sum, 1.0);\n" +
            "}\n";

    // ── OES pass-through shader for rendering camera to FBO ─────────────────
    private static final String OES_PASSTHROUGH_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uCameraTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uCameraTexture, vTexCoord);\n" +
            "}\n";

    // ── Geometry: full-screen quad ──────────────────────────────────────────
    private static final float[] QUAD_VERTICES = {
        -1f, -1f,   // bottom-left
         1f, -1f,   // bottom-right
        -1f,  1f,   // top-left
         1f,  1f,   // top-right
    };
    private static final float[] QUAD_TEX_COORDS = {
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    };

    // ── GL handles ──────────────────────────────────────────────────────────
    private int programId;
    private int oesTextureId;
    private int lutTextureId;
    private int maskTextureId;

    private int uMvpMatrix, uTexMatrix, uCameraTexture, uLutTexture;
    private int uLutSize, uLutEnabled;
    private int aPosition, aTexCoord;
    // T2 enhancement uniform locations
    private int uEnhanceMode, uFaceCenter, uFaceLiftRadius, uFaceLiftStrength;
    private int uHdrStrength, uGndPosition, uGndStrength;
    private int uStarFloor, uStarGain, uStarThreshold;
    private int uNightGamma, uNightHighlightCap, uNightDenoise;
    private int uFoodSaturation, uFoodWarmR, uFoodWarmG;
    private int uPanoramaBand, uPanoramaLineWidth;
    private int uPortraitCenter, uPortraitInnerRadius, uPortraitOuterRadius, uPortraitBlurStrength;
    // T3 USM uniform locations (main shader)
    private int uBlurTexture, uUsmEnabled, uUsmStrength, uBlurAvailable;
    private int uMaskTexture, uMaskEnabled;

    // T3 FBO+USM shader programs and handles
    private int oesPassthroughProgramId;
    private int blurHProgramId;
    private int blurVProgramId;
    private int oesPassAPosition, oesPassATexCoord, oesPassUTexMatrix, oesPassUMvpMatrix, oesPassUCameraTexture;
    private int blurHAPosition, blurHATexCoord, blurHUTexture, blurHUTexelSize;
    private int blurVAPosition, blurVATexCoord, blurVUTexture, blurVUTexelSize;
    // FBO handles: camera→FBO, blurH→FBO, blurV→FBO
    private int fboCamera, texCamera;
    private int fboBlurH, texBlurH;
    private int fboBlurV, texBlurV;
    private boolean fbosInitialized = false;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private final float[] mvpMatrix = new float[16];
    private final float[] stMatrix  = new float[16];

    // ── State ───────────────────────────────────────────────────────────────
    private SurfaceTexture surfaceTexture;
    /**
     * Signals that at least one new camera frame is waiting in the BufferQueue.
     * <p>MUST only be read/written while holding {@link #frameLock}.  Using a
     * lock (rather than just {@code volatile}) closes the race where the GL
     * thread overwrites a {@code true} set by the frame-available listener
     * while it is running {@code updateTexImage()} — which previously caused
     * the BufferQueue to fill, the camera producer to block, and the preview
     * to freeze until the app was killed.
     */
    private boolean frameAvailable = false;
    private final Object frameLock = new Object();
    private volatile boolean glReady = false;
    private boolean surfaceCreatedBefore = false;  // GL context recreation flag
    private GLSurfaceView glView;
    private volatile SurfaceRebindListener surfaceRebindListener;
    /** Guards against firing the rebind callback in a tight loop. */
    private volatile long lastRebindRequestElapsedMs = 0L;
    private static final long REBIND_THROTTLE_MS = 2000L;

    // Dedicated thread for SurfaceTexture.onFrameAvailable callbacks.  Using
    // the main thread here is unsafe: any main-thread stall (layout, GC,
    // inference post-processing, binder call, dialog) delays the callback,
    // lets the BufferQueue fill up, blocks the camera producer, and freezes
    // the preview.  A dedicated HandlerThread keeps the pipeline alive
    // regardless of UI thread load.
    private HandlerThread frameCallbackThread;
    private Handler frameCallbackHandler;

    // ── Watchdog ────────────────────────────────────────────────────────────
    // If the GL thread hasn't produced a frame for a while even though the
    // camera is streaming (surfaceTexture != null), re-kick requestRender()
    // as a safety net.  This rescues the pipeline from transient stalls
    // without requiring the user to kill the app.
    private static final long WATCHDOG_INTERVAL_MS = 500L;
    private static final long WATCHDOG_STALL_MS   = 1500L;
    private volatile long lastFrameRenderedElapsedMs = 0L;
    private Handler watchdogHandler;
    /** Hard-stall threshold: trigger full CameraX rebind to recover. */
    private static final long WATCHDOG_HARD_STALL_MS = 3000L;
    private final Runnable watchdogRunnable = new Runnable() {
        @Override public void run() {
            try {
                if (glReady) {
                    long now = SystemClock.elapsedRealtime();
                    long since = now - lastFrameRenderedElapsedMs;
                    boolean hasPending;
                    synchronized (frameLock) { hasPending = frameAvailable; }
                    if (surfaceTexture == null) {
                        // No surface attached yet but watchdog is alive.
                        // If this persists, ask the controller to rebind.
                        if (lastFrameRenderedElapsedMs == 0
                                && (now - lastRebindRequestElapsedMs) > WATCHDOG_HARD_STALL_MS) {
                            Log.w(TAG, "No SurfaceTexture for >" + WATCHDOG_HARD_STALL_MS
                                    + " ms — requesting CameraX rebind");
                            requestSurfaceRebind();
                        }
                    } else if (lastFrameRenderedElapsedMs > 0
                            && since > WATCHDOG_HARD_STALL_MS) {
                        // Frames stopped flowing despite a live surface —
                        // this is the classic freeze.  Kick a render first;
                        // if that doesn't help on the next tick, escalate.
                        Log.w(TAG, "Hard preview stall (" + since
                                + " ms since last draw, pending=" + hasPending
                                + ") — requesting CameraX rebind");
                        requestRender();
                        requestSurfaceRebind();
                    } else if (lastFrameRenderedElapsedMs > 0
                            && since > WATCHDOG_STALL_MS) {
                        Log.w(TAG, "Preview stall detected (" + since
                                + " ms since last draw, pending=" + hasPending
                                + ") — kicking render");
                        requestRender();
                    } else if (hasPending) {
                        requestRender();
                    }
                }
            } finally {
                if (watchdogHandler != null) {
                    watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                }
            }
        }
    };

    /**
     * Notify the controller (on the main thread) that it should re-bind
     * CameraX use cases so a fresh SurfaceRequest is delivered.  Throttled
     * to avoid rebind-storms when the renderer is in a degenerate state.
     */
    private void requestSurfaceRebind() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRebindRequestElapsedMs < REBIND_THROTTLE_MS) {
            return;
        }
        lastRebindRequestElapsedMs = now;
        final SurfaceRebindListener l = surfaceRebindListener;
        if (l == null || glView == null) {
            return;
        }
        glView.post(() -> {
            try {
                l.onSurfaceRebindRequested();
            } catch (Throwable t) {
                Log.w(TAG, "surfaceRebindListener threw", t);
            }
        });
    }

    // Pending LUT upload
    private Bitmap pendingLutBitmap;
    private Bitmap lastUploadedLut;   // retained copy for CPU-side capture
    private boolean lutActive = false;
    private final Object lutLock = new Object();
    private Bitmap pendingMaskBitmap;
    private boolean maskActive = false;

    // Pending T2 enhancement state
    private int pendingEnhanceMode = 0;
    private float[] pendingEnhanceParams = null;
    private int activeEnhanceMode = 0;
    private float[] activeEnhanceParams = null;

    // Pending T3 USM state
    private boolean pendingUsmEnabled = false;
    private float pendingUsmRadius = 0;
    private float pendingUsmStrength = 0;
    private boolean activeUsmEnabled = false;
    private float activeUsmStrength = 0;

    // Pending CameraX surface request
    private SurfaceRequest pendingRequest;

    // Camera resolution for aspect-ratio correction
    private int cameraWidth = 0;
    private int cameraHeight = 0;
    private int viewWidth = 0;
    private int viewHeight = 0;

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        // Create OES texture for camera frames
        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        oesTextureId = texIds[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Create LUT texture (initially empty identity)
        GLES20.glGenTextures(1, texIds, 0);
        lutTextureId = texIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Upload identity LUT
        Bitmap identity = LutToneMapper.identityLutBitmap();
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, identity, 0);
        identity.recycle();

        // Create portrait-mask texture (1x1 black by default)
        GLES20.glGenTextures(1, texIds, 0);
        maskTextureId = texIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        Bitmap emptyMask = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        emptyMask.eraseColor(0xFF000000);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, emptyMask, 0);
        emptyMask.recycle();

        // Compile main shader
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition      = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoord      = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uMvpMatrix     = GLES20.glGetUniformLocation(programId, "uMvpMatrix");
        uTexMatrix     = GLES20.glGetUniformLocation(programId, "uTexMatrix");
        uCameraTexture = GLES20.glGetUniformLocation(programId, "uCameraTexture");
        uLutTexture    = GLES20.glGetUniformLocation(programId, "uLutTexture");
        uMaskTexture   = GLES20.glGetUniformLocation(programId, "uMaskTexture");
        uLutSize       = GLES20.glGetUniformLocation(programId, "uLutSize");
        uLutEnabled    = GLES20.glGetUniformLocation(programId, "uLutEnabled");
        uBlurAvailable = GLES20.glGetUniformLocation(programId, "uBlurAvailable");
        uMaskEnabled   = GLES20.glGetUniformLocation(programId, "uMaskEnabled");
        // T2 uniforms
        uEnhanceMode      = GLES20.glGetUniformLocation(programId, "uEnhanceMode");
        uFaceCenter        = GLES20.glGetUniformLocation(programId, "uFaceCenter");
        uFaceLiftRadius    = GLES20.glGetUniformLocation(programId, "uFaceLiftRadius");
        uFaceLiftStrength  = GLES20.glGetUniformLocation(programId, "uFaceLiftStrength");
        uHdrStrength       = GLES20.glGetUniformLocation(programId, "uHdrStrength");
        uGndPosition       = GLES20.glGetUniformLocation(programId, "uGndPosition");
        uGndStrength       = GLES20.glGetUniformLocation(programId, "uGndStrength");
        uStarFloor         = GLES20.glGetUniformLocation(programId, "uStarFloor");
        uStarGain          = GLES20.glGetUniformLocation(programId, "uStarGain");
        uStarThreshold     = GLES20.glGetUniformLocation(programId, "uStarThreshold");
        uNightGamma        = GLES20.glGetUniformLocation(programId, "uNightGamma");
        uNightHighlightCap = GLES20.glGetUniformLocation(programId, "uNightHighlightCap");
        uNightDenoise      = GLES20.glGetUniformLocation(programId, "uNightDenoise");
        uFoodSaturation    = GLES20.glGetUniformLocation(programId, "uFoodSaturation");
        uFoodWarmR         = GLES20.glGetUniformLocation(programId, "uFoodWarmR");
        uFoodWarmG         = GLES20.glGetUniformLocation(programId, "uFoodWarmG");
        uPanoramaBand      = GLES20.glGetUniformLocation(programId, "uPanoramaBand");
        uPanoramaLineWidth = GLES20.glGetUniformLocation(programId, "uPanoramaLineWidth");
        uPortraitCenter    = GLES20.glGetUniformLocation(programId, "uPortraitCenter");
        uPortraitInnerRadius = GLES20.glGetUniformLocation(programId, "uPortraitInnerRadius");
        uPortraitOuterRadius = GLES20.glGetUniformLocation(programId, "uPortraitOuterRadius");
        uPortraitBlurStrength = GLES20.glGetUniformLocation(programId, "uPortraitBlurStrength");
        // T3 USM uniforms (main shader)
        uBlurTexture   = GLES20.glGetUniformLocation(programId, "uBlurTexture");
        uUsmEnabled    = GLES20.glGetUniformLocation(programId, "uUsmEnabled");
        uUsmStrength   = GLES20.glGetUniformLocation(programId, "uUsmStrength");

        // Compile T3 blur shaders
        oesPassthroughProgramId = createProgram(VERTEX_SHADER, OES_PASSTHROUGH_FRAGMENT_SHADER);
        oesPassAPosition      = GLES20.glGetAttribLocation(oesPassthroughProgramId, "aPosition");
        oesPassATexCoord      = GLES20.glGetAttribLocation(oesPassthroughProgramId, "aTexCoord");
        oesPassUMvpMatrix     = GLES20.glGetUniformLocation(oesPassthroughProgramId, "uMvpMatrix");
        oesPassUTexMatrix     = GLES20.glGetUniformLocation(oesPassthroughProgramId, "uTexMatrix");
        oesPassUCameraTexture = GLES20.glGetUniformLocation(oesPassthroughProgramId, "uCameraTexture");

        blurHProgramId = createProgram(BLUR_VERTEX_SHADER, BLUR_H_FRAGMENT_SHADER);
        blurHAPosition  = GLES20.glGetAttribLocation(blurHProgramId, "aPosition");
        blurHATexCoord  = GLES20.glGetAttribLocation(blurHProgramId, "aTexCoord");
        blurHUTexture   = GLES20.glGetUniformLocation(blurHProgramId, "uTexture");
        blurHUTexelSize = GLES20.glGetUniformLocation(blurHProgramId, "uTexelSize");

        blurVProgramId = createProgram(BLUR_VERTEX_SHADER, BLUR_V_FRAGMENT_SHADER);
        blurVAPosition  = GLES20.glGetAttribLocation(blurVProgramId, "aPosition");
        blurVATexCoord  = GLES20.glGetAttribLocation(blurVProgramId, "aTexCoord");
        blurVUTexture   = GLES20.glGetUniformLocation(blurVProgramId, "uTexture");
        blurVUTexelSize = GLES20.glGetUniformLocation(blurVProgramId, "uTexelSize");

        // Create vertex / tex-coord buffers
        vertexBuffer   = toFloatBuffer(QUAD_VERTICES);
        texCoordBuffer = toFloatBuffer(QUAD_TEX_COORDS);

        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.setIdentityM(stMatrix, 0);

        glReady = true;

        // Handle GL context recreation.  The old SurfaceTexture and its
        // Surface are tied to the now-invalid old OES texture ID, AND
        // CameraX still holds the old (dead) Surface internally — so it
        // will never on its own issue a new SurfaceRequest.  We release
        // the stale objects locally and ask the controller to re-bind
        // CameraX use cases, which forces a fresh SurfaceRequest to flow
        // through the SurfaceProvider lambda back to handleSurfaceRequest.
        boolean contextWasRecreated = surfaceCreatedBefore;
        if (contextWasRecreated) {
            Log.w(TAG, "GL context recreated — releasing stale SurfaceTexture and requesting rebind");
            if (surfaceTexture != null) {
                try { surfaceTexture.release(); } catch (Throwable ignored) {}
                surfaceTexture = null;
            }
            synchronized (frameLock) {
                frameAvailable = false;
            }
            lastFrameRenderedElapsedMs = 0L;
            stopWatchdog();
            // Drop any cached pending request that referenced the old
            // context — its Surface is now invalid.  A fresh request
            // will arrive after the controller rebinds.
            pendingRequest = null;
            requestSurfaceRebind();
        }
        surfaceCreatedBefore = true;

        // If a CameraX request arrived before GL was ready, handle it now
        if (pendingRequest != null) {
            provideSurfaceToCameraX(pendingRequest);
            pendingRequest = null;
        }

        // Start the watchdog unconditionally so the "no SurfaceTexture for
        // >3s" recovery path can fire even if no SurfaceRequest ever
        // arrives after a context recreation.
        startWatchdog();

        Log.i(TAG, "GL surface created, OES tex=" + oesTextureId
                + " LUT tex=" + lutTextureId);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        viewWidth = width;
        viewHeight = height;
        updateMvpMatrix();
        initFBOs(width, height);
        Log.i(TAG, "GL surface changed: " + width + "×" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (surfaceTexture == null) return;

        // Update camera frame.
        //
        // IMPORTANT: clear the flag BEFORE calling updateTexImage(), under
        // the same lock used by the onFrameAvailable listener.  Previously
        // the flag was cleared AFTER updateTexImage, which created a window
        // where a new frame's callback could set frameAvailable=true and
        // then get clobbered back to false by this thread.  The listener's
        // paired requestRender() would then fire an empty draw, leaving the
        // just-queued buffer unconsumed in the BufferQueue.  After a few
        // repetitions the queue filled up, the camera producer blocked,
        // and no further frame-available callbacks arrived — which is the
        // freeze-until-app-is-killed symptom reported.
        boolean shouldConsumeFrame;
        synchronized (frameLock) {
            shouldConsumeFrame = frameAvailable;
            frameAvailable = false;
        }
        if (shouldConsumeFrame) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage failed", e);
            }
        }
        lastFrameRenderedElapsedMs = SystemClock.elapsedRealtime();

        // Upload pending LUT if needed
        synchronized (lutLock) {
            if (pendingLutBitmap != null) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pendingLutBitmap, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                if (lastUploadedLut != null) {
                    lastUploadedLut.recycle();
                }
                lastUploadedLut = pendingLutBitmap.copy(pendingLutBitmap.getConfig(), false);
                pendingLutBitmap.recycle();
                pendingLutBitmap = null;
                lutActive = true;
            }
            // Sync T2 enhancement state
            activeEnhanceMode = pendingEnhanceMode;
            activeEnhanceParams = pendingEnhanceParams;
            // Sync T3 USM state
            activeUsmEnabled = pendingUsmEnabled;
            activeUsmStrength = pendingUsmStrength;
            if (pendingMaskBitmap != null) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pendingMaskBitmap, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                pendingMaskBitmap.recycle();
                pendingMaskBitmap = null;
                maskActive = true;
            }
        }

        // T3 USM / blur-dependent modes: if enabled, do multi-pass (camera→FBO→blurH→blurV), then composite
        if (needsBlurPass() && fbosInitialized) {
            drawUsmMultiPass();
        } else {
            drawSinglePass(-1); // no blur texture
        }
    }

    /** Single-pass draw: camera + LUT + T2 enhancements, optionally with blur texture for USM. */
    private void drawSinglePass(int blurTexId) {
        GLES20.glUseProgram(programId);

        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0);

        // Bind camera OES texture to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(uCameraTexture, 0);

        // Bind LUT texture to unit 1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId);
        GLES20.glUniform1i(uLutTexture, 1);

        // Bind blur texture to unit 2 (for USM)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        if (blurTexId >= 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTexId);
        } else {
            // Bind a zero texture to avoid undefined sampler state on some drivers
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glUniform1i(uBlurTexture, 2);
        GLES20.glUniform1f(uBlurAvailable, blurTexId >= 0 ? 1.0f : 0.0f);

        // Bind portrait mask texture to unit 3
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId);
        GLES20.glUniform1i(uMaskTexture, 3);
        GLES20.glUniform1f(uMaskEnabled, maskActive ? 1.0f : 0.0f);

        // Restore active texture to unit 0 for clean GL state
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        GLES20.glUniform1f(uLutSize, (float) LutToneMapper.LUT_SIZE);
        GLES20.glUniform1f(uLutEnabled, lutActive ? 1.0f : 0.0f);

        // T2 enhancement uniforms
        GLES20.glUniform1i(uEnhanceMode, activeEnhanceMode);
        setEnhancementUniforms();

        // T3 USM uniforms
        GLES20.glUniform1f(uUsmEnabled, (blurTexId >= 0 && activeUsmEnabled) ? 1.0f : 0.0f);
        GLES20.glUniform1f(uUsmStrength, activeUsmStrength);

        // Vertex attributes
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
    }

    /** T3 multi-pass USM: camera→FBO, blurH, blurV, then composite to screen. */
    private void drawUsmMultiPass() {
        // Identity MVP for FBO passes — the FBO texture is viewport-sized,
        // so the camera OES texture must be rendered 1:1 into it.  The
        // aspect-ratio correction is applied only in the final screen pass.
        float[] identityMvp = new float[16];
        Matrix.setIdentityM(identityMvp, 0);

        // Pass 1: Render camera to texCamera via OES passthrough
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboCamera);
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(oesPassthroughProgramId);
        GLES20.glUniformMatrix4fv(oesPassUMvpMatrix, 1, false, identityMvp, 0);
        GLES20.glUniformMatrix4fv(oesPassUTexMatrix, 1, false, stMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(oesPassUCameraTexture, 0);
        drawQuad(oesPassAPosition, oesPassATexCoord);

        // Pass 2: Horizontal blur (texCamera → texBlurH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBlurH);
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(blurHProgramId);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texCamera);
        GLES20.glUniform1i(blurHUTexture, 0);
        GLES20.glUniform1f(blurHUTexelSize, 1.0f / viewWidth);
        drawQuad(blurHAPosition, blurHATexCoord);

        // Pass 3: Vertical blur (texBlurH → texBlurV)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBlurV);
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(blurVProgramId);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBlurH);
        GLES20.glUniform1i(blurVUTexture, 0);
        GLES20.glUniform1f(blurVUTexelSize, 1.0f / viewHeight);
        drawQuad(blurVAPosition, blurVATexCoord);

        // Pass 4: Composite to screen with main shader (camera + blur + LUT + T2)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawSinglePass(texBlurV);

        // Defensive: reset GL state so a subsequent single-pass frame
        // (after USM is toggled off) starts from a clean slate.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private boolean needsBlurPass() {
        return activeUsmEnabled || activeEnhanceMode == 4 || activeEnhanceMode == 7;
    }

    /** Draw a fullscreen quad with specified attribute locations. */
    private void drawQuad(int posAttrib, int texAttrib) {
        GLES20.glEnableVertexAttribArray(posAttrib);
        GLES20.glVertexAttribPointer(posAttrib, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(texAttrib);
        GLES20.glVertexAttribPointer(texAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posAttrib);
        GLES20.glDisableVertexAttribArray(texAttrib);
    }

    /** Set T2 shader uniforms based on active enhancement mode and params. */
    private void setEnhancementUniforms() {
        float[] p = activeEnhanceParams;
        switch (activeEnhanceMode) {
            case 1: // Face Lift + HDR
                if (p != null && p.length >= 5) {
                    GLES20.glUniform2f(uFaceCenter, p[0], p[1]);
                    GLES20.glUniform1f(uFaceLiftRadius, p[2]);
                    GLES20.glUniform1f(uFaceLiftStrength, p[3]);
                    GLES20.glUniform1f(uHdrStrength, p[4]);
                }
                break;
            case 2: // GND
                if (p != null && p.length >= 2) {
                    GLES20.glUniform1f(uGndPosition, p[0]);
                    GLES20.glUniform1f(uGndStrength, p[1]);
                }
                break;
            case 3: // Star Sky
                if (p != null && p.length >= 4) {
                    GLES20.glUniform1f(uStarFloor, p[0]);
                    GLES20.glUniform1f(uStarGain, p[1]);
                    GLES20.glUniform1f(uStarThreshold, p[2]);
                    // starGlow is built into the shader constant
                }
                break;
            case 4: // Night
                if (p != null && p.length >= 3) {
                    GLES20.glUniform1f(uNightGamma, p[0]);
                    GLES20.glUniform1f(uNightHighlightCap, p[1]);
                    GLES20.glUniform1f(uNightDenoise, p[2]);
                }
                break;
            case 5: // Food
                if (p != null && p.length >= 3) {
                    GLES20.glUniform1f(uFoodSaturation, p[0]);
                    GLES20.glUniform1f(uFoodWarmR, p[1]);
                    GLES20.glUniform1f(uFoodWarmG, p[2]);
                }
                break;
            case 6: // Panorama
                if (p != null && p.length >= 2) {
                    GLES20.glUniform1f(uPanoramaBand, p[0]);
                    GLES20.glUniform1f(uPanoramaLineWidth, p[1]);
                }
                break;
            case 7: // Portrait
                if (p != null && p.length >= 5) {
                    GLES20.glUniform2f(uPortraitCenter, p[0], p[1]);
                    GLES20.glUniform1f(uPortraitInnerRadius, p[2]);
                    GLES20.glUniform1f(uPortraitOuterRadius, p[3]);
                    GLES20.glUniform1f(uPortraitBlurStrength, p[4]);
                }
                break;
            default:
                break;
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Set the GLSurfaceView reference (needed for requestRender callbacks).
     */
    public void setGlView(GLSurfaceView view) {
        this.glView = view;
    }

    /**
     * Register a listener to be invoked (on the main thread) when the
     * renderer needs CameraX use cases to be re-bound — e.g. after EGL
     * context loss or a hard preview stall.  See {@link SurfaceRebindListener}.
     */
    public void setSurfaceRebindListener(SurfaceRebindListener listener) {
        this.surfaceRebindListener = listener;
    }

    /**
     * Handle a CameraX surface request.  If GL is ready, provide the surface
     * immediately; otherwise queue it for onSurfaceCreated.
     */
    public void handleSurfaceRequest(SurfaceRequest request) {
        if (!glReady) {
            pendingRequest = request;
            return;
        }
        provideSurfaceToCameraX(request);
    }

    /**
     * Upload a new LUT bitmap.  Thread-safe — can be called from any thread.
     * The actual GL texture upload happens on the next draw frame.
     */
    public void updateLut(Bitmap lutBitmap) {
        if (lutBitmap == null) return;
        synchronized (lutLock) {
            if (pendingLutBitmap != null) {
                pendingLutBitmap.recycle();
            }
            pendingLutBitmap = lutBitmap;
        }
        requestRender();
    }

    /**
     * Return a copy of the current active LUT bitmap, or {@code null} if
     * no LUT is active.  Used to apply the same grading to captured photos.
     */
    public Bitmap getCurrentLutBitmap() {
        synchronized (lutLock) {
            if (!lutActive || lastUploadedLut == null) return null;
            // Return a copy — the caller may recycle it independently.
            return lastUploadedLut.copy(lastUploadedLut.getConfig(), false);
        }
    }

    /**
     * Get the preview viewport width (view surface pixel size, not camera sensor).
     * Returns 0 if onSurfaceChanged has not yet been called.
     */
    public int getViewWidth() {
        return viewWidth;
    }

    /**
     * Get the preview viewport height (view surface pixel size, not camera sensor).
     * Returns 0 if onSurfaceChanged has not yet been called.
     */
    public int getViewHeight() {
        return viewHeight;
    }

    /**
     * Disable the LUT (show raw camera output).
     */
    public void clearLut() {
        synchronized (lutLock) {
            if (pendingLutBitmap != null) {
                pendingLutBitmap.recycle();
                pendingLutBitmap = null;
            }
            if (lastUploadedLut != null) {
                lastUploadedLut.recycle();
                lastUploadedLut = null;
            }
            lutActive = false;
        }
        requestRender();
    }

    /**
     * Set the T2 enhancement mode and shader parameters. Thread-safe.
     * @param mode 0=none, 1=faceLift+HDR, 2=GND, 3=starSky
     * @param params mode-specific parameter array (or null for mode 0)
     */
    public void updateEnhancement(int mode, float[] params) {
        synchronized (lutLock) {
            pendingEnhanceMode = mode;
            pendingEnhanceParams = params;
        }
        requestRender();
    }

    /**
     * Set T3 USM (Unsharp Mask) parameters. Thread-safe.
     */
    public void updateUsm(boolean enabled, float radius, float strength) {
        synchronized (lutLock) {
            pendingUsmEnabled = enabled;
            pendingUsmRadius = radius;
            pendingUsmStrength = strength;
        }
        requestRender();
    }

    /**
     * Clear all enhancements (T2 + T3). Thread-safe.
     */
    public void clearEnhancements() {
        synchronized (lutLock) {
            pendingEnhanceMode = 0;
            pendingEnhanceParams = null;
            pendingUsmEnabled = false;
            pendingUsmRadius = 0;
            pendingUsmStrength = 0;
        }
        requestRender();
    }

    /** Upload or replace the portrait segmentation mask. */
    public void updatePortraitMask(Bitmap maskBitmap) {
        if (maskBitmap == null) return;
        synchronized (lutLock) {
            if (pendingMaskBitmap != null) {
                pendingMaskBitmap.recycle();
            }
            pendingMaskBitmap = maskBitmap;
        }
        requestRender();
    }

    /** Clear the portrait segmentation mask and disable mask-based blending. */
    public void clearPortraitMask() {
        synchronized (lutLock) {
            if (pendingMaskBitmap != null) {
                pendingMaskBitmap.recycle();
                pendingMaskBitmap = null;
            }
            maskActive = false;
        }
        requestRender();
    }

    /**
     * Release background resources (frame-callback thread + watchdog).
     * Safe to call from any thread.  Should be invoked when the hosting
     * view is detached from the window to avoid leaking the HandlerThread.
     */
    public void release() {
        shutdownFrameCallbackThread();
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Compute a center-crop MVP matrix that preserves camera aspect ratio.
     * <p>This mimics PreviewView's "fillCenter" scaleType: the camera image
     * fills the view completely, cropping the excess (no black bars).
     */
    private void updateMvpMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0);
        if (cameraWidth <= 0 || cameraHeight <= 0
                || viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float cameraAspect = (float) cameraWidth / cameraHeight;
        float viewAspect   = (float) viewWidth  / viewHeight;

        // Center-crop: scale the quad UP on the narrower axis so the
        // camera image fills the view. Excess is cropped by the viewport.
        if (cameraAspect > viewAspect) {
            // Camera is wider than view → scale x up
            float sx = cameraAspect / viewAspect;
            Matrix.scaleM(mvpMatrix, 0, sx, 1f, 1f);
        } else {
            // Camera is taller than view → scale y up
            float sy = viewAspect / cameraAspect;
            Matrix.scaleM(mvpMatrix, 0, 1f, sy, 1f);
        }
    }

    private void provideSurfaceToCameraX(SurfaceRequest request) {
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        // Reset pending-frame state for the new surface.
        synchronized (frameLock) {
            frameAvailable = false;
        }
        lastFrameRenderedElapsedMs = 0L;

        surfaceTexture = new SurfaceTexture(oesTextureId);
        // Deliver frame-available callbacks on a dedicated HandlerThread.
        // The GL thread has no Looper, and using the main thread causes
        // the preview to freeze whenever the UI thread stalls (layout, GC,
        // binder calls, dialogs): the callback is delayed, frames pile up
        // in the BufferQueue, the camera producer blocks, and no further
        // callbacks arrive.  A dedicated thread isolates the preview
        // pipeline from UI-thread load.
        ensureFrameCallbackThread();
        surfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameLock) {
                frameAvailable = true;
            }
            requestRender();
        }, frameCallbackHandler);

        Size resolution = request.getResolution();
        cameraWidth = resolution.getWidth();
        cameraHeight = resolution.getHeight();
        surfaceTexture.setDefaultBufferSize(cameraWidth, cameraHeight);
        updateMvpMatrix();
        Log.i(TAG, "Camera resolution: " + cameraWidth + "×" + cameraHeight);

        Surface surface = new Surface(surfaceTexture);
        try {
            request.provideSurface(surface,
                    ContextCompat.getMainExecutor(glView.getContext()),
                    result -> {
                        surface.release();
                        // Don't release surfaceTexture here — it may still be in use
                        // for rendering. It will be released in the next handleSurfaceRequest
                        // or when the renderer is destroyed.
                    });
        } catch (IllegalStateException e) {
            // Request was already completed/cancelled (e.g. CameraX rebind
            // raced with a surface request).  Drop the dead Surface so we
            // don't leak; the next bindUseCases() will produce a fresh
            // request.
            Log.w(TAG, "provideSurface rejected (request already complete): "
                    + e.getMessage());
            surface.release();
            if (surfaceTexture != null) {
                try { surfaceTexture.release(); } catch (Throwable ignored) {}
                surfaceTexture = null;
            }
            requestSurfaceRebind();
            return;
        }

        startWatchdog();
    }

    private void ensureFrameCallbackThread() {
        if (frameCallbackThread == null || !frameCallbackThread.isAlive()) {
            frameCallbackThread = new HandlerThread("GLFrameAvailableCb");
            frameCallbackThread.start();
            frameCallbackHandler = new Handler(frameCallbackThread.getLooper());
        }
    }

    private void startWatchdog() {
        if (watchdogHandler == null) {
            ensureFrameCallbackThread();
            watchdogHandler = new Handler(frameCallbackThread.getLooper());
        }
        watchdogHandler.removeCallbacks(watchdogRunnable);
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    private void stopWatchdog() {
        if (watchdogHandler != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogHandler = null;
        }
    }

    private void shutdownFrameCallbackThread() {
        stopWatchdog();
        if (frameCallbackThread != null) {
            frameCallbackThread.quitSafely();
            frameCallbackThread = null;
            frameCallbackHandler = null;
        }
    }

    private void requestRender() {
        if (glView != null) {
            glView.requestRender();
        }
    }

    // ── FBO management ──────────────────────────────────────────────────────

    private void initFBOs(int width, int height) {
        destroyFBOs();
        int[] ids = new int[1];

        // FBO for camera passthrough
        texCamera = createFboTexture(width, height);
        GLES20.glGenFramebuffers(1, ids, 0);
        fboCamera = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboCamera);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texCamera, 0);

        // FBO for horizontal blur
        texBlurH = createFboTexture(width, height);
        GLES20.glGenFramebuffers(1, ids, 0);
        fboBlurH = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBlurH);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texBlurH, 0);

        // FBO for vertical blur
        texBlurV = createFboTexture(width, height);
        GLES20.glGenFramebuffers(1, ids, 0);
        fboBlurV = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBlurV);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texBlurV, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        fbosInitialized = true;
        Log.i(TAG, "FBOs initialized: " + width + "×" + height);
    }

    private int createFboTexture(int width, int height) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int tex = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        return tex;
    }

    private void destroyFBOs() {
        if (!fbosInitialized) return;
        int[] fbos = {fboCamera, fboBlurH, fboBlurV};
        GLES20.glDeleteFramebuffers(3, fbos, 0);
        int[] texs = {texCamera, texBlurH, texBlurV};
        GLES20.glDeleteTextures(3, texs, 0);
        fbosInitialized = false;
    }

    // ── GL helpers ──────────────────────────────────────────────────────────

    private static int createProgram(String vertexSrc, String fragmentSrc) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            Log.e(TAG, "glCreateProgram returned 0 — GL state may be lost");
            return 0;
        }
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            Log.e(TAG, "Program link failed: " + log);
            return 0;
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            Log.e(TAG, "glCreateShader returned 0 (type=" + type + ")");
            return 0;
        }
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            Log.e(TAG, "Shader compile failed (type=" + type + "): " + log);
            return 0;
        }
        return shader;
    }

    private static FloatBuffer toFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }
}
