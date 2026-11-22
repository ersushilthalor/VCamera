package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialVideoRecordingTimelineTest {

    @Test
    fun waitsForBothStreamsThenStartsBothOutputsOnTheFirstReadyMainFrame() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_000_000_000L))
        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_500_000_000L))

        // The ultra-wide frame completes readiness, but never emits an independent PTS.
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 2_000_000_000L))
        assertNull(timeline.getRecordingStartTimestampNs())

        // The next MAIN frame is rendered to both encoder surfaces at exactly PTS zero.
        assertEquals(
            0L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 2_033_000_000L)
        )
        assertEquals(2_033_000_000L, timeline.getRecordingStartTimestampNs())
        assertEquals(
            33_000_000L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 2_066_000_000L)
        )
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 2_033_000_000L))
    }

    @Test
    fun ultraWideReadinessDoesNotCreateASeparateClockWhenItArrivesFirst() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 5_000_000_000L))
        assertEquals(
            0L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 5_100_000_000L)
        )
        assertEquals(5_100_000_000L, timeline.getRecordingStartTimestampNs())

        // Ultra-wide callbacks only refresh the cached frame. They cannot change either PTS.
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 5_133_000_000L))
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 5_166_000_000L))
        assertEquals(
            33_000_000L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 5_133_000_000L)
        )
    }

    @Test
    fun resetStartsANewIndependentRecordingTimeline() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 100L))
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 200L))
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = false, timestampNs = 233L))

        timeline.reset()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_000L))
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 1_050L))
        assertEquals(1_083L, timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_083L))
        assertEquals(1_083L, timeline.getRecordingStartTimestampNs())
    }

    @Test
    fun ignoresInvalidFrameTimestamps() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 0L))
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = -1L))
        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 100L))
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 200L))
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = false, timestampNs = 233L))
    }
}
