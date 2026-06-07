package com.samsung.camera.intelligence.guidance;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Rule-of-thirds or golden-ratio grid lines.
 */
public class GridOverlay extends GuidanceOverlay {

    private String gridType;
    private List<float[]> powerPoints;
    // Diagonal guide-line endpoints for golden-triangles (pairs of [x1, y1, x2, y2])
    private List<float[]> diagonalPoints;

    public GridOverlay() {
        super(GuidanceCategory.COMPOSITION, GuidanceUrgency.INFO, "");
        this.overlayType = "grid";
        this.gridType = "rule_of_thirds";
        this.powerPoints = Arrays.asList(
            new float[]{1.0f/3, 1.0f/3},
            new float[]{2.0f/3, 1.0f/3},
            new float[]{1.0f/3, 2.0f/3},
            new float[]{2.0f/3, 2.0f/3}
        );
        this.diagonalPoints = null;
    }

    public GridOverlay(GuidanceCategory category, GuidanceUrgency urgency, String message) {
        super(category, urgency, message);
        this.overlayType = "grid";
        this.gridType = "rule_of_thirds";
        this.powerPoints = Arrays.asList(
            new float[]{1.0f/3, 1.0f/3},
            new float[]{2.0f/3, 1.0f/3},
            new float[]{1.0f/3, 2.0f/3},
            new float[]{2.0f/3, 2.0f/3}
        );
        this.diagonalPoints = null;
    }

    public GridOverlay(GuidanceCategory category, GuidanceUrgency urgency, String message,
                       String gridType, List<float[]> powerPoints, List<float[]> diagonalPoints) {
        super(category, urgency, message);
        this.overlayType = "grid";
        this.gridType = gridType;
        this.powerPoints = powerPoints;
        this.diagonalPoints = diagonalPoints;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("grid_type", gridType);
        map.put("power_points", powerPoints);
        if (diagonalPoints != null) {
            map.put("diagonal_points", diagonalPoints);
        }
        return map;
    }

    public String getGridType() { return gridType; }
    public void setGridType(String gridType) { this.gridType = gridType; }

    public List<float[]> getPowerPoints() { return powerPoints; }
    public void setPowerPoints(List<float[]> powerPoints) { this.powerPoints = powerPoints; }

    public List<float[]> getDiagonalPoints() { return diagonalPoints; }
    public void setDiagonalPoints(List<float[]> diagonalPoints) { this.diagonalPoints = diagonalPoints; }
}
