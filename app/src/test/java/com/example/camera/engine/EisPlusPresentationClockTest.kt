package com.example.camera.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class EisPlusPresentationClockTest {
    @Test
    fun rebasesNewSensorTimestampThatPredatesOriginalRecordingEpoch() {
        val lastOutputNs = 500_000_000L
        val frameIntervalNs = 33_333_333L

        val result = calculateEisPlusPresentationTimestamp(
            sourceTimestampNs = 500_000_000L,
            recordingStartTimestampNs = 1_000_000_000L,
            totalPausedDurationNs = 0L,
            lastOutputTimestampNs = lastOutputNs,
            frameIntervalNs = frameIntervalNs,
            currentStreamClockOffsetNs = 0L,
            handoffPending = true,
        )

        assertNotNull(result)
        assertEquals(lastOutputNs + frameIntervalNs, result!!.presentationTimeNs)
        assertEquals(
            lastOutputNs + frameIntervalNs - (500_000_000L - 1_000_000_000L),
            result.streamClockOffsetNs,
        )
    }

    @Test
    fun appliesClockRebaseOffsetExactlyOnce() {
        val lastOutputNs = 2_000_000_000L
        val frameIntervalNs = 33_333_333L
        val expectedNextNs = lastOutputNs + frameIntervalNs

        val result = calculateEisPlusPresentationTimestamp(
            sourceTimestampNs = 900_000_000L,
            recordingStartTimestampNs = 1_000_000_000L,
            totalPausedDurationNs = 0L,
            lastOutputTimestampNs = lastOutputNs,
            frameIntervalNs = frameIntervalNs,
            currentStreamClockOffsetNs = 0L,
            handoffPending = true,
        )

        assertNotNull(result)
        assertEquals(expectedNextNs, result!!.presentationTimeNs)
        assertEquals(expectedNextNs + 100_000_000L, result.streamClockOffsetNs)
    }

    @Test
    fun dropsAnOldTimestampWhenNoHandoffIsPending() {
        val result = calculateEisPlusPresentationTimestamp(
            sourceTimestampNs = 900_000_000L,
            recordingStartTimestampNs = 1_000_000_000L,
            totalPausedDurationNs = 0L,
            lastOutputTimestampNs = 500_000_000L,
            frameIntervalNs = 33_333_333L,
            currentStreamClockOffsetNs = 0L,
            handoffPending = false,
        )

        assertNull(result)
    }

    @Test
    fun preservesMonotonicTimelineWithoutUnnecessaryRebase() {
        val result = calculateEisPlusPresentationTimestamp(
            sourceTimestampNs = 1_100_000_000L,
            recordingStartTimestampNs = 1_000_000_000L,
            totalPausedDurationNs = 0L,
            lastOutputTimestampNs = 50_000_000L,
            frameIntervalNs = 33_333_333L,
            currentStreamClockOffsetNs = 7_000_000L,
            handoffPending = false,
        )

        assertNotNull(result)
        assertEquals(107_000_000L, result!!.presentationTimeNs)
        assertEquals(7_000_000L, result.streamClockOffsetNs)
    }
}
