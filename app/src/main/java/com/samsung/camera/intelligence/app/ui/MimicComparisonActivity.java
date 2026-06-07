package com.samsung.camera.intelligence.app.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.samsung.camera.intelligence.app.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Three-up A/B/Reference viewer for the comparison-snapshot triplet
 * produced by Mimic mode capture. Pinch-zoom and pan are mirrored across
 * all three panes so the user can inspect the same region of every image
 * at once.
 */
public class MimicComparisonActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "result_path";
    public static final String EXTRA_ORIG = "orig_path";
    public static final String EXTRA_REF = "ref_path";
    public static final String EXTRA_META = "meta_path";

    private final ImageView[] views = new ImageView[3];
    private final Bitmap[] bitmaps = new Bitmap[3];
    private final Matrix[] baseMatrices = new Matrix[]{new Matrix(), new Matrix(), new Matrix()};
    private final Matrix sharedMatrix = new Matrix();
    private float currentScale = 1f;
    private float lastFocusX = 0f, lastFocusY = 0f;
    private boolean panning = false;
    private float panLastX = 0f, panLastY = 0f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private TextView metaView;
    private View rootGesture;

    public static Intent intent(Context ctx, String resultPath, String origPath,
                                String refPath, String metaPath) {
        Intent i = new Intent(ctx, MimicComparisonActivity.class);
        i.putExtra(EXTRA_RESULT, resultPath);
        i.putExtra(EXTRA_ORIG, origPath);
        i.putExtra(EXTRA_REF, refPath);
        i.putExtra(EXTRA_META, metaPath);
        return i;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mimic_comparison);

        views[0] = findViewById(R.id.image_orig);
        views[1] = findViewById(R.id.image_result);
        views[2] = findViewById(R.id.image_ref);
        metaView = findViewById(R.id.text_meta);
        rootGesture = findViewById(R.id.gesture_root);
        for (ImageView iv : views) {
            iv.setScaleType(ImageView.ScaleType.MATRIX);
        }

        Intent in = getIntent();
        String[] paths = new String[]{
                in.getStringExtra(EXTRA_ORIG),
                in.getStringExtra(EXTRA_RESULT),
                in.getStringExtra(EXTRA_REF)
        };
        for (int i = 0; i < 3; i++) {
            bitmaps[i] = paths[i] != null ? safeDecode(paths[i]) : null;
            if (bitmaps[i] != null) {
                views[i].setImageBitmap(bitmaps[i]);
            } else {
                views[i].setImageResource(android.R.color.darker_gray);
            }
        }

        setupGestures();
        loadMeta(in.getStringExtra(EXTRA_META));

        findViewById(R.id.button_close).setOnClickListener(v -> finish());

        // Lay out base matrices once views have measured.
        getWindow().getDecorView().post(this::applyBaseMatrices);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (int i = 0; i < bitmaps.length; i++) {
            if (bitmaps[i] != null && !bitmaps[i].isRecycled()) bitmaps[i].recycle();
            bitmaps[i] = null;
        }
    }

    private void applyBaseMatrices() {
        for (int i = 0; i < 3; i++) {
            ImageView iv = views[i];
            Bitmap bm = bitmaps[i];
            if (bm == null || iv.getWidth() <= 0 || iv.getHeight() <= 0) continue;
            float vw = iv.getWidth(), vh = iv.getHeight();
            float scale = Math.min(vw / bm.getWidth(), vh / bm.getHeight());
            float dx = (vw - bm.getWidth() * scale) / 2f;
            float dy = (vh - bm.getHeight() * scale) / 2f;
            baseMatrices[i].reset();
            baseMatrices[i].postScale(scale, scale);
            baseMatrices[i].postTranslate(dx, dy);
        }
        sharedMatrix.reset();
        currentScale = 1f;
        applySharedMatrix();
    }

    private void applySharedMatrix() {
        Matrix tmp = new Matrix();
        for (int i = 0; i < 3; i++) {
            tmp.set(baseMatrices[i]);
            tmp.postConcat(sharedMatrix);
            views[i].setImageMatrix(tmp);
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float newScale = Math.max(1f, Math.min(8f, currentScale * factor));
                factor = newScale / currentScale;
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                sharedMatrix.postScale(factor, factor, lastFocusX, lastFocusY);
                currentScale = newScale;
                applySharedMatrix();
                return true;
            }
        });
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                sharedMatrix.reset();
                currentScale = 1f;
                applySharedMatrix();
                return true;
            }
        });

        View.OnTouchListener tl = (v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    panLastX = event.getX();
                    panLastY = event.getY();
                    panning = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (panning && event.getPointerCount() == 1 && currentScale > 1f) {
                        float dx = event.getX() - panLastX;
                        float dy = event.getY() - panLastY;
                        sharedMatrix.postTranslate(dx, dy);
                        applySharedMatrix();
                        panLastX = event.getX();
                        panLastY = event.getY();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    panning = false;
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() <= 2) {
                        int idx = event.getActionIndex() == 0 ? 1 : 0;
                        panLastX = event.getX(idx);
                        panLastY = event.getY(idx);
                        panning = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    panning = false;
                    break;
            }
            return true;
        };
        rootGesture.setOnTouchListener(tl);
    }

    private void loadMeta(String metaPath) {
        if (metaPath == null) {
            metaView.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(metaPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            sb.append("(failed to read meta: ").append(e.getMessage()).append(')');
        }
        metaView.setText(sb.toString().trim());
    }

    @Nullable
    private Bitmap safeDecode(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            BitmapFactory.Options bb = new BitmapFactory.Options();
            bb.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bb);
            int sample = 1;
            int max = Math.max(bb.outWidth, bb.outHeight);
            while (max / sample > 1600) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeFile(path, opts);
            if (bm == null) return null;
            int rot = readExifRotation(path);
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, false);
                if (rotated != bm) bm.recycle();
                return rotated;
            }
            return bm;
        } catch (Exception e) {
            Toast.makeText(this, "decode failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private static int readExifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
