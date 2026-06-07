package com.samsung.camera.intelligence.guidance;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 9 (Composition v2 — Plan A) — Silhouette state machine.
 *
 * Drives {@link SilhouetteOverlay.State} based on the per-frame
 * {@link FramingTemplateOverlay} (which already carries the GAIC top-1 crop,
 * the live alignment score, and the Phase-7 target-lock flag).
 *
 * <pre>
 *   OFF
 *    │  has crop & scene allowed → ARMING (start arming timer)
 *    ▼
 *   ARMING ──────── elapsed ≥ acquireMs ─────────► ACTIVE
 *    │ no crop / disallowed scene for sustainCooldownMs                ▲
 *    ▼                                                                 │
 *   OFF                                                                │
 *                                                                      │
 *   ACTIVE ──── targetLocked & alignment ≥ lockEnter ──► LOCKED        │
 *    │ alignment drop / crop loss for releaseMs                         │
 *    ▼                                                                 │
 *   ARMING                                                             │
 *                                                                      │
 *   LOCKED ──── alignment ≤ lockExit OR !targetLocked ────► ACTIVE ───┘
 *    │ no crop for cooldown                                            │
 *    ▼                                                                 │
 *   OFF                                                                │
 * </pre>
 *
 * Subject magnetic-snap is intentionally NOT implemented (deferred until
 * the Yolact-on-CLIP-B/32 head ships).  Plan A ships crop-frame outline
 * only.
 *
 * Phase 10 — scene allow-list: only auto-arm in scenes where reframing
 * help is most valuable (portrait / landscape / architecture / cityscape).
 * Other scenes can still display silhouette if the user explicitly opted
 * in via {@link #setSceneFilterEnabled(boolean)}.
 *
 * The controller is single-threaded and re-used across frames by
 * {@link OverlayGenerator}. Call {@link #reset()} on camera switch.
 */
public class SilhouetteController {

    private static final Set<String> ALLOWED_SCENES = new HashSet<>(Arrays.asList(
            "portrait", "human_single", "human_face", "human_full_body",
            "landscape", "cityscape", "architecture",
            "architecture_exterior", "architecture_interior"
    ));

    // ---- Tunables ----
    private long acquireMs = 800L;          // ARMING duration before going ACTIVE
    private long releaseMs = 1200L;         // crop missing → ACTIVE → ARMING
    private long cooldownMs = 1500L;        // any state → OFF when crop absent
    private float lockEnterAlignment = 0.85f;
    private float lockExitAlignment = 0.70f;
    private boolean sceneFilterEnabled = true;

    // ---- State ----
    private SilhouetteOverlay.State state = SilhouetteOverlay.State.OFF;
    private long stateEnteredMs = 0L;
    private long lastCropSeenMs = 0L;
    /** Set to true on the tick we transitioned into LOCKED, consumed once. */
    private boolean pendingLockTransition = false;

    // ---- Config ----
    public void setAcquireMs(long v) { this.acquireMs = Math.max(0L, v); }
    public void setReleaseMs(long v) { this.releaseMs = Math.max(0L, v); }
    public void setCooldownMs(long v) { this.cooldownMs = Math.max(0L, v); }
    public void setLockEnterAlignment(float v) { this.lockEnterAlignment = v; }
    public void setLockExitAlignment(float v) { this.lockExitAlignment = v; }
    public void setSceneFilterEnabled(boolean v) { this.sceneFilterEnabled = v; }

    public SilhouetteOverlay.State getState() { return state; }

    public void reset() {
        state = SilhouetteOverlay.State.OFF;
        stateEnteredMs = 0L;
        lastCropSeenMs = 0L;
        pendingLockTransition = false;
    }

    /**
     * Advance the state machine one frame. Returns the overlay to render,
     * or {@code null} when the silhouette must be hidden (state OFF).
     *
     * @param tpl     latest framing template (may be null when no crop is available)
     * @param sceneId current scene type id (e.g. {@code "portrait"}); may be null
     * @param nowMs   monotonic millis (use {@link System#currentTimeMillis()})
     */
    public SilhouetteOverlay tick(FramingTemplateOverlay tpl, String sceneId, long nowMs) {
        float[] crop = tpl != null ? tpl.getTargetCropNorm() : null;
        boolean cropOk = crop != null && crop.length >= 4
            && crop[2] > 0.03f && crop[3] > 0.03f;
        boolean sceneOk = !sceneFilterEnabled || sceneId == null
                || ALLOWED_SCENES.contains(sceneId);
        boolean armable = cropOk && sceneOk;

        if (cropOk) {
            lastCropSeenMs = nowMs;
        }

        float alignment = tpl != null ? tpl.getAlignmentScore() : 0f;
        boolean targetLocked = tpl != null && tpl.isTargetLocked();

        SilhouetteOverlay.State next = state;
        switch (state) {
            case OFF:
                if (armable) next = SilhouetteOverlay.State.ARMING;
                break;
            case ARMING:
                if (!cropOk && nowMs - lastCropSeenMs >= cooldownMs) {
                    next = SilhouetteOverlay.State.OFF;
                } else if (cropOk && nowMs - stateEnteredMs >= acquireMs) {
                    next = SilhouetteOverlay.State.ACTIVE;
                }
                break;
            case ACTIVE:
                if (!cropOk && nowMs - lastCropSeenMs >= cooldownMs) {
                    next = SilhouetteOverlay.State.OFF;
                } else if (!cropOk && nowMs - lastCropSeenMs >= releaseMs) {
                    next = SilhouetteOverlay.State.ARMING;
                } else if (targetLocked && alignment >= lockEnterAlignment) {
                    next = SilhouetteOverlay.State.LOCKED;
                }
                break;
            case LOCKED:
                if (!cropOk && nowMs - lastCropSeenMs >= cooldownMs) {
                    next = SilhouetteOverlay.State.OFF;
                } else if (!targetLocked || alignment <= lockExitAlignment) {
                    next = SilhouetteOverlay.State.ACTIVE;
                }
                break;
        }

        if (next != state) {
            pendingLockTransition = (next == SilhouetteOverlay.State.LOCKED);
            state = next;
            stateEnteredMs = nowMs;
        }

        if (state == SilhouetteOverlay.State.OFF || crop == null) {
            return null;
        }

        float progress;
        if (state == SilhouetteOverlay.State.ARMING) {
            progress = acquireMs > 0
                    ? Math.min(1f, (nowMs - stateEnteredMs) / (float) acquireMs)
                    : 1f;
        } else {
            progress = 1f;
        }

        boolean lockEdge = pendingLockTransition;
        pendingLockTransition = false;

        return new SilhouetteOverlay(
                GuidanceCategory.COMPOSITION,
                GuidanceUrgency.SUGGESTION,
                state == SilhouetteOverlay.State.LOCKED ? "Framing matches" : "Framing guide",
                crop.clone(),
                state,
                progress,
                lockEdge);
    }
}
