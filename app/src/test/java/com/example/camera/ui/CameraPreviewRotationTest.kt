package com.example.camera.ui

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewRotationTest {
    @Test
    fun portraitDisplayCompensatesForFrontAndBackSensorOrientation() {
        assertEquals(
            90,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = 90,
                displayRotation = Surface.ROTATION_0,
                isFrontCamera = false
            )
        )
        assertEquals(
            270,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = 270,
                displayRotation = Surface.ROTATION_0,
                isFrontCamera = true
            )
        )
    }

    @Test
    fun landscapeDisplayKeepsBothFacingDirectionsUpright() {
        assertEquals(
            0,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = 90,
                displayRotation = Surface.ROTATION_90,
                isFrontCamera = false
            )
        )
        assertEquals(
            0,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = 270,
                displayRotation = Surface.ROTATION_90,
                isFrontCamera = true
            )
        )
        assertEquals(
            180,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = 90,
                displayRotation = Surface.ROTATION_270,
                isFrontCamera = false
            )
        )
        assertEquals(
            180,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = 270,
                displayRotation = Surface.ROTATION_270,
                isFrontCamera = true
            )
        )
    }

    @Test
    fun normalizesSensorOrientation() {
        assertEquals(
            90,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = -270,
                displayRotation = Surface.ROTATION_0,
                isFrontCamera = false
            )
        )
        assertEquals(
            90,
            calculateCameraPreviewRotationDegrees(
                sensorOrientation = 450,
                displayRotation = Surface.ROTATION_0,
                isFrontCamera = false
            )
        )
    }
}
