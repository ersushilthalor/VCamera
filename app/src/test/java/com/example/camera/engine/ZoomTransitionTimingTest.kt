package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomTransitionTimingTest {

    @Test
    fun zoomTransitionDurationIsFixedAt300Milliseconds() {
        assertEquals(400L, ZoomTransitionController.TOTAL_TRANSITION_DURATION_MS)
        assertEquals(300L, ZoomTransitionController.VIDEO_TRANSITION_DURATION_MS)
    }

    @Test
    fun oneXHoldDurationIsFixedAt200Milliseconds() {
        assertEquals(200L, ZoomTransitionController.DEFAULT_HOLD_AT_ONE_X_DURATION_MS)
    }

    @Test
    fun directHalfXToOneXReservesHoldInsideVideoDurationButNotPhotoDuration() {
        assertEquals(
            300L,
            ZoomTransitionController.calculateTransitionMotionDurationMs(
                durationMs = 300L,
                holdDurationMs = 200L,
                isUltraWideToMainSwitch = true,
                isDirectHalfXToOneXHold = true
            )
        )
        assertEquals(
            100L,
            ZoomTransitionController.calculateTransitionMotionDurationMs(
                durationMs = 300L,
                holdDurationMs = 200L,
                isUltraWideToMainSwitch = true,
                isDirectHalfXToOneXHold = true,
                includeHoldWithinDuration = true
            )
        )
        assertEquals(
            100L,
            ZoomTransitionController.calculateTransitionMotionDurationMs(
                durationMs = 300L,
                holdDurationMs = 200L,
                isUltraWideToMainSwitch = true,
                isDirectHalfXToOneXHold = false,
                includeHoldWithinDuration = true
            )
        )
    }

    @Test
    fun holdTimeHandoffOnlyAppliesToDirectHalfXToOneXTransition() {
        assertEquals(true, ZoomTransitionController.isHoldTimeHandoffEligible(0.5f, 1.0f))
        assertEquals(false, ZoomTransitionController.isHoldTimeHandoffEligible(0.5f, 2.0f))
        assertEquals(false, ZoomTransitionController.isHoldTimeHandoffEligible(1.0f, 0.5f))
    }

    @Test
    fun frameHandoffUsesFixedTimeFadeIndependentOfCameraFrameRate() {
        assertEquals(160L, ZoomTransitionController.FRAME_HANDOFF_FADE_DURATION_MS)
        assertEquals(
            0f,
            ZoomTransitionController.calculateFrameHandoffProgress(
                timeProgress = 0f,
                targetSource = PreviewStreamSource.MAIN,
                currentZoom = 1f
            ),
            0.0001f
        )
    }

    @Test
    fun frameHandoffFadeUsesCosineEasingAndMonotonicAlphaStepLimit() {
        var progress = 0f
        val halfTimeProgress = ZoomTransitionController.calculateFrameHandoffProgress(
            timeProgress = 0.5f,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1f,
            previousProgress = progress
        )
        assertEquals(0.5f, halfTimeProgress, 0.0001f)
        assertTrue(halfTimeProgress >= progress)
        assertTrue(halfTimeProgress - progress <= 0.3001f)

        progress = halfTimeProgress
        repeat(4) {
            val next = ZoomTransitionController.calculateFrameHandoffProgress(
                timeProgress = 1f,
                targetSource = PreviewStreamSource.MAIN,
                currentZoom = 1f,
                previousProgress = progress
            )
            assertTrue(next >= progress)
            assertTrue(next - progress <= 0.3001f)
            progress = next
        }
        assertEquals(1f, progress, 0.0001f)
    }

    @Test
    fun zoomTrajectoryCanAccelerateFadeWithoutReversingAlpha() {
        val progress = ZoomTransitionController.calculateFrameHandoffProgress(
            timeProgress = 0f,
            targetSource = PreviewStreamSource.MAIN,
            currentZoom = 1.2f,
            previousProgress = 0.1f
        )
        assertEquals(0.4f, progress, 0.0001f)
        assertTrue(progress >= 0.1f)
    }

    @Test
    fun zoomUpdatesUseFixed16MillisecondCadence() {
        assertEquals(16L, ZoomTransitionController.ZOOM_UPDATE_INTERVAL_MS)
    }
}
