package com.samsung.camera.intelligence.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PhotoDefectOverlayView extends View {

    public static class Region {
        public final float x;
        public final float y;
        public final float w;
        public final float h;
        public final float confidence;
        public final String label;

        public Region(float x, float y, float w, float h, float confidence, String label) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.confidence = confidence;
            this.label = label;
        }
    }

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Region> regions = new ArrayList<>();
    private final List<RectF> regionRects = new ArrayList<>();
    private int imageWidth = 0;
    private int imageHeight = 0;
    private OnRegionClickListener onRegionClickListener;

    public interface OnRegionClickListener {
        void onRegionClick(Region region);
    }

    public PhotoDefectOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        boxPaint.setColor(Color.parseColor("#FF8B8B"));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor("#33FF8B8B"));

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setFakeBoldText(true);

        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.parseColor("#CC131722"));
    }

    public void setImageInfo(int width, int height) {
        imageWidth = width;
        imageHeight = height;
        invalidate();
    }

    public void setRegions(List<Region> newRegions) {
        regions.clear();
        regionRects.clear();
        if (newRegions != null) {
            regions.addAll(newRegions);
        }
        invalidate();
    }

    public void clearRegions() {
        regions.clear();
        regionRects.clear();
        invalidate();
    }

    public void setOnRegionClickListener(OnRegionClickListener listener) {
        onRegionClickListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (regions.isEmpty() || imageWidth <= 0 || imageHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        float scale = Math.min((float) getWidth() / imageWidth, (float) getHeight() / imageHeight);
        float displayWidth = imageWidth * scale;
        float displayHeight = imageHeight * scale;
        float leftOffset = (getWidth() - displayWidth) / 2f;
        float topOffset = (getHeight() - displayHeight) / 2f;
        regionRects.clear();

        for (Region region : regions) {
            RectF rect = new RectF(
                    leftOffset + region.x * displayWidth,
                    topOffset + region.y * displayHeight,
                    leftOffset + (region.x + region.w) * displayWidth,
                    topOffset + (region.y + region.h) * displayHeight
            );
            regionRects.add(rect);
            canvas.drawRoundRect(rect, 16f, 16f, fillPaint);
            canvas.drawRoundRect(rect, 16f, 16f, boxPaint);

            String label = region.label == null ? "Issue" : region.label;
            String badge = String.format(Locale.US, "%s %.0f%%", label, region.confidence * 100f);
            float textWidth = textPaint.measureText(badge);
            float textHeight = textPaint.getTextSize();
            float badgeLeft = rect.left;
            float badgeTop = Math.max(topOffset + 8f, rect.top - textHeight - 20f);
            RectF badgeRect = new RectF(
                    badgeLeft,
                    badgeTop,
                    badgeLeft + textWidth + 28f,
                    badgeTop + textHeight + 18f
            );
            canvas.drawRoundRect(badgeRect, 14f, 14f, textBgPaint);
            canvas.drawText(badge, badgeRect.left + 14f, badgeRect.bottom - 12f, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || onRegionClickListener == null) {
            return super.onTouchEvent(event);
        }
        for (int i = 0; i < regionRects.size() && i < regions.size(); i++) {
            if (regionRects.get(i).contains(event.getX(), event.getY())) {
                onRegionClickListener.onRegionClick(regions.get(i));
                return true;
            }
        }
        return super.onTouchEvent(event);
    }
}