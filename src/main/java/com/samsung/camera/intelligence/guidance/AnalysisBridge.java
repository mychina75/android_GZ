package com.samsung.camera.intelligence.guidance;

import com.samsung.camera.intelligence.models.SceneAnalysisResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridge — converts between recommendation-layer and guidance-layer types.
 *
 * The recommendation pipeline uses {@link SceneAnalysisResult} (rich enums)
 * while the guidance pipeline uses {@link FrameAnalysis} (lightweight, string-based).
 * This class provides bidirectional conversion so both pipelines can interoperate.
 *
 * Also provides a convenience method to decode a raw model-output map
 * into a {@link FrameAnalysis} without instantiating a full FrameAnalyzer.
 *
 * Ported from Python guidance/bridge.py
 */
public class AnalysisBridge {

    /**
     * Convert a {@link SceneAnalysisResult} (from recommendation.scene_parser)
     * into a {@link FrameAnalysis} (for guidance.OverlayGenerator).
     *
     * @param scene A SceneAnalysisResult instance (enum-typed)
     * @return Equivalent FrameAnalysis with string-typed classifications
     */
    public static FrameAnalysis sceneAnalysisToFrameAnalysis(SceneAnalysisResult scene) {
        FrameAnalysis fa = new FrameAnalysis();

        // Classifications — SceneAnalysisResult stores enums; FrameAnalysis stores string values
        fa.setSceneType(enumValue(scene.getSceneType()));
        fa.setSceneConfidence(scene.getSceneTypeConfidence());
        fa.setLightingCondition(enumValue(scene.getLightingCondition()));
        fa.setLightingConfidence(scene.getLightingConfidence());
        fa.setMotionType(enumValue(scene.getMotionType()));
        fa.setMotionConfidence(scene.getMotionConfidence());
        fa.setMainSubject(enumValue(scene.getMainSubject()));
        fa.setSubjectConfidence(scene.getSubjectConfidence());

        // Quality metrics
        fa.setContrastValue(scene.getContrastValue());
        fa.setSharpnessValue(scene.getSharpnessValue());
        fa.setNoiseLevel(scene.getNoiseLevel());

        // Composition
        fa.setCompositionScore(scene.getCompositionScore());
        fa.setNeedsCompositionEdit(scene.isNeedsCompositionEdit());
        fa.setCompositionIssues(
                scene.getCompositionIssues() != null
                        ? new ArrayList<>(scene.getCompositionIssues())
                        : new ArrayList<>()
        );
        fa.setTilted(scene.isTilted());
        fa.setTiltAngle(scene.getTiltAngle());

        // Subject location — SceneAnalysisResult stores bounding box [x, y, w, h] normalized.
        // FrameAnalysis stores the center.
        float[] bb = scene.getSubjectBoundingBox();
        if (bb != null && bb.length >= 4) {
            fa.setSubjectCenterX(bb[0] + bb[2] / 2.0f);
            fa.setSubjectCenterY(bb[1] + bb[3] / 2.0f);
        }

        // Binary flags
        fa.setHasFace(scene.isHasFace());
        fa.setFaceCount(scene.getFaceCount());
        fa.setHasText(scene.isHasText());
        fa.setHasShadow(scene.isHasShadow());
        fa.setHasReflection(scene.isHasReflection());
        fa.setHasBackgroundPeople(scene.isHasBackgroundPeople());
        fa.setHasFlare(scene.isHasFlare());
        fa.setHasMoire(scene.isHasMoire());

        // Motion
        fa.setFlowMagnitude(scene.getMotionSpeed() != null ? scene.getMotionSpeed() : 0.0f);

        // Lighting
        fa.setEstimatedLux(scene.getEstimatedLux());

        // Feature embedding (for aesthetic transfer / master match)
        // Python uses siglip_features for the embedding; Java featureEmbedding is equivalent
        if (scene.getSiglipFeatures() != null) {
            fa.setFeatureEmbedding(scene.getSiglipFeatures());
        } else {
            fa.setFeatureEmbedding(scene.getFeatureEmbedding());
        }

        // Color analysis — primarily populated by CV (color_guide) but
        // SceneAnalysisResult may carry them when pre-computed offline.
        // Default to empty / false (matching Python bridge.py behavior).

        // Phase 1b composition features — may be present on SceneAnalysisResult
        // when pre-computed via the model; default to safe values otherwise.
        fa.setHasSymmetry(scene.isHasSymmetry());
        fa.setHasDiagonalLines(scene.isHasDiagonalLines());
        fa.setHasLeadingLines(scene.isHasLeadingLines());
        fa.setSubjectFillRatio(scene.getSubjectFillRatio());
        fa.setSubjectCount(scene.getSubjectCount());
        fa.setVisualComplexity(scene.getVisualComplexity());
        fa.setSceneDepthLayers(scene.getSceneDepthLayers());

        fa.setBrightnessValue(scene.getBrightnessValue());
        fa.setBlurLevel(scene.getBlurLevel());

        return fa;
    }

    /**
     * Convenience: decode a raw model-output map into a FrameAnalysis.
     * Delegates to a default FrameAnalyzer.decode() internally.
     *
     * @param outputs Raw key→value model outputs
     * @return Decoded FrameAnalysis
     */
    public static FrameAnalysis outputsDictToFrameAnalysis(Map<String, float[]> outputs) {
        com.samsung.camera.intelligence.inference.FrameAnalyzer analyzer =
                new com.samsung.camera.intelligence.inference.FrameAnalyzer();
        return analyzer.decode(outputs);
    }

    // ---- Helper ----

    /**
     * Return the .getValue() of an enum, or toString() for non-enums.
     */
    private static String enumValue(Object enumObj) {
        if (enumObj == null) return "unknown";
        try {
            // All our enums implement getValue()
            java.lang.reflect.Method m = enumObj.getClass().getMethod("getValue");
            Object val = m.invoke(enumObj);
            return val != null ? val.toString() : enumObj.toString();
        } catch (Exception e) {
            return enumObj.toString();
        }
    }
}
