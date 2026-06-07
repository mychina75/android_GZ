package com.samsung.camera.intelligence.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.View;

import androidx.annotation.Nullable;

import com.samsung.camera.intelligence.guidance.AngleSuggestion;
import com.samsung.camera.intelligence.guidance.AlertBadge;
import com.samsung.camera.intelligence.guidance.CompositionAdvice;
import com.samsung.camera.intelligence.guidance.CompositionTipOverlay;
import com.samsung.camera.intelligence.guidance.DirectionArrow;
import com.samsung.camera.intelligence.guidance.FrameAnalysis;
import com.samsung.camera.intelligence.guidance.FramingTemplateOverlay;
import com.samsung.camera.intelligence.guidance.GridOverlay;
import com.samsung.camera.intelligence.guidance.GuidanceFrame;
import com.samsung.camera.intelligence.guidance.GuidanceOverlay;
import com.samsung.camera.intelligence.guidance.HorizonLine;
import com.samsung.camera.intelligence.guidance.MasterMatchOverlay;
import com.samsung.camera.intelligence.guidance.SubjectGuide;
import com.samsung.camera.intelligence.guidance.SilhouetteOverlay;
import com.samsung.camera.intelligence.guidance.SymmetryGuideOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GuidanceOverlayView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deviceLevelReferencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deviceLevelActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deviceLevelSnapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deviceLevelCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint devicePitchGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subjectBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Phase 8–10 (Composition v2 — Plan A) silhouette / ghost-frame paints.
    // Animated dashed outline: white in ARMING/ACTIVE, green in LOCKED.
    private final Paint silhouettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint silhouetteFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SilhouetteOverlay.State lastSilhouetteState = SilhouetteOverlay.State.OFF;

    private List<GuidanceOverlay> overlays = new ArrayList<>();
    private float[] subjectBoundingBoxNorm = null;
    private float[] cropBoundingBoxNorm = null;
    @Nullable private Float renderedLiveDotX = null;
    @Nullable private Float renderedLiveDotY = null;
    private long renderedLiveDotUpdatedMs = 0L;

    // Phase 3.5 — when a FramingTemplateOverlay is present in the current
    // frame, suppress the legacy blue subject bbox / amber crop bbox / cardinal
    // DirectionArrow / subject-anchored tip card. The template renderer
    // already covers all of those with stabilized geometry.
    private boolean templateActive = false;

    // Phase 6 — Pro HUD toggle: when OFF (default) the legacy diagnostic
    // widgets (subject bbox, crop bbox, composition HUD, tip card, edge
    // nudge bars, side match-ring) are hidden so the consumer-friendly
    // Aim Reticle stands alone. Toggled from Settings → Pro HUD.
    private boolean proHudVisible = false;
    // Phase 6 — overlay external model outputs only when explicitly enabled.
    private boolean showExternalComparisonOverlay = false;
    public void setProHudVisible(boolean v) { this.proHudVisible = v; invalidate(); }
    public void setShowExternalComparisonOverlay(boolean v) {
        this.showExternalComparisonOverlay = v; invalidate();
    }

    // Phase 4 (Composition v2) — consumer-facing advice + horizon flags.
    private boolean showAdviceChips = true;
    private boolean showHorizonLevelGuide = true;
    private boolean showTargetCropFrameInConsumer = false;
    @Nullable private CompositionAdvice compositionAdvice = null;
    private long compositionAdviceShownAt = 0L;
    @Nullable private FrameAnalysis latestAnalysis = null;
    private final Paint chipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizonGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setShowAdviceChips(boolean v) { this.showAdviceChips = v; invalidate(); }
    public void setShowHorizonLevelGuide(boolean v) { this.showHorizonLevelGuide = v; invalidate(); }
    public void setShowTargetCropFrameInConsumer(boolean v) {
        this.showTargetCropFrameInConsumer = v; invalidate();
    }

    /**
     * Phase 1 (Composition v2) — push the latest scheduler output. Pass
     * {@code null} to clear immediately. Repeating the same techniqueId
     * within its TTL does not reset the fade-in timer (avoids flicker
     * when the rule fires multiple frames in a row).
     */
    public void setCompositionAdvice(@Nullable CompositionAdvice advice) {
        if (advice == null) {
            this.compositionAdvice = null;
            this.compositionAdviceShownAt = 0L;
            invalidate();
            return;
        }
        boolean sameAsCurrent = this.compositionAdvice != null
                && this.compositionAdvice.techniqueId.equals(advice.techniqueId);
        this.compositionAdvice = advice;
        if (!sameAsCurrent) {
            this.compositionAdviceShownAt = System.currentTimeMillis();
        }
        invalidate();
    }
    private String fallbackMessage;
    private Float deviceHorizonAngleDeg = null;
    private Float devicePitchDeg = null;
    private int deviceDisplayRotation = Surface.ROTATION_0;
    private boolean deviceShootingLandscape = false;

    private List<GuidanceOverlay> currentTextOverlays = new ArrayList<>();
    private int currentTextIndex = 0;
    private final Handler rotateHandler = new Handler(Looper.getMainLooper());
    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!currentTextOverlays.isEmpty()) {
                currentTextIndex = (currentTextIndex + 1) % currentTextOverlays.size();
                invalidate();
                rotateHandler.postDelayed(this, 2000);
            }
        }
    };

    public GuidanceOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setColor(Color.argb(220, 255, 255, 255));

        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(4f);
        accentPaint.setColor(Color.argb(255, 34, 197, 94));

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36f);
        badgePaint.setColor(Color.BLACK);
        badgePaint.setTextSize(24f);
        badgePaint.setFakeBoldText(true);

        cardPaint.setColor(Color.argb(170, 0, 0, 0));
        badgeFillPaint.setStyle(Paint.Style.FILL);

        deviceLevelReferencePaint.setStyle(Paint.Style.STROKE);
        deviceLevelReferencePaint.setStrokeWidth(2f);
        deviceLevelReferencePaint.setColor(Color.argb(120, 255, 255, 255));

        deviceLevelActivePaint.setStyle(Paint.Style.STROKE);
        deviceLevelActivePaint.setStrokeWidth(5f);
        deviceLevelActivePaint.setStrokeCap(Paint.Cap.ROUND);

        deviceLevelSnapPaint.setStyle(Paint.Style.STROKE);
        deviceLevelSnapPaint.setStrokeCap(Paint.Cap.ROUND);

        deviceLevelCirclePaint.setStyle(Paint.Style.STROKE);
        deviceLevelCirclePaint.setStrokeWidth(2.4f);
        deviceLevelCirclePaint.setColor(Color.argb(170, 255, 255, 255));

        devicePitchGuidePaint.setStyle(Paint.Style.STROKE);
        devicePitchGuidePaint.setStrokeCap(Paint.Cap.ROUND);
        devicePitchGuidePaint.setStrokeWidth(4f);

        subjectBoxPaint.setStyle(Paint.Style.STROKE);
        subjectBoxPaint.setStrokeWidth(4f);
        subjectBoxPaint.setColor(Color.argb(255, 70, 224, 126));

        cropBoxPaint.setStyle(Paint.Style.STROKE);
        cropBoxPaint.setStrokeWidth(4f);
        cropBoxPaint.setColor(Color.argb(255, 255, 182, 72));

        // Phase 8–10 silhouette paint setup. Stroke is configured per-state
        // in drawSilhouette; defaults here keep instances valid before first
        // draw.
        silhouettePaint.setStyle(Paint.Style.STROKE);
        silhouettePaint.setStrokeCap(Paint.Cap.ROUND);
        silhouettePaint.setStrokeJoin(Paint.Join.ROUND);
        silhouettePaint.setStrokeWidth(5f);
        silhouettePaint.setColor(Color.argb(220, 255, 255, 255));
        silhouetteFillPaint.setStyle(Paint.Style.FILL);
        silhouetteFillPaint.setColor(Color.argb(40, 255, 255, 255));

        // Phase 4 (Composition v2) — chip + horizon guide paints.
        chipBgPaint.setStyle(Paint.Style.FILL);
        chipBgPaint.setColor(Color.argb(190, 16, 20, 28));
        chipTextPaint.setColor(Color.WHITE);
        chipTextPaint.setTextSize(30f);
        chipTextPaint.setFakeBoldText(true);
        chipTextPaint.setTextAlign(Paint.Align.CENTER);
        horizonGuidePaint.setStyle(Paint.Style.STROKE);
        horizonGuidePaint.setStrokeWidth(3f);
        horizonGuidePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setGuidanceFrame(GuidanceFrame frame) {
        this.overlays = frame == null ? new ArrayList<>() : frame.getOverlays();
        this.latestAnalysis = frame == null ? null : frame.getAnalysis();

        // Phase 3.5 — detect whether the new framing template is active so
        // legacy widgets can be suppressed without flag-plumbing through the
        // analyzer pipeline.
        boolean active = false;
        for (GuidanceOverlay o : overlays) {
            if (o instanceof FramingTemplateOverlay) { active = true; break; }
        }
        this.templateActive = active;
        if (!active) {
            renderedLiveDotX = null;
            renderedLiveDotY = null;
            renderedLiveDotUpdatedMs = 0L;
        }

        List<GuidanceOverlay> newTexts = new ArrayList<>();
        for (GuidanceOverlay o : overlays) {
            // Phase A.3 — DirectionArrow & SubjectGuide are now drawn on the
            // canvas and therefore must NOT also appear in the rotating text bar.
            // Phase 3 — FramingTemplateOverlay drives the side ring + edge bars,
            // it carries its own message but should not be force-shown as text.
            if (!(o instanceof GridOverlay) && !(o instanceof HorizonLine)
                    && !(o instanceof SubjectGuide)
                    && !(o instanceof DirectionArrow)
                    && !(o instanceof FramingTemplateOverlay)
                    && !(o instanceof SymmetryGuideOverlay)) {
                newTexts.add(o);
            }
        }
        // Sort by urgency (desc) then category (desc) so highest-priority shows first
        java.util.Collections.sort(newTexts, (a, b) ->
                Integer.compare(overlayScore(b), overlayScore(a)));

        boolean changed = newTexts.size() != currentTextOverlays.size();
        if (!changed) {
            for (int i = 0; i < newTexts.size(); i++) {
                String a = buildOverlayBody(newTexts.get(i));
                String b = buildOverlayBody(currentTextOverlays.get(i));
                if (a == null ? b != null : !a.equals(b)) {
                    changed = true;
                    break;
                }
            }
        }

        if (changed) {
            currentTextOverlays = newTexts;
            if (currentTextIndex >= currentTextOverlays.size()) {
                currentTextIndex = 0;
            }
            rotateHandler.removeCallbacks(rotateRunnable);
            if (currentTextOverlays.size() > 1) {
                rotateHandler.postDelayed(rotateRunnable, 2000);
            }
        }

        invalidate();
    }

    public void setCompositionBoundingBoxes(@Nullable float[] subjectBoundingBox,
                                            @Nullable float[] cropBoundingBox) {
        this.subjectBoundingBoxNorm = sanitizeBox(subjectBoundingBox);
        this.cropBoundingBoxNorm = sanitizeBox(cropBoundingBox);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        List<GuidanceOverlay> textOverlays = new ArrayList<>();

        for (GuidanceOverlay overlay : overlays) {
            if (overlay instanceof GridOverlay) {
                if (!templateActive || proHudVisible) {
                    drawGrid(canvas, (GridOverlay) overlay, w, h);
                }
            } else if (overlay instanceof HorizonLine) {
                // Model-derived horizon line is disabled in favor of the device sensor level indicator.
            } else if (overlay instanceof SubjectGuide) {
                // Phase A.2 — re-enabled with confidence gate (set in CompositionGuide).
                // Phase 6 — suppress when the new framing template owns the
                // subject visualisation (unless Pro HUD is on).
                if (!templateActive || proHudVisible) {
                    drawSubjectGuide(canvas, (SubjectGuide) overlay, w, h);
                }
            } else if (overlay instanceof DirectionArrow) {
                // Phase 3.5 — framing template's edge nudge bars supersede
                // the cardinal arrow. Suppress when the template is on.
                if (!templateActive) {
                    drawArrow(canvas, (DirectionArrow) overlay, w, h);
                }
            } else if (overlay instanceof FramingTemplateOverlay) {
                drawFramingTemplate(canvas, (FramingTemplateOverlay) overlay, w, h);
            } else if (overlay instanceof SilhouetteOverlay) {
                drawSilhouette(canvas, (SilhouetteOverlay) overlay, w, h);
            } else if (overlay instanceof SymmetryGuideOverlay) {
                drawSymmetry(canvas, (SymmetryGuideOverlay) overlay, w, h);
            } else {
                textOverlays.add(overlay);
            }
        }

        drawCompositionBoundingBoxes(canvas, w, h);
        drawDeviceLevelIndicator(canvas, w, h);
        // Phase B.1 / B.2 — inline composition HUD widgets (fill-ratio, color chips, score badge)
        // Phase 6 — gated behind Pro HUD when the new template is active.
        if (!templateActive || proHudVisible) {
            drawCompositionHud(canvas, w, h);
            // Phase B.5 — anchored composition tip card (single, near subject bbox)
            drawCompositionTipCard(canvas, w, h);
        }
        drawTextOverlayCards(canvas, textOverlays, w, h);

        // Phase 4 (Composition v2) — consumer chip + horizon level guide.
        if (showHorizonLevelGuide && latestAnalysis != null) {
            drawHorizonLevelGuide(canvas, latestAnalysis, w, h);
        }
        if (showAdviceChips) {
            drawCompositionAdviceChip(canvas, w, h);
        }
    }

    private void drawGrid(Canvas c, GridOverlay overlay, float w, float h) {
        String type = overlay.getGridType() == null ? "rule_of_thirds" : overlay.getGridType();
        switch (type) {
            case "golden_ratio": {
                // Phi-grid: 4 lines at 0.382 / 0.618
                final float a = 0.381966f;        // 1 - 1/phi
                final float b = 1.0f - a;          // 1/phi  ≈ 0.618
                c.drawLine(a * w, 0, a * w, h, linePaint);
                c.drawLine(b * w, 0, b * w, h, linePaint);
                c.drawLine(0, a * h, w, a * h, linePaint);
                c.drawLine(0, b * h, w, b * h, linePaint);
                break;
            }
            case "golden_triangles": {
                // Main diagonal + two perpendiculars from opposite corners
                if (overlay.getDiagonalPoints() != null) {
                    for (float[] p : overlay.getDiagonalPoints()) {
                        if (p != null && p.length >= 4) {
                            c.drawLine(p[0] * w, p[1] * h, p[2] * w, p[3] * h, accentPaint);
                        }
                    }
                } else {
                    // Sensible fallback if diagonal_points were stripped
                    c.drawLine(0, 0, w, h, accentPaint);
                    c.drawLine(0, h, 0.6f * w, 0, accentPaint);
                    c.drawLine(w, 0, 0.4f * w, h, accentPaint);
                }
                break;
            }
            case "rule_of_thirds":
            default: {
                c.drawLine(w / 3f, 0, w / 3f, h, linePaint);
                c.drawLine(2f * w / 3f, 0, 2f * w / 3f, h, linePaint);
                c.drawLine(0, h / 3f, w, h / 3f, linePaint);
                c.drawLine(0, 2f * h / 3f, w, 2f * h / 3f, linePaint);
                if (overlay.getDiagonalPoints() != null) {
                    for (float[] p : overlay.getDiagonalPoints()) {
                        if (p != null && p.length >= 4) {
                            c.drawLine(p[0] * w, p[1] * h, p[2] * w, p[3] * h, accentPaint);
                        }
                    }
                }
                break;
            }
        }
    }

    private void drawCompositionBoundingBoxes(Canvas canvas, float w, float h) {
        // Phase 3.5 \u2014 the framing template renderer covers subject + crop
        // visualisation, so don't double-draw the legacy corner boxes.
        if (templateActive) {
            return;
        }
        if (subjectBoundingBoxNorm != null) {
            drawCornerBox(canvas, subjectBoundingBoxNorm, w, h, subjectBoxPaint);
        }
        if (cropBoundingBoxNorm != null) {
            drawCornerBox(canvas, cropBoundingBoxNorm, w, h, cropBoxPaint);
        }
    }

    private void drawCornerBox(Canvas canvas, float[] box, float viewW, float viewH, Paint paint) {
        float x = box[0] * viewW;
        float y = box[1] * viewH;
        float bw = box[2] * viewW;
        float bh = box[3] * viewH;
        if (bw < 6f || bh < 6f) {
            return;
        }

        float x2 = x + bw;
        float y2 = y + bh;
        float corner = Math.min(36f, Math.min(bw, bh) * 0.22f);

        // Top-left
        canvas.drawLine(x, y, x + corner, y, paint);
        canvas.drawLine(x, y, x, y + corner, paint);
        // Top-right
        canvas.drawLine(x2 - corner, y, x2, y, paint);
        canvas.drawLine(x2, y, x2, y + corner, paint);
        // Bottom-left
        canvas.drawLine(x, y2, x + corner, y2, paint);
        canvas.drawLine(x, y2 - corner, x, y2, paint);
        // Bottom-right
        canvas.drawLine(x2 - corner, y2, x2, y2, paint);
        canvas.drawLine(x2, y2 - corner, x2, y2, paint);
    }

    @Nullable
    private float[] sanitizeBox(@Nullable float[] box) {
        if (box == null || box.length < 4) {
            return null;
        }
        float x = clamp01(box[0]);
        float y = clamp01(box[1]);
        float w = clamp01(box[2]);
        float h = clamp01(box[3]);
        if (w <= 0.001f || h <= 0.001f) {
            return null;
        }
        if (x + w > 1.0f) {
            w = 1.0f - x;
        }
        if (y + h > 1.0f) {
            h = 1.0f - y;
        }
        if (w <= 0.001f || h <= 0.001f) {
            return null;
        }
        return new float[]{x, y, w, h};
    }

    private float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private void drawDeviceLevelIndicator(Canvas c, float w, float h) {
        if (deviceHorizonAngleDeg == null || devicePitchDeg == null) {
            return;
        }

        float centerX = w / 2f;
        float centerY = h / 2f;
        boolean isLandscape = deviceShootingLandscape;
        float baseSize = Math.min(w, h);
        float rawTiltDeg = deviceHorizonAngleDeg;
        float rawPitchDeg = devicePitchDeg;
        float snapRange = isLandscape ? 1.6f : 2.0f;
        float hardSnapThreshold = isLandscape ? 0.18f : 0.28f;
        float tiltDeg = applySnapToLevel(rawTiltDeg, hardSnapThreshold, snapRange);
        float absTiltDeg = Math.abs(rawTiltDeg);
        float snapStrength = computeSnapStrength(absTiltDeg, hardSnapThreshold, snapRange);
        boolean snappedLevel = absTiltDeg < snapRange;

        float levelThreshold = isLandscape ? 0.75f : 1.10f;
        float warningThreshold = isLandscape ? 2.2f : 3.0f;
        int activeColor;
        if (Math.abs(tiltDeg) < levelThreshold) {
            activeColor = Color.argb(245, 106, 214, 120);
        } else if (Math.abs(tiltDeg) < warningThreshold) {
            activeColor = Color.argb(245, 255, 208, 64);
        } else {
            activeColor = Color.argb(245, 255, 151, 64);
        }

        float indicatorCenterY = centerY + (isLandscape ? baseSize * 0.020f : baseSize * 0.012f);
        float referenceLength = isLandscape ? baseSize * 0.58f : baseSize * 0.40f;
        float activeLength = isLandscape ? baseSize * 0.26f : baseSize * 0.20f;
        float circleRadius = isLandscape ? baseSize * 0.072f : baseSize * 0.082f;

        deviceLevelReferencePaint.setColor(Color.argb(96, 255, 255, 255));
        deviceLevelReferencePaint.setStrokeWidth(isLandscape ? 2.6f : 2.2f);
        deviceLevelActivePaint.setColor(activeColor);
        deviceLevelActivePaint.setStrokeWidth(isLandscape ? 5.8f : 5.0f);
        deviceLevelSnapPaint.setColor(Color.argb((int) (80 + snapStrength * 90), 220, 255, 220));
        deviceLevelSnapPaint.setStrokeWidth((isLandscape ? 9f : 8f) + snapStrength * 1.5f);
        deviceLevelCirclePaint.setColor(snappedLevel
                ? Color.argb(200, 106, 214, 120)
                : Color.argb(170, 255, 255, 255));
        deviceLevelCirclePaint.setStrokeWidth(isLandscape ? 2.8f : 2.4f);

        c.drawLine(
            centerX - referenceLength / 2f,
            indicatorCenterY,
            centerX + referenceLength / 2f,
            indicatorCenterY,
            deviceLevelReferencePaint
        );

        c.drawCircle(centerX, indicatorCenterY, circleRadius, deviceLevelCirclePaint);

        float[] pitchThresholds = isLandscape
                ? new float[]{3.0f, 7.0f, 12.0f}
                : new float[]{4.0f, 9.0f, 15.0f};
        drawPitchLadder(c, centerX, indicatorCenterY, circleRadius, rawPitchDeg, pitchThresholds, true);
        drawPitchLadder(c, centerX, indicatorCenterY, circleRadius, rawPitchDeg, pitchThresholds, false);

        float angle = (float) Math.toRadians(tiltDeg);
        float length = activeLength;
        float dx = (float) Math.cos(angle) * length / 2f;
        float dy = (float) Math.sin(angle) * length / 2f;

        if (snappedLevel) {
            c.drawLine(
                centerX - dx,
                indicatorCenterY - dy,
                centerX + dx,
                indicatorCenterY + dy,
                deviceLevelSnapPaint
            );
        }

        c.drawLine(
            centerX - dx,
            indicatorCenterY - dy,
            centerX + dx,
            indicatorCenterY + dy,
            deviceLevelActivePaint
        );

        float tickLen = isLandscape ? 12f : 10f;
        float centerGap = circleRadius + (isLandscape ? 10f : 8f);
        c.drawLine(centerX - dx, indicatorCenterY - dy,
            centerX - dx + tickLen, indicatorCenterY - dy, deviceLevelActivePaint);
        c.drawLine(centerX + dx, indicatorCenterY + dy,
            centerX + dx - tickLen, indicatorCenterY + dy, deviceLevelActivePaint);

        Paint centerMarkerPaint = new Paint(deviceLevelReferencePaint);
        centerMarkerPaint.setColor(Color.argb(180, 255, 255, 255));
        centerMarkerPaint.setStrokeWidth(isLandscape ? 3.2f : 2.8f);
        c.drawLine(centerX - centerGap, indicatorCenterY, centerX - 5f, indicatorCenterY, centerMarkerPaint);
        c.drawLine(centerX + 5f, indicatorCenterY, centerX + centerGap, indicatorCenterY, centerMarkerPaint);
        c.drawLine(centerX, indicatorCenterY - (isLandscape ? 11f : 8f), centerX, indicatorCenterY + (isLandscape ? 11f : 8f), centerMarkerPaint);

        Paint centerDotPaint = new Paint(deviceLevelActivePaint);
        centerDotPaint.setStyle(Paint.Style.FILL);
        c.drawCircle(centerX, indicatorCenterY, isLandscape ? 3.8f : 3.4f, centerDotPaint);
    }

    private void drawPitchLadder(Canvas canvas,
                                 float centerX,
                                 float centerY,
                                 float circleRadius,
                                 float pitchDeg,
                                 float[] thresholds,
                                 boolean upperSide) {
        boolean targetSide = upperSide ? pitchDeg > 0.0f : pitchDeg < 0.0f;
        float absPitch = Math.abs(pitchDeg);
        float sign = upperSide ? -1.0f : 1.0f;
        float[] lengthRatios = {0.62f, 0.50f, 0.38f};
        float[] offsets = {0.22f, 0.38f, 0.54f};

        for (int i = 0; i < 3; i++) {
            float y = centerY + sign * circleRadius * offsets[i];
            float halfLen = circleRadius * lengthRatios[i];

            int color = Color.argb(92, 255, 255, 255);
            float strokeWidth = 2.4f;
            if (targetSide && absPitch >= thresholds[i]) {
                color = i == 0
                        ? Color.argb(230, 106, 214, 120)
                        : Color.argb(230, 82, 201, 108);
                strokeWidth = 3.6f;
            }

            devicePitchGuidePaint.setColor(color);
            devicePitchGuidePaint.setStrokeWidth(strokeWidth);
            canvas.drawLine(centerX - halfLen, y, centerX + halfLen, y, devicePitchGuidePaint);
        }
    }

    private float applySnapToLevel(float tiltDeg, float hardSnapThreshold, float snapRange) {
        float absTilt = Math.abs(tiltDeg);
        if (absTilt <= hardSnapThreshold) {
            return 0.0f;
        }
        if (absTilt >= snapRange) {
            return tiltDeg;
        }

        float normalized = (absTilt - hardSnapThreshold) / (snapRange - hardSnapThreshold);
        float eased = normalized * normalized * normalized;
        return Math.signum(tiltDeg) * absTilt * eased;
    }

    private float computeSnapStrength(float absTiltDeg, float hardSnapThreshold, float snapRange) {
        if (absTiltDeg <= hardSnapThreshold) {
            return 1.0f;
        }
        if (absTiltDeg >= snapRange) {
            return 0.0f;
        }
        float normalized = (absTiltDeg - hardSnapThreshold) / (snapRange - hardSnapThreshold);
        return 1.0f - normalized;
    }

    private final Paint subjectGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subjectDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawSubjectGuide(Canvas c, SubjectGuide overlay, float w, float h) {
        // Phase A.2 — draw a dashed line from current subject center to the
        // nearest power-point. Color encodes how far off we are.
        float curX = overlay.getCurrentX() * w;
        float curY = overlay.getCurrentY() * h;
        float tgtX = overlay.getTargetX() * w;
        float tgtY = overlay.getTargetY() * h;

        float distNorm = (float) Math.hypot(
                overlay.getCurrentX() - overlay.getTargetX(),
                overlay.getCurrentY() - overlay.getTargetY());
        int color;
        if (distNorm > 0.30f) {
            color = Color.argb(220, 255, 96, 96);   // red
        } else if (distNorm > 0.15f) {
            color = Color.argb(220, 255, 180, 64);  // orange
        } else {
            color = Color.argb(220, 96, 220, 120);  // green
        }

        subjectGuidePaint.setStyle(Paint.Style.STROKE);
        subjectGuidePaint.setStrokeWidth(3.5f);
        subjectGuidePaint.setColor(color);
        subjectGuidePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{14f, 10f}, 0));
        c.drawLine(curX, curY, tgtX, tgtY, subjectGuidePaint);
        subjectGuidePaint.setPathEffect(null);

        // Filled dot at current subject center
        subjectDotPaint.setStyle(Paint.Style.FILL);
        subjectDotPaint.setColor(color);
        c.drawCircle(curX, curY, 9f, subjectDotPaint);

        // Hollow ring at target intersection
        subjectGuidePaint.setStrokeWidth(3f);
        subjectGuidePaint.setColor(Color.argb(255, 255, 255, 255));
        c.drawCircle(tgtX, tgtY, 14f, subjectGuidePaint);
        subjectGuidePaint.setColor(color);
        c.drawCircle(tgtX, tgtY, 18f, subjectGuidePaint);

        // Small "Aim" label so the user understands the ring marks the
        // suggested subject placement (one of the rule-of-thirds power points).
        badgePaint.setTextSize(22f);
        badgePaint.setTextAlign(android.graphics.Paint.Align.LEFT);
        badgePaint.setColor(Color.argb(180, 0, 0, 0));
        badgePaint.setStyle(android.graphics.Paint.Style.FILL);
        float labelLeft = tgtX + 24f;
        float labelTop = tgtY - 14f;
        c.drawRoundRect(labelLeft, labelTop, labelLeft + 64f, labelTop + 28f, 6f, 6f, badgePaint);
        badgePaint.setColor(Color.argb(255, 255, 255, 255));
        c.drawText("Aim", labelLeft + 12f, labelTop + 21f, badgePaint);
    }

    private final Paint arrowFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawArrow(Canvas c, DirectionArrow overlay, float w, float h) {
        float magnitude = Math.max(0.0f, Math.min(1f, overlay.getMagnitude()));
        if (magnitude < 0.05f) {
            return;
        }
        // Phase A.3 — draw at screen edge so arrow doesn't sit on top of subject.
        // Alpha scales with magnitude so big offsets produce more attention-grabbing arrows.
        int alpha = (int) (140 + 115 * magnitude);
        int arrowColor = Color.argb(alpha, 255, 196, 64);
        float length = 50f + magnitude * 90f;          // 50–140 px
        String direction = overlay.getDirection() == null ? "" : overlay.getDirection().toLowerCase(Locale.ROOT);

        float marginEdge = 60f;
        float startX, startY, endX, endY;
        switch (direction) {
            case "left":
                startY = endY = h * 0.62f;
                startX = marginEdge + length;
                endX = marginEdge;
                break;
            case "right":
                startY = endY = h * 0.62f;
                startX = w - marginEdge - length;
                endX = w - marginEdge;
                break;
            case "up":
                startX = endX = w * 0.5f;
                startY = marginEdge + length + 60f; // skip below status bar area
                endY = marginEdge + 60f;
                break;
            case "down":
                startX = endX = w * 0.5f;
                startY = h - marginEdge - length - 220f;
                endY = h - marginEdge - 220f;
                break;
            default:
                return;
        }

        Paint shaft = new Paint(accentPaint);
        shaft.setColor(arrowColor);
        shaft.setStrokeWidth(7f);
        shaft.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(startX, startY, endX, endY, shaft);

        float arrowSize = 26f + magnitude * 10f;
        float angle = (float) Math.atan2(endY - startY, endX - startX);
        arrowFillPaint.setStyle(Paint.Style.FILL);
        arrowFillPaint.setColor(arrowColor);

        Path path = new Path();
        path.moveTo(endX, endY);
        path.lineTo(
                endX - arrowSize * (float) Math.cos(angle - Math.PI / 6),
                endY - arrowSize * (float) Math.sin(angle - Math.PI / 6));
        path.lineTo(
                endX - arrowSize * (float) Math.cos(angle + Math.PI / 6),
                endY - arrowSize * (float) Math.sin(angle + Math.PI / 6));
        path.close();
        c.drawPath(path, arrowFillPaint);
    }

    // ---- Phase 3 \u2014 Framing Template overlay ----

    private final Paint templateFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint templateAnchorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint templateGhostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint matchRingTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint matchRingProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint matchRingTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nudgeBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Phase 6 — Aim Reticle paints.
    private final Paint templateSketchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint templateRingTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint templateRingArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coachPillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint coachPillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint externalSubjectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint externalCropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint externalLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomPillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint zoomPillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomProgressTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomProgressFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float autoZoomCurrentRatio = 1f;
    private float autoZoomTargetRatio = 1f;
    private boolean autoZoomActive = false;
    private long autoZoomUpdatedMs = 0L;

    // Sticky-lock state machine for the match ring + capture CTA.
    private float matchScoreEma = 0f;
    private boolean matchLocked = false;
    private long matchLockEnteredMs = 0L;
    private float[] lockedTargetCropNorm = null;
    private int activeTargetRevision = -1;
    private static final float MATCH_LOCK_THRESHOLD = 0.85f;
    private static final float MATCH_UNLOCK_THRESHOLD = 0.70f;
    private static final long MATCH_MIN_LOCK_MS = 1000L;
    private static final float MATCH_EMA_ALPHA = 0.20f;

    private MatchLockListener matchLockListener;

    public interface MatchLockListener {
        /** Fired on transitions of the match-lock state. */
        void onMatchLockChanged(boolean locked, @Nullable float[] lockedCropNorm);
    }

    public void setMatchLockListener(@Nullable MatchLockListener listener) {
        this.matchLockListener = listener;
    }

    public void setAutoZoomState(float currentRatio, float targetRatio, boolean active) {
        autoZoomCurrentRatio = Math.max(1f, currentRatio);
        autoZoomTargetRatio = Math.max(1f, targetRatio);
        autoZoomActive = active;
        autoZoomUpdatedMs = (!active && autoZoomTargetRatio <= 1.01f)
            ? 0L : System.currentTimeMillis();
        invalidate();
    }

    /** True when the live frame is matching the AI-suggested template. */
    public boolean isMatchLocked() {
        return matchLocked;
    }

    /** When locked, the AI-suggested crop in display-normalized [x,y,w,h]. */
    @Nullable
    public float[] getLockedCropNorm() {
        return lockedTargetCropNorm == null ? null : lockedTargetCropNorm.clone();
    }

    private void clearMatchLock(boolean notify) {
        boolean wasLocked = matchLocked;
        matchLocked = false;
        matchLockEnteredMs = 0L;
        lockedTargetCropNorm = null;
        matchScoreEma = 0f;
        if (notify && wasLocked && matchLockListener != null) {
            matchLockListener.onMatchLockChanged(false, null);
        }
    }

    private void drawFramingTemplate(Canvas c, FramingTemplateOverlay overlay, float w, float h) {
        float[] crop = overlay.getTargetCropNorm();
        if (crop == null || crop.length < 4) {
            return;
        }

        boolean targetLocked = overlay.isTargetLocked();
        if (!targetLocked) {
            activeTargetRevision = -1;
            clearMatchLock(true);
        } else if (activeTargetRevision != overlay.getTargetRevision()) {
            activeTargetRevision = overlay.getTargetRevision();
            clearMatchLock(true);
        }

        float drivingScore = targetLocked ? overlay.getAlignmentScore() : 0f;
        if (drivingScore <= 0f && targetLocked) drivingScore = overlay.getMatchScore();
        matchScoreEma = matchScoreEma + MATCH_EMA_ALPHA * (drivingScore - matchScoreEma);

        long now = System.currentTimeMillis();
        boolean wasLocked = matchLocked;
        if (matchLocked) {
            if (matchScoreEma < MATCH_UNLOCK_THRESHOLD
                    && (now - matchLockEnteredMs) >= MATCH_MIN_LOCK_MS) {
                matchLocked = false;
                lockedTargetCropNorm = null;
            }
        } else {
            if (targetLocked && matchScoreEma >= MATCH_LOCK_THRESHOLD) {
                matchLocked = true;
                matchLockEnteredMs = now;
                lockedTargetCropNorm = crop.clone();
                triggerMatchLockHaptic();
            }
        }
        if (matchLocked != wasLocked && matchLockListener != null) {
            matchLockListener.onMatchLockChanged(matchLocked,
                    matchLocked ? lockedTargetCropNorm : null);
        }

        int templateColor = templateColorFor(matchScoreEma, matchLocked);

        // ---- 1. Fixed target frame: this is the target the user aligns to. ----
        // Phase 4 (Composition v2): in consumer mode the heavy white frame is
        // hidden by default. Show it only for Pro HUD, during an active
        // auto-zoom flash, or when the dev settings flag opts back in.
        if (proHudVisible || autoZoomActive || showTargetCropFrameInConsumer) {
            drawTargetFrame(c, overlay, templateColor, w, h);
        }

        if (proHudVisible) {
            drawTemplateSketch(c, overlay, w, h);
        }

        if (proHudVisible) {
            drawRawSuggestedCrop(c, overlay, w, h);
        }

        // ---- 2. External sub-model comparison overlay. ----
        if (showExternalComparisonOverlay) {
            drawExternalComparison(c, overlay, w, h);
        }

        // ---- 3. Live subject marker. Full aim reticle is diagnostic-only. ----
        if (proHudVisible) {
            drawAimReticle(c, overlay, templateColor, w, h);
        } else {
            drawMinimalLiveSubjectDot(c, overlay, w, h);
        }

        // ---- 4. Coach hint pill at top-center (replaces wobbly tip card). ----
        drawCoachPill(c, overlay, w, h);

        // ---- 4b. Compact zoom cue when auto-zoom is useful or active. ----
        drawZoomCue(c, overlay, w, h);

        // ---- 5. Pro HUD widgets (legacy diagnostic view, opt-in). ----
        if (proHudVisible) {
            drawProHudWidgets(c, overlay, templateColor, w, h);
        }
    }

    private void drawTargetFrame(Canvas c, FramingTemplateOverlay overlay,
                                 int templateColor, float w, float h) {
        float[] crop = overlay.getTargetCropNorm();
        if (crop == null || crop.length < 4) return;

        float tx = crop[0] * w, ty = crop[1] * h;
        float tw = crop[2] * w, th = crop[3] * h;
        boolean targetLocked = overlay.isTargetLocked();
        int alpha = targetLocked ? 230 : (int) (90 + 100 * overlay.getTargetLockProgress());
        int frameColor = matchLocked
            ? Color.argb(alpha, 74, 222, 128)
            : Color.argb(alpha, 255, 255, 255);

        templateFramePaint.setStyle(Paint.Style.STROKE);
        templateFramePaint.setStrokeWidth(matchLocked ? 7f : (targetLocked ? 5f : 3.5f));
        templateFramePaint.setColor(frameColor);
        templateFramePaint.setPathEffect(targetLocked
                ? null
                : new android.graphics.DashPathEffect(new float[]{18f, 12f}, 0));
        c.drawRect(tx, ty, tx + tw, ty + th, templateFramePaint);
        templateFramePaint.setPathEffect(null);

        float tick = Math.max(18f, Math.min(tw, th) * 0.10f);
        templateFramePaint.setStrokeWidth(matchLocked ? 8f : (targetLocked ? 6f : 5f));
        c.drawLine(tx, ty, tx + tick, ty, templateFramePaint);
        c.drawLine(tx, ty, tx, ty + tick, templateFramePaint);
        c.drawLine(tx + tw - tick, ty, tx + tw, ty, templateFramePaint);
        c.drawLine(tx + tw, ty, tx + tw, ty + tick, templateFramePaint);
        c.drawLine(tx, ty + th - tick, tx, ty + th, templateFramePaint);
        c.drawLine(tx, ty + th, tx + tick, ty + th, templateFramePaint);
        c.drawLine(tx + tw - tick, ty + th, tx + tw, ty + th, templateFramePaint);
        c.drawLine(tx + tw, ty + th - tick, tx + tw, ty + th, templateFramePaint);
    }

    private void drawMinimalLiveSubjectDot(Canvas c, FramingTemplateOverlay overlay, float w, float h) {
        if (!overlay.isTargetLocked()) return;
        float[] live = getRenderedLiveSubjectCenter(overlay);
        if (live == null || live.length < 2) return;
        float lx = live[0] * w;
        float ly = live[1] * h;
        float radius = Math.max(16f, Math.min(w, h) * 0.022f);

        templateAnchorPaint.setStyle(Paint.Style.FILL);
        templateAnchorPaint.setColor(Color.argb(88, 255, 255, 255));
        c.drawCircle(lx, ly, radius, templateAnchorPaint);
        templateAnchorPaint.setStyle(Paint.Style.STROKE);
        templateAnchorPaint.setStrokeWidth(3f);
        templateAnchorPaint.setColor(Color.argb(190, 255, 255, 255));
        c.drawCircle(lx, ly, radius, templateAnchorPaint);
        templateAnchorPaint.setStyle(Paint.Style.FILL);
        templateAnchorPaint.setColor(Color.argb(190, 255, 255, 255));
        c.drawCircle(lx, ly, Math.max(5f, radius * 0.34f), templateAnchorPaint);
    }

    private void drawZoomCue(Canvas c, FramingTemplateOverlay overlay, float w, float h) {
        if (!overlay.isTargetLocked()) return;
        float overlayTarget = overlay.getTargetZoomRatio();
        boolean freshZoomState = System.currentTimeMillis() - autoZoomUpdatedMs < 1800L;
        float target = autoZoomTargetRatio > 1.01f ? autoZoomTargetRatio : overlayTarget;
        if (target <= 1.05f && !autoZoomActive && !freshZoomState) return;

        float current = autoZoomCurrentRatio > 1.01f ? autoZoomCurrentRatio : 1f;
        float progress = target <= 1f ? 1f : Math.max(0f, Math.min(1f, (current - 1f) / (target - 1f)));
        String label = autoZoomActive
                ? String.format(Locale.US, "Zoom %.1fx -> %.1fx", current, target)
                : String.format(Locale.US, "Zoom %.1fx", target);

        zoomPillTextPaint.setTextSize(28f);
        zoomPillTextPaint.setFakeBoldText(true);
        zoomPillTextPaint.setColor(Color.WHITE);
        zoomPillTextPaint.setTextAlign(Paint.Align.LEFT);
        float textW = zoomPillTextPaint.measureText(label);
        float padX = 18f;
        float pillW = Math.max(164f, textW + padX * 2f);
        float pillH = 58f;

        float[] crop = overlay.getTargetCropNorm();
        float right = w - 32f;
        float top = 132f;
        if (crop != null && crop.length >= 4) {
            right = Math.min(w - 32f, (crop[0] + crop[2]) * w - 18f);
            top = Math.max(126f, crop[1] * h + 22f);
        }
        float left = Math.max(32f, right - pillW);
        right = left + pillW;
        android.graphics.RectF pill = new android.graphics.RectF(left, top, right, top + pillH);

        zoomPillBgPaint.setStyle(Paint.Style.FILL);
        zoomPillBgPaint.setColor(Color.argb(188, 16, 20, 28));
        c.drawRoundRect(pill, 18f, 18f, zoomPillBgPaint);
        c.drawText(label, left + padX, top + 37f, zoomPillTextPaint);

        float barLeft = left + padX;
        float barTop = top + pillH - 10f;
        float barRight = right - padX;
        zoomProgressTrackPaint.setStyle(Paint.Style.STROKE);
        zoomProgressTrackPaint.setStrokeWidth(4f);
        zoomProgressTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        zoomProgressTrackPaint.setColor(Color.argb(95, 255, 255, 255));
        c.drawLine(barLeft, barTop, barRight, barTop, zoomProgressTrackPaint);
        zoomProgressFillPaint.setStyle(Paint.Style.STROKE);
        zoomProgressFillPaint.setStrokeWidth(4f);
        zoomProgressFillPaint.setStrokeCap(Paint.Cap.ROUND);
        zoomProgressFillPaint.setColor(Color.argb(220, 96, 165, 250));
        c.drawLine(barLeft, barTop, barLeft + (barRight - barLeft) * progress, barTop,
                zoomProgressFillPaint);
    }

    /**
     * Draws the template-specific composition guide lines (symmetry axis,
     * leading lines, golden spiral, horizon line, etc.) underneath the
     * Aim Reticle.
     */
    private void drawTemplateSketch(Canvas c, FramingTemplateOverlay overlay, float w, float h) {
        com.samsung.camera.intelligence.guidance.CompositionTemplate.Type t = overlay.getTemplateType();
        float[] p = overlay.getSketchParams();
        templateSketchPaint.setStyle(Paint.Style.STROKE);
        templateSketchPaint.setStrokeWidth(2.5f);
        templateSketchPaint.setColor(Color.argb(110, 255, 255, 255));
        templateSketchPaint.setPathEffect(null);

        switch (t) {
            case RULE_OF_THIRDS: {
                // Light 3x3 grid + 4 small dots, chosen one larger.
                templateSketchPaint.setColor(Color.argb(70, 255, 255, 255));
                c.drawLine(w / 3f, 0, w / 3f, h, templateSketchPaint);
                c.drawLine(2 * w / 3f, 0, 2 * w / 3f, h, templateSketchPaint);
                c.drawLine(0, h / 3f, w, h / 3f, templateSketchPaint);
                c.drawLine(0, 2 * h / 3f, w, 2 * h / 3f, templateSketchPaint);
                templateSketchPaint.setStyle(Paint.Style.FILL);
                templateSketchPaint.setColor(Color.argb(140, 255, 255, 255));
                float[][] pts = {{1f / 3f, 1f / 3f}, {2f / 3f, 1f / 3f}, {1f / 3f, 2f / 3f}, {2f / 3f, 2f / 3f}};
                float chosenU = (p.length >= 2) ? p[0] : 1f / 3f;
                float chosenV = (p.length >= 2) ? p[1] : 2f / 3f;
                for (float[] pt : pts) {
                    boolean chosen = Math.abs(pt[0] - chosenU) < 0.05f && Math.abs(pt[1] - chosenV) < 0.05f;
                    c.drawCircle(pt[0] * w, pt[1] * h, chosen ? 6f : 3f, templateSketchPaint);
                }
                break;
            }
            case SYMMETRY_VERTICAL: {
                float ax = (p.length >= 1 ? p[0] : 0.5f) * w;
                templateSketchPaint.setColor(Color.argb(160, 255, 255, 255));
                templateSketchPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{14f, 10f}, 0));
                c.drawLine(ax, 0, ax, h, templateSketchPaint);
                templateSketchPaint.setPathEffect(null);
                break;
            }
            case SYMMETRY_HORIZONTAL: {
                float ay = (p.length >= 1 ? p[0] : 0.5f) * h;
                templateSketchPaint.setColor(Color.argb(160, 255, 255, 255));
                templateSketchPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{14f, 10f}, 0));
                c.drawLine(0, ay, w, ay, templateSketchPaint);
                templateSketchPaint.setPathEffect(null);
                break;
            }
            case HORIZON_THIRDS: {
                float hy = (p.length >= 1 ? p[0] : 0.667f) * h;
                templateSketchPaint.setColor(Color.argb(180, 255, 235, 120));
                templateSketchPaint.setStrokeWidth(3f);
                c.drawLine(0, hy, w, hy, templateSketchPaint);
                break;
            }
            case LEADING_LINES_X: {
                // No drawn sketch — the bullseye + faint RoT grid already
                // communicate the recommended placement. Drawn fake X lines
                // were confusing per user feedback.
                break;
            }
            case DIAGONAL: {
                // No drawn sketch — same reasoning as LEADING_LINES_X.
                break;
            }
            case GOLDEN_SPIRAL: {
                drawGoldenSpiral(c, p, w, h);
                break;
            }
            case CENTERED: {
                templateSketchPaint.setColor(Color.argb(140, 255, 255, 255));
                templateSketchPaint.setStrokeWidth(2f);
                float r1 = Math.min(w, h) * 0.04f;
                float r2 = Math.min(w, h) * 0.10f;
                c.drawCircle(w * 0.5f, h * 0.5f, r1, templateSketchPaint);
                c.drawCircle(w * 0.5f, h * 0.5f, r2, templateSketchPaint);
                c.drawLine(w * 0.5f - r2 * 1.4f, h * 0.5f, w * 0.5f - r1, h * 0.5f, templateSketchPaint);
                c.drawLine(w * 0.5f + r1, h * 0.5f, w * 0.5f + r2 * 1.4f, h * 0.5f, templateSketchPaint);
                c.drawLine(w * 0.5f, h * 0.5f - r2 * 1.4f, w * 0.5f, h * 0.5f - r1, templateSketchPaint);
                c.drawLine(w * 0.5f, h * 0.5f + r1, w * 0.5f, h * 0.5f + r2 * 1.4f, templateSketchPaint);
                break;
            }
            case FRAME_WITHIN_FRAME: {
                if (p.length >= 4) {
                    templateSketchPaint.setColor(Color.argb(160, 255, 255, 255));
                    templateSketchPaint.setStrokeWidth(3f);
                    c.drawRect(p[0] * w, p[1] * h, (p[0] + p[2]) * w, (p[1] + p[3]) * h, templateSketchPaint);
                }
                break;
            }
        }
    }

    private void drawGoldenSpiral(Canvas c, float[] p, float w, float h) {
        // Approximate phi spiral as 4 quarter-circle arcs of decreasing radius.
        // p[4] = orientation 0..3 (which corner the spiral pole is in).
        templateSketchPaint.setColor(Color.argb(150, 255, 220, 120));
        templateSketchPaint.setStyle(Paint.Style.STROKE);
        templateSketchPaint.setStrokeWidth(2.5f);
        float bx = p.length >= 4 ? p[0] * w : 0f;
        float by = p.length >= 4 ? p[1] * h : 0f;
        float bw = p.length >= 4 ? p[2] * w : w;
        float bh = p.length >= 4 ? p[3] * h : h;
        int orient = p.length >= 5 ? Math.round(p[4]) : 0;
        // Draw a phi grid as a simple spiral approximation: nested rectangles
        // with arcs in the decreasing squares.
        float phi = 1.618f;
        float curW = bw, curH = bh;
        float curX = bx, curY = by;
        for (int i = 0; i < 5 && curW > 12f && curH > 12f; i++) {
            float side = Math.min(curW, curH) / phi;
            android.graphics.RectF arcRect;
            int corner = (orient + i) & 3;
            switch (corner) {
                case 0: // top-left
                    arcRect = new android.graphics.RectF(curX, curY, curX + side * 2, curY + side * 2);
                    c.drawArc(arcRect, 180f, 90f, false, templateSketchPaint);
                    curX += side; curW -= side;
                    break;
                case 1: // top-right
                    arcRect = new android.graphics.RectF(curX + curW - side * 2, curY, curX + curW, curY + side * 2);
                    c.drawArc(arcRect, 270f, 90f, false, templateSketchPaint);
                    curY += side; curH -= side;
                    break;
                case 2: // bottom-right
                    arcRect = new android.graphics.RectF(curX + curW - side * 2, curY + curH - side * 2, curX + curW, curY + curH);
                    c.drawArc(arcRect, 0f, 90f, false, templateSketchPaint);
                    curW -= side;
                    break;
                case 3: // bottom-left
                    arcRect = new android.graphics.RectF(curX, curY + curH - side * 2, curX + side * 2, curY + curH);
                    c.drawArc(arcRect, 90f, 90f, false, templateSketchPaint);
                    curH -= side;
                    break;
            }
        }
    }

    private void drawAimReticle(Canvas c, FramingTemplateOverlay overlay,
                                int templateColor, float w, float h) {
        float[] anchor = overlay.getAnchorNorm();
        if (anchor == null || anchor.length < 2) return;
        float ax = anchor[0] * w;
        float ay = anchor[1] * h;
        float ringR = Math.min(w, h) * 0.075f;
        float stroke = 8f;

        // Target ring (background track + colored progress arc).
        templateRingTrackPaint.setStyle(Paint.Style.STROKE);
        templateRingTrackPaint.setStrokeWidth(stroke);
        templateRingTrackPaint.setColor(Color.argb(110, 255, 255, 255));
        c.drawCircle(ax, ay, ringR, templateRingTrackPaint);

        templateRingArcPaint.setStyle(Paint.Style.STROKE);
        templateRingArcPaint.setStrokeWidth(stroke);
        templateRingArcPaint.setStrokeCap(Paint.Cap.ROUND);
        templateRingArcPaint.setColor(templateColor);
        android.graphics.RectF rf = new android.graphics.RectF(
                ax - ringR, ay - ringR, ax + ringR, ay + ringR);
        float sweep = 360f * Math.max(0f, Math.min(1f, matchScoreEma));
        c.drawArc(rf, -90f, sweep, false, templateRingArcPaint);

        // Filled bullseye dot in the ring centre.
        templateRingArcPaint.setStyle(Paint.Style.FILL);
        templateRingArcPaint.setColor(Color.argb(220, 255, 255, 255));
        c.drawCircle(ax, ay, 6f, templateRingArcPaint);

        // Live subject dot — coloured by template state.
        float[] live = getRenderedLiveSubjectCenter(overlay);
        if (live != null && live.length >= 2) {
            float lx = live[0] * w, ly = live[1] * h;
            templateRingArcPaint.setStyle(Paint.Style.FILL);
            templateRingArcPaint.setColor(templateColor);
            c.drawCircle(lx, ly, 12f, templateRingArcPaint);
            templateRingArcPaint.setStyle(Paint.Style.STROKE);
            templateRingArcPaint.setStrokeWidth(2f);
            templateRingArcPaint.setColor(Color.argb(200, 255, 255, 255));
            c.drawCircle(lx, ly, 13f, templateRingArcPaint);

            // When close, draw a tether line so the user sees how to nudge.
            float dist = (float) Math.hypot(lx - ax, ly - ay);
            if (dist > 6f && dist < ringR * 3.0f) {
                templateRingArcPaint.setStyle(Paint.Style.STROKE);
                templateRingArcPaint.setStrokeWidth(2f);
                templateRingArcPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{8f, 8f}, 0));
                templateRingArcPaint.setColor(Color.argb(140, 255, 255, 255));
                c.drawLine(lx, ly, ax, ay, templateRingArcPaint);
                templateRingArcPaint.setPathEffect(null);
            }
        }
    }

    @Nullable
    private float[] getRenderedLiveSubjectCenter(FramingTemplateOverlay overlay) {
        float[] target = overlay.getDisplayLiveSubjectCenterNorm();
        if (target == null || target.length < 2) {
            renderedLiveDotX = null;
            renderedLiveDotY = null;
            renderedLiveDotUpdatedMs = 0L;
            return null;
        }

        float targetX = clampNorm(target[0]);
        float targetY = clampNorm(target[1]);
        long now = System.currentTimeMillis();
        if (renderedLiveDotX == null || renderedLiveDotY == null || renderedLiveDotUpdatedMs == 0L) {
            renderedLiveDotX = targetX;
            renderedLiveDotY = targetY;
            renderedLiveDotUpdatedMs = now;
            return new float[]{targetX, targetY};
        }

        long dtMs = Math.max(1L, Math.min(64L, now - renderedLiveDotUpdatedMs));
        renderedLiveDotUpdatedMs = now;
        float dx = targetX - renderedLiveDotX;
        float dy = targetY - renderedLiveDotY;
        float dist = (float) Math.hypot(dx, dy);
        if (dist > 0.0015f) {
            float alpha = Math.max(0.08f, Math.min(0.36f, dtMs / 140f));
            renderedLiveDotX += alpha * dx;
            renderedLiveDotY += alpha * dy;
            postInvalidateOnAnimation();
        }
        return new float[]{renderedLiveDotX, renderedLiveDotY};
    }

    private void drawCoachPill(Canvas c, FramingTemplateOverlay overlay, float w, float h) {
        String hint = overlay.getCoachHint();
        if (hint == null || hint.isEmpty()) return;
        coachPillTextPaint.setColor(Color.WHITE);
        coachPillTextPaint.setTextSize(32f);
        // Use LEFT alignment for StaticLayout-based wrapping; the layout
        // itself is centered when we translate the canvas below.
        coachPillTextPaint.setTextAlign(Paint.Align.LEFT);
        coachPillTextPaint.setFakeBoldText(true);

        String text;
        if (!overlay.isTargetLocked()) {
            text = "Setting target";
        } else if (overlay.getState() == FramingTemplateOverlay.TemplateState.LOCKED) {
            text = "Aligned - ready";
        } else if (proHudVisible) {
            String shortName = overlay.getShortName();
            text = (shortName != null && !shortName.isEmpty())
                    ? "Target set - " + shortName + " - " + hint
                    : "Target set - " + hint;
        } else {
            text = "Target set - align subject";
        }

        // Constrain pill to ~62% of the screen so it never overlaps the
        // Settings button on the left or the Level indicator on the right.
        // Wrap long coach hints across up to 2 lines via StaticLayout.
        float padX = 24f, padY = 12f;
        int maxPillWidth = (int) Math.max(160f, Math.min(w - 320f, w * 0.62f));
        int innerWidth = (int) Math.max(80f, maxPillWidth - padX * 2);

        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), coachPillTextPaint, innerWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(2f, 1f)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build();

        int lineCount = layout.getLineCount();
        float pillW = innerWidth + padX * 2;
        float pillH = layout.getHeight() + padY * 2;
        // Push the pill down a touch so a 1-line message no longer rides on
        // top of the Level indicator at h*0.10. Multi-line messages grow
        // downward only.
        float pillCx = w * 0.5f;
        float pillTop = h * 0.13f;
        float radius = lineCount > 1 ? 22f : pillH * 0.5f;

        coachPillBgPaint.setStyle(Paint.Style.FILL);
        coachPillBgPaint.setColor(Color.argb(180, 0, 0, 0));
        android.graphics.RectF r = new android.graphics.RectF(
                pillCx - pillW * 0.5f, pillTop,
                pillCx + pillW * 0.5f, pillTop + pillH);
        c.drawRoundRect(r, radius, radius, coachPillBgPaint);

        c.save();
        c.translate(pillCx - innerWidth * 0.5f, pillTop + padY);
        layout.draw(c);
        c.restore();
    }

    /**
     * Draw the RAW multi-task model suggested_crop as a yellow dashed
     * rectangle plus a small "MTM crop" label so testers can see whether
     * the model's crop output is sensible vs. what the stabilizer ends up
     * showing.
     */
    private void drawRawSuggestedCrop(Canvas c, FramingTemplateOverlay overlay,
                                      float w, float h) {
        float[] raw = overlay.getRawSuggestedCropNorm();
        if (raw == null || raw.length < 4) return;
        if (raw[2] < 0.05f || raw[3] < 0.05f) return;
        externalCropPaint.setStyle(Paint.Style.STROKE);
        externalCropPaint.setStrokeWidth(3f);
        externalCropPaint.setColor(Color.argb(220, 255, 220, 60));
        externalCropPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{14f, 8f}, 0));
        c.drawRect(raw[0] * w, raw[1] * h,
                (raw[0] + raw[2]) * w, (raw[1] + raw[3]) * h, externalCropPaint);
        externalCropPaint.setPathEffect(null);
        externalLabelPaint.setColor(Color.argb(220, 255, 220, 60));
        externalLabelPaint.setTextSize(22f);
        externalLabelPaint.setFakeBoldText(true);
        c.drawText("MTM crop", raw[0] * w + 4f, raw[1] * h - 6f, externalLabelPaint);
    }

    /**
     * Draw external sub-model (U²-Netp + GAIC v2) outputs in distinct
     * colors so the user can A/B them against the main backbone heads.
     * U²-Netp bbox + center: cyan. GAIC v2 crop: magenta.
     */
    private void drawExternalComparison(Canvas c, FramingTemplateOverlay overlay, float w, float h) {
        float[] extBbox = overlay.getExternalSubjectBboxNorm();
        float[] extCenter = overlay.getExternalSubjectCenterNorm();
        float[] extCrop = overlay.getExternalCropNorm();

        if (extBbox != null && extBbox.length >= 4) {
            externalSubjectPaint.setStyle(Paint.Style.STROKE);
            externalSubjectPaint.setStrokeWidth(3f);
            externalSubjectPaint.setColor(Color.argb(220, 80, 230, 255));
            externalSubjectPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{12f, 6f}, 0));
            c.drawRect(extBbox[0] * w, extBbox[1] * h,
                    (extBbox[0] + extBbox[2]) * w, (extBbox[1] + extBbox[3]) * h,
                    externalSubjectPaint);
            externalSubjectPaint.setPathEffect(null);
            // Tag.
            externalLabelPaint.setColor(Color.argb(220, 80, 230, 255));
            externalLabelPaint.setTextSize(22f);
            externalLabelPaint.setFakeBoldText(true);
            c.drawText("U²-Netp", extBbox[0] * w + 4f, extBbox[1] * h - 6f, externalLabelPaint);
        }
        if (extCenter != null && extCenter.length >= 2) {
            externalSubjectPaint.setStyle(Paint.Style.FILL);
            externalSubjectPaint.setColor(Color.argb(255, 80, 230, 255));
            c.drawCircle(extCenter[0] * w, extCenter[1] * h, 7f, externalSubjectPaint);
            externalSubjectPaint.setStyle(Paint.Style.STROKE);
            externalSubjectPaint.setStrokeWidth(2f);
            externalSubjectPaint.setColor(Color.argb(220, 0, 0, 0));
            c.drawCircle(extCenter[0] * w, extCenter[1] * h, 8f, externalSubjectPaint);
        }
        if (extCrop != null && extCrop.length >= 4) {
            externalCropPaint.setStyle(Paint.Style.STROKE);
            externalCropPaint.setStrokeWidth(3f);
            externalCropPaint.setColor(Color.argb(220, 255, 96, 200));
            externalCropPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{6f, 6f}, 0));
            c.drawRect(extCrop[0] * w, extCrop[1] * h,
                    (extCrop[0] + extCrop[2]) * w, (extCrop[1] + extCrop[3]) * h,
                    externalCropPaint);
            externalCropPaint.setPathEffect(null);
            externalLabelPaint.setColor(Color.argb(220, 255, 96, 200));
            externalLabelPaint.setTextSize(22f);
            externalLabelPaint.setFakeBoldText(true);
            c.drawText("GAIC v2", extCrop[0] * w + 4f,
                    (extCrop[1] + extCrop[3]) * h - 6f, externalLabelPaint);
        }
    }

        /** Pro HUD: edge bars + match ring diagnostics. */
    private void drawProHudWidgets(Canvas c, FramingTemplateOverlay overlay,
                                   int templateColor, float w, float h) {
        drawEdgeNudgeBars(c, overlay.getPanHints(), w, h, templateColor);
        drawMatchRing(c, matchScoreEma, matchLocked, w, h, templateColor);
    }

    private void drawEdgeNudgeBars(Canvas c, float[] panHints, float w, float h, int color) {
        if (panHints == null || panHints.length < 4) return;
        float maxBar = Math.min(w, h) * 0.04f;
        float inset = 14f;
        nudgeBarPaint.setStyle(Paint.Style.FILL);

        // left
        float thickness = Math.min(maxBar, panHints[0] * 4f * maxBar);
        if (thickness > 2f) {
            nudgeBarPaint.setColor(withAlpha(color, 200));
            c.drawRect(inset, h * 0.25f, inset + thickness, h * 0.75f, nudgeBarPaint);
        }
        // top
        thickness = Math.min(maxBar, panHints[1] * 4f * maxBar);
        if (thickness > 2f) {
            nudgeBarPaint.setColor(withAlpha(color, 200));
            c.drawRect(w * 0.25f, inset, w * 0.75f, inset + thickness, nudgeBarPaint);
        }
        // right
        thickness = Math.min(maxBar, panHints[2] * 4f * maxBar);
        if (thickness > 2f) {
            nudgeBarPaint.setColor(withAlpha(color, 200));
            c.drawRect(w - inset - thickness, h * 0.25f, w - inset, h * 0.75f, nudgeBarPaint);
        }
        // bottom
        thickness = Math.min(maxBar, panHints[3] * 4f * maxBar);
        if (thickness > 2f) {
            nudgeBarPaint.setColor(withAlpha(color, 200));
            c.drawRect(w * 0.25f, h - inset - thickness, w * 0.75f, h - inset, nudgeBarPaint);
        }
    }

    private void drawMatchRing(Canvas c, float score, boolean locked, float w, float h, int color) {
        float radius = Math.min(w, h) * 0.058f;
        float cx = w - radius - 26f;
        float cy = h * 0.42f;
        float stroke = radius * 0.32f;

        matchRingTrackPaint.setStyle(Paint.Style.STROKE);
        matchRingTrackPaint.setStrokeWidth(stroke);
        matchRingTrackPaint.setColor(Color.argb(80, 255, 255, 255));
        c.drawCircle(cx, cy, radius, matchRingTrackPaint);

        matchRingProgressPaint.setStyle(Paint.Style.STROKE);
        matchRingProgressPaint.setStrokeWidth(stroke);
        matchRingProgressPaint.setStrokeCap(Paint.Cap.ROUND);
        matchRingProgressPaint.setColor(color);
        android.graphics.RectF rf = new android.graphics.RectF(
                cx - radius, cy - radius, cx + radius, cy + radius);
        c.drawArc(rf, -90f, 360f * Math.max(0f, Math.min(1f, score)), false, matchRingProgressPaint);

        matchRingTextPaint.setColor(Color.WHITE);
        matchRingTextPaint.setFakeBoldText(true);
        matchRingTextPaint.setTextSize(radius * 0.55f);
        matchRingTextPaint.setTextAlign(Paint.Align.CENTER);
        String label = locked ? "OK" : Integer.toString(Math.round(score * 100));
        c.drawText(label, cx, cy + radius * 0.20f, matchRingTextPaint);
        matchRingTextPaint.setTextSize(radius * 0.28f);
        matchRingTextPaint.setFakeBoldText(false);
        c.drawText(locked ? "ready" : "match", cx, cy + radius * 0.62f, matchRingTextPaint);
    }

    private int templateColorFor(float score, boolean locked) {
        if (locked) return Color.argb(255, 96, 220, 120);
        if (score >= 0.70f) return Color.argb(255, 255, 196, 64);
        return Color.argb(255, 255, 96, 96);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void triggerMatchLockHaptic() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) return;
            android.os.Vibrator vib;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.os.VibratorManager mgr =
                        (android.os.VibratorManager) ctx.getSystemService(
                                android.content.Context.VIBRATOR_MANAGER_SERVICE);
                vib = mgr == null ? null : mgr.getDefaultVibrator();
            } else {
                vib = (android.os.Vibrator) ctx.getSystemService(
                        android.content.Context.VIBRATOR_SERVICE);
            }
            if (vib == null || !vib.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(
                        20L, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vib.vibrate(20L);
            }
        } catch (Throwable ignore) {
            // Haptic is purely a nicety \u2014 never fail the draw because of it.
        }
    }

    /**
     * Phase 8-10 (Composition v2 - Plan A) - silhouette / ghost-frame
     * outline. Crop-only (no subject silhouette); the subject head is
     * intentionally OFF until a stronger segmentation backbone replaces
     * U2-Netp.
     */
    private void drawSilhouette(Canvas c, SilhouetteOverlay overlay, float w, float h) {
        float[] crop = overlay.getCropNorm();
        if (crop == null || crop.length < 4) return;
        float left = clampNorm(crop[0]) * w;
        float top = clampNorm(crop[1]) * h;
        float right = clampNorm(crop[0] + crop[2]) * w;
        float bottom = clampNorm(crop[1] + crop[3]) * h;
        if (right - left < 12f || bottom - top < 12f) return;

        SilhouetteOverlay.State s = overlay.getState();
        // Visual language:
        //   ARMING - faint dashed white frame, fading in via progress
        //   ACTIVE - solid dashed white frame
        //   LOCKED - green frame + faint fill, ready-to-shoot
        int strokeAlpha;
        int color;
        float[] dashes;
        switch (s) {
            case LOCKED:
                color = Color.argb(255, 70, 224, 126);
                dashes = null;
                strokeAlpha = 255;
                break;
            case ACTIVE:
                color = Color.argb(255, 255, 255, 255);
                dashes = new float[]{18f, 12f};
                strokeAlpha = 230;
                break;
            case ARMING:
            default:
                color = Color.argb(255, 255, 255, 255);
                dashes = new float[]{10f, 14f};
                strokeAlpha = (int) (140f + 90f * Math.max(0f, Math.min(1f, overlay.getProgress())));
                break;
        }
        silhouettePaint.setColor(color);
        silhouettePaint.setAlpha(strokeAlpha);
        silhouettePaint.setStrokeWidth(s == SilhouetteOverlay.State.LOCKED ? 6f : 5f);
        silhouettePaint.setPathEffect(dashes == null
                ? null : new android.graphics.DashPathEffect(dashes, 0f));
        c.drawRect(left, top, right, bottom, silhouettePaint);

        if (s == SilhouetteOverlay.State.LOCKED) {
            silhouetteFillPaint.setColor(Color.argb(36, 70, 224, 126));
            c.drawRect(left, top, right, bottom, silhouetteFillPaint);
        }

        // Phase 10 - corner brackets give the silhouette a stronger "frame"
        // affordance. Length = 8% of the shorter rect side, capped at 36px.
        // Use a non-dashed paint snapshot so the brackets are continuous
        // even when the rect itself is dashed.
        Paint bracket = new Paint(silhouettePaint);
        bracket.setPathEffect(null);
        float cornerLen = Math.min(36f, 0.08f * Math.min(right - left, bottom - top));
        c.drawLine(left, top, left + cornerLen, top, bracket);
        c.drawLine(left, top, left, top + cornerLen, bracket);
        c.drawLine(right, top, right - cornerLen, top, bracket);
        c.drawLine(right, top, right, top + cornerLen, bracket);
        c.drawLine(left, bottom, left + cornerLen, bottom, bracket);
        c.drawLine(left, bottom, left, bottom - cornerLen, bracket);
        c.drawLine(right, bottom, right - cornerLen, bottom, bracket);
        c.drawLine(right, bottom, right, bottom - cornerLen, bracket);
        silhouettePaint.setPathEffect(null);

        // Phase 10 - single haptic on the LOCKED edge transition. Reuse the
        // existing match-lock vibrator helper so we don't double-buzz when
        // FramingTemplateOverlay is also locked at the same instant.
        if (overlay.isLockTransition() && lastSilhouetteState != SilhouetteOverlay.State.LOCKED) {
            triggerMatchLockHaptic();
        }
        lastSilhouetteState = s;
    }

    private static float clampNorm(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private void drawSymmetry(Canvas c, SymmetryGuideOverlay overlay, float w, float h) {
        // Phase B.3 — bold center axis + faint mirror lines on either side
        Paint center = new Paint(accentPaint);
        center.setColor(Color.argb(220, 134, 220, 246));
        center.setStrokeWidth(4f);
        Paint mirror = new Paint(accentPaint);
        mirror.setColor(Color.argb(110, 134, 220, 246));
        mirror.setStrokeWidth(2f);
        mirror.setPathEffect(new android.graphics.DashPathEffect(new float[]{12f, 12f}, 0));

        float pos = Math.max(0.05f, Math.min(0.95f, overlay.getAxisPosition()));
        if ("horizontal".equalsIgnoreCase(overlay.getAxis())) {
            float y = pos * h;
            c.drawLine(0, y, w, y, center);
            c.drawLine(0, y - h * 0.10f, w, y - h * 0.10f, mirror);
            c.drawLine(0, y + h * 0.10f, w, y + h * 0.10f, mirror);
        } else {
            float x = pos * w;
            c.drawLine(x, 0, x, h, center);
            c.drawLine(x - w * 0.10f, 0, x - w * 0.10f, h, mirror);
            c.drawLine(x + w * 0.10f, 0, x + w * 0.10f, h, mirror);
        }
    }

    // ─── Phase B HUD widgets (composition score / fill ratio / color chips) ───

    private Float lastCompositionScore = null;
    private Float lastSubjectFillRatio = null;
    private List<String> lastDominantColors = null;
    private boolean lastSuggestBw = false;
    private String lastCompositionTip = null;
    private final Paint hudFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hudTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hudSmallTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Phase A.4/B — push the latest FrameAnalysis so the overlay can draw inline
     * HUD widgets (composition-score badge, fill-ratio meter, dominant-color
     * chips). Caller passes null when the toggle is off so the HUD vanishes.
     */
    public void applyFrameAnalysis(@Nullable FrameAnalysis analysis) {
        if (analysis == null) {
            lastCompositionScore = null;
            lastSubjectFillRatio = null;
            lastDominantColors = null;
            lastSuggestBw = false;
            lastCompositionTip = null;
        } else {
            float s = analysis.getCompositionScore();
            lastCompositionScore = (s > 0.0f) ? s : null;
            float fr = analysis.getSubjectFillRatio();
            lastSubjectFillRatio = (fr > 0.0f) ? fr : null;
            lastDominantColors = analysis.getDominantColors();
            lastSuggestBw = analysis.isSuggestBw();
        }
        invalidate();
    }

    public void setCompositionTip(@Nullable String tip) {
        this.lastCompositionTip = (tip == null || tip.trim().isEmpty()) ? null : tip.trim();
        invalidate();
    }

    private void drawCompositionHud(Canvas c, float w, float h) {
        // Score badge — bottom-right (Recommendation A3 position B "near level indicator")
        if (lastCompositionScore != null) {
            int score = Math.round(lastCompositionScore * 100f);
            int color;
            if (score >= 75) {
                color = Color.argb(230, 96, 220, 120);
            } else if (score >= 50) {
                color = Color.argb(230, 255, 208, 64);
            } else {
                color = Color.argb(230, 255, 138, 64);
            }
            float r = 52f;
            float cx = w - r - 32f;
            float cy = h * 0.52f;

            hudStrokePaint.setStyle(Paint.Style.STROKE);
            hudStrokePaint.setStrokeWidth(5f);
            hudStrokePaint.setColor(Color.argb(120, 0, 0, 0));
            c.drawCircle(cx, cy, r + 1f, hudStrokePaint);

            hudFillPaint.setStyle(Paint.Style.FILL);
            hudFillPaint.setColor(Color.argb(150, 0, 0, 0));
            c.drawCircle(cx, cy, r, hudFillPaint);

            hudStrokePaint.setColor(color);
            hudStrokePaint.setStrokeWidth(5.5f);
            c.drawCircle(cx, cy, r, hudStrokePaint);

            hudTextPaint.setColor(color);
            hudTextPaint.setTextSize(46f);
            hudTextPaint.setFakeBoldText(true);
            hudTextPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(score), cx, cy + 16f, hudTextPaint);

            hudSmallTextPaint.setColor(Color.argb(200, 255, 255, 255));
            hudSmallTextPaint.setTextSize(24f);
            hudSmallTextPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("score", cx, cy + r + 26f, hudSmallTextPaint);
        }

        // Fill-ratio mini gauge — left edge, vertical bar at ~35% height
        if (lastSubjectFillRatio != null) {
            float fr = Math.max(0f, Math.min(1f, lastSubjectFillRatio));
            float barX = 32f;
            float barTop = h * 0.35f;
            float barH = h * 0.22f;
            float barW = 8f;

            // Track
            hudFillPaint.setStyle(Paint.Style.FILL);
            hudFillPaint.setColor(Color.argb(120, 0, 0, 0));
            c.drawRoundRect(barX, barTop, barX + barW, barTop + barH, 4f, 4f, hudFillPaint);

            // Fill segment grows downward
            int barColor;
            if (fr < 0.15f || fr > 0.85f) {
                barColor = Color.argb(230, 255, 138, 64);
            } else if (fr < 0.25f || fr > 0.70f) {
                barColor = Color.argb(230, 255, 208, 64);
            } else {
                barColor = Color.argb(230, 96, 220, 120);
            }
            hudFillPaint.setColor(barColor);
            float fillH = barH * fr;
            c.drawRoundRect(barX, barTop + (barH - fillH), barX + barW, barTop + barH, 4f, 4f, hudFillPaint);

            // Sweet-spot ticks at 25% / 70%
            hudStrokePaint.setStyle(Paint.Style.STROKE);
            hudStrokePaint.setColor(Color.argb(180, 255, 255, 255));
            hudStrokePaint.setStrokeWidth(1.5f);
            c.drawLine(barX - 3f, barTop + barH * (1f - 0.25f), barX + barW + 3f, barTop + barH * (1f - 0.25f), hudStrokePaint);
            c.drawLine(barX - 3f, barTop + barH * (1f - 0.70f), barX + barW + 3f, barTop + barH * (1f - 0.70f), hudStrokePaint);

            hudSmallTextPaint.setColor(Color.argb(210, 255, 255, 255));
            hudSmallTextPaint.setTextSize(22f);
            hudSmallTextPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText("fill " + Math.round(fr * 100) + "%", barX - 4f, barTop - 12f, hudSmallTextPaint);
        }

        // Dominant-color chips strip — top-left under status bar
        if (lastDominantColors != null && !lastDominantColors.isEmpty()) {
            float chipSize = 22f;
            float gap = 6f;
            float originX = 32f;
            float originY = h * 0.30f - chipSize - 8f;
            int max = Math.min(5, lastDominantColors.size());
            hudFillPaint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < max; i++) {
                int chipColor = colorNameToInt(lastDominantColors.get(i));
                hudFillPaint.setColor(chipColor);
                float cx = originX + i * (chipSize + gap);
                c.drawRoundRect(cx, originY, cx + chipSize, originY + chipSize, 4f, 4f, hudFillPaint);
            }
            // B&W suggestion badge
            if (lastSuggestBw) {
                float bx = originX + max * (chipSize + gap) + 6f;
                float bwW = 46f;
                hudFillPaint.setColor(Color.argb(220, 30, 30, 30));
                c.drawRoundRect(bx, originY, bx + bwW, originY + chipSize, 5f, 5f, hudFillPaint);
                hudSmallTextPaint.setColor(Color.argb(255, 240, 240, 240));
                hudSmallTextPaint.setTextSize(16f);
                hudSmallTextPaint.setTextAlign(Paint.Align.CENTER);
                hudSmallTextPaint.setFakeBoldText(true);
                c.drawText("B&W", bx + bwW / 2f, originY + chipSize - 5f, hudSmallTextPaint);
                hudSmallTextPaint.setFakeBoldText(false);
            }
        }
    }

    private int colorNameToInt(String name) {
        if (name == null) return Color.GRAY;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "red":     return Color.argb(230, 230, 60, 60);
            case "orange":  return Color.argb(230, 255, 154, 40);
            case "yellow":  return Color.argb(230, 255, 219, 64);
            case "green":   return Color.argb(230, 96, 200, 96);
            case "cyan":    return Color.argb(230, 80, 200, 220);
            case "blue":    return Color.argb(230, 64, 130, 240);
            case "purple":  return Color.argb(230, 168, 96, 220);
            case "pink":    return Color.argb(230, 255, 140, 190);
            case "white":   return Color.argb(230, 235, 235, 235);
            case "black":   return Color.argb(230, 30, 30, 30);
            case "gray":
            case "grey":    return Color.argb(230, 140, 140, 140);
            default:        return Color.argb(230, 140, 140, 140);
        }
    }

    private void drawCompositionTipCard(Canvas c, float w, float h) {
        // Phase B.5 — anchored single-line composition tip card. Anchored to
        // top-of-subject-bbox if available, otherwise top-center.
        if (lastCompositionTip == null) {
            return;
        }
        String text = lastCompositionTip;
        if (text.length() > 60) {
            text = text.substring(0, 57) + "…";
        }
        hudTextPaint.setColor(Color.WHITE);
        hudTextPaint.setTextSize(36f);
        hudTextPaint.setFakeBoldText(false);
        hudTextPaint.setTextAlign(Paint.Align.LEFT);

        float tw = hudTextPaint.measureText(text);
        float padH = 22f;
        float padV = 14f;
        float boxW = tw + padH * 2f;
        float boxH = 48f + padV;

        float left, top;
        if (subjectBoundingBoxNorm != null && !templateActive) {
            float bx = subjectBoundingBoxNorm[0] * w;
            float by = subjectBoundingBoxNorm[1] * h;
            float bw = subjectBoundingBoxNorm[2] * w;
            left = Math.max(16f, Math.min(w - boxW - 16f, bx + bw / 2f - boxW / 2f));
            top = Math.max(140f, by - boxH - 14f);
        } else {
            // Template mode (or no bbox): pin to a stable top-center position
            // so the user can read it without it jumping with the subject.
            left = (w - boxW) / 2f;
            top = h * 0.10f;
        }

        hudFillPaint.setStyle(Paint.Style.FILL);
        hudFillPaint.setColor(Color.argb(180, 12, 36, 52));
        c.drawRoundRect(left, top, left + boxW, top + boxH, 14f, 14f, hudFillPaint);
        c.drawText(text, left + padH, top + boxH - padV - 2f, hudTextPaint);
    }

    private void drawTextOverlayCards(Canvas canvas, List<GuidanceOverlay> textOverlays, float width, float height) {
        // Text guidance is now shown in the guide_text TextView (top overlay bar).
        // Skip all Canvas-drawn text cards to avoid duplicate bottom-of-screen cards.
        return;
    }

    @SuppressWarnings("unused")
    private void drawTextOverlayCardsLegacy(Canvas canvas, List<GuidanceOverlay> textOverlays, float width, float height) {
        if (currentTextOverlays.isEmpty()) {
            if (fallbackMessage != null && !fallbackMessage.trim().isEmpty()) {
                drawFallbackCard(canvas, fallbackMessage.trim(), width, height);
            }
            return;
        }

        int idx = currentTextIndex % currentTextOverlays.size();
        GuidanceOverlay overlay = currentTextOverlays.get(idx);
        String body = buildOverlayBody(overlay);
        if (body == null || body.trim().isEmpty()) {
            return;
        }

        float sideMargin = 24f;
        float cardWidth = width - sideMargin * 2f;
        String badge = buildOverlayBadge(overlay);

        StaticLayout textLayout = StaticLayout.Builder.obtain(
                        body,
                        0,
                        body.length(),
                        textPaint,
                        (int) (cardWidth - 32f))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();

        float badgeHeight = badge == null ? 0f : 38f;
        float cardHeight = 20f + badgeHeight + textLayout.getHeight() + 20f;
        float bottomY = height - 220f;
        float topY = bottomY - cardHeight;
        if (topY < 300f) {
            topY = 300f;
        }

        cardPaint.setColor(resolveCardColor(overlay));
        canvas.drawRoundRect(sideMargin, topY, width - sideMargin, topY + cardHeight, 18f, 18f, cardPaint);

        float textTop = topY + 14f;
        if (badge != null) {
            float badgeLeft = sideMargin + 14f;
            float badgeTop = topY + 12f;
            float badgeTextWidth = badgePaint.measureText(badge);
            badgeFillPaint.setColor(resolveBadgeColor(overlay));
            canvas.drawRoundRect(
                    badgeLeft,
                    badgeTop,
                    badgeLeft + badgeTextWidth + 26f,
                    badgeTop + 30f,
                    15f,
                    15f,
                    badgeFillPaint);
            canvas.drawText(badge, badgeLeft + 13f, badgeTop + 21f, badgePaint);
            textTop += 42f;
        }

        canvas.save();
        canvas.translate(sideMargin + 16f, textTop);
        textLayout.draw(canvas);
        canvas.restore();

        // Draw page indicator dots
        if (currentTextOverlays.size() > 1) {
            float dotRadius = 4f;
            float dotSpacing = 14f;
            float totalDotsWidth = currentTextOverlays.size() * dotRadius * 2f
                    + (currentTextOverlays.size() - 1) * (dotSpacing - dotRadius * 2f);
            float dotStartX = width / 2f - totalDotsWidth / 2f;
            float dotY = topY + cardHeight + 12f;
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < currentTextOverlays.size(); i++) {
                dotPaint.setColor(i == idx ? Color.WHITE : Color.argb(100, 255, 255, 255));
                canvas.drawCircle(dotStartX + i * dotSpacing + dotRadius, dotY, dotRadius, dotPaint);
            }
        }
    }

    public void setFallbackMessage(String message) {
        this.fallbackMessage = message;
        if (currentTextOverlays.isEmpty()) {
            invalidate();
        }
    }

    private boolean isLandscapeRotation(int rotation) {
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
    }

    public void setDeviceLevelPose(float horizonAngleDeg,
                                   float pitchDeg,
                                   int displayRotation,
                                   boolean shootingLandscape) {
        this.deviceHorizonAngleDeg = horizonAngleDeg;
        this.devicePitchDeg = pitchDeg;
        this.deviceDisplayRotation = displayRotation;
        this.deviceShootingLandscape = shootingLandscape;
        invalidate();
    }

    private int overlayScore(GuidanceOverlay overlay) {
        int u = 0, c = 0;
        if (overlay.getUrgency() != null) {
            switch (overlay.getUrgency()) {
                case CRITICAL: u = 4; break;
                case WARNING:  u = 3; break;
                case SUGGESTION: u = 2; break;
                case INFO:     u = 1; break;
            }
        }
        if (overlay.getCategory() != null) {
            switch (overlay.getCategory()) {
                case OBSTRUCTION: c = 4; break;
                case TECHNICAL:   c = 3; break;
                case ANGLE:       c = 2; break;
                case COMPOSITION: c = 1; break;
            }
        }
        return u * 10 + c;
    }

    private String buildOverlayBadge(GuidanceOverlay overlay) {
        if (overlay instanceof AlertBadge) {
            return prettyUrgency(overlay) + " Alert";
        }
        if (overlay instanceof CompositionTipOverlay) {
            CompositionTipOverlay tip = (CompositionTipOverlay) overlay;
            if (tip.getTechniqueName() != null && !tip.getTechniqueName().isEmpty()) {
                return tip.getTechniqueName();
            }
            return "Composition";
        }
        if (overlay instanceof AngleSuggestion) {
            return "Angle";
        }
        if (overlay instanceof DirectionArrow) {
            return prettyUrgency(overlay) + " | " + prettyCategory(overlay);
        }
        if (overlay instanceof MasterMatchOverlay) {
            return "MasterMatch";
        }
        return prettyCategory(overlay);
    }

    private String buildOverlayBody(GuidanceOverlay overlay) {
        if (overlay == null) {
            return null;
        }
        if (overlay instanceof CompositionTipOverlay) {
            CompositionTipOverlay tip = (CompositionTipOverlay) overlay;
            if (tip.getTipText() != null && !tip.getTipText().trim().isEmpty()) {
                return tip.getTipText().trim();
            }
        }
        if (overlay instanceof MasterMatchOverlay) {
            MasterMatchOverlay match = (MasterMatchOverlay) overlay;
            String title = match.getPhotoTitle() == null ? "" : match.getPhotoTitle().trim();
            String author = match.getPhotographerName() == null ? "" : match.getPhotographerName().trim();
            if (!title.isEmpty() && !author.isEmpty()) {
                return title + "\nBy " + author;
            }
        }
        return overlay.getMessage();
    }

    private int resolveCardColor(GuidanceOverlay overlay) {
        if (overlay instanceof AlertBadge) {
            return Color.argb(205, 64, 48, 10);
        }
        if (overlay instanceof DirectionArrow) {
            return Color.argb(195, 52, 38, 12);
        }
        if (overlay instanceof CompositionTipOverlay) {
            return Color.argb(190, 12, 36, 52);
        }
        if (overlay instanceof MasterMatchOverlay) {
            return Color.argb(195, 28, 24, 56);
        }
        return Color.argb(170, 0, 0, 0);
    }

    private int resolveBadgeColor(GuidanceOverlay overlay) {
        if (overlay instanceof AlertBadge) {
            return Color.argb(255, 245, 196, 64);
        }
        if (overlay instanceof DirectionArrow) {
            return Color.argb(255, 255, 180, 64);
        }
        if (overlay instanceof MasterMatchOverlay) {
            return Color.argb(255, 154, 140, 255);
        }
        return Color.argb(255, 118, 214, 255);
    }

    private void drawFallbackCard(Canvas canvas, String text, float width, float height) {
        float sideMargin = 24f;
        float cardWidth = width - sideMargin * 2f;

        StaticLayout textLayout = StaticLayout.Builder.obtain(
                        text, 0, text.length(), textPaint, (int) (cardWidth - 32f))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();

        float cardHeight = 20f + textLayout.getHeight() + 20f;
        float bottomY = height - 220f;
        float topY = bottomY - cardHeight;
        if (topY < 300f) topY = 300f;

        cardPaint.setColor(Color.argb(170, 0, 0, 0));
        canvas.drawRoundRect(sideMargin, topY, width - sideMargin, topY + cardHeight, 18f, 18f, cardPaint);

        canvas.save();
        canvas.translate(sideMargin + 16f, topY + 14f);
        textLayout.draw(canvas);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        rotateHandler.removeCallbacks(rotateRunnable);
    }

    private String prettyUrgency(GuidanceOverlay overlay) {
        if (overlay == null || overlay.getUrgency() == null) {
            return "Info";
        }
        switch (overlay.getUrgency()) {
            case CRITICAL:
                return "Critical";
            case WARNING:
                return "Warning";
            case SUGGESTION:
                return "Suggestion";
            case INFO:
            default:
                return "Info";
        }
    }

    private String prettyCategory(GuidanceOverlay overlay) {
        if (overlay == null || overlay.getCategory() == null) {
            return "Guide";
        }
        switch (overlay.getCategory()) {
            case COMPOSITION:
                return "Composition";
            case TECHNICAL:
                return "Technical";
            case ANGLE:
                return "Angle";
            case OBSTRUCTION:
                return "Obstruction";
            default:
                return "Guide";
        }
    }

    // ----------------------------------------------------------------
    // Phase 4 (Composition v2) — consumer chip + horizon level guide
    // ----------------------------------------------------------------

    private void drawCompositionAdviceChip(Canvas c, float w, float h) {
        CompositionAdvice advice = this.compositionAdvice;
        if (advice == null) return;
        long now = System.currentTimeMillis();
        long age = now - compositionAdviceShownAt;
        long ttl = Math.max(500L, advice.ttlMs);
        if (age >= ttl) {
            this.compositionAdvice = null;
            return;
        }

        // Prefer the chip label (very short), fall back to the coach hint
        // truncated to ~16 CJK characters so the pill never wraps.
        String text = advice.hasChip() ? advice.chipLabel : advice.coachHint;
        if (text == null || text.isEmpty()) text = advice.secondaryTip;
        if (text == null || text.isEmpty()) return;
        if (text.length() > 18) text = text.substring(0, 17) + "…";

        // Fade-in over first 180ms, fade-out over last 400ms.
        float alpha = 1f;
        if (age < 180L) {
            alpha = age / 180f;
        } else if (ttl - age < 400L) {
            alpha = Math.max(0f, (ttl - age) / 400f);
        }

        float padX = 22f;
        float padY = 12f;
        chipTextPaint.setTextSize(30f);
        float textW = chipTextPaint.measureText(text);
        Paint.FontMetrics fm = chipTextPaint.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float pillW = textW + padX * 2f;
        float pillH = textH + padY * 2f;
        float left = (w - pillW) / 2f;
        float top = h - pillH - 96f; // sit above the bottom shutter row
        if (top < h * 0.62f) top = h * 0.62f;
        android.graphics.RectF pill = new android.graphics.RectF(
                left, top, left + pillW, top + pillH);

        int bgColor = severityColor(advice.severity);
        chipBgPaint.setColor(applyAlpha(bgColor, alpha));
        c.drawRoundRect(pill, pillH / 2f, pillH / 2f, chipBgPaint);

        chipTextPaint.setColor(applyAlpha(Color.WHITE, alpha));
        float baseline = top + padY - fm.ascent;
        c.drawText(text, w / 2f, baseline, chipTextPaint);

        // Schedule a redraw shortly so the fade animates without external ticks.
        postInvalidateDelayed(80L);
    }

    private static int severityColor(CompositionAdvice.Severity sev) {
        if (sev == null) return Color.argb(190, 16, 20, 28);
        switch (sev) {
            case CRITICAL: return Color.argb(220, 200, 50, 50);
            case WARN:     return Color.argb(210, 220, 130, 30);
            case COACH:    return Color.argb(200, 16, 20, 28);
            case INFO:
            default:       return Color.argb(170, 16, 20, 28);
        }
    }

    private static int applyAlpha(int argb, float scale) {
        int a = (int) (Color.alpha(argb) * Math.max(0f, Math.min(1f, scale)));
        return Color.argb(a, Color.red(argb), Color.green(argb), Color.blue(argb));
    }

    /**
     * Phase 4 — when {@code |tilt_angle| > 1.5°} draw a dashed level
     * reference line and a solid tilted line. Hidden once horizon is level
     * to avoid clutter; the device-sensor level indicator continues to
     * provide finer feedback in the corner.
     */
    private void drawHorizonLevelGuide(Canvas c, FrameAnalysis a, float w, float h) {
        float angle = a.getTiltAngle();
        if (Float.isNaN(angle) || Math.abs(angle) <= 1.5f) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float halfLen = Math.min(w, h) * 0.32f;

        // Reference (dashed level).
        horizonGuidePaint.setColor(Color.argb(120, 255, 255, 255));
        horizonGuidePaint.setPathEffect(
                new android.graphics.DashPathEffect(new float[]{14f, 10f}, 0));
        horizonGuidePaint.setStrokeWidth(3f);
        c.drawLine(cx - halfLen, cy, cx + halfLen, cy, horizonGuidePaint);

        // Tilted line: warm amber if > 1.5°, red-ish if > 5°.
        horizonGuidePaint.setPathEffect(null);
        horizonGuidePaint.setStrokeWidth(4.5f);
        int color = Math.abs(angle) > 5f
                ? Color.argb(220, 230, 90, 60)
                : Color.argb(220, 245, 170, 70);
        horizonGuidePaint.setColor(color);
        double rad = Math.toRadians(angle);
        float dx = (float) (Math.cos(rad) * halfLen);
        float dy = (float) (Math.sin(rad) * halfLen);
        c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, horizonGuidePaint);
    }
}
