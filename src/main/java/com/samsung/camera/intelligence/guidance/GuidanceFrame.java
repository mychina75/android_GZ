package com.samsung.camera.intelligence.guidance;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete per-frame guidance output.
 * Contains the list of overlay directives that a mobile UI should render,
 * together with timing metadata.
 * Ported from Python GuidanceFrame dataclass in guidance/enums.py.
 */
public class GuidanceFrame {

    private List<GuidanceOverlay> overlays;
    private int frameIndex;
    private double timestampMs;
    private double pipelineLatencyMs;
    private double modelLatencyMs;
    private FrameAnalysis analysis;

    public GuidanceFrame() {
        this.overlays = new ArrayList<>();
        this.frameIndex = 0;
        this.timestampMs = 0.0;
        this.pipelineLatencyMs = 0.0;
        this.modelLatencyMs = 0.0;
    }

    /**
     * Get all alert badges.
     */
    public List<AlertBadge> getAlerts() {
        List<AlertBadge> alerts = new ArrayList<>();
        for (GuidanceOverlay overlay : overlays) {
            if (overlay instanceof AlertBadge) {
                alerts.add((AlertBadge) overlay);
            }
        }
        return alerts;
    }

    /**
     * Check if any overlay is critical.
     */
    public boolean hasCritical() {
        for (GuidanceOverlay overlay : overlays) {
            if (overlay.getUrgency() == GuidanceUrgency.CRITICAL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert to map for serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("frame_index", frameIndex);
        map.put("timestamp_ms", timestampMs);
        map.put("pipeline_latency_ms", Math.round(pipelineLatencyMs * 100.0) / 100.0);
        map.put("model_latency_ms", Math.round(modelLatencyMs * 100.0) / 100.0);
        map.put("overlay_count", overlays.size());

        List<Map<String, Object>> overlayList = new ArrayList<>();
        for (GuidanceOverlay overlay : overlays) {
            overlayList.add(overlay.toMap());
        }
        map.put("overlays", overlayList);
        return map;
    }

    /**
     * Convert to JSON string.
     */
    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("frame_index", frameIndex);
            json.put("timestamp_ms", timestampMs);
            json.put("pipeline_latency_ms", Math.round(pipelineLatencyMs * 100.0) / 100.0);
            json.put("model_latency_ms", Math.round(modelLatencyMs * 100.0) / 100.0);
            json.put("overlay_count", overlays.size());

            JSONArray overlayArray = new JSONArray();
            for (GuidanceOverlay overlay : overlays) {
                overlayArray.put(new JSONObject(overlay.toMap()));
            }
            json.put("overlays", overlayArray);
            return json.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    // Getters and Setters
    public List<GuidanceOverlay> getOverlays() { return overlays; }
    public void setOverlays(List<GuidanceOverlay> overlays) { this.overlays = overlays; }

    public int getFrameIndex() { return frameIndex; }
    public void setFrameIndex(int frameIndex) { this.frameIndex = frameIndex; }

    public double getTimestampMs() { return timestampMs; }
    public void setTimestampMs(double timestampMs) { this.timestampMs = timestampMs; }

    public double getPipelineLatencyMs() { return pipelineLatencyMs; }
    public void setPipelineLatencyMs(double pipelineLatencyMs) { this.pipelineLatencyMs = pipelineLatencyMs; }

    public double getModelLatencyMs() { return modelLatencyMs; }
    public void setModelLatencyMs(double modelLatencyMs) { this.modelLatencyMs = modelLatencyMs; }

    public FrameAnalysis getAnalysis() { return analysis; }
    public void setAnalysis(FrameAnalysis analysis) { this.analysis = analysis; }
}
