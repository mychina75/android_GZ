package com.samsung.camera.intelligence.app.camera;

import java.util.Locale;

public class CameraWorkflowStateMachine {

    public enum Mode {
        PHOTO,
        VIDEO,
        PRO,
        PRO_VIDEO,
        NIGHT,
        PORTRAIT,
        FOOD,
        MACRO,
        PANORAMA,
        SINGLE_TAKE,
        SLOW_MOTION,
        HYPERLAPSE,
        DUAL_RECORDING,
        PORTRAIT_VIDEO,
        MIMIC,
        UNKNOWN
    }

    /** Modes disabled in demo (video-related). */
    private static final java.util.Set<Mode> DISABLED_MODES = java.util.EnumSet.of(
            Mode.VIDEO, Mode.PRO_VIDEO, Mode.PORTRAIT_VIDEO
    );

    /** Modes that map to PHOTO in demo. */
    private static final java.util.Map<Mode, Mode> DEMO_MODE_MAP;
    static {
        java.util.Map<Mode, Mode> map = new java.util.EnumMap<>(Mode.class);
        map.put(Mode.SINGLE_TAKE, Mode.PHOTO);
        map.put(Mode.HYPERLAPSE, Mode.PHOTO);
        map.put(Mode.SLOW_MOTION, Mode.PHOTO);
        map.put(Mode.DUAL_RECORDING, Mode.PHOTO);
        DEMO_MODE_MAP = java.util.Collections.unmodifiableMap(map);
    }

    /** Returns the effective demo mode, mapping disabled/remapped modes. */
    public static Mode toDemoMode(Mode mode) {
        if (mode == null) return Mode.PHOTO;
        if (DISABLED_MODES.contains(mode)) return Mode.PHOTO;
        Mode mapped = DEMO_MODE_MAP.get(mode);
        return mapped != null ? mapped : mode;
    }

    /** Demo-visible modes for the mode picker. */
    public static Mode[] getDemoModes() {
        return new Mode[]{
            Mode.PHOTO,
            Mode.PORTRAIT,
            Mode.PRO,
            Mode.NIGHT,
            Mode.FOOD,
            Mode.MACRO,
            Mode.PANORAMA,
        };
    }

    private Mode currentMode = Mode.PHOTO;

    public Mode getCurrentMode() {
        return currentMode;
    }

    public String getCurrentModeName() {
        return toModeName(currentMode);
    }

    public void forceMode(Mode mode) {
        this.currentMode = mode == null ? Mode.UNKNOWN : mode;
    }

    public void updateFromRecommendation(String modeName) {
        this.currentMode = fromModeName(modeName);
    }

    public boolean allowsVideoRecording() {
        return currentMode == Mode.VIDEO || currentMode == Mode.PRO_VIDEO
                || currentMode == Mode.SLOW_MOTION || currentMode == Mode.HYPERLAPSE
                || currentMode == Mode.DUAL_RECORDING || currentMode == Mode.PORTRAIT_VIDEO;
    }

    public boolean prefersManualExposure() {
        return currentMode == Mode.PRO || currentMode == Mode.PRO_VIDEO
                || currentMode == Mode.MIMIC;
    }

    public static Mode fromModeName(String modeName) {
        if (modeName == null) {
            return Mode.UNKNOWN;
        }
        String m = modeName.trim().toLowerCase(Locale.ROOT);
        switch (m) {
            case "photo":
                return Mode.PHOTO;
            case "video":
                return Mode.VIDEO;
            case "pro":
                return Mode.PRO;
            case "pro_video":
                return Mode.PRO_VIDEO;
            case "night":
                return Mode.NIGHT;
            case "portrait":
                return Mode.PORTRAIT;
            case "food":
                return Mode.FOOD;
            case "macro":
                return Mode.MACRO;
            case "panorama":
                return Mode.PANORAMA;
            case "single_take":
                return Mode.SINGLE_TAKE;
            case "slow_motion":
                return Mode.SLOW_MOTION;
            case "hyperlapse":
                return Mode.HYPERLAPSE;
            case "dual_recording":
                return Mode.DUAL_RECORDING;
            case "portrait_video":
                return Mode.PORTRAIT_VIDEO;
            case "mimic":
            case "mastermatch":
                return Mode.MIMIC;
            default:
                return Mode.UNKNOWN;
        }
    }

    public static String toModeName(Mode mode) {
        if (mode == null) {
            return "Photo";
        }
        switch (mode) {
            case PHOTO:
                return "Photo";
            case VIDEO:
                return "Video";
            case PRO:
                return "Pro";
            case PRO_VIDEO:
                return "Pro_video";
            case NIGHT:
                return "Night";
            case PORTRAIT:
                return "Portrait";
            case FOOD:
                return "Food";
            case MACRO:
                return "Macro";
            case PANORAMA:
                return "Panorama";
            case SINGLE_TAKE:
                return "Single_take";
            case SLOW_MOTION:
                return "Slow_motion";
            case HYPERLAPSE:
                return "Hyperlapse";
            case DUAL_RECORDING:
                return "Dual_recording";
            case PORTRAIT_VIDEO:
                return "Portrait_video";
            case MIMIC:
                return "Mimic";
            default:
                return "Photo";
        }
    }
}
