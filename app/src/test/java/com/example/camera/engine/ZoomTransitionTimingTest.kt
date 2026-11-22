package com.example.camera.engine

import com.example.camera.model.LensType
import com.example.camera.ui.GALLERY_LENS_SWITCH_DURATION_MS
import com.example.camera.ui.calculateGalleryLensSwitchZoomScale
import org.junit.Assert.assertEquals
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
    fun specialVideoUltraWideRecordingKeepsNativeUncroppedFieldOfView() {
        assertEquals(
            1.0f,
            CameraOpticalCalibration.resolveSpecialVideoStandbyCropRatio(
                calibratedCropRatio = 1.4375f,
                standbyLensType = LensType.ULTRAWIDE,
                isSpecialVideoRecordingActive = true
            ),
            0.0f
        )
    }

    @Test
    fun specialVideoCropOverrideDoesNotChangeNormalStandbyOrMainLensCrop() {
        assertEquals(
            1.4375f,
            CameraOpticalCalibration.resolveSpecialVideoStandbyCropRatio(
                calibratedCropRatio = 1.4375f,
                standbyLensType = LensType.ULTRAWIDE,
                isSpecialVideoRecordingActive = false
            ),
            0.0f
        )
        assertEquals(
            1.4375f,
            CameraOpticalCalibration.resolveSpecialVideoStandbyCropRatio(
                calibratedCropRatio = 1.4375f,
                standbyLensType = LensType.WIDE,
                isSpecialVideoRecordingActive = true
            ),
            0.0f
        )
    }

    @Test
    fun galleryLensSwitchUses300MillisecondZoomAnimation() {
        assertEquals(300, GALLERY_LENS_SWITCH_DURATION_MS)
    }

    @Test
    fun mainToUltraWideAnimatesFromMatchingMainZoomToFullUltraWide() {
        assertEquals(
            2.0f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = true,
                transitionFromUltraWide = false,
                progress = 0.0f,
                isTransitionRunning = true
            ),
            0.0001f
        )
        assertEquals(
            1.5f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = true,
                transitionFromUltraWide = false,
                progress = 0.5f,
                isTransitionRunning = true
            ),
            0.0001f
        )
        assertEquals(
            1.0f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = true,
                transitionFromUltraWide = false,
                progress = 1.0f,
                isTransitionRunning = true
            ),
            0.0001f
        )
    }

    @Test
    fun ultraWideToMainAnimatesZoomInBeforeReturningToMainFrame() {
        assertEquals(
            1.0f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = true,
                transitionFromUltraWide = true,
                progress = 0.0f,
                isTransitionRunning = true
            ),
            0.0001f
        )
        assertEquals(
            1.5f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = true,
                transitionFromUltraWide = true,
                progress = 0.5f,
                isTransitionRunning = true
            ),
            0.0001f
        )
        assertEquals(
            2.0f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = true,
                transitionFromUltraWide = true,
                progress = 1.0f,
                isTransitionRunning = true
            ),
            0.0001f
        )
    }

    @Test
    fun galleryZoomIsOnlyAppliedToUltraWidePlayerDuringTransition() {
        assertEquals(
            1.0f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = false,
                transitionFromUltraWide = false,
                progress = 0.0f,
                isTransitionRunning = true
            ),
            0.0001f
        )
        assertEquals(
            1.0f,
            calculateGalleryLensSwitchZoomScale(
                isUltraWidePlayer = true,
                transitionFromUltraWide = false,
                progress = 0.5f,
                isTransitionRunning = false
            ),
            0.0001f
        )
    }

    @Test
    fun zoomUpdatesUseFixed16MillisecondCadence() {
        assertEquals(16L, ZoomTransitionController.ZOOM_UPDATE_INTERVAL_MS)
    }
}
