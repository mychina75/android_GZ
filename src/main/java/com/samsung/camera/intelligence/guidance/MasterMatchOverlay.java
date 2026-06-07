package com.samsung.camera.intelligence.guidance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aesthetic transfer card showing a matched professional photograph.
 */
public class MasterMatchOverlay extends GuidanceOverlay {

    private String photographerName;
    private String photoTitle;
    private String photoId;
    private float similarityScore;
    private List<String> styleTags;
    private Map<String, Object> proModeParams;
    private String thumbnailUrl;

    public MasterMatchOverlay() {
        super(GuidanceCategory.COMPOSITION, GuidanceUrgency.SUGGESTION, "");
        this.overlayType = "master_match";
        this.photographerName = "";
        this.photoTitle = "";
        this.photoId = "";
        this.similarityScore = 0.0f;
        this.styleTags = new ArrayList<>();
        this.proModeParams = new HashMap<>();
        this.thumbnailUrl = "";
    }

    public MasterMatchOverlay(String photographerName, String photoTitle, String photoId,
                              float similarityScore, List<String> styleTags,
                              Map<String, Object> proModeParams, String thumbnailUrl) {
        super(GuidanceCategory.COMPOSITION, GuidanceUrgency.SUGGESTION, "");
        this.overlayType = "master_match";
        this.photographerName = photographerName;
        this.photoTitle = photoTitle;
        this.photoId = photoId;
        this.similarityScore = similarityScore;
        this.styleTags = styleTags != null ? styleTags : new ArrayList<>();
        this.proModeParams = proModeParams != null ? proModeParams : new HashMap<>();
        this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : "";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("photographer_name", photographerName);
        map.put("photo_title", photoTitle);
        map.put("photo_id", photoId);
        map.put("similarity_score", Math.round(similarityScore * 1000.0f) / 1000.0f);
        map.put("style_tags", styleTags);
        map.put("pro_mode_params", proModeParams);
        map.put("thumbnail_url", thumbnailUrl);
        return map;
    }

    // Getters and setters
    public String getPhotographerName() { return photographerName; }
    public void setPhotographerName(String photographerName) { this.photographerName = photographerName; }

    public String getPhotoTitle() { return photoTitle; }
    public void setPhotoTitle(String photoTitle) { this.photoTitle = photoTitle; }

    public String getPhotoId() { return photoId; }
    public void setPhotoId(String photoId) { this.photoId = photoId; }

    public float getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(float similarityScore) { this.similarityScore = similarityScore; }

    public List<String> getStyleTags() { return styleTags; }
    public void setStyleTags(List<String> styleTags) { this.styleTags = styleTags; }

    public Map<String, Object> getProModeParams() { return proModeParams; }
    public void setProModeParams(Map<String, Object> proModeParams) { this.proModeParams = proModeParams; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
}
