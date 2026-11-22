package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialVideoRecordingTimelineTest {

    @Test
    fun waitsForBothStreamsAndStartsAtTheLaterFirstFrame() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_000_000_000L))
        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_500_000_000L))

        assertEquals(
            2_000_000_000L,
            timeline.onFrameAvailable(isUltraWide = true, timestampNs = 2_000_000_000L)
        )
        assertEquals(
            2_000_000_000L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 2_033_000_000L)
        )
    }

    @Test
    fun usesTheLaterTimestampWhenUltraWideArrivesFirst() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 5_000_000_000L))
        assertEquals(
            5_100_000_000L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 5_100_000_000L)
        )
    }

    @Test
    fun resetStartsANewIndependentRecordingTimeline() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 100L))
        assertEquals(200L, timeline.onFrameAvailable(isUltraWide = true, timestampNs = 200L))

        timeline.reset()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_000L))
        assertEquals(1_050L, timeline.onFrameAvailable(isUltraWide = true, timestampNs = 1_050L))
    }

    @Test
    fun ignoresInvalidFrameTimestamps() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 0L))
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = -1L))
        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 100L))
        assertEquals(200L, timeline.onFrameAvailable(isUltraWide = true, timestampNs = 200L))
    }
}
