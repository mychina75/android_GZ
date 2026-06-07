package com.samsung.camera.intelligence.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 8-10 (Composition v2 - Plan A) - state-machine tests for
 * {@link SilhouetteController}. Stubs FramingTemplateOverlay with the only
 * fields the controller reads (target crop, alignment score, target-locked).
 */
public class SilhouetteControllerTest {

    private static FramingTemplateOverlay tpl(float[] crop, float alignment, boolean locked) {
        return new FramingTemplateOverlay(
                GuidanceCategory.COMPOSITION,
                GuidanceUrgency.SUGGESTION,
                "test",
                CompositionTemplate.Type.RULE_OF_THIRDS,
                crop,
                new float[]{0.5f, 0.5f},
                new float[]{0.5f, 0.5f},
                null,
                alignment,
                FramingTemplateOverlay.TemplateState.GUIDING,
                "",
                "RoT",
                1.0f,
                null, null, alignment,
                null, null, null, 0f, null,
                locked, locked ? 1f : 0f, 0, "test");
    }

    private static final float[] CROP = new float[]{0.1f, 0.1f, 0.9f, 0.9f};

    @Test
    public void offByDefault_noTickEffect() {
        SilhouetteController c = new SilhouetteController();
        assertEquals(SilhouetteOverlay.State.OFF, c.getState());
    }

    @Test
    public void armingProgressesToActiveAfterAcquireMs() {
        SilhouetteController c = new SilhouetteController();
        c.setAcquireMs(500L);
        c.setSceneFilterEnabled(false);
        SilhouetteOverlay s0 = c.tick(tpl(CROP, 0.4f, false), null, 1000L);
        assertNotNull(s0);
        assertEquals(SilhouetteOverlay.State.ARMING, s0.getState());
        assertTrue(s0.getProgress() < 0.1f);

        SilhouetteOverlay s1 = c.tick(tpl(CROP, 0.4f, false), null, 1300L);
        assertEquals(SilhouetteOverlay.State.ARMING, s1.getState());
        assertTrue(s1.getProgress() > 0.5f && s1.getProgress() < 1f);

        SilhouetteOverlay s2 = c.tick(tpl(CROP, 0.4f, false), null, 1700L);
        assertEquals(SilhouetteOverlay.State.ACTIVE, s2.getState());
        assertEquals(1f, s2.getProgress(), 1e-6f);
    }

    @Test
    public void activeLocksWhenTargetLockedAndAlignmentHigh() {
        SilhouetteController c = new SilhouetteController();
        c.setAcquireMs(0L);
        c.setSceneFilterEnabled(false);
        // OFF -> ARMING -> ACTIVE in two ticks (acquireMs=0).
        c.tick(tpl(CROP, 0.5f, false), null, 0L);
        SilhouetteOverlay active = c.tick(tpl(CROP, 0.5f, false), null, 10L);
        assertEquals(SilhouetteOverlay.State.ACTIVE, active.getState());

        SilhouetteOverlay locked = c.tick(tpl(CROP, 0.9f, true), null, 20L);
        assertEquals(SilhouetteOverlay.State.LOCKED, locked.getState());
        assertTrue("LOCKED transition must fire haptic edge", locked.isLockTransition());

        // Subsequent tick at LOCKED must NOT re-fire haptic edge.
        SilhouetteOverlay still = c.tick(tpl(CROP, 0.9f, true), null, 30L);
        assertEquals(SilhouetteOverlay.State.LOCKED, still.getState());
        assertFalse(still.isLockTransition());
    }

    @Test
    public void lockedReleasesWhenAlignmentDrops() {
        SilhouetteController c = new SilhouetteController();
        c.setAcquireMs(0L);
        c.setSceneFilterEnabled(false);
        c.tick(tpl(CROP, 0.5f, false), null, 0L);
        c.tick(tpl(CROP, 0.5f, false), null, 10L);
        c.tick(tpl(CROP, 0.9f, true), null, 20L);
        // alignment drops below the exit threshold (0.70 default).
        SilhouetteOverlay back = c.tick(tpl(CROP, 0.5f, true), null, 30L);
        assertEquals(SilhouetteOverlay.State.ACTIVE, back.getState());
    }

    @Test
    public void cooldownReturnsToOffWhenCropMissing() {
        SilhouetteController c = new SilhouetteController();
        c.setAcquireMs(0L);
        c.setCooldownMs(100L);
        c.setSceneFilterEnabled(false);
        c.tick(tpl(CROP, 0.5f, false), null, 0L);
        c.tick(tpl(CROP, 0.5f, false), null, 10L);
        // Crop disappears for longer than cooldown.
        SilhouetteOverlay gone = c.tick(null, null, 200L);
        assertNull("OFF must return null overlay", gone);
        assertEquals(SilhouetteOverlay.State.OFF, c.getState());
    }

    @Test
    public void sceneFilterBlocksDisallowedScenes() {
        SilhouetteController c = new SilhouetteController();
        c.setSceneFilterEnabled(true);
        SilhouetteOverlay s = c.tick(tpl(CROP, 0.5f, false), "macro_food", 0L);
        assertNull("disallowed scene must not arm", s);
        assertEquals(SilhouetteOverlay.State.OFF, c.getState());

        SilhouetteOverlay ok = c.tick(tpl(CROP, 0.5f, false), "portrait", 10L);
        assertNotNull(ok);
        assertEquals(SilhouetteOverlay.State.ARMING, ok.getState());
    }

    @Test
    public void acceptsXywhCropWhenWidthIsLessThanX() {
        SilhouetteController c = new SilhouetteController();
        c.setSceneFilterEnabled(false);

        SilhouetteOverlay s = c.tick(
                tpl(new float[]{0.55f, 0.20f, 0.25f, 0.50f}, 0.4f, false),
                null,
                0L);

        assertNotNull(s);
        assertEquals(SilhouetteOverlay.State.ARMING, s.getState());
    }

    @Test
    public void resetClearsState() {
        SilhouetteController c = new SilhouetteController();
        c.setAcquireMs(0L);
        c.setSceneFilterEnabled(false);
        c.tick(tpl(CROP, 0.5f, false), null, 0L);
        c.tick(tpl(CROP, 0.5f, false), null, 10L);
        assertEquals(SilhouetteOverlay.State.ACTIVE, c.getState());
        c.reset();
        assertEquals(SilhouetteOverlay.State.OFF, c.getState());
    }
}
