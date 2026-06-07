package com.samsung.camera.intelligence.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Complete tool recommendation result.
 * Ported from Python ToolRecommendationResult dataclass in models/enums.py.
 */
public class ToolRecommendationResult {

    private SceneAnalysisResult sceneAnalysis;
    private CameraApp recommendedApp;
    private List<ToolRecommendation> tools;
    private ToolRecommendation captureTool;
    private List<ToolRecommendation> postProcessingTools;

    // Alternative recommendations
    private List<ToolRecommendation> alternativeTools;
    private CameraApp alternativeApp;

    // Validation info
    private List<String> validationConflicts;
    private List<String> removedToolsDueToConflicts;
    private List<String> alternativeValidationConflicts;
    private List<String> alternativeRemovedToolsDueToConflicts;

    public ToolRecommendationResult() {
        this.tools = new ArrayList<>();
        this.postProcessingTools = new ArrayList<>();
        this.alternativeTools = new ArrayList<>();
        this.validationConflicts = new ArrayList<>();
        this.removedToolsDueToConflicts = new ArrayList<>();
        this.alternativeValidationConflicts = new ArrayList<>();
        this.alternativeRemovedToolsDueToConflicts = new ArrayList<>();
    }

    public ToolRecommendationResult(SceneAnalysisResult sceneAnalysis,
                                    CameraApp recommendedApp,
                                    List<ToolRecommendation> tools,
                                    ToolRecommendation captureTool,
                                    List<ToolRecommendation> postProcessingTools) {
        this.sceneAnalysis = sceneAnalysis;
        this.recommendedApp = recommendedApp;
        this.tools = tools != null ? tools : new ArrayList<>();
        this.captureTool = captureTool;
        this.postProcessingTools = postProcessingTools != null ? postProcessingTools : new ArrayList<>();
        this.alternativeTools = new ArrayList<>();
        this.validationConflicts = new ArrayList<>();
        this.removedToolsDueToConflicts = new ArrayList<>();
        this.alternativeValidationConflicts = new ArrayList<>();
        this.alternativeRemovedToolsDueToConflicts = new ArrayList<>();
    }

    /**
     * Get tools ordered by priority.
     */
    public List<ToolRecommendation> getOrderedTools() {
        List<ToolRecommendation> allTools = new ArrayList<>(tools);
        if (captureTool != null) {
            allTools.add(captureTool);
        }
        allTools.addAll(postProcessingTools);
        Collections.sort(allTools, Comparator.comparingInt(ToolRecommendation::getPriority));
        return allTools;
    }

    // Getters and Setters
    public SceneAnalysisResult getSceneAnalysis() { return sceneAnalysis; }
    public void setSceneAnalysis(SceneAnalysisResult sceneAnalysis) { this.sceneAnalysis = sceneAnalysis; }

    public CameraApp getRecommendedApp() { return recommendedApp; }
    public void setRecommendedApp(CameraApp recommendedApp) { this.recommendedApp = recommendedApp; }

    public List<ToolRecommendation> getTools() { return tools; }
    public void setTools(List<ToolRecommendation> tools) { this.tools = tools; }

    public ToolRecommendation getCaptureTool() { return captureTool; }
    public void setCaptureTool(ToolRecommendation captureTool) { this.captureTool = captureTool; }

    public List<ToolRecommendation> getPostProcessingTools() { return postProcessingTools; }
    public void setPostProcessingTools(List<ToolRecommendation> postProcessingTools) { this.postProcessingTools = postProcessingTools; }

    public List<ToolRecommendation> getAlternativeTools() { return alternativeTools; }
    public void setAlternativeTools(List<ToolRecommendation> alternativeTools) { this.alternativeTools = alternativeTools; }

    public CameraApp getAlternativeApp() { return alternativeApp; }
    public void setAlternativeApp(CameraApp alternativeApp) { this.alternativeApp = alternativeApp; }

    public List<String> getValidationConflicts() { return validationConflicts; }
    public void setValidationConflicts(List<String> validationConflicts) { this.validationConflicts = validationConflicts; }

    public List<String> getRemovedToolsDueToConflicts() { return removedToolsDueToConflicts; }
    public void setRemovedToolsDueToConflicts(List<String> removedToolsDueToConflicts) { this.removedToolsDueToConflicts = removedToolsDueToConflicts; }

    public List<String> getAlternativeValidationConflicts() { return alternativeValidationConflicts; }
    public void setAlternativeValidationConflicts(List<String> alternativeValidationConflicts) { this.alternativeValidationConflicts = alternativeValidationConflicts; }

    public List<String> getAlternativeRemovedToolsDueToConflicts() { return alternativeRemovedToolsDueToConflicts; }
    public void setAlternativeRemovedToolsDueToConflicts(List<String> alternativeRemovedToolsDueToConflicts) { this.alternativeRemovedToolsDueToConflicts = alternativeRemovedToolsDueToConflicts; }
}
