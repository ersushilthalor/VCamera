package com.example.camera.engine

import kotlin.math.abs
import kotlin.math.max

internal data class EisPlusPresentationClockResult(
    val presentationTimeNs: Long,
    val streamClockOffsetNs: Long,
)

/**
 * Maps a sensor timestamp onto the relay's continuous encoder timeline.
 *
 * A physical/logical camera switch can change the sensor timestamp epoch even when the
 * logical source label stays the same. During a handoff, rebase that sensor clock against
 * the last emitted PTS before rejecting timestamps that precede the original camera epoch.
 */
internal fun calculateEisPlusPresentationTimestamp(
    sourceTimestampNs: Long,
    recordingStartTimestampNs: Long,
    totalPausedDurationNs: Long,
    lastOutputTimestampNs: Long,
    frameIntervalNs: Long,
    currentStreamClockOffsetNs: Long,
    handoffPending: Boolean,
): EisPlusPresentationClockResult? {
    if (recordingStartTimestampNs <= 0L) return null

    val safeFrameIntervalNs = max(1_000_000L, frameIntervalNs)
    val rawPresentationNs = sourceTimestampNs - recordingStartTimestampNs - totalPausedDurationNs

    // Older timestamps are invalid during a stable sensor epoch, but may be perfectly valid
    // immediately after a camera handoff. Let the pending handoff rebase them first.
    if (!handoffPending && sourceTimestampNs < recordingStartTimestampNs) return null

    var streamClockOffsetNs = currentStreamClockOffsetNs
    if (handoffPending) {
        val expectedNextNs = if (lastOutputTimestampNs >= 0L) {
            lastOutputTimestampNs + safeFrameIntervalNs
        } else {
            0L
        }
        val currentCandidateNs = rawPresentationNs + streamClockOffsetNs
        val needsRebase = currentCandidateNs < 0L ||
            (lastOutputTimestampNs >= 0L &&
                abs(currentCandidateNs - expectedNextNs) > 150_000_000L)

        if (needsRebase) {
            // Set the offset from the raw timestamp exactly once. The returned PTS below
            // applies that offset once; do not also replace the PTS with expectedNextNs here.
            streamClockOffsetNs = expectedNextNs - rawPresentationNs
        }
    }

    var presentationTimeNs = rawPresentationNs + streamClockOffsetNs
    if (presentationTimeNs < 0L) presentationTimeNs = 0L
    if (lastOutputTimestampNs >= 0L && presentationTimeNs <= lastOutputTimestampNs) {
        presentationTimeNs = lastOutputTimestampNs + max(1_000_000L, safeFrameIntervalNs / 4L)
    }

    return EisPlusPresentationClockResult(
        presentationTimeNs = presentationTimeNs,
        streamClockOffsetNs = streamClockOffsetNs,
    )
}
