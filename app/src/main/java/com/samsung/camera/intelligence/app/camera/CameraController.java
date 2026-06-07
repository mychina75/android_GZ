package com.samsung.camera.intelligence.app.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;

import com.samsung.camera.intelligence.app.camera.CameraGLPreview;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import android.util.Rational;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.core.Preview.SurfaceProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraController {

    public interface FrameListener {
        void onFrame(Bitmap bitmap);
    }

    public interface StatusListener {
        void onStatus(String status);
    }

    public interface CaptureListener {
        void onPhotoSaved(String path);

        void onVideoSaved(String path);

        void onCaptureError(String reason);

        /**
         * Optional: invoked after the comparison-snapshot trio (result /
         * original / reference / meta) has been written. Any path may be
         * {@code null} if that artifact was not requested or failed to save.
         */
        default void onComparisonReady(String resultPath, String origPath,
                                       String refPath, String metaPath) {}
    }

    private static final String TAG = "CameraController";
    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final SurfaceProvider surfaceProvider;
    private final CameraGLPreview glPreview;   // nullable — only set when using GL pipeline
    private final ExecutorService analysisExecutor;
    private final StatusListener statusListener;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageAnalysis imageAnalysis;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private String activeVideoPath;
    private CameraCapabilityProfile capabilityProfile;
    private String logicalMode = "Photo";
    private CameraProSettings proSettings = CameraProSettings.defaults();
    private boolean useFrontCamera = false;
    private FrameListener currentFrameListener;

    /**
     * When true, {@link #applyToneCurve} skips touching the GPU LUT — the
     * preview is being driven by an externally-supplied baked-cluster LUT
     * via {@link #applyDirectLutBitmap}.  Other Camera2 settings
     * (ISO/shutter/EV/WB/AF) still apply normally.
     */
    private boolean lutOverrideActive = false;

    /**
     * Create a CameraController using a {@link CameraGLPreview} for GPU-based
     * LUT tone grading.  The preview frames are rendered through an OpenGL
     * pipeline and the ImageAnalysis stream sees raw (un-tone-mapped) frames,
     * breaking the feedback loop.
     */
    public CameraController(Context context,
                            LifecycleOwner lifecycleOwner,
                            CameraGLPreview glPreview,
                            StatusListener statusListener) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.surfaceProvider = glPreview.getSurfaceProvider();
        this.glPreview = glPreview;
        this.statusListener = statusListener;
        this.analysisExecutor = Executors.newSingleThreadExecutor();

        // Wire the renderer's surface-rebind callback.  When the GL context
        // is recreated (memory pressure, screen off/on, certain OEM ROMs)
        // CameraX silently keeps holding the old, dead Surface and never
        // re-issues a SurfaceRequest on its own — which is the root cause
        // of the “preview frozen on last frame” bug.  Re-binding our use
        // cases forces CameraX to call the SurfaceProvider lambda again
        // with a fresh request that the renderer can wire up.
        glPreview.setSurfaceRebindListener(() -> {
            if (cameraProvider == null || currentFrameListener == null) {
                return;
            }
            // bindUseCases must run on the main thread (CameraX requirement);
            // the renderer already posts the callback to the main thread,
            // but be defensive in case that contract changes.
            ContextCompat.getMainExecutor(this.context).execute(() -> {
                try {
                    Log.w(TAG, "Renderer requested surface rebind — re-binding CameraX use cases");
                    bindUseCases(currentFrameListener);
                } catch (Throwable t) {
                    Log.e(TAG, "Surface rebind failed", t);
                }
            });
        });
    }

    public void start(@NonNull FrameListener frameListener) {
        this.currentFrameListener = frameListener;
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(context);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                bindUseCases(frameListener);
                statusListener.onStatus("Camera started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera", e);
                statusListener.onStatus("Camera start failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindUseCases(FrameListener frameListener) {
        if (cameraProvider == null) {
            return;
        }
        cameraProvider.unbindAll();

        // Pin all camera use-cases to the same aspect ratio so the captured
        // JPEG matches the live preview FOV. Without this, CameraX picks
        // 16:9 for Preview and 4:3 for ImageCapture independently — and
        // the post-capture center-crop then trims the 4:3 JPEG much more
        // than the GL preview trims its 16:9 frame, making photos look
        // zoomed-in vs. the preview.
        int targetAspect = AspectRatio.RATIO_4_3;
        ResolutionSelector resSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(new AspectRatioStrategy(
                        targetAspect, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .build();

        Preview preview = new Preview.Builder()
                .setResolutionSelector(resSelector)
                .build();
        preview.setSurfaceProvider(surfaceProvider);

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resSelector)
                .build();

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);

        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resSelector)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, image -> {
            try {
                Bitmap bitmap = YuvBitmapConverter.toBitmap(image);
                // CameraX delivers ImageAnalysis frames in sensor (landscape)
                // orientation. The GL preview rotates them for display, but
                // the analysis bitmap stays in sensor space — so model heads
                // that emit normalized coords (subject_bbox, subject_center)
                // would be in landscape coords and produce a stretched-tall
                // bbox + swapped axes when overlaid on the portrait preview.
                // Rotate to display orientation so coords align with the view.
                int rot = image.getImageInfo().getRotationDegrees();
                if (rot != 0 && bitmap != null) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(rot);
                    // Front camera preview is mirrored horizontally on the
                    // GL preview; mirror the analysis bitmap too so the
                    // bbox draws over the correct side of the user's face.
                    if (useFrontCamera) {
                        m.postScale(-1f, 1f, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
                    }
                    Bitmap rotated = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                    if (rotated != bitmap) {
                        bitmap.recycle();
                        bitmap = rotated;
                    }
                } else if (useFrontCamera && bitmap != null) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postScale(-1f, 1f, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
                    Bitmap mirrored = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                    if (mirrored != bitmap) {
                        bitmap.recycle();
                        bitmap = mirrored;
                    }
                }
                frameListener.onFrame(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "Frame pipeline error: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage(), e);
                statusListener.onStatus("Pipeline: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            } finally {
                image.close();
            }
        });

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(useFrontCamera
                        ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK)
                .build();

        // Build a UseCaseGroup with a shared ViewPort. The ViewPort makes
        // CameraX deliver the SAME effective FOV across Preview, ImageCapture
        // and ImageAnalysis — so the saved JPEG matches what the user sees.
        // Aspect ratio is taken from the GL preview view dimensions when
        // available; otherwise we fall back to portrait 3:4 (matching the
        // 4:3 sensor aspect rotated to display orientation).
        int vw = (glPreview != null) ? glPreview.getPreviewViewWidth()  : 0;
        int vh = (glPreview != null) ? glPreview.getPreviewViewHeight() : 0;
        Rational viewportRatio;
        if (vw > 0 && vh > 0) {
            viewportRatio = new Rational(vw, vh);
        } else {
            viewportRatio = new Rational(3, 4);
        }
        int rotation = (glPreview != null && glPreview.getDisplay() != null)
                ? glPreview.getDisplay().getRotation()
                : android.view.Surface.ROTATION_0;
        ViewPort viewPort = new ViewPort.Builder(viewportRatio, rotation)
                .setScaleType(ViewPort.FILL_CENTER)
                .build();
        UseCaseGroup group = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture)
                .addUseCase(imageAnalysis)
                .addUseCase(videoCapture)
                .setViewPort(viewPort)
                .build();
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, group);

        capabilityProfile = probeCapabilities();

        applyCurrentProSettings();
    }

    public void stop() {
        stopRecording();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        analysisExecutor.shutdownNow();
    }

    public void capturePhoto(@NonNull CaptureListener listener) {
        capturePhoto(listener, null, null);
    }

    public void capturePhoto(@NonNull CaptureListener listener,
                             @androidx.annotation.Nullable float[] aiCropNorm) {
        capturePhoto(listener, aiCropNorm, null);
    }

    /**
     * Phase 4 — capture with an optional AI-suggested crop. When
     * {@code aiCropNorm} is non-null and looks usable, it is applied AFTER
     * the existing viewport-aspect center-crop in {@link #applyLutToSavedPhoto},
     * so the saved JPEG matches the framing template the user just locked.
     *
     * <p>If {@code extras} is non-null, the controller will additionally:
     *   <ul>
     *     <li>copy the un-graded sensor JPEG to {@code <base>_orig.jpg}</li>
     *     <li>copy the matched reference image to {@code <base>_ref.<ext>}</li>
     *     <li>write a {@code <base>_meta.txt} dump of the supplied tone params</li>
     *     <li>publish the result, the original, and the reference into the
     *         system Gallery (Pictures/IntelligentCamera) via
     *         {@link com.samsung.camera.intelligence.app.util.MediaStoreSaver}</li>
     *     <li>invoke {@link CaptureListener#onComparisonReady} with the
     *         private-storage paths so the UI can launch a 3-up viewer.</li>
     *   </ul>
     */
    public void capturePhoto(@NonNull CaptureListener listener,
                             @androidx.annotation.Nullable float[] aiCropNorm,
                             @androidx.annotation.Nullable CaptureExtras extras) {
        if (imageCapture == null) {
            listener.onCaptureError("ImageCapture not ready");
            return;
        }

        // Snapshot the current LUT before capture (it may change during async save)
        final Bitmap lutSnapshot = (glPreview != null) ? glPreview.getCurrentLutBitmap() : null;

        // Snapshot preview viewport dimensions NOW (on UI thread) to avoid threading race
        // when applyLutToSavedPhoto runs in background thread later
        final int previewW = (glPreview != null) ? glPreview.getPreviewViewWidth() : 0;
        final int previewH = (glPreview != null) ? glPreview.getPreviewViewHeight() : 0;
        final float[] aiCropSnapshot = (aiCropNorm == null) ? null : aiCropNorm.clone();
        final CaptureExtras extrasSnapshot = extras;
        Log.w(TAG, "[DIAG] capturePhoto: snapshotted previewW=" + previewW + " previewH=" + previewH
                + " aiCrop=" + (aiCropSnapshot == null ? "none" : java.util.Arrays.toString(aiCropSnapshot))
                + " extras=" + (extrasSnapshot == null ? "none" : "yes"));

        File out = newCaptureFile("IMG_", ".jpg");
        ImageCapture.OutputFileOptions output = new ImageCapture.OutputFileOptions.Builder(out).build();

        imageCapture.takePicture(output, ContextCompat.getMainExecutor(context), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                final boolean needsPostProcess = lutSnapshot != null || aiCropSnapshot != null;
                if (needsPostProcess || extrasSnapshot != null) {
                    analysisExecutor.execute(() -> {
                        // Step 1 — snapshot the un-graded original (BEFORE LUT/crop).
                        File origFile = null;
                        if (extrasSnapshot != null && extrasSnapshot.saveOriginal) {
                            origFile = saveOriginalSibling(out);
                        }
                        // Step 2 — apply LUT + crop to the result file in place.
                        if (needsPostProcess) {
                            applyLutToSavedPhoto(out, lutSnapshot, previewW, previewH, aiCropSnapshot);
                        }
                        // Step 3 — copy reference image and write meta dump.
                        File refFile = (extrasSnapshot != null) ? saveReferenceSibling(out, extrasSnapshot) : null;
                        File metaFile = (extrasSnapshot != null) ? saveMetaSibling(out, extrasSnapshot) : null;
                        // Step 4 — publish to system Gallery.
                        if (extrasSnapshot != null) {
                            publishTrioToGallery(out, origFile, refFile);
                        }
                        listener.onPhotoSaved(out.getAbsolutePath());
                        if (extrasSnapshot != null) {
                            listener.onComparisonReady(
                                    out.getAbsolutePath(),
                                    origFile != null ? origFile.getAbsolutePath() : null,
                                    refFile  != null ? refFile.getAbsolutePath()  : null,
                                    metaFile != null ? metaFile.getAbsolutePath() : null);
                        }
                    });
                } else {
                    listener.onPhotoSaved(out.getAbsolutePath());
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                if (lutSnapshot != null) lutSnapshot.recycle();
                listener.onCaptureError(exception.getMessage());
            }
        });
    }

    // ---- Comparison-trio helpers -----------------------------------------

    private File saveOriginalSibling(File resultFile) {
        File dst = siblingFor(resultFile, "_orig.jpg");
        try (java.io.FileInputStream in = new java.io.FileInputStream(resultFile);
             java.io.FileOutputStream os = new java.io.FileOutputStream(dst);
             java.nio.channels.FileChannel src = in.getChannel();
             java.nio.channels.FileChannel dstCh = os.getChannel()) {
            dstCh.transferFrom(src, 0, src.size());
            return dst;
        } catch (Exception e) {
            Log.w(TAG, "saveOriginalSibling failed", e);
            return null;
        }
    }

    private File saveReferenceSibling(File resultFile, CaptureExtras extras) {
        java.io.InputStream in = null;
        String ext = ".jpg";
        try {
            if (extras.referenceAssetPath != null && !extras.referenceAssetPath.isEmpty()) {
                in = context.getAssets().open(extras.referenceAssetPath);
                int dot = extras.referenceAssetPath.lastIndexOf('.');
                if (dot >= 0) ext = extras.referenceAssetPath.substring(dot);
            } else if (extras.referenceUri != null) {
                in = context.getContentResolver().openInputStream(extras.referenceUri);
                String mime = context.getContentResolver().getType(extras.referenceUri);
                if (mime != null) {
                    String guess = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    if (guess != null && !guess.isEmpty()) ext = "." + guess;
                }
            } else {
                return null;
            }
            if (in == null) return null;
            File dst = siblingFor(resultFile, "_ref" + ext);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return dst;
        } catch (Exception e) {
            Log.w(TAG, "saveReferenceSibling failed", e);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) {}
        }
    }

    private File saveMetaSibling(File resultFile, CaptureExtras extras) {
        File dst = siblingFor(resultFile, "_meta.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(dst)) {
            w.write("# Intelligent Camera comparison snapshot\n");
            w.write("timestamp=" + System.currentTimeMillis() + "\n");
            w.write("result_file=" + resultFile.getName() + "\n");
            if (extras.referenceLabel != null) {
                w.write("reference_label=" + extras.referenceLabel + "\n");
            }
            if (extras.meta != null) {
                for (java.util.Map.Entry<String, Object> e : extras.meta.entrySet()) {
                    Object v = e.getValue();
                    w.write(e.getKey() + "=" + (v == null ? "" : v.toString()) + "\n");
                }
            }
            return dst;
        } catch (Exception e) {
            Log.w(TAG, "saveMetaSibling failed", e);
            return null;
        }
    }

    private void publishTrioToGallery(File resultFile,
                                      @androidx.annotation.Nullable File origFile,
                                      @androidx.annotation.Nullable File refFile) {
        String base = stripExt(resultFile.getName());
        try {
            com.samsung.camera.intelligence.app.util.MediaStoreSaver.saveImage(
                    context, resultFile, base + ".jpg", "image/jpeg");
            if (origFile != null && origFile.exists()) {
                com.samsung.camera.intelligence.app.util.MediaStoreSaver.saveImage(
                        context, origFile, base + "_orig.jpg", "image/jpeg");
            }
            if (refFile != null && refFile.exists()) {
                String mime = guessMime(refFile.getName());
                com.samsung.camera.intelligence.app.util.MediaStoreSaver.saveImage(
                        context, refFile, refFile.getName(), mime);
            }
        } catch (Exception e) {
            Log.w(TAG, "publishTrioToGallery failed", e);
        }
    }

    private static File siblingFor(File anchor, String suffix) {
        String name = stripExt(anchor.getName()) + suffix;
        return new File(anchor.getParentFile(), name);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "image/*";
    }

    /**
     * Post-process a saved JPEG by applying the GPU LUT on the CPU so that
     * the captured photo matches the tone-graded preview. Also center-crop
     * the image to match the preview viewport's aspect ratio (fillCenter/center-crop
     * mode), so the final JPEG shows the same field-of-view as the live preview.
     */
    private void applyLutToSavedPhoto(File photoFile, Bitmap lutBitmap, int previewW, int previewH) {
        applyLutToSavedPhoto(photoFile, lutBitmap, previewW, previewH, null);
    }

    private void applyLutToSavedPhoto(File photoFile, Bitmap lutBitmap, int previewW, int previewH,
                                      @androidx.annotation.Nullable float[] aiCropNorm) {
        try {
            // Preserve EXIF metadata before re-encoding
            android.media.ExifInterface exif = new android.media.ExifInterface(
                    photoFile.getAbsolutePath());
            String orientation = exif.getAttribute(android.media.ExifInterface.TAG_ORIENTATION);
            String dateTime = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME);
            String make = exif.getAttribute(android.media.ExifInterface.TAG_MAKE);
            String model = exif.getAttribute(android.media.ExifInterface.TAG_MODEL);

            // Force ARGB_8888 decode (full 8-bit per channel) to avoid the
            // RGB_565 fallback that some manufacturers use for large bitmaps,
            // which severely amplifies moire on screen content.
            android.graphics.BitmapFactory.Options decodeOpts =
                    new android.graphics.BitmapFactory.Options();
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decodeOpts.inMutable = false;
            Bitmap original = android.graphics.BitmapFactory.decodeFile(
                    photoFile.getAbsolutePath(), decodeOpts);
            if (original == null) {
                Log.w(TAG, "applyLutToSavedPhoto: failed to decode " + photoFile);
                if (lutBitmap != null) lutBitmap.recycle();
                return;
            }

            // Apply LUT for tone grading (skip when no LUT was provided)
            Bitmap graded;
            if (lutBitmap != null) {
                graded = LutToneMapper.applyLutToBitmap(original, lutBitmap);
                original.recycle();
                lutBitmap.recycle();
            } else {
                graded = original;
            }

            // CRITICAL: rotate bitmap to display orientation BEFORE center-crop.
            // CameraX saves JPEGs in sensor (landscape) orientation with EXIF rotation
            // metadata. If we crop the landscape bitmap directly, we'd crop in the
            // wrong axis. We need to physically rotate to match what user sees.
            int exifOrient = android.media.ExifInterface.ORIENTATION_NORMAL;
            try {
                exifOrient = Integer.parseInt(orientation != null ? orientation : "1");
            } catch (NumberFormatException ignored) {}
            int rotationDegrees = 0;
            switch (exifOrient) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:  rotationDegrees = 90;  break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: rotationDegrees = 180; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: rotationDegrees = 270; break;
            }
            Log.w(TAG, "[DIAG] EXIF orientation=" + exifOrient + " → rotation=" + rotationDegrees + "°");
            if (rotationDegrees != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotationDegrees);
                // CRITICAL: filter=FALSE for axis-aligned 90/180/270 rotation.
                // Bilinear filtering on pure rotations causes 2x2-neighborhood
                // sampling of every output pixel, which low-pass filters
                // high-frequency periodic content (LCD pixel grid) and turns
                // crisp moire into smeared/colored moire. Pure rotations are
                // exact pixel remaps and need no filtering.
                Bitmap rotated = Bitmap.createBitmap(graded, 0, 0,
                        graded.getWidth(), graded.getHeight(), m, false);
                if (rotated != graded) {
                    graded.recycle();
                    graded = rotated;
                }
                Log.w(TAG, "[DIAG] Rotated to display orientation: " + graded.getWidth() + "x" + graded.getHeight());
            }

            // Center-crop to match preview viewport aspect ratio (fillCenter mode).
            // Now both bitmap and preview are in display orientation, so direct comparison works.
            Log.w(TAG, "[DIAG] applyLutToSavedPhoto: previewW=" + previewW + " previewH=" + previewH
                    + " graded size=" + graded.getWidth() + "x" + graded.getHeight());
            
            if (previewW > 0 && previewH > 0) {
                float viewAspect = (float) previewW / previewH;
                float bitmapAspect = (float) graded.getWidth() / graded.getHeight();
                Log.w(TAG, "[DIAG] Aspect: viewAspect=" + viewAspect + " bitmapAspect="
                        + bitmapAspect + " diff=" + Math.abs(bitmapAspect - viewAspect));
                Bitmap cropped = graded;
                if (Math.abs(bitmapAspect - viewAspect) > 0.01f) {
                    // Aspect ratios don't match: center-crop the bitmap to fit
                    // the view's aspect ratio
                    int cropW = graded.getWidth();
                    int cropH = graded.getHeight();
                    if (bitmapAspect > viewAspect) {
                        // Bitmap is wider → crop width
                        cropW = (int) (cropH * viewAspect);
                    } else {
                        // Bitmap is taller → crop height
                        cropH = (int) (cropW / viewAspect);
                    }
                    int x = (graded.getWidth() - cropW) / 2;
                    int y = (graded.getHeight() - cropH) / 2;
                    int origW = graded.getWidth();
                    int origH = graded.getHeight();
                    cropped = Bitmap.createBitmap(graded, x, y, cropW, cropH);
                    graded.recycle();
                    Log.w(TAG, "[DIAG] JPEG center-cropped: " + origW + "x" + origH
                            + " → " + cropped.getWidth() + "x" + cropped.getHeight()
                            + " to match preview aspect " + viewAspect);
                } else {
                    Log.w(TAG, "[DIAG] No crop needed: aspect match within 0.01f");
                }
                graded = cropped;
            } else {
                Log.w(TAG, "[DIAG] WARNING: Preview dimensions not snapshotted! previewW=" + previewW + " previewH=" + previewH);
            }

            // Phase 4 — apply the AI-suggested crop AFTER the viewport center-crop.
            // The aiCropNorm coordinates are normalized to the live preview, which
            // already matches the just-cropped bitmap, so we can multiply directly.
            if (aiCropNorm != null && aiCropNorm.length >= 4
                    && aiCropNorm[2] > 0.05f && aiCropNorm[3] > 0.05f
                    && !(aiCropNorm[2] >= 0.98f && aiCropNorm[3] >= 0.98f)) {
                int gw = graded.getWidth();
                int gh = graded.getHeight();
                int cx = Math.max(0, Math.round(aiCropNorm[0] * gw));
                int cy = Math.max(0, Math.round(aiCropNorm[1] * gh));
                int cw = Math.min(gw - cx, Math.round(aiCropNorm[2] * gw));
                int ch = Math.min(gh - cy, Math.round(aiCropNorm[3] * gh));
                if (cw > 16 && ch > 16) {
                    Bitmap aiCropped = Bitmap.createBitmap(graded, cx, cy, cw, ch);
                    if (aiCropped != graded) {
                        graded.recycle();
                        graded = aiCropped;
                    }
                    Log.w(TAG, "[DIAG] AI snap-to-template crop: " + gw + "x" + gh
                            + " → " + cw + "x" + ch + " at (" + cx + "," + cy + ")");
                } else {
                    Log.w(TAG, "[DIAG] AI crop too small after rounding, skipped");
                }
            }

            // JPEG quality 100 to minimize re-encoding loss. The DCT in JPEG
            // is particularly poor at compressing periodic high-frequency
            // content (screen moire), so any quality drop visibly amplifies
            // pre-existing moire patterns. We've already lost some quality
            // from the original CameraX encode → decode round-trip; we should
            // not lose more on the re-encode.
            java.io.FileOutputStream fos = new java.io.FileOutputStream(photoFile);
            try {
                graded.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            } finally {
                fos.close();
            }
            graded.recycle();

            // Restore key EXIF attributes. IMPORTANT: orientation is set to NORMAL (1)
            // because we already physically rotated the bitmap to display orientation.
            // Keeping the original EXIF rotation would cause double-rotation in viewers.
            android.media.ExifInterface exifOut = new android.media.ExifInterface(
                    photoFile.getAbsolutePath());
            exifOut.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,
                    String.valueOf(android.media.ExifInterface.ORIENTATION_NORMAL));
            if (dateTime != null) exifOut.setAttribute(
                    android.media.ExifInterface.TAG_DATETIME, dateTime);
            if (make != null) exifOut.setAttribute(
                    android.media.ExifInterface.TAG_MAKE, make);
            if (model != null) exifOut.setAttribute(
                    android.media.ExifInterface.TAG_MODEL, model);
            exifOut.saveAttributes();

            Log.i(TAG, "LUT applied to captured photo: " + photoFile.getName());
        } catch (Exception e) {
            Log.w(TAG, "applyLutToSavedPhoto failed", e);
            lutBitmap.recycle();
        }
    }

    public void startRecording(boolean withAudio, @NonNull CaptureListener listener) {
        if (videoCapture == null) {
            listener.onCaptureError("VideoCapture not ready");
            return;
        }
        if (activeRecording != null) {
            listener.onCaptureError("Recording already in progress");
            return;
        }

        File out = newCaptureFile("VID_", ".mp4");
        activeVideoPath = out.getAbsolutePath();
        FileOutputOptions options = new FileOutputOptions.Builder(out).build();

        PendingRecording pending = videoCapture.getOutput().prepareRecording(context, options);
        if (withAudio) {
            pending = pending.withAudioEnabled();
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(context), event -> {
            if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                if (finalize.hasError()) {
                    listener.onCaptureError("Video finalize error: " + finalize.getError());
                } else {
                    listener.onVideoSaved(activeVideoPath);
                }
                activeRecording = null;
                activeVideoPath = null;
            }
        });

        statusListener.onStatus("Recording started");
    }

    public void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
            statusListener.onStatus("Recording stopped");
        }
    }

    public boolean isRecording() {
        return activeRecording != null;
    }

    public void setLogicalMode(String modeName) {
        this.logicalMode = modeName == null ? "Photo" : modeName;
        // Note: Camera2 settings are NOT applied here to avoid redundant
        // rapid setCaptureRequestOptions calls during mode switch.
        // The caller must explicitly call applyDirectProSettings() afterwards.
        statusListener.onStatus("Mode -> " + this.logicalMode);
    }

    public String getLogicalMode() {
        return logicalMode;
    }

    public boolean isFrontCamera() {
        return useFrontCamera;
    }

    public void switchCamera() {
        useFrontCamera = !useFrontCamera;
        if (cameraProvider != null && currentFrameListener != null) {
            bindUseCases(currentFrameListener);
            statusListener.onStatus(useFrontCamera ? "Switched to front camera" : "Switched to rear camera");
        }
    }

    public void setZoom(float zoomRatio) {
        if (camera == null) {
            return;
        }
        float target = Math.max(1.0f, zoomRatio);
        camera.getCameraControl().setZoomRatio(target);
    }

    /** Phase 6 — current zoom ratio, or 1.0 when no camera bound. */
    public float getCurrentZoom() {
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) {
            return 1.0f;
        }
        return camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
    }

    /** Phase 6 — max supported zoom ratio, or 1.0 when no camera bound. */
    public float getMaxZoom() {
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) {
            return 1.0f;
        }
        return camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
    }

    public void setFlashMode(String mode) {
        if (imageCapture == null) {
            return;
        }
        String m = mode == null ? "off" : mode.toLowerCase();
        if ("on".equals(m)) {
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_ON);
        } else if ("auto".equals(m)) {
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_AUTO);
        } else {
            imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
        }
        statusListener.onStatus("Flash -> " + m);
    }

    public void applyIso(int iso) {
        proSettings = new CameraProSettings(
                iso,
                proSettings.shutterNs,
                proSettings.ev,
                proSettings.whiteBalanceMode,
                proSettings.whiteBalanceKelvin,
                proSettings.focusMode,
                proSettings.focusDistance,
                proSettings.meteringMode
        );
        applyCurrentProSettings();
    }

    public void applyShutter(String shutter) {
        Long shutterNs = CameraProSettings.parseShutterToNs(shutter);
        proSettings = new CameraProSettings(
                proSettings.iso,
                shutterNs,
                proSettings.ev,
                proSettings.whiteBalanceMode,
                proSettings.whiteBalanceKelvin,
                proSettings.focusMode,
                proSettings.focusDistance,
                proSettings.meteringMode
        );
        applyCurrentProSettings();
    }

    public void applyEv(float ev) {
        proSettings = new CameraProSettings(
                proSettings.iso,
                proSettings.shutterNs,
                ev,
                proSettings.whiteBalanceMode,
                proSettings.whiteBalanceKelvin,
                proSettings.focusMode,
                proSettings.focusDistance,
                proSettings.meteringMode
        );
        applyCurrentProSettings();
    }

    public void applyWhiteBalance(String mode, Integer kelvin) {
        proSettings = new CameraProSettings(
                proSettings.iso,
                proSettings.shutterNs,
                proSettings.ev,
                mode,
                kelvin,
                proSettings.focusMode,
                proSettings.focusDistance,
                proSettings.meteringMode
        );
        applyCurrentProSettings();
    }

    public void applyFocus(String mode, Float distance) {
        proSettings = new CameraProSettings(
                proSettings.iso,
                proSettings.shutterNs,
                proSettings.ev,
                proSettings.whiteBalanceMode,
                proSettings.whiteBalanceKelvin,
                mode,
                distance,
                proSettings.meteringMode
        );
        applyCurrentProSettings();
    }

    public void applyMetering(String mode) {
        proSettings = new CameraProSettings(
                proSettings.iso,
                proSettings.shutterNs,
                proSettings.ev,
                proSettings.whiteBalanceMode,
                proSettings.whiteBalanceKelvin,
                proSettings.focusMode,
                proSettings.focusDistance,
                mode
        );
        applyCurrentProSettings();
    }

    public void applyDirectProSettings(CameraProSettings settings) {
        this.proSettings = settings;
        applyCurrentProSettings();
    }

    public CameraProSettings getCurrentProSettings() {
        return proSettings;
    }

    private void applyCurrentProSettings() {
        if (camera == null) {
            return;
        }
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
            boolean proMode = isProLogicalMode();
            CameraProSettings safe = sanitizeProSettings(proSettings);

            Log.w(TAG, "[DIAG] applyProSettings: logicalMode=" + logicalMode
                    + " proMode=" + proMode + " iso=" + safe.iso
                    + " shutterNs=" + safe.shutterNs + " ev=" + safe.ev
                    + " wb=" + safe.whiteBalanceMode + " focus=" + safe.focusMode);

            if (proMode && safe.iso != null && safe.shutterNs != null && canUseManualExposure()) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, safe.iso);
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, safe.shutterNs);
                Log.w(TAG, "[DIAG] Manual exposure SET: ISO=" + safe.iso + " shutter=" + safe.shutterNs);
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                if (proMode) {
                    Log.w(TAG, "[DIAG] Manual exposure SKIPPED: iso=" + safe.iso
                            + " shutterNs=" + safe.shutterNs + " canManual=" + canUseManualExposure());
                }
            }

            if (proMode) {
            if (safe.whiteBalanceKelvin != null && safe.whiteBalanceKelvin >= 2300
                    && safe.whiteBalanceKelvin <= 10000) {
                // Manual WB via color correction gains derived from kelvin
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_OFF);
                RggbChannelVector gains = kelvinToGains(safe.whiteBalanceKelvin);
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS, gains);
                // Identity color transform — let gains alone shift the WB
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    new ColorSpaceTransform(new int[]{
                        1,1, 0,1, 0,1,
                        0,1, 1,1, 0,1,
                        0,1, 0,1, 1,1}));
                Log.w(TAG, "[DIAG] WB kelvin=" + safe.whiteBalanceKelvin
                        + " gains R=" + gains.getRed()
                        + " G=" + gains.getGreenEven()
                        + " B=" + gains.getBlue());
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,
                    mapAwbMode(safe.whiteBalanceMode));
                // Clear any manual-WB color-correction state left over from a
                // prior Kelvin frame; otherwise a stale TRANSFORM_MATRIX + gains
                // fight the active AWB and produce a blue cast (e.g. in Mimic).
                builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_FAST);
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                mapAfMode(safe.focusMode));
            } else {
            // Leaving Pro should always restore camera automation.
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO);
            // Drop any manual color-correction transform/gains so AWB is not
            // overridden by stale Pro-mode WB state (root cause of blue cast).
            builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_FAST);
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            // Apply tone curve if supported and tone params present
            applyToneCurve(builder, proMode, safe);

            Camera2CameraControl.from(camera.getCameraControl())
                    .setCaptureRequestOptions(builder.build());

            applyEvCompensation(proMode ? safe.ev : 0f);

            statusListener.onStatus(
                    "Pro applied: ISO=" + safe.iso
                        + " shutterNs=" + safe.shutterNs
                        + " EV=" + safe.ev
                            + " mode=" + logicalMode
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply pro settings", e);
            statusListener.onStatus("Apply pro failed: " + e.getMessage());
        }
    }

    private boolean isProLogicalMode() {
        return "pro".equalsIgnoreCase(logicalMode)
                || "pro_video".equalsIgnoreCase(logicalMode)
                || "mimic".equalsIgnoreCase(logicalMode);
    }

    private void applyEvCompensation(Float ev) {
        if (camera == null || ev == null) {
            return;
        }
        try {
                Camera2CameraInfo info = Camera2CameraInfo.from(camera.getCameraInfo());
                Range<Integer> range = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                Rational step = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (range == null || step == null || step.floatValue() == 0f) {
                return;
            }
            int index = Math.round(ev / step.floatValue());
            index = Math.max(range.getLower(), Math.min(range.getUpper(), index));
            camera.getCameraControl().setExposureCompensationIndex(index);
        } catch (Exception e) {
            Log.w(TAG, "EV compensation not supported", e);
        }
    }

    /**
     * Apply tone adjustments via the GPU LUT pipeline instead of Camera2
     * TONEMAP_MODE_CONTRAST_CURVE.
     *
     * <p>The Camera2 repeating request always uses TONEMAP_MODE_HIGH_QUALITY
     * (the ISP's default tone mapping).  This means the ImageAnalysis stream
     * receives unmodified frames, completely breaking the feedback loop that
     * caused progressive brightening.
     *
     * <p>Tone adjustments are applied ONLY to the display via a 3D LUT
     * rendered in the fragment shader of CameraGLPreview.
     */
    private void applyToneCurve(CaptureRequestOptions.Builder builder,
                                boolean proMode, CameraProSettings settings) {
        // Always keep Camera2 in HIGH_QUALITY mode —
        // never touch TONEMAP_MODE_CONTRAST_CURVE on the repeating request.
        if (capabilityProfile != null && capabilityProfile.tonemapCurveSupported) {
            builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
        }

        // Route tone params to the GPU LUT pipeline.
        // Skip when an external LUT override is active (Mimic mode with
        // cluster-based baked LUT) — that path manages the LUT independently.
        if (lutOverrideActive) {
            Log.i(TAG, "applyToneCurve: skipped LUT update (override active)");
            return;
        }
        if (glPreview != null && proMode && settings.hasToneParams()) {
            float contrast   = settings.contrast   != null ? settings.contrast   : 0f;
            float highlights  = settings.highlights  != null ? settings.highlights  : 0f;
            float shadows     = settings.shadows     != null ? settings.shadows     : 0f;
            float saturation  = settings.saturation  != null ? settings.saturation  : 0f;
            float highlightWarmth = settings.highlightWarmth != null ? settings.highlightWarmth : 0f;
            float shadowTint     = settings.shadowTint      != null ? settings.shadowTint      : 0f;

            float[] tone = LutToneMapper.dampenExtremeToneSpread(contrast, highlights, shadows);
            contrast = tone[0];
            highlights = tone[1];
            shadows = tone[2];

            glPreview.updateLutFromToneParams(contrast, highlights, shadows, saturation,
                    highlightWarmth, shadowTint);

            Log.i(TAG, "GPU LUT applied: C=" + contrast
                    + " H=" + highlights + " Sh=" + shadows
                    + " Sat=" + saturation
                    + " Warmth=" + highlightWarmth + " Tint=" + shadowTint);
        } else if (glPreview != null) {
            // No tone adjustment or not in pro mode → clear LUT
            glPreview.clearLut();
        }
    }

    /** Get the GL preview reference (for external LUT updates). */
    public CameraGLPreview getGlPreview() {
        return glPreview;
    }

    /**
     * Push an externally-prepared LUT bitmap (e.g. composed from a baked
     * cluster LUT + per-photo tone overlay) directly to the GPU preview.
     * While the override is active, {@link #applyToneCurve} will not
     * overwrite the LUT — but other Camera2 settings (ISO/shutter/EV/WB/AF)
     * continue to apply.
     *
     * <p>The bitmap MUST be a fresh copy (or one whose ownership the caller
     * is willing to transfer) — the GL renderer recycles uploaded LUTs.
     */
    public void applyDirectLutBitmap(android.graphics.Bitmap lutBitmap) {
        if (glPreview == null || lutBitmap == null) return;
        lutOverrideActive = true;
        glPreview.updateLut(lutBitmap);
        Log.i(TAG, "applyDirectLutBitmap: LUT override active");
    }

    /**
     * Clear the external LUT override, allowing {@link #applyToneCurve} to
     * resume managing the GPU LUT.  Call this when leaving Mimic mode or
     * switching to a master photo without cluster_id.
     */
    public void clearLutOverride() {
        if (!lutOverrideActive) return;
        lutOverrideActive = false;
        if (glPreview != null) glPreview.clearLut();
        Log.i(TAG, "clearLutOverride: LUT override cleared");
    }

    public boolean isLutOverrideActive() {
        return lutOverrideActive;
    }

    private static int mapAwbMode(String mode) {
        if (mode == null) {
            return CaptureRequest.CONTROL_AWB_MODE_AUTO;
        }
        switch (mode.toLowerCase()) {
            case "daylight":
                return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
            case "cloudy":
            case "shade":
                return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            case "tungsten":
                return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
            case "fluorescent":
                return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
            default:
                return CaptureRequest.CONTROL_AWB_MODE_AUTO;
        }
    }

    /**
     * Approximate RGGB channel gains from a color temperature in Kelvin.
     * Uses Tanner Helland's algorithm to convert CCT → sRGB multipliers,
     * then normalises so green = 1.0 and returns an RGGB vector.
     */
    private static RggbChannelVector kelvinToGains(int kelvin) {
        double temp = kelvin / 100.0;
        double red, green, blue;

        if (temp <= 66) {
            red = 255;
            green = 99.4708025861 * Math.log(temp) - 161.1195681661;
            blue = temp <= 19 ? 0
                    : 138.5177312231 * Math.log(temp - 10) - 305.0447927307;
        } else {
            red = 329.698727446 * Math.pow(temp - 60, -0.1332047592);
            green = 288.1221695283 * Math.pow(temp - 60, -0.0755148492);
            blue = 255;
        }

        red   = Math.max(1, Math.min(255, red))   / 255.0;
        green = Math.max(1, Math.min(255, green)) / 255.0;
        blue  = Math.max(1, Math.min(255, blue))  / 255.0;

        // Normalise so green channel = 1.0
        float rGain = (float) (green / red);
        float bGain = (float) (green / blue);
        // Clamp to reasonable range
        rGain = Math.max(0.5f, Math.min(4.0f, rGain));
        bGain = Math.max(0.5f, Math.min(4.0f, bGain));
        return new RggbChannelVector(rGain, 1.0f, 1.0f, bGain);
    }

    private static int mapAfMode(String mode) {
        if (mode == null) {
            return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        }
        switch (mode.toLowerCase()) {
            case "manual":
                return CaptureRequest.CONTROL_AF_MODE_OFF;
            case "center":
                return CaptureRequest.CONTROL_AF_MODE_AUTO;
            default:
                return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        }
    }

    private CameraCapabilityProfile probeCapabilities() {
        try {
            Camera2CameraInfo info = Camera2CameraInfo.from(camera.getCameraInfo());
            Range<Integer> isoRange = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> exposureRange = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> evRange = info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            int[] aeModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            boolean manualExposure = false;
            if (aeModes != null) {
                for (int mode : aeModes) {
                    if (mode == CaptureRequest.CONTROL_AE_MODE_OFF) {
                        manualExposure = true;
                        break;
                    }
                }
            }

            // Probe tonemap curve support
            boolean tonemapSupported = false;
            int tonemapMaxPoints = 0;
            try {
                int[] tonemapModes = info.getCameraCharacteristic(
                        CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
                if (tonemapModes != null) {
                    for (int m : tonemapModes) {
                        if (m == CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE) {
                            tonemapSupported = true;
                            break;
                        }
                    }
                }
                Integer maxPts = info.getCameraCharacteristic(
                        CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
                if (maxPts != null) {
                    tonemapMaxPoints = maxPts;
                }
                Log.i(TAG, "Tonemap probe: supported=" + tonemapSupported
                        + " maxPoints=" + tonemapMaxPoints);
            } catch (Exception e) {
                Log.w(TAG, "Tonemap probe failed", e);
            }

            return new CameraCapabilityProfile(isoRange, exposureRange, evRange, manualExposure,
                    tonemapSupported, tonemapMaxPoints);
        } catch (Exception e) {
            Log.w(TAG, "Capability probe failed", e);
            return CameraCapabilityProfile.unknown();
        }
    }

    private boolean canUseManualExposure() {
        return capabilityProfile == null || capabilityProfile.manualExposureSupported;
    }

    private CameraProSettings sanitizeProSettings(CameraProSettings in) {
        if (in == null) {
            return CameraProSettings.defaults();
        }
        Integer iso = in.iso;
        Long shutterNs = in.shutterNs;
        Float ev = in.ev;

        if (capabilityProfile != null && capabilityProfile.isoRange != null && iso != null) {
            iso = clampInt(iso, capabilityProfile.isoRange);
        }
        if (capabilityProfile != null && capabilityProfile.exposureRange != null && shutterNs != null) {
            shutterNs = clampLong(shutterNs, capabilityProfile.exposureRange);
        }
        if (ev != null) {
            ev = Math.max(-3.0f, Math.min(3.0f, ev));
        }

        return new CameraProSettings(
                iso,
                shutterNs,
                ev,
                in.whiteBalanceMode,
                in.whiteBalanceKelvin,
                in.focusMode,
                in.focusDistance,
                in.meteringMode,
                clampTone(in.contrast),
                clampTone(in.highlights),
                clampTone(in.shadows),
                clampTone(in.saturation),
                clampTone(in.highlightWarmth),
                clampTone(in.shadowTint)
        );
    }

    private static Float clampTone(Float val) {
        if (val == null) return null;
        return Math.max(-100f, Math.min(100f, val));
    }

    private static int clampInt(int value, Range<Integer> range) {
        return Math.max(range.getLower(), Math.min(range.getUpper(), value));
    }

    private static long clampLong(long value, Range<Long> range) {
        return Math.max(range.getLower(), Math.min(range.getUpper(), value));
    }

    public CameraCapabilityProfile getCapabilityProfile() {
        return capabilityProfile;
    }

    public static class CameraCapabilityProfile {
        public final Range<Integer> isoRange;
        public final Range<Long> exposureRange;
        public final Range<Integer> evRange;
        public final boolean manualExposureSupported;
        public final boolean tonemapCurveSupported;
        public final int tonemapMaxCurvePoints;

        public CameraCapabilityProfile(Range<Integer> isoRange,
                                       Range<Long> exposureRange,
                                       Range<Integer> evRange,
                                       boolean manualExposureSupported,
                                       boolean tonemapCurveSupported,
                                       int tonemapMaxCurvePoints) {
            this.isoRange = isoRange;
            this.exposureRange = exposureRange;
            this.evRange = evRange;
            this.manualExposureSupported = manualExposureSupported;
            this.tonemapCurveSupported = tonemapCurveSupported;
            this.tonemapMaxCurvePoints = tonemapMaxCurvePoints;
        }

        public static CameraCapabilityProfile unknown() {
            return new CameraCapabilityProfile(null, null, null, true, false, 0);
        }
    }

    /**
     * Helper to get the preview viewport aspect ratio for JPEG center-crop.
     */
    public float getPreviewAspectRatio() {
        if (glPreview == null) return 0f;
        int w = glPreview.getPreviewViewWidth();
        int h = glPreview.getPreviewViewHeight();
        if (w <= 0 || h <= 0) return 0f;
        return (float) w / h;
    }

    private File newCaptureFile(String prefix, String ext) {
        File dir = new File(context.getExternalFilesDir(null), "captures");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create captures dir");
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = prefix + ts;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            name += "_" + System.currentTimeMillis() % 1000;
        }
        return new File(dir, name + ext);
    }
}
