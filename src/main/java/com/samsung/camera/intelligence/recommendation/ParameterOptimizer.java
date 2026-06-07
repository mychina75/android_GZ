package com.samsung.camera.intelligence.recommendation;

import com.samsung.camera.intelligence.models.*;

/**
 * Advanced parameter optimizer for camera settings.
 * Ported from Python ParameterOptimizer in recommendation/parameter_optimizer.py.
 */
public class ParameterOptimizer {

    /**
     * Optimize ISO value based on lighting and noise tolerance.
     */
    public static int optimizeIso(SceneAnalysisResult scene) {
        float lux = scene.getEstimatedLux() != null ? scene.getEstimatedLux() : 5000f;
        float noiseTolerance = scene.getNoiseLevel();

        int baseIso;
        if (lux < 10) baseIso = 3200;
        else if (lux < 100) baseIso = 1600;
        else if (lux < 500) baseIso = 800;
        else if (lux < 2000) baseIso = 400;
        else if (lux < 10000) baseIso = 200;
        else baseIso = 100;

        // Adjust for noise tolerance
        if (noiseTolerance > 0.3f) {
            baseIso = Math.max(100, baseIso / 2);
        }

        return baseIso;
    }

    /**
     * Optimize shutter speed based on motion and lighting.
     */
    public static String optimizeShutterSpeed(SceneAnalysisResult scene) {
        MotionType motion = scene.getMotionType();
        LightingCondition lighting = scene.getLightingCondition();

        int baseShutter;
        if (motion == MotionType.VERY_FAST) baseShutter = 1000;
        else if (motion == MotionType.FAST) baseShutter = 500;
        else if (motion == MotionType.NORMAL) baseShutter = 250;
        else if (motion == MotionType.SLOW) baseShutter = 60;
        else baseShutter = 125;

        // Adjust for lighting
        if (lighting == LightingCondition.VERY_LOW_LIGHT || lighting == LightingCondition.LOW_LIGHT) {
            baseShutter = Math.max(30, baseShutter / 2);
        } else if (lighting == LightingCondition.BRIGHT || lighting == LightingCondition.VERY_BRIGHT) {
            baseShutter = Math.min(4000, baseShutter * 2);
        }

        return "1/" + baseShutter;
    }

    /**
     * Optimize white balance based on lighting condition.
     */
    public static String optimizeWhiteBalance(SceneAnalysisResult scene) {
        LightingCondition lighting = scene.getLightingCondition();

        if (lighting == LightingCondition.ARTIFICIAL) return "tungsten";
        if (lighting == LightingCondition.CLOUDY) return "cloudy";
        if (lighting == LightingCondition.GOLDEN_HOUR) return "shade";
        if (lighting == LightingCondition.BLUE_HOUR) return "shade";
        if (lighting == LightingCondition.INDOOR) return "fluorescent";
        return "auto";
    }

    /**
     * Optimize ND filter strength for motion blur effects.
     */
    public static int optimizeNdFilterStrength(SceneAnalysisResult scene) {
        LightingCondition lighting = scene.getLightingCondition();
        SceneType sceneType = scene.getSceneType();

        if (lighting == LightingCondition.BRIGHT || lighting == LightingCondition.VERY_BRIGHT) {
            if (sceneType == SceneType.WATERFALL) return 64;
            else return 16;
        } else if (lighting == LightingCondition.NORMAL) {
            return 8;
        } else {
            return 4;
        }
    }
}
