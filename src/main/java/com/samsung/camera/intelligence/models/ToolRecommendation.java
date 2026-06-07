package com.samsung.camera.intelligence.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool recommendation result with parameters.
 * Ported from Python ToolRecommendation dataclass in models/enums.py.
 */
public class ToolRecommendation {

    private String toolName;
    private String toolServer; // Camera, ExpertRaw, Gallery, PhotoEditor
    private int priority; // 1 = highest priority
    private Map<String, Object> parameters;
    private String reason;
    private float confidence;
    private List<String> prerequisites;
    private List<String> incompatibleWith;

    public ToolRecommendation() {
        this.parameters = new HashMap<>();
        this.reason = "";
        this.confidence = 0.0f;
        this.prerequisites = new ArrayList<>();
        this.incompatibleWith = new ArrayList<>();
    }

    public ToolRecommendation(String toolName, String toolServer, int priority,
                              Map<String, Object> parameters, String reason,
                              float confidence) {
        this.toolName = toolName;
        this.toolServer = toolServer;
        this.priority = priority;
        this.parameters = parameters != null ? parameters : new HashMap<>();
        this.reason = reason != null ? reason : "";
        this.confidence = confidence;
        this.prerequisites = new ArrayList<>();
        this.incompatibleWith = new ArrayList<>();
    }

    // Getters and Setters
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getToolServer() { return toolServer; }
    public void setToolServer(String toolServer) { this.toolServer = toolServer; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public float getConfidence() { return confidence; }
    public void setConfidence(float confidence) { this.confidence = confidence; }

    public List<String> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(List<String> prerequisites) { this.prerequisites = prerequisites; }

    public List<String> getIncompatibleWith() { return incompatibleWith; }
    public void setIncompatibleWith(List<String> incompatibleWith) { this.incompatibleWith = incompatibleWith; }
}
