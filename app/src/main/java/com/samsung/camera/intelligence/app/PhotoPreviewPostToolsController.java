package com.samsung.camera.intelligence.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.ExifInterface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.samsung.camera.intelligence.CameraIntelligenceManager;
import com.samsung.camera.intelligence.app.ui.PhotoDefectOverlayView;
import com.samsung.camera.intelligence.app.ui.RecommendationAdapter;
import com.samsung.camera.intelligence.models.ToolRecommendation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class PhotoPreviewPostToolsController {

    public interface CaptureInfoProvider {
        String buildCaptureInfoText();
    }

    private final AppCompatActivity activity;
    private final CameraIntelligenceManager intelligenceManager;
    private final View photoPreviewContainer;
    private final ImageView photoPreviewImage;
    private final PhotoDefectOverlayView photoDefectOverlayView;
    private final TextView photoDefectSummaryText;
    private final RecyclerView photoPostToolsList;
    private final Consumer<String> statusSink;
    private final Runnable onPreviewClosed;
    private final CaptureInfoProvider captureInfoProvider;
    private final RecommendationAdapter photoPostRecommendationAdapter = new RecommendationAdapter();

    // Pinch-zoom & pan state
    private final Matrix imageMatrix = new Matrix();
    private final Matrix baseMatrix = new Matrix();
    private float currentScale = 1f;
    private final PointF lastTouch = new PointF();
    private boolean isPanning = false;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private Runnable pendingMatrixSwitch;

    public PhotoPreviewPostToolsController(
            AppCompatActivity activity,
            CameraIntelligenceManager intelligenceManager,
            View photoPreviewContainer,
            ImageView photoPreviewImage,
            PhotoDefectOverlayView photoDefectOverlayView,
            TextView photoDefectSummaryText,
            RecyclerView photoPostToolsList,
            Consumer<String> statusSink,
                Runnable onPreviewClosed,
                CaptureInfoProvider captureInfoProvider
    ) {
        this.activity = activity;
        this.intelligenceManager = intelligenceManager;
        this.photoPreviewContainer = photoPreviewContainer;
        this.photoPreviewImage = photoPreviewImage;
        this.photoDefectOverlayView = photoDefectOverlayView;
        this.photoDefectSummaryText = photoDefectSummaryText;
        this.photoPostToolsList = photoPostToolsList;
        this.statusSink = statusSink;
        this.onPreviewClosed = onPreviewClosed;
        this.captureInfoProvider = captureInfoProvider;

        if (this.photoPostToolsList != null) {
            this.photoPostToolsList.setLayoutManager(new LinearLayoutManager(activity));
            this.photoPostToolsList.setAdapter(photoPostRecommendationAdapter);
        }
        setupZoomPanTouch();
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupZoomPanTouch() {
        scaleDetector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float newScale = currentScale * factor;
                newScale = Math.max(1f, Math.min(5f, newScale));
                factor = newScale / currentScale;
                imageMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                currentScale = newScale;
                photoPreviewImage.setImageMatrix(imageMatrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetImageTransform();
                return true;
            }
        });

        photoPreviewImage.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouch.set(event.getX(), event.getY());
                    isPanning = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isPanning && event.getPointerCount() == 1 && currentScale > 1f) {
                        float dx = event.getX() - lastTouch.x;
                        float dy = event.getY() - lastTouch.y;
                        imageMatrix.postTranslate(dx, dy);
                        photoPreviewImage.setImageMatrix(imageMatrix);
                        lastTouch.set(event.getX(), event.getY());
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    isPanning = false;
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() <= 2) {
                        int idx = event.getActionIndex() == 0 ? 1 : 0;
                        lastTouch.set(event.getX(idx), event.getY(idx));
                        isPanning = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isPanning = false;
                    break;
            }
            return true;
        });
    }

    private void resetImageTransform() {
        imageMatrix.set(baseMatrix);
        currentScale = 1f;
        photoPreviewImage.setImageMatrix(imageMatrix);
    }

    private void applyBasePhotoMatrix(Bitmap photo) {
        if (photo == null) {
            return;
        }
        int viewWidth = photoPreviewImage.getWidth();
        int viewHeight = photoPreviewImage.getHeight();
        int bitmapWidth = photo.getWidth();
        int bitmapHeight = photo.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return;
        }

        float scale = Math.max(
                (float) viewWidth / (float) bitmapWidth,
                (float) viewHeight / (float) bitmapHeight);
        float dx = (viewWidth - bitmapWidth * scale) * 0.5f;
        float dy = (viewHeight - bitmapHeight * scale) * 0.5f;

        baseMatrix.reset();
        baseMatrix.postScale(scale, scale);
        baseMatrix.postTranslate(dx, dy);

        imageMatrix.set(baseMatrix);
        currentScale = 1f;
        photoPreviewImage.setScaleType(ImageView.ScaleType.MATRIX);
        photoPreviewImage.setImageMatrix(imageMatrix);
    }

    public void bindCloseButton(Button closePreviewButton) {
        closePreviewButton.setOnClickListener(v -> clearPreview());
    }

    public boolean isPreviewVisible() {
        return photoPreviewContainer != null && photoPreviewContainer.getVisibility() == View.VISIBLE;
    }

    public void clearPreview() {
        // Cancel any pending post() from showPhotoPreview to prevent it from
        // modifying the ImageView after the container is hidden.
        if (pendingMatrixSwitch != null) {
            photoPreviewImage.removeCallbacks(pendingMatrixSwitch);
            pendingMatrixSwitch = null;
        }
        photoPreviewImage.setScaleType(ImageView.ScaleType.MATRIX);
        photoPreviewImage.setImageBitmap(null);
        resetImageTransform();
        photoPreviewContainer.setVisibility(View.GONE);
        photoDefectOverlayView.clearRegions();
        photoDefectSummaryText.setText(R.string.photo_defect_summary_idle);
        photoPostRecommendationAdapter.submit(new ArrayList<>());
        photoPostRecommendationAdapter.setHighlightedToolName(null);
        onPreviewClosed.run();
    }

    public void showPhotoPreview(String photoPath) {
        statusSink.accept("Photo saved: " + photoPath);
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoPath, opts);
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sampleSize = 1;
            while (maxDim / sampleSize > 1920) {
                sampleSize *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap photo = BitmapFactory.decodeFile(photoPath, opts);
            if (photo != null) {
                int rotation = getExifRotation(photoPath);
                if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    // filter=false: axis-aligned 90/180/270 rotation needs no
                    // bilinear sampling; using filter=true smears periodic
                    // high-frequency content (LCD moire) due to 2x2 sampling.
                    Bitmap rotated = Bitmap.createBitmap(photo, 0, 0,
                            photo.getWidth(), photo.getHeight(), matrix, false);
                    photo.recycle();
                    photo = rotated;
                }
                final Bitmap previewPhoto = photo;
                photoPreviewImage.setImageBitmap(previewPhoto);
                photoPreviewContainer.setVisibility(View.VISIBLE);

                // After layout, compute the same center-crop style baseline the
                // live GL preview uses, then layer pinch-zoom / pan on top.
                if (pendingMatrixSwitch != null) {
                    photoPreviewImage.removeCallbacks(pendingMatrixSwitch);
                }
                pendingMatrixSwitch = () -> {
                    if (!isPreviewVisible()) return;
                    if (photoPreviewImage.getWidth() <= 0 || photoPreviewImage.getHeight() <= 0) {
                        photoPreviewImage.post(pendingMatrixSwitch);
                        return;
                    }
                    applyBasePhotoMatrix(previewPhoto);
                    pendingMatrixSwitch = null;
                };
                photoPreviewImage.post(pendingMatrixSwitch);
                photoDefectOverlayView.setImageInfo(photo.getWidth(), photo.getHeight());
                photoDefectOverlayView.clearRegions();
                if (captureInfoProvider != null) {
                    String info = captureInfoProvider.buildCaptureInfoText();
                    if (info != null && !info.trim().isEmpty()) {
                        photoDefectSummaryText.setText(info);
                    } else {
                        photoDefectSummaryText.setText(R.string.photo_defect_summary_idle);
                    }
                } else {
                    photoDefectSummaryText.setText(R.string.photo_defect_summary_idle);
                }
                photoPostRecommendationAdapter.submit(new ArrayList<>());
                photoPostRecommendationAdapter.setHighlightedToolName(null);
            }
        } catch (Exception e) {
            statusSink.accept("Preview failed: " + e.getMessage());
        }
    }

    public void analyzePhotoForPostProcessing(String photoPath) {
        new Thread(() -> {
            try {
                Bitmap photo = BitmapFactory.decodeFile(photoPath);
                if (photo == null) {
                    return;
                }
                int rotation = getExifRotation(photoPath);
                if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    Bitmap rotated = Bitmap.createBitmap(photo, 0, 0,
                            photo.getWidth(), photo.getHeight(), matrix, true);
                    photo.recycle();
                    photo = rotated;
                }
                CameraIntelligenceManager.FrameResult result = intelligenceManager.processFrame(photo);
                if (result == null || result.toolResult == null) {
                    photo.recycle();
                    return;
                }
                List<ToolRecommendation> postTools = result.toolResult.getPostProcessingTools();
                final String finalDefectSummary;
                if (captureInfoProvider != null) {
                    String info = captureInfoProvider.buildCaptureInfoText();
                    finalDefectSummary = (info == null || info.trim().isEmpty())
                            ? activity.getString(R.string.photo_defect_summary_idle)
                            : info;
                } else {
                    finalDefectSummary = activity.getString(R.string.photo_defect_summary_idle);
                }
                photo.recycle();
                activity.runOnUiThread(() -> {
                    photoDefectOverlayView.clearRegions();
                    photoDefectSummaryText.setText(finalDefectSummary);
                    photoPostRecommendationAdapter.submit(new ArrayList<>());
                    photoPostRecommendationAdapter.setHighlightedToolName(null);
                });
            } catch (Exception ignore) {
            }
        }, "photo-analysis").start();
    }

    public void highlightPostTool(String prettyLabel) {
        if (prettyLabel == null || photoPostToolsList == null) {
            return;
        }
        String toolName = toolNameFromPrettyPostLabel(prettyLabel);
        if (toolName == null) {
            return;
        }
        photoPostRecommendationAdapter.setHighlightedToolName(toolName);
        int position = photoPostRecommendationAdapter.findPositionForTool(toolName);
        if (position >= 0) {
            photoPostToolsList.smoothScrollToPosition(position);
        }
    }

    private int getExifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private List<PhotoDefectOverlayView.Region> extractDefectRegions(List<ToolRecommendation> postTools) {
        List<PhotoDefectOverlayView.Region> regions = new ArrayList<>();
        if (postTools == null) {
            return regions;
        }
        for (ToolRecommendation tool : postTools) {
            if (tool == null || tool.getParameters() == null) {
                continue;
            }
            Object targetAreas = tool.getParameters().get("target_areas");
            if (!(targetAreas instanceof List)) {
                continue;
            }
            List<?> areaList = (List<?>) targetAreas;
            for (Object area : areaList) {
                if (!(area instanceof Map)) {
                    continue;
                }
                Map<?, ?> areaMap = (Map<?, ?>) area;
                float x = parseFloat(areaMap.get("x"), 0f);
                float y = parseFloat(areaMap.get("y"), 0f);
                float w = parseFloat(areaMap.get("w"), 0f);
                float h = parseFloat(areaMap.get("h"), 0f);
                float confidence = parseFloat(areaMap.get("confidence"), 0.5f);
                regions.add(new PhotoDefectOverlayView.Region(
                        x, y, w, h, confidence, prettyPostToolName(tool.getToolName())
                ));
            }
        }
        return regions;
    }

    private String buildDefectSummary(List<PhotoDefectOverlayView.Region> regions) {
        if (regions == null || regions.isEmpty()) {
            return activity.getString(R.string.photo_defect_summary_none);
        }
        Set<String> labels = new LinkedHashSet<>();
        for (PhotoDefectOverlayView.Region region : regions) {
            labels.add(region.label);
        }
        return activity.getString(
                R.string.photo_defect_summary_format,
                regions.size(),
                android.text.TextUtils.join(", ", labels)
        );
    }

    private String toolNameFromPrettyPostLabel(String prettyLabel) {
        switch (prettyLabel) {
            case "Shadow cleanup":
                return "PhotoEditor_RemoveShadow";
            case "Reflection cleanup":
                return "PhotoEditor_removeReflection";
            case "Background people cleanup":
                return "PhotoEditor_removeBackgroundPeople";
            case "Flare cleanup":
                return "PhotoEditor_RemoveFlare";
            case "AI straighten":
                return "PhotoEditor_GenAIAutoTilt";
            case "AI expand":
                return "PhotoEditor_GenAIExpand";
            case "Smart crop (AI)":
                return "PhotoEditor_SmartCrop";
            case "Recompose":
                return "PhotoEditor_Recompose";
            case "Enhance composition":
                return "PhotoEditor_CompositionEnhancer";
            case "Auto enhance":
                return "Gallery_AutoFit";
            case "Auto straighten":
                return "Gallery_AutoTilt";
            case "Crop":
                return "Gallery_Crop";
            case "Object remover":
                return "Gallery_ObjectRemover";
            default:
                return null;
        }
    }

    private String prettyPostToolName(String toolName) {
        if (toolName == null) {
            return "Edit";
        }
        switch (toolName) {
            case "PhotoEditor_RemoveShadow":
                return "Shadow cleanup";
            case "PhotoEditor_removeReflection":
                return "Reflection cleanup";
            case "PhotoEditor_removeBackgroundPeople":
                return "Background people cleanup";
            case "PhotoEditor_RemoveFlare":
                return "Flare cleanup";
            case "PhotoEditor_GenAIAutoTilt":
                return "AI straighten";
            case "PhotoEditor_GenAIExpand":
                return "AI expand";
            case "PhotoEditor_SmartCrop":
                return "Smart crop (AI)";
            case "PhotoEditor_Recompose":
                return "Recompose";
            case "PhotoEditor_CompositionEnhancer":
                return "Enhance composition";
            case "Gallery_AutoFit":
                return "Auto enhance";
            case "Gallery_AutoTilt":
                return "Auto straighten";
            case "Gallery_Crop":
                return "Crop";
            case "Gallery_ObjectRemover":
                return "Object remover";
            default:
                return titleCase(toolName.replace("PhotoEditor_", "").replace("Gallery_", ""));
        }
    }

    private float parseFloat(Object value, float fallback) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        try {
            return value == null ? fallback : Float.parseFloat(String.valueOf(value));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private String titleCase(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Unknown";
        }
        String normalized = raw.replace('_', ' ').trim();
        String[] parts = normalized.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.US));
            }
        }
        return out.toString();
    }
}
