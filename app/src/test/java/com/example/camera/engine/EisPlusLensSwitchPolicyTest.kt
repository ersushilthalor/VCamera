package com.example.camera.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EisPlusLensSwitchPolicyTest {
    @Test
    fun reconfiguresSameLogicalCameraWhenEisPlusPreviewOwnsSingleSensorOutput() {
        assertTrue(
            requiresPhysicalLensReconfigurationForEisPlusPreview(
                isVideoPreview = true,
                previewRelayActive = true,
                isRecording = false,
                sameCameraDevice = true,
                switchingMainAndUltraWide = true,
            )
        )
    }

    @Test
    fun keepsExistingInstantPathWhenDualOutputRelayIsNotActive() {
        assertFalse(
            requiresPhysicalLensReconfigurationForEisPlusPreview(
                isVideoPreview = true,
                previewRelayActive = false,
                isRecording = false,
                sameCameraDevice = true,
                switchingMainAndUltraWide = true,
            )
        )
    }

    @Test
    fun doesNotChangeActiveRecordingHandoffPath() {
        assertFalse(
            requiresPhysicalLensReconfigurationForEisPlusPreview(
                isVideoPreview = true,
                previewRelayActive = true,
                isRecording = true,
                sameCameraDevice = true,
                switchingMainAndUltraWide = true,
            )
        )
    }

    @Test
    fun doesNotChangeDifferentDeviceOrNonWideLensTransitions() {
        assertFalse(
            requiresPhysicalLensReconfigurationForEisPlusPreview(
                isVideoPreview = true,
                previewRelayActive = true,
                isRecording = false,
                sameCameraDevice = false,
                switchingMainAndUltraWide = true,
            )
        )
        assertFalse(
            requiresPhysicalLensReconfigurationForEisPlusPreview(
                isVideoPreview = true,
                previewRelayActive = true,
                isRecording = false,
                sameCameraDevice = true,
                switchingMainAndUltraWide = false,
            )
        )
    }
}
