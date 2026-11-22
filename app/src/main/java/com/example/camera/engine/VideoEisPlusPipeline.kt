package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.util.Log
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

    /** Camera2Engine uses this only for a confirmed video-stabilization HAL conflict. */
    @Volatile var onRuntimeUnavailable: (() -> Unit)? = null

    init {
        coordinator.onEnhancedStabilizationUnavailable = {
            isUnavailable = true
            onRuntimeUnavailable?.invoke()
        }
    }

    val surface get() = imageReader?.surface
    val shouldRequestOisSamples get() = coordinator.shouldRequestOisSamples
    val supportsOpticalTelemetry get() = coordinator.supportsOpticalTelemetry
    val shouldDisableOisForMissingTelemetry get() = coordinator.shouldDisableOisForMissingTelemetry
    val isRunning get() = session?.isOperational == true

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
        stop()
        lastStartFailureReason = supportFailureReason(characteristics, timingCharacteristics)
            ?: if (width <= 0 || height <= 0) "no matching positive-sized YUV output" else null
        if (lastStartFailureReason != null) {
            isUnavailable = true
            Log.w("VideoEisPlusPipeline", "Photon EIS+ start unavailable: $lastStartFailureReason")
            onUnavailable()
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
                stop()
                isUnavailable = true
                Log.w("VideoEisPlusPipeline", "Photon EIS+ unavailable: $lastStartFailureReason")
                onUnavailable()
                false
            } else {
                lastStartFailureReason = null
                isUnavailable = false
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

    fun dequeueFrame(): StabilizationFrame? = session?.dequeueFrame()

    fun stop() {
        try { session?.stop() } catch (_: Throwable) {}
        session = null
        val reader = imageReader
        imageReader = null
        if (reader != null) {
            try { coordinator.discardStabilizationImages(reader) } catch (_: Throwable) {}
            try { reader.close() } catch (_: Throwable) {}
        }
    }
}
