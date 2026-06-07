package com.samsung.camera.intelligence.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Smoke tests for CompositionAdvisor — verify rule firing, priority
 * resolution, and cooldown behavior. Cannot exercise system clock fully
 * without injection; we sleep briefly between calls to differentiate
 * cooldown windows.
 */
public class CompositionAdvisorTest {

    private static FrameAnalysis emptyAnalysis() {
        FrameAnalysis a = new FrameAnalysis();
        a.setCompositionIssues(new ArrayList<>());
        return a;
    }

    @Test
    public void noFireOnEmptyAnalysis() {
        CompositionAdvisor advisor = new CompositionAdvisor();
        FrameAnalysis a = emptyAnalysis();
        // Stable scene + no triggers + secondary not yet eligible → null.
        assertNull(advisor.advise(a, false, 0f));
    }

    @Test
    public void cutOffWinsOverHorizon() {
        CompositionAdvisor advisor = new CompositionAdvisor();
        FrameAnalysis a = emptyAnalysis();
        a.setTiltAngle(8f);
        a.setCompositionIssues(new ArrayList<>(Arrays.asList(
                "subject_cut_off", "horizon_not_level")));
        CompositionAdvice advice = advisor.advise(a, false, 0f);
        assertNotNull(advice);
        assertEquals("subject_cut_off", advice.techniqueId);
        assertEquals(CompositionAdvice.Severity.CRITICAL, advice.severity);
    }

    @Test
    public void horizonFiresOnTiltOnly() {
        CompositionAdvisor advisor = new CompositionAdvisor();
        FrameAnalysis a = emptyAnalysis();
        a.setTiltAngle(3.2f);
        CompositionAdvice advice = advisor.advise(a, false, 0f);
        assertNotNull(advice);
        assertEquals("horizon_not_level", advice.techniqueId);
    }

    @Test
    public void offCenterRequiresTargetLockAndLowAlignment() {
        CompositionAdvisor advisor = new CompositionAdvisor();
        FrameAnalysis a = emptyAnalysis();
        a.setCompositionIssues(new ArrayList<>(Arrays.asList("subject_off_center")));
        // No target lock → rule must not fire even if the issue is set.
        assertNull(advisor.advise(a, false, 0f));
        // Target locked + alignment already good → still suppressed.
        assertNull(advisor.advise(a, true, 0.9f));
        // Target locked + low alignment → fires.
        CompositionAdvice advice = advisor.advise(a, true, 0.3f);
        assertNotNull(advice);
        assertEquals("off_center_after_lock", advice.techniqueId);
    }

    @Test
    public void cooldownSuppressesRepeatedFires() throws InterruptedException {
        CompositionAdvisor advisor = new CompositionAdvisor();
        advisor.setAdviceCoolDownMs(2000L);
        FrameAnalysis a = emptyAnalysis();
        a.setHasSymmetry(true);
        CompositionAdvice first = advisor.advise(a, false, 0f);
        assertNotNull(first);
        assertEquals("symmetry", first.techniqueId);
        // Immediate re-call within cooldown → no advice.
        Thread.sleep(20L);
        assertNull(advisor.advise(a, false, 0f));
    }

    @Test
    public void muteSilencesAllAdvice() {
        CompositionAdvisor advisor = new CompositionAdvisor();
        advisor.muteFor(500L);
        FrameAnalysis a = emptyAnalysis();
        a.setCompositionIssues(new ArrayList<>(Arrays.asList("subject_cut_off")));
        assertNull(advisor.advise(a, false, 0f));
    }

    @Test
    public void fillTheFrameCoachOnTinySubject() {
        CompositionAdvisor advisor = new CompositionAdvisor();
        FrameAnalysis a = emptyAnalysis();
        a.setSubjectFillRatio(0.07f);
        CompositionAdvice advice = advisor.advise(a, false, 0f);
        assertNotNull(advice);
        assertEquals("fill_the_frame", advice.techniqueId);
    }

    @Test
    public void defaultRuleListNonEmpty() {
        CompositionAdvisor advisor = new CompositionAdvisor();
        List<TechniqueRule> rules = advisor.getRules();
        assertTrue("expected at least 15 rules", rules.size() >= 15);
    }
}
