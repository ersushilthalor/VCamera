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
