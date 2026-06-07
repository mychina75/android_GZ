package com.samsung.camera.intelligence.app.camera;

public class CameraProSettings {

    public final Integer iso;
    public final Long shutterNs;
    public final Float ev;
    public final String whiteBalanceMode;
    public final Integer whiteBalanceKelvin;
    public final String focusMode;
    public final Float focusDistance;
    public final String meteringMode;
    public final Float contrast;    // -100..+100, null = no adjustment
    public final Float highlights;  // -100..+100, null = no adjustment
    public final Float shadows;     // -100..+100, null = no adjustment
    public final Float saturation;  // -100..+100, null = no adjustment
    public final Float highlightWarmth; // -100..+100, null = no adjustment
    public final Float shadowTint;      // -100..+100, null = no adjustment

    public CameraProSettings(
            Integer iso,
            Long shutterNs,
            Float ev,
            String whiteBalanceMode,
            Integer whiteBalanceKelvin,
            String focusMode,
            Float focusDistance,
            String meteringMode) {
        this(iso, shutterNs, ev, whiteBalanceMode, whiteBalanceKelvin,
             focusMode, focusDistance, meteringMode, null, null, null, null, null, null);
    }

    public CameraProSettings(
            Integer iso,
            Long shutterNs,
            Float ev,
            String whiteBalanceMode,
            Integer whiteBalanceKelvin,
            String focusMode,
            Float focusDistance,
            String meteringMode,
            Float contrast,
            Float highlights,
            Float shadows,
            Float saturation) {
        this(iso, shutterNs, ev, whiteBalanceMode, whiteBalanceKelvin,
             focusMode, focusDistance, meteringMode, contrast, highlights, shadows, saturation,
             null, null);
    }

    public CameraProSettings(
            Integer iso,
            Long shutterNs,
            Float ev,
            String whiteBalanceMode,
            Integer whiteBalanceKelvin,
            String focusMode,
            Float focusDistance,
            String meteringMode,
            Float contrast,
            Float highlights,
            Float shadows,
            Float saturation,
            Float highlightWarmth,
            Float shadowTint) {
        this.iso = iso;
        this.shutterNs = shutterNs;
        this.ev = ev;
        this.whiteBalanceMode = whiteBalanceMode;
        this.whiteBalanceKelvin = whiteBalanceKelvin;
        this.focusMode = focusMode;
        this.focusDistance = focusDistance;
        this.meteringMode = meteringMode;
        this.contrast = contrast;
        this.highlights = highlights;
        this.shadows = shadows;
        this.saturation = saturation;
        this.highlightWarmth = highlightWarmth;
        this.shadowTint = shadowTint;
    }

    public boolean hasToneParams() {
        return contrast != null || highlights != null || shadows != null || saturation != null
                || highlightWarmth != null || shadowTint != null;
    }

    public static CameraProSettings defaults() {
        return new CameraProSettings(null, null, 0f,
                "auto", null, "multi_point", null, "matrix");
    }

    public int getIso() { return iso == null ? 0 : iso; }
    public long getShutterSpeedNs() { return shutterNs == null ? 0L : shutterNs; }
    public Float getEvCompensation() { return ev; }
    public String getWhiteBalanceMode() { return whiteBalanceMode; }
    public String getFocusMode() { return focusMode; }
    public Float getContrast() { return contrast; }
    public Float getHighlights() { return highlights; }
    public Float getShadows() { return shadows; }
    public Float getSaturation() { return saturation; }
    public Float getHighlightWarmth() { return highlightWarmth; }
    public Float getShadowTint() { return shadowTint; }

    public static String formatShutterFromNs(long ns) {
        if (ns <= 0) return "";
        double seconds = ns / 1_000_000_000.0;
        if (seconds >= 1.0) {
            return String.format(java.util.Locale.US, "%.1f", seconds);
        }
        long denom = Math.round(1.0 / seconds);
        return "1/" + denom;
    }

    public static Long parseShutterToNs(String shutter) {
        if (shutter == null || shutter.trim().isEmpty()) {
            return null;
        }
        String s = shutter.trim();
        double seconds;
        try {
            if (s.contains("/")) {
                String[] parts = s.split("/");
                if (parts.length != 2) {
                    return null;
                }
                double num = Double.parseDouble(parts[0]);
                double den = Double.parseDouble(parts[1]);
                if (den == 0) {
                    return null;
                }
                seconds = num / den;
            } else {
                seconds = Double.parseDouble(s);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (seconds <= 0) {
            return null;
        }
        return (long) (seconds * 1_000_000_000L);
    }
}
