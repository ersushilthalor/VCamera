package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomTransitionTimingTest {

    @Test
    fun zoomTransitionDurationIsFixedAt300Milliseconds() {
        assertEquals(400L, ZoomTransitionController.TOTAL_TRANSITION_DURATION_MS)
        assertEquals(300L, ZoomTransitionController.VIDEO_TRANSITION_DURATION_MS)
    }

    @Test
    fun configuredOneXHoldDurationUsesTheFullSupportedRange() {
        assertEquals(0L, ZoomTransitionController.clampHoldAtOneXDurationMs(0L))
        assertEquals(20L, ZoomTransitionController.clampHoldAtOneXDurationMs(20L))
        assertEquals(175L, ZoomTransitionController.clampHoldAtOneXDurationMs(175L))
        assertEquals(300L, ZoomTransitionController.clampHoldAtOneXDurationMs(500L))
    }

    @Test
    fun holdTimeHandoffOnlyAppliesToDirectHalfXToOneXTransition() {
        assertEquals(true, ZoomTransitionController.isHoldTimeHandoffEligible(0.5f, 1.0f))
        assertEquals(false, ZoomTransitionController.isHoldTimeHandoffEligible(0.5f, 2.0f))
        assertEquals(false, ZoomTransitionController.isHoldTimeHandoffEligible(1.0f, 0.5f))
    }

    @Test
    fun recordingFrameRateDoesNotStretchZoomTransition() {
        val recordingFrameRates = listOf(24, 30, 60, 120)

        recordingFrameRates.forEach { fps ->
            assertEquals(
                "Zoom duration must remain 300ms at ${fps}fps",
                300L,
                ZoomTransitionController.framePacedTransitionDurationMs(
                    durationMs = 300L,
                    fps = fps
                )
            )
        }
    }

    @Test
    fun framePacingPreservesTheRequestedDuration() {
        assertEquals(
            1200L,
            ZoomTransitionController.framePacedTransitionDurationMs(
                durationMs = 1200L,
                fps = 24
            )
        )
    }
}
