package com.samsung.camera.intelligence.config;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Arrays;
import java.util.List;

/**
 * Lightweight runtime device capability profile.
 */
public class DeviceProfile {

    private final String manufacturer;
    private final String model;
    private final int sdkInt;
    private final boolean gpuAccelerationAvailable;
    private final List<String> supportedResolutions;

    public DeviceProfile(String manufacturer,
                         String model,
                         int sdkInt,
                         boolean gpuAccelerationAvailable,
                         List<String> supportedResolutions) {
        this.manufacturer = manufacturer;
        this.model = model;
        this.sdkInt = sdkInt;
        this.gpuAccelerationAvailable = gpuAccelerationAvailable;
        this.supportedResolutions = supportedResolutions;
    }

    /**
     * Detect a best-effort profile from Android runtime information.
     */
    public static DeviceProfile detect(Context context) {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "unknown";
        String model = Build.MODEL != null ? Build.MODEL : "unknown";
        int sdkInt = Build.VERSION.SDK_INT;

        boolean hasOpenGlEs3 = false;
        try {
            PackageManager pm = context != null ? context.getPackageManager() : null;
            hasOpenGlEs3 = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK);
        } catch (Exception ignored) {
            hasOpenGlEs3 = false;
        }

        List<String> resolutions = Arrays.asList("12MP", "50MP", "108MP", "200MP");
        return new DeviceProfile(manufacturer, model, sdkInt, hasOpenGlEs3, resolutions);
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getModel() {
        return model;
    }

    public int getSdkInt() {
        return sdkInt;
    }

    public boolean isGpuAccelerationAvailable() {
        return gpuAccelerationAvailable;
    }

    public List<String> getSupportedResolutions() {
        return supportedResolutions;
    }
}
