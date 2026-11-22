package com.example.camera.stabilization

import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Bounded native-input trace for post-recording EIS diagnostics.
 *
 * This contains no image payload and stays outside the stabilizer's timing path. It retains the
 * Java-to-native call sequence in memory, then writes one immutable CSV snapshot on a background
 * thread. The diagnostic trace is never read by or executed by the camera pipeline.
 */
internal class MgcEisDiagnosticTrace(
    private val cacheDirectory: File,
) {
    companion object {
        private const val TAG = "MgcEisTrace"
        private const val MAX_FRAME_CALLS = 180
        private const val MAX_GYRO_SAMPLES = 8_192
    }

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Photon-MGC-EIS-Trace").apply { isDaemon = true }
    }
    private val entries = ArrayList<String>(MAX_FRAME_CALLS + MAX_GYRO_SAMPLES)

    private var active = false
    private var snapshotQueued = false
    private var gyroCount = 0
    private var frameCount = 0

    @Synchronized
    fun start(width: Int, height: Int, frontFacing: Boolean, strength: Float) {
        entries.clear()
        active = true
        snapshotQueued = false
        gyroCount = 0
        frameCount = 0
        EisPlusDiagnosticLog.event("NATIVE_TRACE", "CAPTURE_STARTED", "size=${width}x$height frontFacing=$frontFacing strength=$strength")
        entries += listOf(
            "HEADER",
            width.toString(),
            height.toString(),
            frontFacing.toString(),
            strength.toString(),
        ).joinToString(",")
    }

    @Synchronized
    fun recordGyro(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (!active || snapshotQueued || gyroCount >= MAX_GYRO_SAMPLES) return
        entries += listOf("G", timestampNs, x, y, z).joinToString(",")
        gyroCount += 1
    }

    @Synchronized
    fun recordFrame(
        input: MgcEisNativeEngine.FrameInput,
        firstRowCenterTimestampNs: Long,
        croppedRollingShutterSkewNs: Long,
        inverseFocalLength: Float,
        activeWidth: Int,
        activeHeight: Int,
        resultTimestampNs: Long,
        matrices: FloatArray,
    ) {
        if (!active || snapshotQueued || frameCount >= MAX_FRAME_CALLS) return
        val fields = ArrayList<String>(20 + matrices.size)
        fields += "F"
        fields += input.sensorTimestampNs.toString()
        fields += firstRowCenterTimestampNs.toString()
        fields += input.exposureTimeNs.toString()
        fields += croppedRollingShutterSkewNs.toString()
        fields += inverseFocalLength.toString()
        fields += activeWidth.toString()
        fields += activeHeight.toString()
        fields += input.cropRegion.width().toString()
        fields += input.cropRegion.height().toString()
        fields += resultTimestampNs.toString()
        if (resultTimestampNs >= 0L) {
            matrices.forEach { fields += it.toString() }
        }
        entries += fields.joinToString(",")
        frameCount += 1
        if (frameCount == MAX_FRAME_CALLS) queueSnapshotLocked()
    }

    @Synchronized
    fun stop() {
        if (active && !snapshotQueued && frameCount > 0) queueSnapshotLocked()
        active = false
    }

    @Synchronized
    private fun queueSnapshotLocked() {
        if (snapshotQueued) return
        snapshotQueued = true
        val content = entries.joinToString(separator = "\n", postfix = "\n")
        val savedGyroCount = gyroCount
        val savedFrameCount = frameCount
        writer.execute {
            try {
                val directory = File(cacheDirectory, EisPlusDiagnosticLog.LOG_DIRECTORY_NAME).apply { mkdirs() }
                val file = File(directory, "mgc-eis-${System.currentTimeMillis()}.csv")
                file.writeText(content, Charsets.UTF_8)
                EisPlusDiagnosticLog.event("NATIVE_TRACE", "SAVED", "file=${file.name}, gyro=$savedGyroCount, frames=$savedFrameCount")
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Saved differential trace: %s (gyro=%d, frames=%d)",
                        file.absolutePath,
                        savedGyroCount,
                        savedFrameCount,
                    ),
                )
            } catch (error: Throwable) {
                EisPlusDiagnosticLog.event("NATIVE_TRACE", "SAVE_FAILED", error.toString(), "ERROR")
                Log.e(TAG, "Unable to save MGC differential trace", error)
            }
        }
    }
}
