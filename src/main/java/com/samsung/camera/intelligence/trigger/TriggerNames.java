package com.samsung.camera.intelligence.trigger;

import java.util.Arrays;
import java.util.List;

/**
 * Canonical names and indices of the 11 trigger nudges.
 *
 * <p>MUST stay aligned with
 * {@code recommendation/triggers/trigger_names.py} on the Python side.</p>
 */
public final class TriggerNames {
    private TriggerNames() {}

    public static final String ND_FILTER       = "nd_filter";        // ①
    public static final String ONLY_ME         = "only_me";          // ②
    public static final String MINIATURE       = "miniature";        // ③
    public static final String FLASH           = "flash";            // ④
    public static final String TELE_PORTRAIT   = "tele_portrait";    // ⑤
    public static final String LIGHT_BOX       = "light_box";        // ⑥
    public static final String UW_SELFIE       = "uw_selfie";        // ⑦
    public static final String LENS_BLOCKED    = "lens_blocked";     // ⑧
    public static final String OUT_OF_FOCUS    = "out_of_focus";     // ⑨
    public static final String BUSINESS_CARD   = "business_card";    // ⑩
    public static final String WIFI_CREDENTIAL = "wifi_credential";  // ⑪

    public static final List<String> ALL = Arrays.asList(
            ND_FILTER, ONLY_ME, MINIATURE, FLASH, TELE_PORTRAIT,
            LIGHT_BOX, UW_SELFIE, LENS_BLOCKED, OUT_OF_FOCUS,
            BUSINESS_CARD, WIFI_CREDENTIAL
    );

    public static final int COUNT = ALL.size();
}
