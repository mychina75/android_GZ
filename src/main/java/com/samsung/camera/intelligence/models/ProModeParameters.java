package com.samsung.camera.intelligence.models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete Pro mode camera parameters for optimal scene shooting.
 * Ported from Python ProModeParameters dataclass in models/enums.py.
 */
public class ProModeParameters {

    // Valid ranges
    public static final int ISO_MIN = 50;
    public static final int ISO_MAX = 3200;
    public static final float EV_MIN = -3.0f;
    public static final float EV_MAX = 3.0f;
    public static final int KELVIN_MIN = 2300;
    public static final int KELVIN_MAX = 10000;

    public static final List<String> SHUTTER_SPEEDS = Arrays.asList(
        "1/12000", "1/8000", "1/6000", "1/4000", "1/3200", "1/2500", "1/2000",
        "1/1600", "1/1250", "1/1000", "1/800", "1/640", "1/500", "1/400",
        "1/320", "1/250", "1/200", "1/160", "1/125", "1/100", "1/80", "1/60",
        "1/50", "1/40", "1/30", "1/25", "1/20", "1/15", "1/13", "1/10",
        "1/8", "1/6", "1/5", "1/4", "1/3", "1/2.5", "1/2", "1/1.6", "1/1.3",
        "1", "1.3", "1.6", "2", "2.5", "3", "4", "5", "6", "8", "10",
        "13", "15", "20", "25", "30"
    );

    private int iso;
    private String shutterSpeed;
    private float ev;
    private WhiteBalanceMode whiteBalance;
    private Integer whiteBalanceKelvin; // nullable
    private FocusMode focusMode;
    private Float manualFocus; // nullable, 0~1
    private MeteringMode meteringMode;
    private Float contrast;   // nullable, -100..+100
    private Float highlights;  // nullable, -100..+100
    private Float shadows;     // nullable, -100..+100
    private Float saturation;  // nullable, -100..+100

    public ProModeParameters() {
        this.iso = 200;
        this.shutterSpeed = "1/125";
        this.ev = 0.0f;
        this.whiteBalance = WhiteBalanceMode.AUTO;
        this.whiteBalanceKelvin = null;
        this.focusMode = FocusMode.MULTI_POINT;
        this.manualFocus = null;
        this.meteringMode = MeteringMode.MATRIX;
    }

    public ProModeParameters(int iso, String shutterSpeed, float ev,
                             WhiteBalanceMode whiteBalance, Integer whiteBalanceKelvin,
                             FocusMode focusMode, Float manualFocus,
                             MeteringMode meteringMode) {
        this.iso = iso;
        this.shutterSpeed = shutterSpeed;
        this.ev = ev;
        this.whiteBalance = whiteBalance;
        this.whiteBalanceKelvin = whiteBalanceKelvin;
        this.focusMode = focusMode;
        this.manualFocus = manualFocus;
        this.meteringMode = meteringMode;
    }

    /**
     * Validate all parameters and return list of errors.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (iso < ISO_MIN || iso > ISO_MAX) {
            errors.add("ISO " + iso + " out of range [" + ISO_MIN + ", " + ISO_MAX + "]");
        }

        if (!SHUTTER_SPEEDS.contains(shutterSpeed)) {
            errors.add("Shutter speed " + shutterSpeed + " not in valid list");
        }

        if (ev < EV_MIN || ev > EV_MAX) {
            errors.add("EV " + ev + " out of range [" + EV_MIN + ", " + EV_MAX + "]");
        }

        if (whiteBalanceKelvin != null) {
            if (whiteBalanceKelvin < KELVIN_MIN || whiteBalanceKelvin > KELVIN_MAX) {
                errors.add("White balance " + whiteBalanceKelvin + "K out of range ["
                        + KELVIN_MIN + ", " + KELVIN_MAX + "]");
            }
        }

        if (focusMode == FocusMode.MANUAL) {
            if (manualFocus == null) {
                errors.add("Manual focus requires manual_focus value (0~1)");
            } else if (manualFocus < 0.0f || manualFocus > 1.0f) {
                errors.add("Manual focus " + manualFocus + " out of range (0~1)");
            }
        }

        return errors;
    }

    /**
     * Convert to map for tool parameters.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put("iso", iso);
        result.put("shutter_speed", shutterSpeed);
        result.put("ev", ev);
        result.put("white_balance", whiteBalance.getValue());
        result.put("focus_mode", focusMode.getValue());
        result.put("metering_mode", meteringMode.getValue());

        if (whiteBalanceKelvin != null) {
            result.put("white_balance_kelvin", whiteBalanceKelvin);
        }
        if (focusMode == FocusMode.MANUAL && manualFocus != null) {
            result.put("manual_focus", manualFocus);
        }
        if (contrast != null) result.put("contrast", contrast);
        if (highlights != null) result.put("highlights", highlights);
        if (shadows != null) result.put("shadows", shadows);
        if (saturation != null) result.put("saturation", saturation);
        return result;
    }

    // Getters and Setters
    public int getIso() { return iso; }
    public void setIso(int iso) { this.iso = iso; }

    public String getShutterSpeed() { return shutterSpeed; }
    public void setShutterSpeed(String shutterSpeed) { this.shutterSpeed = shutterSpeed; }

    public float getEv() { return ev; }
    public void setEv(float ev) { this.ev = ev; }

    public WhiteBalanceMode getWhiteBalance() { return whiteBalance; }
    public void setWhiteBalance(WhiteBalanceMode whiteBalance) { this.whiteBalance = whiteBalance; }

    public Integer getWhiteBalanceKelvin() { return whiteBalanceKelvin; }
    public void setWhiteBalanceKelvin(Integer whiteBalanceKelvin) { this.whiteBalanceKelvin = whiteBalanceKelvin; }

    public FocusMode getFocusMode() { return focusMode; }
    public void setFocusMode(FocusMode focusMode) { this.focusMode = focusMode; }

    public Float getManualFocus() { return manualFocus; }
    public void setManualFocus(Float manualFocus) { this.manualFocus = manualFocus; }

    public MeteringMode getMeteringMode() { return meteringMode; }
    public void setMeteringMode(MeteringMode meteringMode) { this.meteringMode = meteringMode; }

    public Float getContrast() { return contrast; }
    public void setContrast(Float contrast) { this.contrast = contrast; }

    public Float getHighlights() { return highlights; }
    public void setHighlights(Float highlights) { this.highlights = highlights; }

    public Float getShadows() { return shadows; }
    public void setShadows(Float shadows) { this.shadows = shadows; }

    public Float getSaturation() { return saturation; }
    public void setSaturation(Float saturation) { this.saturation = saturation; }
}
