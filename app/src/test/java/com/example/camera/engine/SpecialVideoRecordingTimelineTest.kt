package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialVideoRecordingTimelineTest {

    @Test
    fun waitsForBothStreamsThenStartsEachEncodedStreamAtZero() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_000_000_000L))
        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_500_000_000L))

        // Ultra-wide completes the readiness barrier. Its first encoded frame starts at PTS 0.
        assertEquals(
            0L,
            timeline.onFrameAvailable(isUltraWide = true, timestampNs = 2_000_000_000L)
        )
        assertEquals(2_000_000_000L, timeline.getRecordingStartTimestampNs())

        // Main's first post-barrier frame must also start at PTS 0, not one frame late.
        assertEquals(
            0L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 2_033_000_000L)
        )
        assertEquals(
            33_000_000L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 2_066_000_000L)
        )
        assertEquals(
            33_000_000L,
            timeline.onFrameAvailable(isUltraWide = true, timestampNs = 2_033_000_000L)
        )
    }

    @Test
    fun usesTheSameZeroPtsWhenUltraWideIsTheEarlierStream() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = 5_000_000_000L))
        assertEquals(
            0L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 5_100_000_000L)
        )
        assertEquals(5_100_000_000L, timeline.getRecordingStartTimestampNs())

        assertEquals(
            0L,
            timeline.onFrameAvailable(isUltraWide = true, timestampNs = 5_033_000_000L)
        )
        assertEquals(
            33_000_000L,
            timeline.onFrameAvailable(isUltraWide = true, timestampNs = 5_066_000_000L)
        )
        assertEquals(
            33_000_000L,
            timeline.onFrameAvailable(isUltraWide = false, timestampNs = 5_133_000_000L)
        )
    }

    @Test
    fun resetStartsANewIndependentRecordingTimeline() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 100L))
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = true, timestampNs = 200L))
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = false, timestampNs = 233L))

        timeline.reset()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_000L))
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = true, timestampNs = 1_050L))
        assertEquals(1_050L, timeline.getRecordingStartTimestampNs())
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = false, timestampNs = 1_083L))
    }

    @Test
    fun ignoresInvalidFrameTimestamps() {
        val timeline = SpecialVideoRecordingTimeline()

        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 0L))
        assertNull(timeline.onFrameAvailable(isUltraWide = true, timestampNs = -1L))
        assertNull(timeline.onFrameAvailable(isUltraWide = false, timestampNs = 100L))
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = true, timestampNs = 200L))
        assertEquals(0L, timeline.onFrameAvailable(isUltraWide = false, timestampNs = 233L))
    }
}
