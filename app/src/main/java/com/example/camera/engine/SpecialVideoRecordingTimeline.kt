package com.example.camera.engine

/**
 * Waits until both Special Video camera streams have delivered a valid frame, then assigns
 * each stream its own first encoded frame timestamp as PTS zero.
 *
 * The first camera can begin delivering frames before the second camera is ready. Those
 * early frames are only used to establish readiness; the first frame from each camera
 * that is actually sent to its encoder starts at zero. This prevents the earlier stream
 * from beginning one frame late after the common readiness barrier.
 *
 * Calls are synchronized because recording start/stop can be initiated off the relay GL thread.
 */
internal class SpecialVideoRecordingTimeline {

    private var firstMainReadyTimestampNs = 0L
    private var firstUltraWideReadyTimestampNs = 0L
    private var recordingStartTimestampNs = 0L
    private var firstMainOutputTimestampNs = 0L
    private var firstUltraWideOutputTimestampNs = 0L

    @Synchronized
    fun onFrameAvailable(isUltraWide: Boolean, timestampNs: Long): Long? {
        if (timestampNs <= 0L) return null

        if (isUltraWide) {
            if (firstUltraWideReadyTimestampNs == 0L) {
                firstUltraWideReadyTimestampNs = timestampNs
            }
        } else if (firstMainReadyTimestampNs == 0L) {
            firstMainReadyTimestampNs = timestampNs
        }

        if (
            recordingStartTimestampNs == 0L &&
            firstMainReadyTimestampNs > 0L &&
            firstUltraWideReadyTimestampNs > 0L
        ) {
            recordingStartTimestampNs = maxOf(
                firstMainReadyTimestampNs,
                firstUltraWideReadyTimestampNs
            )
        }

        if (recordingStartTimestampNs == 0L) return null

        return if (isUltraWide) {
            if (firstUltraWideOutputTimestampNs == 0L) {
                firstUltraWideOutputTimestampNs = timestampNs
            }
            (timestampNs - firstUltraWideOutputTimestampNs).coerceAtLeast(0L)
        } else {
            if (firstMainOutputTimestampNs == 0L) {
                firstMainOutputTimestampNs = timestampNs
            }
            (timestampNs - firstMainOutputTimestampNs).coerceAtLeast(0L)
        }
    }

    @Synchronized
    fun getRecordingStartTimestampNs(): Long? =
        recordingStartTimestampNs.takeIf { it > 0L }

    @Synchronized
    fun reset() {
        firstMainReadyTimestampNs = 0L
        firstUltraWideReadyTimestampNs = 0L
        recordingStartTimestampNs = 0L
        firstMainOutputTimestampNs = 0L
        firstUltraWideOutputTimestampNs = 0L
    }
}
