package com.samsung.camera.intelligence.guidance;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class FramingTemplateOverlayTest {

    @Test
    public void displayLiveSubjectUsesFastCenterWithoutChangingStableCenter() {
        float[] stableCenter = new float[]{0.30f, 0.40f};
        float[] fastCenter = new float[]{0.62f, 0.55f};

        FramingTemplateOverlay overlay = overlay(stableCenter, fastCenter);

        assertArrayEquals(stableCenter, overlay.getLiveSubjectCenterNorm(), 0.0001f);
        assertArrayEquals(fastCenter, overlay.getFastLiveSubjectCenterNorm(), 0.0001f);
        assertArrayEquals(fastCenter, overlay.getDisplayLiveSubjectCenterNorm(), 0.0001f);
    }

    @Test
    public void displayLiveSubjectFallsBackToStableCenter() {
        float[] stableCenter = new float[]{0.30f, 0.40f};

        FramingTemplateOverlay overlay = overlay(stableCenter, null);

        assertArrayEquals(stableCenter, overlay.getLiveSubjectCenterNorm(), 0.0001f);
        assertArrayEquals(stableCenter, overlay.getDisplayLiveSubjectCenterNorm(), 0.0001f);
    }

    private static FramingTemplateOverlay overlay(float[] stableCenter, float[] fastCenter) {
        return new FramingTemplateOverlay(
                GuidanceCategory.COMPOSITION,
                GuidanceUrgency.SUGGESTION,
                "test",
                CompositionTemplate.Type.RULE_OF_THIRDS,
                new float[]{0.10f, 0.10f, 0.80f, 0.80f},
                new float[]{0.50f, 0.50f},
                stableCenter,
                fastCenter,
                new float[0],
                0.50f,
                FramingTemplateOverlay.TemplateState.GUIDING,
                "",
                "RoT",
                1.0f,
                null,
                null,
                0.50f,
                null,
                null,
                null,
                0f,
                null,
                true,
                1f,
                1,
                "test");
    }
}
