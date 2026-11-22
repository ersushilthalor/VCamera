package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.util.Log
import com.example.camera.stabilization.EisPlusDiagnosticLog
import com.example.camera.stabilization.RealtimeStabilizationCoordinator
import com.example.camera.stabilization.StabilizationFrame
import com.example.camera.stabilization.StabilizationImageReader
import com.example.camera.stabilization.StabilizationUseCase

/**
 * Camera2 YUV/gyro bridge for Photon MGC EIS+. Its reader is an additional camera output;
 * the coordinator only emits a frame after matching the image SENSOR_TIMESTAMP with its
 * capture metadata and look-ahead pose.
 */
internal class VideoEisPlusPipeline(private val context: android.content.Context) {
    private val coordinator = RealtimeStabilizationCoordinator(context.applicationContext)
    @Volatile private var imageReader: StabilizationImageReader? = null
    @Volatile private var session: RealtimeStabilizationCoordinator.Session? = null
    @Volatile var isUnavailable: Boolean = false
        private set
    @Volatile var lastStartFailureReason: String? = null
        private set
    private var dequeuedFrameCount = 0L

    /** Camera2Engine uses this only for a confirmed video-stabilization HAL conflict. */
    @Volatile var onRuntimeUnavailable: (() -> Unit)? = null

    init {
        coordinator.onEnhancedStabilizationUnavailable = {
            EisPlusDiagnosticLog.event("PIPELINE", "RUNTIME_UNAVAILABLE", "HAL reported video stabilization remained active after OFF request", "ERROR")
            isUnavailable = true
            onRuntimeUnavailable?.invoke()
        }
    }

    val surface get() = imageReader?.surface
    val shouldRequestOisSamples get() = coordinator.shouldRequestOisSamples
    val supportsOpticalTelemetry get() = coordinator.supportsOpticalTelemetry
    val shouldDisableOisForMissingTelemetry get() = coordinator.shouldDisableOisForMissingTelemetry
    val isRunning get() = session?.isOperational == true
    /** Number of timestamp-matched native results waiting for the relay consumer. */
    val pendingFrameCount: Int get() = session?.pendingResultCount() ?: 0

    fun supportFailureReason(
        characteristics: CameraCharacteristics,
        timingCharacteristics: CameraCharacteristics = characteristics
    ): String? = coordinator.cameraSupportFailureReason(characteristics, timingCharacteristics)

    fun isSupported(
        characteristics: CameraCharacteristics,
        timingCharacteristics: CameraCharacteristics = characteristics
    ): Boolean = supportFailureReason(characteristics, timingCharacteristics) == null

    fun start(
        cameraId: String,
        characteristics: CameraCharacteristics,
        timingCharacteristics: CameraCharacteristics,
        width: Int,
        height: Int,
        frontFacing: Boolean,
        strength: Float,
        lookaheadFrames: Int,
        imageHandler: Handler,
        onFrameAvailable: () -> Unit,
        onUnavailable: () -> Unit
    ): Boolean {
        if (session != null || imageReader != null) stop()
        EisPlusDiagnosticLog.beginIfNeeded(
            context.applicationContext,
            "cameraId=$cameraId size=${width}x$height frontFacing=$frontFacing strength=$strength lookahead=$lookaheadFrames",
        )
        dequeuedFrameCount = 0L
        EisPlusDiagnosticLog.event("PIPELINE", "START_REQUEST", "cameraId=$cameraId")
        lastStartFailureReason = supportFailureReason(characteristics, timingCharacteristics)
            ?: if (width <= 0 || height <= 0) "no matching positive-sized YUV output" else null
        if (lastStartFailureReason != null) {
            isUnavailable = true
            EisPlusDiagnosticLog.event("PIPELINE", "START_REJECTED", lastStartFailureReason.orEmpty(), "ERROR")
            Log.w("VideoEisPlusPipeline", "Photon EIS+ start unavailable: $lastStartFailureReason")
            onUnavailable()
            EisPlusDiagnosticLog.finish("pipeline_start_rejected: ${lastStartFailureReason.orEmpty()}")
            return false
        }
        return try {
            coordinator.configureCamera(cameraId, characteristics, timingCharacteristics)
            val reader = StabilizationImageReader(
                width,
                height,
                RealtimeStabilizationCoordinator.STABILIZATION_IMAGE_READER_MAX_IMAGES
            )
            imageReader = reader
            reader.setOnImageAvailableListener(imageHandler) { source ->
                while (true) {
                    val image = source.acquireNextImage() ?: break
                    coordinator.submitStabilizationImage(image)
                }
            }
            val eisSession = coordinator.createSession(
                useCase = StabilizationUseCase.VIDEO,
                strength = strength,
                frameWidth = width,
                frameHeight = height,
                lookaheadFrames = lookaheadFrames,
                onFrameAvailable = onFrameAvailable
            )
            session = eisSession
            if (!eisSession.start()) {
                lastStartFailureReason = "MGC native engine or calibrated gyro stream failed to start"
                EisPlusDiagnosticLog.event("PIPELINE", "SESSION_START_FAILED", lastStartFailureReason.orEmpty(), "ERROR")
                stop()
                isUnavailable = true
                Log.w("VideoEisPlusPipeline", "Photon EIS+ unavailable: $lastStartFailureReason")
                onUnavailable()
                false
            } else {
                lastStartFailureReason = null
                isUnavailable = false
                EisPlusDiagnosticLog.event("PIPELINE", "SESSION_ACTIVE", "cameraId=$cameraId size=${width}x$height")
                Log.i(
                    "VideoEisPlusPipeline",
                    "Photon EIS+ input started: camera=$cameraId size=${width}x$height " +
                        "lookahead=$lookaheadFrames opticalTelemetry=$supportsOpticalTelemetry",
                )
                true
            }
        } catch (t: Throwable) {
            lastStartFailureReason = t.message?.takeIf { it.isNotBlank() }
                ?: "pipeline initialization exception"
            EisPlusDiagnosticLog.event("PIPELINE", "INITIALIZATION_EXCEPTION", "${t.javaClass.simpleName}: ${t.message}", "ERROR")
            Log.e("VideoEisPlusPipeline", "Failed to initialize Photon EIS+: $lastStartFailureReason", t)
            stop()
            isUnavailable = true
            onUnavailable()
            false
        }
    }

    fun submitCaptureResult(
        result: TotalCaptureResult,
        request: CaptureRequest,
        generation: Long,
        outputPhysicalCameraId: String? = null
    ) {
        if (!isRunning) return
        coordinator.submitCaptureResult(result, generation, request, outputPhysicalCameraId)
    }

    fun dequeueFrame(): StabilizationFrame? {
        val frame = session?.dequeueFrame()
        if (frame != null) {
            dequeuedFrameCount += 1L
            if (dequeuedFrameCount <= 3L || dequeuedFrameCount % 30L == 0L) {
                EisPlusDiagnosticLog.event(
                    "PIPELINE", "FRAME_DELIVERED",
                    "count=$dequeuedFrameCount timestampNs=${frame.timestampNs} transformApplied=${frame.transform != null} image=${frame.image.width}x${frame.image.height}",
                    if (frame.transform != null) "INFO" else "WARN",
                )
            }
        }
        return frame
    }

    fun stop() {
        EisPlusDiagnosticLog.event(
            "PIPELINE", "STOP",
            "running=$isRunning unavailable=$isUnavailable deliveredFrames=$dequeuedFrameCount failure=${lastStartFailureReason.orEmpty()}",
        )
        try { session?.stop() } catch (error: Throwable) {
            EisPlusDiagnosticLog.event("PIPELINE", "SESSION_STOP_EXCEPTION", error.toString(), "WARN")
        }
        session = null
        val reader = imageReader
        imageReader = null
        if (reader != null) {
            try { coordinator.discardStabilizationImages(reader) } catch (_: Throwable) {}
            try { reader.close() } catch (error: Throwable) {
                EisPlusDiagnosticLog.event("PIPELINE", "IMAGE_READER_CLOSE_EXCEPTION", error.toString(), "WARN")
            }
        }
        EisPlusDiagnosticLog.finish("video_eis_plus_pipeline_stopped")
    }
}
