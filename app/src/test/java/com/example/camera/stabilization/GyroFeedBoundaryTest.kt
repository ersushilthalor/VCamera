package com.example.camera.stabilization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroFeedBoundaryTest {

    @Test
    fun acceptsSamplesAtOrBeforeTheLatestCameraBoundary() {
        val boundaryNs = 1_000_000_000L

        assertTrue(isGyroSampleWithinFeedBoundary(999_000_000L, boundaryNs))
        assertTrue(isGyroSampleWithinFeedBoundary(boundaryNs, boundaryNs))
    }

    @Test
    fun holdsFutureGyroSamplesUntilTheCameraBoundaryCatchesUp() {
        val boundaryNs = 1_000_000_000L

        assertFalse(isGyroSampleWithinFeedBoundary(1_033_333_333L, boundaryNs))
        assertFalse(isGyroSampleWithinFeedBoundary(1_200_000_000L, boundaryNs))
    }

    @Test
    fun rejectsUnsetOrInvalidTimestamps() {
        assertFalse(isGyroSampleWithinFeedBoundary(0L, 1_000_000_000L))
        assertFalse(isGyroSampleWithinFeedBoundary(-1L, 1_000_000_000L))
        assertFalse(isGyroSampleWithinFeedBoundary(1L, 0L))
        assertFalse(isGyroSampleWithinFeedBoundary(1L, -1L))
    }
}
