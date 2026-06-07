package com.samsung.camera.intelligence.recommendation;

import android.util.Log;

import com.samsung.camera.intelligence.models.*;
import com.samsung.camera.intelligence.trigger.NativeBridge;

import java.util.*;

/**
 * Maps professional EXIF parameters to phone Pro Mode parameters.
 * Ported from Python ExposureMapper in recommendation/exposure_mapper.py.
 *
 * EV100 = log2(N^2 / t) - log2(ISO / 100)
 */
public class ExposureMapper {

    private static final String TAG = "ExposureMapper";

    // Backward-compatible default quantization tables
    private static final int[] DEFAULT_VALID_ISOS = {50, 100, 200, 400, 800, 1600, 3200};
    private static final String[] DEFAULT_VALID_SHUTTERS = {
            "1/12000", "1/8000", "1/6000", "1/4000", "1/3200", "1/2500", "1/2000",
            "1/1600", "1/1250", "1/1000", "1/800", "1/640", "1/500", "1/400",
            "1/320", "1/250", "1/200", "1/160", "1/125", "1/100", "1/80", "1/60",
            "1/50", "1/40", "1/30", "1/25", "1/20", "1/15", "1/13", "1/10",
            "1/8", "1/6", "1/5", "1/4", "1/3", "1/2.5", "1/2", "1/1.6", "1/1.3",
            "1", "1.3", "1.6", "2", "2.5", "3", "4", "5", "6", "8", "10",
            "13", "15", "20", "25", "30"
    };

    /**
     * Camera sensor profile for a phone model.
     */
    public static class SensorProfile {
        public final String sensorName;
        public final float fixedAperture;
        public final int baseIso;
        public final int maxUsableIso;
        public final float minShutterSeconds;
        public final float maxShutterSeconds;
        public final int[] validIsos;
        public final String[] validShutters;

        public SensorProfile(String sensorName,
                             float fixedAperture,
                             int baseIso,
                             int maxUsableIso,
                             float minShutterSeconds,
                             float maxShutterSeconds,
                             int[] validIsos,
                             String[] validShutters) {
            this.sensorName = sensorName;
            this.fixedAperture = fixedAperture;
            this.baseIso = baseIso;
            this.maxUsableIso = maxUsableIso;
            this.minShutterSeconds = minShutterSeconds;
            this.maxShutterSeconds = maxShutterSeconds;
            this.validIsos = validIsos != null ? validIsos : DEFAULT_VALID_ISOS;
            this.validShutters = validShutters != null ? validShutters : DEFAULT_VALID_SHUTTERS;
        }
    }

    /**
     * Samsung model profiles.
     *
     * S25U/S26U profiles are predictive assumptions and should be calibrated
     * against final firmware/HAL values.
     */
    public static final class SensorProfiles {
        public static final SensorProfile S24U_MAIN = new SensorProfile(
                "S24U_HP2_MAIN",
                1.7f,
                50,
                3200,
                shutterToSeconds("1/12000"),
                shutterToSeconds("30"),
                Arrays.copyOf(DEFAULT_VALID_ISOS, DEFAULT_VALID_ISOS.length),
                Arrays.copyOf(DEFAULT_VALID_SHUTTERS, DEFAULT_VALID_SHUTTERS.length)
        );

        public static final SensorProfile S25U_MAIN = new SensorProfile(
                "S25U_HP2_MAIN_PREDICTED",
                1.7f,
                50,
                3200,
                shutterToSeconds("1/12000"),
                shutterToSeconds("30"),
                Arrays.copyOf(DEFAULT_VALID_ISOS, DEFAULT_VALID_ISOS.length),
                Arrays.copyOf(DEFAULT_VALID_SHUTTERS, DEFAULT_VALID_SHUTTERS.length)
        );

        public static final SensorProfile S26U_MAIN = new SensorProfile(
                "S26U_HP2_MAIN_PREDICTED",
                1.4f,
                50,
                3200,
                shutterToSeconds("1/12000"),
                shutterToSeconds("30"),
                Arrays.copyOf(DEFAULT_VALID_ISOS, DEFAULT_VALID_ISOS.length),
                Arrays.copyOf(DEFAULT_VALID_SHUTTERS, DEFAULT_VALID_SHUTTERS.length)
        );

        private SensorProfiles() {}

        public static SensorProfile fromModel(String model) {
            if (model == null) {
                return S24U_MAIN;
            }
            String m = model.toLowerCase(Locale.ROOT).replace("-", " ").replace("_", " ");
            if (m.contains("s26") || m.contains("sm s948") || m.contains("sm-s948")) {
                return S26U_MAIN;
            }
            if (m.contains("s25") || m.contains("sm s938") || m.contains("sm-s938")) {
                return S25U_MAIN;
            }
            if (m.contains("s24") || m.contains("sm s928") || m.contains("sm-s928")) {
                return S24U_MAIN;
            }
            return S24U_MAIN;
        }
    }

    private final SensorProfile sensorProfile;
    private final float fixedAperture;
    private final int baseIso;
    private final int maxUsableIso;
    private final float minShutterSeconds;
    private final float maxShutterSeconds;
    private final int[] validIsos;
    private final String[] validShutters;

    public ExposureMapper() {
        this(SensorProfiles.S24U_MAIN);
    }

    /**
     * Model-based constructor skeleton for automatic profile selection.
     */
    public ExposureMapper(String deviceModel) {
        this(SensorProfiles.fromModel(deviceModel));
    }

    public ExposureMapper(SensorProfile profile) {
        SensorProfile p = profile != null ? profile : SensorProfiles.S24U_MAIN;
        this.sensorProfile = p;
        this.fixedAperture = p.fixedAperture;
        this.baseIso = p.baseIso;
        this.maxUsableIso = p.maxUsableIso;
        this.minShutterSeconds = p.minShutterSeconds;
        this.maxShutterSeconds = p.maxShutterSeconds;
        this.validIsos = p.validIsos;
        this.validShutters = p.validShutters;
    }

    // Backward-compatible constructor
    public ExposureMapper(float fixedAperture, int baseIso, int maxUsableIso,
                          float minShutterSeconds, float maxShutterSeconds) {
        this(new SensorProfile(
                "custom",
                fixedAperture,
                baseIso,
                maxUsableIso,
                minShutterSeconds,
                maxShutterSeconds,
                Arrays.copyOf(DEFAULT_VALID_ISOS, DEFAULT_VALID_ISOS.length),
                Arrays.copyOf(DEFAULT_VALID_SHUTTERS, DEFAULT_VALID_SHUTTERS.length)
        ));
    }

    // Noise advantage in stops for different sensor types
    private static final Map<String, Float> SENSOR_NOISE_ADVANTAGE;
    static {
        SENSOR_NOISE_ADVANTAGE = new HashMap<>();
        SENSOR_NOISE_ADVANTAGE.put("medium_format", 4.0f);
        SENSOR_NOISE_ADVANTAGE.put("full_frame", 3.0f);
        SENSOR_NOISE_ADVANTAGE.put("apsc", 2.0f);
        SENSOR_NOISE_ADVANTAGE.put("micro_four_thirds", 1.5f);
        SENSOR_NOISE_ADVANTAGE.put("mobile", 0.0f);
    }

    public SensorProfile getSensorProfile() {
        return sensorProfile;
    }

    /**
     * Map reference EXIF parameters to phone Pro Mode parameters.
     */
    public ProModeParameters mapToPhone(
            float refAperture, String refShutterSpeed, int refIso,
            Integer refWbKelvin, String sensorType,
            float currentNoise, String currentLighting, String currentMotion) {

        String lighting = normalizeLabel(currentLighting);
        String motion = normalizeLabel(currentMotion);

        if (NativeBridge.isAvailable() && supportsNativeMapping()) {
            try {
                float[] nativeOut = NativeBridge.mapExposure(
                        fixedAperture,
                        baseIso,
                        maxUsableIso,
                        minShutterSeconds,
                        maxShutterSeconds,
                        refAperture,
                        refShutterSpeed,
                        refIso,
                        refWbKelvin != null ? refWbKelvin : -1,
                        sensorType != null ? sensorType : "full_frame",
                        currentNoise,
                        lighting,
                        motion);
                if (nativeOut != null && nativeOut.length >= 6) {
                    int qIso = Math.round(nativeOut[0]);
                    int shutterIndex = Math.max(0, Math.min(DEFAULT_VALID_SHUTTERS.length - 1,
                            Math.round(nativeOut[1])));
                    float evOffset = nativeOut[2];
                    WhiteBalanceMode wbMode = whiteBalanceModeFromCode(Math.round(nativeOut[3]));
                    int wbKelvinValue = Math.round(nativeOut[4]);
                    Integer wbKelvin = wbKelvinValue > 0 ? wbKelvinValue : null;
                    MeteringMode metering = meteringModeFromCode(Math.round(nativeOut[5]));
                    return new ProModeParameters(
                            qIso,
                            DEFAULT_VALID_SHUTTERS[shutterIndex],
                            evOffset,
                            wbMode,
                            wbKelvin,
                            FocusMode.MULTI_POINT,
                            null,
                            metering);
                }
            } catch (Throwable t) {
                Log.w(TAG, "native exposure mapping failed, using Java fallback", t);
            }
        }

        float refShutterS = shutterToSeconds(refShutterSpeed);
        float refEv = computeEv(refAperture, refShutterS, refIso);

        int targetIso = chooseIso(refIso, currentNoise, lighting, sensorType);

        float evPlusIso = refEv + log2(targetIso / 100.0f);
        float targetShutterS = (fixedAperture * fixedAperture) / (float) Math.pow(2, evPlusIso);

        targetShutterS = Math.max(minShutterSeconds, Math.min(maxShutterSeconds, targetShutterS));

        if (targetShutterS <= minShutterSeconds) {
            targetIso = adjustIsoForBright(refEv, targetShutterS);
        } else if (targetShutterS >= maxShutterSeconds) {
            targetIso = adjustIsoForDark(refEv, targetShutterS);
        }

        float preMotionShutter = targetShutterS;
        targetShutterS = motionCompensate(targetShutterS, motion);

        if (targetShutterS < preMotionShutter) {
            try {
                float neededIso = 100.0f * (float) Math.pow(2,
                        log2((fixedAperture * fixedAperture) / targetShutterS) - refEv);
                targetIso = (int) Math.max(baseIso, Math.min(maxUsableIso, neededIso));
            } catch (Exception e) {
                // keep previous
            }
        }

        // Guard rail for low-light static scenes (e.g. indoor architecture/product/document):
        // avoid overly fast shutters and negative EV that frequently cause dark/noisy captures.
        if (isLowLightLike(lighting) && isMostlyStatic(motion)) {
            final float handheldPreferredMin = 1.0f / 60.0f;
            if (targetShutterS < handheldPreferredMin) {
                targetShutterS = handheldPreferredMin;
                targetIso = computeIsoForTargetEv(refEv, targetShutterS);
            }
        }

        int qIso = quantizeIsoForProfile(targetIso);
        String qShutter = quantizeShutterForProfile(targetShutterS);

        float actualEv = computeEv(fixedAperture, shutterToSeconds(qShutter), qIso);
        float evOffset = refEv - actualEv;
        evOffset = Math.max(-3.0f, Math.min(3.0f, Math.round(evOffset * 2) / 2.0f));
        if (isLowLightLike(lighting) && evOffset < 0f) {
            evOffset = 0f;
        }

        WhiteBalanceMode wbMode = WhiteBalanceMode.AUTO;
        Integer wbKelvin = null;
        if (refWbKelvin != null) {
            Object[] wbResult = mapWhiteBalance(refWbKelvin);
            wbMode = (WhiteBalanceMode) wbResult[0];
            wbKelvin = (Integer) wbResult[1];
        }

        FocusMode focusMode = FocusMode.MULTI_POINT;
        MeteringMode metering = chooseMeteringMode(lighting);

        return new ProModeParameters(qIso, qShutter, evOffset, wbMode,
                wbKelvin, focusMode, null, metering);
    }

    /**
     * Convenience overload used by MasterMatchGuide.
     */
    public Map<String, Object> mapToPhone(
            Map<String, Object> exif,
            float currentNoise,
            String currentLighting,
            String currentMotion) {

        float refAperture = 2.8f;
        String refShutterSpeed = "1/125";
        int refIso = 200;
        Integer refWbKelvin = null;
        String sensorType = "full_frame";

        if (exif != null) {
            Object apertureObj = exif.get("aperture");
            if (apertureObj instanceof Number) {
                refAperture = ((Number) apertureObj).floatValue();
            }

            Object shutterObj = exif.get("shutter_speed");
            if (shutterObj != null) {
                refShutterSpeed = shutterObj.toString();
            }

            Object isoObj = exif.get("iso");
            if (isoObj instanceof Number) {
                refIso = ((Number) isoObj).intValue();
            }

            Object wbObj = exif.get("white_balance_kelvin");
            if (wbObj instanceof Number) {
                refWbKelvin = ((Number) wbObj).intValue();
            }

            Object sensorObj = exif.get("sensor_type");
            if (sensorObj != null) {
                sensorType = sensorObj.toString();
            }
        }

        ProModeParameters params = mapToPhone(
                refAperture,
                refShutterSpeed,
                refIso,
                refWbKelvin,
                sensorType,
                currentNoise,
                currentLighting,
                currentMotion
        );
        Map<String, Object> result = params.toMap();

        // Pass through tone style parameters from master photo data
        if (exif != null) {
            passIfPresent(result, exif, "tone_contrast", "contrast");
            passIfPresent(result, exif, "tone_highlights", "highlights");
            passIfPresent(result, exif, "tone_shadows", "shadows");
            passIfPresent(result, exif, "tone_saturation", "saturation");
            passIfPresent(result, exif, "tone_highlight_warmth", "highlight_warmth");
            passIfPresent(result, exif, "tone_shadow_tint", "shadow_tint");
            // Absolute brightness / key target + 3-zone split-tone (format_version >= 4)
            passIfPresent(result, exif, "tone_target_brightness", "target_brightness");
            passIfPresent(result, exif, "tone_key_strength", "key_strength");
            passIfPresent(result, exif, "split_shadow_wc", "split_shadow_wc");
            passIfPresent(result, exif, "split_shadow_gm", "split_shadow_gm");
            passIfPresent(result, exif, "split_mid_wc", "split_mid_wc");
            passIfPresent(result, exif, "split_mid_gm", "split_mid_gm");
            passIfPresent(result, exif, "split_high_wc", "split_high_wc");
            passIfPresent(result, exif, "split_high_gm", "split_high_gm");
        }
        return result;
    }

    private static void passIfPresent(Map<String, Object> dst, Map<String, Object> src,
                                      String srcKey, String dstKey) {
        Object val = src.get(srcKey);
        if (val instanceof Number) {
            dst.put(dstKey, ((Number) val).floatValue());
        }
    }

    private int chooseIso(int refIso, float noise, String lighting, String sensorType) {
        float advantageStops = SENSOR_NOISE_ADVANTAGE.containsKey(sensorType)
                ? SENSOR_NOISE_ADVANTAGE.get(sensorType) : 2.0f;
        float effectiveStops = advantageStops / 2.0f;
        float target = refIso / (float) Math.pow(2, effectiveStops);
        target = Math.max(baseIso, target);

        if (noise > 0.4f) target = Math.max(baseIso, target / 2);

        if ("very_low_light".equals(lighting) || "low_light".equals(lighting) || "blue_hour".equals(lighting)) {
            target = Math.max(target, 400);
        } else if ("indoor".equals(lighting) || "artificial".equals(lighting)
                || "mixed".equals(lighting) || "cloudy".equals(lighting)
                || "golden_hour".equals(lighting)) {
            target = Math.max(target, 200);
        } else if ("bright".equals(lighting) || "very_bright".equals(lighting)) {
            target = Math.min(target, 100);
        }

        return (int) Math.max(baseIso, Math.min(maxUsableIso, target));
    }

    private int computeIsoForTargetEv(float refEv, float targetShutterS) {
        try {
            float iso = 100.0f * (float) Math.pow(2,
                    log2((fixedAperture * fixedAperture) / targetShutterS) - refEv);
            return (int) Math.max(baseIso, Math.min(maxUsableIso, iso));
        } catch (Exception e) {
            return baseIso;
        }
    }

    private int adjustIsoForBright(float refEv, float minShutter) {
        try {
            float log2Iso100 = log2((fixedAperture * fixedAperture) / minShutter) - refEv;
            float iso = 100.0f * (float) Math.pow(2, log2Iso100);
            return (int) Math.max(baseIso, Math.min(maxUsableIso, iso));
        } catch (Exception e) {
            return baseIso;
        }
    }

    private int adjustIsoForDark(float refEv, float maxShutter) {
        try {
            float log2Iso100 = log2((fixedAperture * fixedAperture) / maxShutter) - refEv;
            float iso = 100.0f * (float) Math.pow(2, log2Iso100);
            return (int) Math.max(baseIso, Math.min(maxUsableIso, iso));
        } catch (Exception e) {
            return maxUsableIso;
        }
    }

    private float motionCompensate(float shutterS, String motion) {
        Map<String, Float> limits = new HashMap<>();
        limits.put("very_fast", 1.0f / 1000);
        limits.put("fast", 1.0f / 500);
        limits.put("normal", 1.0f / 250);
        limits.put("slow", 1.0f / 60);
        limits.put("chaotic", 1.0f / 500);

        Float limit = limits.get(motion);
        if (limit != null && shutterS > limit) {
            return limit;
        }
        return shutterS;
    }

    private Object[] mapWhiteBalance(int kelvin) {
        int[][] presets = {
                {2700}, {4000}, {5500}, {6500}
        };
        WhiteBalanceMode[] presetModes = {
                WhiteBalanceMode.INCANDESCENT,
                WhiteBalanceMode.FLUORESCENT,
                WhiteBalanceMode.DAYLIGHT,
                WhiteBalanceMode.CLOUDY
        };

        WhiteBalanceMode closestMode = WhiteBalanceMode.AUTO;
        int closestDist = Integer.MAX_VALUE;
        for (int i = 0; i < presets.length; i++) {
            int dist = Math.abs(kelvin - presets[i][0]);
            if (dist < closestDist) {
                closestDist = dist;
                closestMode = presetModes[i];
            }
        }

        if (closestDist > 500) {
            int clamped = Math.max(2300, Math.min(10000, kelvin));
            return new Object[]{WhiteBalanceMode.CUSTOM, clamped};
        }
        return new Object[]{closestMode, null};
    }

    private MeteringMode chooseMeteringMode(String lighting) {
        if ("backlit".equals(lighting)) return MeteringMode.SPOT;
        if ("mixed".equals(lighting) || "artificial".equals(lighting)) return MeteringMode.CENTER_WEIGHTED;
        return MeteringMode.MATRIX;
    }

    private static String normalizeLabel(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isLowLightLike(String lighting) {
        return "very_low_light".equals(lighting)
                || "low_light".equals(lighting)
                || "indoor".equals(lighting)
                || "artificial".equals(lighting)
                || "mixed".equals(lighting)
                || "cloudy".equals(lighting)
                || "blue_hour".equals(lighting)
                || "golden_hour".equals(lighting);
    }

    private static boolean isMostlyStatic(String motion) {
        return "static".equals(motion) || "slow".equals(motion);
    }

    private int quantizeIsoForProfile(int iso) {
        int[] table = validIsos != null && validIsos.length > 0 ? validIsos : DEFAULT_VALID_ISOS;
        int best = table[0];
        int bestDist = Math.abs(iso - best);
        for (int i = 1; i < table.length; i++) {
            int v = table[i];
            int d = Math.abs(iso - v);
            if (d < bestDist) {
                best = v;
                bestDist = d;
            }
        }
        return best;
    }

    private String quantizeShutterForProfile(float shutterS) {
        String[] table = validShutters != null && validShutters.length > 0 ? validShutters : DEFAULT_VALID_SHUTTERS;
        if (shutterS <= 0) shutterS = 1e-6f;
        float logTarget = log2(shutterS);
        String best = table[0];
        float bestDist = Math.abs(logTarget - log2(Math.max(1e-12f, shutterToSeconds(best))));
        for (int i = 1; i < table.length; i++) {
            String s = table[i];
            float candidateS = shutterToSeconds(s);
            if (candidateS <= 0) continue;
            float d = Math.abs(logTarget - log2(candidateS));
            if (d < bestDist) {
                best = s;
                bestDist = d;
            }
        }
        return best;
    }

    // --- Math utilities ---

    public static float computeEv(float aperture, float shutterS, int iso) {
        if (shutterS <= 0) shutterS = 1e-6f;
        return log2((aperture * aperture) / shutterS) - log2(iso / 100.0f);
    }

    public static float log2(float x) {
        return (float) (Math.log(x) / Math.log(2));
    }

    // Backward-compatible static quantizers
    public static int quantizeIso(int iso) {
        int best = DEFAULT_VALID_ISOS[0];
        int bestDist = Math.abs(iso - best);
        for (int v : DEFAULT_VALID_ISOS) {
            int d = Math.abs(iso - v);
            if (d < bestDist) {
                best = v;
                bestDist = d;
            }
        }
        return best;
    }

    public static String quantizeShutter(float shutterS) {
        if (shutterS <= 0) shutterS = 1e-6f;
        float logTarget = log2(shutterS);
        String best = DEFAULT_VALID_SHUTTERS[0];
        float bestDist = Math.abs(logTarget - log2(Math.max(1e-12f, shutterToSeconds(best))));
        for (String s : DEFAULT_VALID_SHUTTERS) {
            float candidateS = shutterToSeconds(s);
            if (candidateS <= 0) continue;
            float d = Math.abs(logTarget - log2(candidateS));
            if (d < bestDist) {
                best = s;
                bestDist = d;
            }
        }
        return best;
    }

    public static float shutterToSeconds(String s) {
        s = s.trim();
        if (s.contains("/")) {
            String[] parts = s.split("/");
            try {
                return Float.parseFloat(parts[0]) / Float.parseFloat(parts[1]);
            } catch (Exception e) {
                return 0.008f;
            }
        }
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return 0.008f;
        }
    }

    private boolean supportsNativeMapping() {
        return Arrays.equals(validIsos, DEFAULT_VALID_ISOS)
                && Arrays.equals(validShutters, DEFAULT_VALID_SHUTTERS);
    }

    private static WhiteBalanceMode whiteBalanceModeFromCode(int code) {
        switch (code) {
            case 1:
                return WhiteBalanceMode.DAYLIGHT;
            case 2:
                return WhiteBalanceMode.CLOUDY;
            case 3:
                return WhiteBalanceMode.FLUORESCENT;
            case 4:
                return WhiteBalanceMode.INCANDESCENT;
            case 5:
                return WhiteBalanceMode.CUSTOM;
            case 0:
            default:
                return WhiteBalanceMode.AUTO;
        }
    }

    private static MeteringMode meteringModeFromCode(int code) {
        switch (code) {
            case 0:
                return MeteringMode.CENTER_WEIGHTED;
            case 2:
                return MeteringMode.SPOT;
            case 1:
            default:
                return MeteringMode.MATRIX;
        }
    }
}
