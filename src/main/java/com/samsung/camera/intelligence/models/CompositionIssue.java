package com.samsung.camera.intelligence.models;

import java.util.HashMap;
import java.util.Map;

/**
 * Composition issues that may require post-processing editing.
 * Matches the Python CompositionIssue enum in models/enums.py.
 */
public enum CompositionIssue {
    NONE("none"),
    SUBJECT_OFF_CENTER("subject_off_center"),
    POOR_RULE_OF_THIRDS("poor_rule_of_thirds"),
    UNBALANCED("unbalanced"),
    DISTRACTING_ELEMENTS("distracting_elements"),
    TOO_MUCH_HEADROOM("too_much_headroom"),
    INSUFFICIENT_HEADROOM("insufficient_headroom"),
    POOR_FRAMING("poor_framing"),
    HORIZON_NOT_LEVEL("horizon_not_level"),
    CLUTTERED_BACKGROUND("cluttered_background"),
    AWKWARD_CROPPING("awkward_cropping"),
    NEEDS_RECOMPOSITION("needs_recomposition"),
    SUBJECT_TOO_SMALL("subject_too_small"),
    SUBJECT_CUT_OFF("subject_cut_off");

    private final String value;
    private static final Map<String, CompositionIssue> VALUE_MAP = new HashMap<>();

    static {
        for (CompositionIssue ci : values()) {
            VALUE_MAP.put(ci.value, ci);
        }
    }

    CompositionIssue(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static CompositionIssue fromValue(String value) {
        CompositionIssue result = VALUE_MAP.get(value);
        return result != null ? result : NONE;
    }
}
