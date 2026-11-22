package com.example.camera.engine

/**
 * Creates one shared presentation timeline for Special Video.
 *
 * Both cameras must produce at least one valid frame before recording starts. The first
 * MAIN frame after that readiness barrier becomes PTS zero and is the master clock for
 * both encoders. Ultra-wide callbacks only refresh its cached OES texture; they never
 * advance an independent output clock.
 *
 * Calls are synchronized because Camera2 callbacks and recording lifecycle operations can
 * arrive from different threads.
 */
internal class SpecialVideoRecordingTimeline {

    private var firstMainReadyTimestampNs = 0L
    private var firstUltraWideReadyTimestampNs = 0L
    private var recordingStartTimestampNs = 0L

    /**
     * Marks the incoming camera as ready. Returns a PTS only for main-camera frames.
     * The caller must use that returned PTS for BOTH the main and ultra-wide encoders.
     */
    @Synchronized
    fun onFrameAvailable(isUltraWide: Boolean, timestampNs: Long): Long? {
        if (timestampNs <= 0L) return null

        if (isUltraWide) {
            if (firstUltraWideReadyTimestampNs == 0L) {
                firstUltraWideReadyTimestampNs = timestampNs
            }
            return null
        }

        if (firstMainReadyTimestampNs == 0L) {
            firstMainReadyTimestampNs = timestampNs
        }

        if (firstMainReadyTimestampNs == 0L || firstUltraWideReadyTimestampNs == 0L) {
            return null
        }

        // Start on a MAIN frame, not on the ultra-wide callback that completes readiness.
        // This makes the very first frame sent to both encoders exactly PTS 0.
        if (recordingStartTimestampNs == 0L) {
            recordingStartTimestampNs = timestampNs
        }

        return (timestampNs - recordingStartTimestampNs).coerceAtLeast(0L)
    }

    @Synchronized
    fun getRecordingStartTimestampNs(): Long? =
        recordingStartTimestampNs.takeIf { it > 0L }

    @Synchronized
    fun reset() {
        firstMainReadyTimestampNs = 0L
        firstUltraWideReadyTimestampNs = 0L
        recordingStartTimestampNs = 0L
    }
}
