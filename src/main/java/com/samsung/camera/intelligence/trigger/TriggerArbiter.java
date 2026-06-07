package com.samsung.camera.intelligence.trigger;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trigger arbitration (mutual-exclusion groups).
 *
 * <p>Mirrors {@code recommendation/triggers/trigger_arbiter.py}.  When several
 * members of a mutually-exclusive group clear their threshold at once, keep
 * only the strongest by margin ({@code score - threshold}) and suppress the
 * rest to just below their threshold.</p>
 *
 * <p>Config from the top-level {@code __arbitration__} key of
 * {@code trigger_rules.json}.</p>
 */
public final class TriggerArbiter {

    private static final String TAG = "TriggerArbiter";

    private static final class Group {
        final String name;
        final List<String> members;
        final float margin;
        Group(String name, List<String> members, float margin) {
            this.name = name;
            this.members = members;
            this.margin = margin;
        }
    }

    private final List<Group> groups;

    public TriggerArbiter(List<Group> groups) {
        this.groups = groups;
    }

    /** Build from the parsed {@code __arbitration__} JSON object (may be null). */
    public static TriggerArbiter fromConfig(JSONObject cfg) {
        List<Group> groups = new ArrayList<>();
        if (cfg != null) {
            JSONArray arr = cfg.optJSONArray("groups");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject g = arr.optJSONObject(i);
                    if (g == null) continue;
                    List<String> members = new ArrayList<>();
                    JSONArray m = g.optJSONArray("members");
                    if (m != null) {
                        for (int j = 0; j < m.length(); j++) {
                            members.add(m.optString(j));
                        }
                    }
                    groups.add(new Group(
                            g.optString("name", ""),
                            members,
                            (float) g.optDouble("margin", 0.0)));
                }
            }
        }
        return new TriggerArbiter(groups);
    }

    /**
     * Suppress losing members of each mutual-exclusion group.  Mutates
     * {@code scores} in place.
     */
    public void apply(Map<String, Float> scores, Map<String, Float> thresholds) {
        final float eps = 1e-3f;
        final float defThr = 0.5f;
        for (Group grp : groups) {
            List<String> names = new ArrayList<>();
            List<Float> margins = new ArrayList<>();
            List<Float> thrs = new ArrayList<>();
            for (String name : grp.members) {
                Float s = scores.get(name);
                if (s == null) continue;
                float thr = thresholds.getOrDefault(name, defThr);
                float margin = s - thr;
                if (margin >= 0f) {
                    names.add(name);
                    margins.add(margin);
                    thrs.add(thr);
                }
            }
            if (names.size() <= 1) continue;
            // Find winner and runner-up by margin.
            int winIdx = 0;
            for (int i = 1; i < margins.size(); i++) {
                if (margins.get(i) > margins.get(winIdx)) winIdx = i;
            }
            float winMargin = margins.get(winIdx);
            float runnerMargin = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < margins.size(); i++) {
                if (i == winIdx) continue;
                if (margins.get(i) > runnerMargin) runnerMargin = margins.get(i);
            }
            if (winMargin - runnerMargin < grp.margin) continue;
            for (int i = 0; i < names.size(); i++) {
                if (i == winIdx) continue;
                String name = names.get(i);
                float capped = Math.min(scores.get(name), thrs.get(i) - eps);
                scores.put(name, capped);
            }
        }
    }
}
