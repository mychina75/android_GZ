package com.samsung.camera.intelligence.guidance;

/**
 * Phase A — composition template metadata for the consumer-friendly
 * "Aim & Capture" framing UX. Identifies the visual style of guidance
 * sketch the renderer should overlay (e.g. rule-of-thirds dot grid,
 * vertical symmetry axis, golden spiral curve, leading-line X, etc.)
 * along with template-specific sketch parameters.
 *
 * The enum is intentionally small. Extending it requires:
 *   1. Add a new {@link Type} entry.
 *   2. Add a routing rule in {@link TemplateChooser}.
 *   3. Add a renderer branch in
 *      {@code GuidanceOverlayView#drawTemplateSketch}.
 */
public final class CompositionTemplate {

    public enum Type {
        /** Default — 3x3 grid with the chosen power-point dot enlarged. */
        RULE_OF_THIRDS,
        /** Vertical symmetry axis through the centre. */
        SYMMETRY_VERTICAL,
        /** Horizontal symmetry axis through the centre. */
        SYMMETRY_HORIZONTAL,
        /** Single horizon line at 1/3 (sky-heavy) or 2/3 (ground-heavy). */
        HORIZON_THIRDS,
        /** Two diagonals from screen corners converging on the anchor. */
        LEADING_LINES_X,
        /** One bold diagonal line through the anchor (corner-to-corner-ish). */
        DIAGONAL,
        /** Bezier-approximated phi spiral with subject at the spiral pole. */
        GOLDEN_SPIRAL,
        /** Concentric crosshair at frame centre — for product/macro/food. */
        CENTERED,
        /** Inset rectangle indicating a natural "frame" within the scene. */
        FRAME_WITHIN_FRAME
    }

    /** What template was picked. */
    public final Type type;
    /** Subject anchor in normalized [0..1] coords — where the white dot should land. */
    public final float[] anchorNorm;
    /**
     * Template-specific sketch params:
     *   - SYMMETRY_VERTICAL  : [axisX]
     *   - SYMMETRY_HORIZONTAL: [axisY]
     *   - HORIZON_THIRDS     : [horizonY]
     *   - LEADING_LINES_X    : [x1,y1,x2,y2, x3,y3,x4,y4] two segments
     *   - DIAGONAL           : [x1,y1,x2,y2]
     *   - GOLDEN_SPIRAL      : [boundsX, boundsY, boundsW, boundsH, orientation]
     *                          orientation ∈ {0,1,2,3} for 4 spiral rotations
     *   - CENTERED           : [] (renderer uses fixed cross-hair at 0.5,0.5)
     *   - FRAME_WITHIN_FRAME : [insetX, insetY, insetW, insetH]
     *   - RULE_OF_THIRDS     : [chosenU, chosenV] — the highlighted power-point
     */
    public final float[] sketchParams;

    /** Short human description for the badge ("Symmetry", "Golden Spiral"…). */
    public final String shortName;

    public CompositionTemplate(Type type, float[] anchorNorm,
                               float[] sketchParams, String shortName) {
        this.type = type;
        this.anchorNorm = anchorNorm;
        this.sketchParams = sketchParams == null ? new float[0] : sketchParams;
        this.shortName = shortName == null ? "" : shortName;
    }
}
