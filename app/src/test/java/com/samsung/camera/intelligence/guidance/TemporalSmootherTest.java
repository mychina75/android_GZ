package com.samsung.camera.intelligence.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TemporalSmootherTest {

    @Test
    public void fastSubjectCenterTracksLargeMoveWithoutSnapping() {
        TemporalSmoother smoother = new TemporalSmoother();

        smoother.smoothAnalysis(frameWithSubject(0.20f, 0.50f));
        FrameAnalysis moved = smoother.smoothAnalysis(frameWithSubject(0.80f, 0.50f));

        assertEquals(0.38f, moved.getSubjectCenterX(), 0.0001f);
        assertEquals(0.50f, moved.getSubjectCenterY(), 0.0001f);
        assertEquals(0.59f, moved.getFastSubjectCenterX(), 0.0001f);
        assertEquals(0.50f, moved.getFastSubjectCenterY(), 0.0001f);
        assertTrue(moved.getFastSubjectCenterX() > moved.getSubjectCenterX());
        assertTrue(moved.getFastSubjectCenterX() < 0.80f);
    }

    @Test
    public void fastSubjectCenterIgnoresTinyJitterInsideDeadZone() {
        TemporalSmoother smoother = new TemporalSmoother();

        smoother.smoothAnalysis(frameWithSubject(0.30f, 0.50f));
        FrameAnalysis jitter = smoother.smoothAnalysis(frameWithSubject(0.304f, 0.502f));

        assertEquals(0.30f, jitter.getFastSubjectCenterX(), 0.0001f);
        assertEquals(0.50f, jitter.getFastSubjectCenterY(), 0.0001f);
    }

    @Test
    public void fastSubjectCenterClearsOnMissingSubject() {
        TemporalSmoother smoother = new TemporalSmoother();
        smoother.smoothAnalysis(frameWithSubject(0.30f, 0.50f));

        FrameAnalysis missing = smoother.smoothAnalysis(new FrameAnalysis());

        assertNull(missing.getFastSubjectCenterX());
        assertNull(missing.getFastSubjectCenterY());
    }

    @Test
    public void otherContinuousFieldsKeepDefaultEmaAlpha() {
        TemporalSmoother smoother = new TemporalSmoother();

        FrameAnalysis first = new FrameAnalysis();
        first.setCompositionScore(0f);
        smoother.smoothAnalysis(first);

        FrameAnalysis second = new FrameAnalysis();
        second.setCompositionScore(1f);
        smoother.smoothAnalysis(second);

        assertEquals(0.30f, second.getCompositionScore(), 0.0001f);
    }

    private static FrameAnalysis frameWithSubject(float centerX, float centerY) {
        FrameAnalysis analysis = new FrameAnalysis();
        analysis.setSubjectCenterX(centerX);
        analysis.setSubjectCenterY(centerY);
        analysis.setSubjectConfidence(1.0f);
        return analysis;
    }
}
