package com.samsung.camera.intelligence.guidance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CompositionTargetLockControllerTest {

    @Test
        public void lockedTargetSmoothlyFollowsIncomingCandidate() {
        CompositionTargetLockController controller = new CompositionTargetLockController();
        controller.setAcquireMs(100L);
        controller.setMinStableFrames(2);

        float[] cropA = new float[]{0.10f, 0.12f, 0.70f, 0.70f};
        float[] cropA2 = new float[]{0.105f, 0.12f, 0.70f, 0.70f};
        float[] anchorA = new float[]{0.33f, 0.35f};

        CompositionTargetLockController.Result first = controller.update(
                cropA, anchorA, CompositionTemplate.Type.RULE_OF_THIRDS,
                new float[]{0.33f, 0.35f}, "Rule of Thirds", new float[]{0.2f, 0.3f},
                "model", true, 0L);
        assertFalse(first.isTargetLocked());

        CompositionTargetLockController.Result locked = controller.update(
                cropA2, anchorA, CompositionTemplate.Type.RULE_OF_THIRDS,
                new float[]{0.33f, 0.35f}, "Rule of Thirds", new float[]{0.2f, 0.3f},
                "model", true, 120L);
        assertTrue(locked.isTargetLocked());
        assertEquals(1, locked.getTargetRevision());

        float[] cropB = new float[]{0.45f, 0.20f, 0.45f, 0.60f};
        float[] anchorB = new float[]{0.70f, 0.45f};
        CompositionTargetLockController.Result afterMove = controller.update(
                cropB, anchorB, CompositionTemplate.Type.RULE_OF_THIRDS,
                new float[]{0.70f, 0.45f}, "Rule of Thirds", new float[]{0.1f, 0.1f},
                "model", true, 240L);

        assertTrue(afterMove.isTargetLocked());
        assertEquals(1, afterMove.getTargetRevision());
        assertArrayEquals(new float[]{0.2016f, 0.1424f, 0.6300f, 0.6720f},
                afterMove.getTargetCropNorm(), 0.0001f);
        assertArrayEquals(new float[]{0.4336f, 0.3780f}, afterMove.getAnchorNorm(), 0.0001f);
    }

    @Test
    public void templateChangeStartsNewArmingCycleAfterLock() {
        CompositionTargetLockController controller = new CompositionTargetLockController();
        controller.setAcquireMs(100L);
        controller.setMinStableFrames(2);

        controller.update(new float[]{0.10f, 0.12f, 0.70f, 0.70f},
                new float[]{0.33f, 0.35f}, CompositionTemplate.Type.RULE_OF_THIRDS,
                new float[]{0.33f, 0.35f}, "Rule of Thirds", null,
                "model", true, 0L);
        CompositionTargetLockController.Result locked = controller.update(
                new float[]{0.105f, 0.12f, 0.70f, 0.70f},
                new float[]{0.33f, 0.35f}, CompositionTemplate.Type.RULE_OF_THIRDS,
                new float[]{0.33f, 0.35f}, "Rule of Thirds", null,
                "model", true, 120L);
        assertTrue(locked.isTargetLocked());

        CompositionTargetLockController.Result restarted = controller.update(
                new float[]{0.45f, 0.20f, 0.45f, 0.60f},
                new float[]{0.70f, 0.45f}, CompositionTemplate.Type.CENTERED,
                new float[0], "Centered", null, "model", true, 240L);

        assertFalse(restarted.isTargetLocked());
        assertEquals(0f, restarted.getProgress(), 0.0001f);
    }

    @Test
    public void unstableCandidateRestartsArming() {
        CompositionTargetLockController controller = new CompositionTargetLockController();
        controller.setAcquireMs(100L);
        controller.setMinStableFrames(2);

        controller.update(new float[]{0.10f, 0.10f, 0.70f, 0.70f},
                new float[]{0.33f, 0.33f}, CompositionTemplate.Type.RULE_OF_THIRDS,
                new float[0], "Rule of Thirds", null, "model", true, 0L);

        CompositionTargetLockController.Result restarted = controller.update(
                new float[]{0.50f, 0.10f, 0.40f, 0.70f},
                new float[]{0.70f, 0.33f}, CompositionTemplate.Type.CENTERED,
                new float[0], "Centered", null, "model", true, 120L);

        assertFalse(restarted.isTargetLocked());
        assertEquals(0f, restarted.getProgress(), 0.0001f);
    }
}
