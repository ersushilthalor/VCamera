package com.example.camera.engine

/**
 * Establishes one Special Video presentation-time origin only after both camera streams
 * have delivered a valid frame for the current recording.
 *
 * Camera2 can start the main and ultra-wide outputs at different moments. Starting the
 * main encoder on its first frame while ultra-wide is still warming up creates a leading
 * gap in the ultra-wide file and makes the main file appear longer. Choosing the later
 * first-frame timestamp makes both files begin at the same logical moment.
 *
 * Calls are synchronized because recording start/stop can be initiated off the relay GL thread.
 */
internal class SpecialVideoRecordingTimeline {

    private var firstMainFrameTimestampNs = 0L
    private var firstUltraWideFrameTimestampNs = 0L
    private var recordingStartTimestampNs = 0L

    @Synchronized
    fun onFrameAvailable(isUltraWide: Boolean, timestampNs: Long): Long? {
        if (timestampNs <= 0L) return null

        if (recordingStartTimestampNs > 0L) {
            return recordingStartTimestampNs
        }

        if (isUltraWide) {
            if (firstUltraWideFrameTimestampNs == 0L) {
                firstUltraWideFrameTimestampNs = timestampNs
            }
        } else if (firstMainFrameTimestampNs == 0L) {
            firstMainFrameTimestampNs = timestampNs
        }

        if (firstMainFrameTimestampNs > 0L && firstUltraWideFrameTimestampNs > 0L) {
            recordingStartTimestampNs = maxOf(
                firstMainFrameTimestampNs,
                firstUltraWideFrameTimestampNs
            )
        }

        return recordingStartTimestampNs.takeIf { it > 0L }
    }

    @Synchronized
    fun reset() {
        firstMainFrameTimestampNs = 0L
        firstUltraWideFrameTimestampNs = 0L
        recordingStartTimestampNs = 0L
    }
}
