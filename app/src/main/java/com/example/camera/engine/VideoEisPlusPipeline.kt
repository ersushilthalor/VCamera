package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.util.Log
import com.example.camera.stabilization.EisPlusDiagnosticLog
import com.example.camera.stabilization.RealtimeStabilizationCoordinator
import com.example.camera.stabilization.StabilizationFrame
import com.example.camera.stabilization.StabilizationImageReader
import com.example.camera.stabilization.StabilizationUseCase
import java.util.concurrent.atomic.AtomicLong

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
    private val dequeuedFrameCount = AtomicLong(0L)
    private val captureResultCount = AtomicLong(0L)
    private val yuvImageCount = AtomicLong(0L)
    @Volatile private var diagnosticSource: String = "UNKNOWN"
    @Volatile private var diagnosticRole: String = "UNBOUND"
    @Volatile private var diagnosticSessionCameraId: String = "unknown"
    @Volatile private var diagnosticPipelineCameraId: String = "unknown"
    @Volatile private var diagnosticPhysicalCameraId: String? = null
    @Volatile private var diagnosticGeneration: Long = -1L

    fun configureDiagnostics(
        source: String,
        role: String,
        sessionCameraId: String,
        pipelineCameraId: String,
        physicalCameraId: String?,
        generation: Long,
    ) {
        diagnosticSource = source
        diagnosticRole = role
        diagnosticSessionCameraId = sessionCameraId
        diagnosticPipelineCameraId = pipelineCameraId
        diagnosticPhysicalCameraId = physicalCameraId
        diagnosticGeneration = generation
        coordinator.setDiagnosticContext(source, role, sessionCameraId, pipelineCameraId, physicalCameraId, generation)
        diagnosticEvent("PIPELINE", "DIAGNOSTIC_IDENTITY",
            "source=$source role=$role sessionCamera=$sessionCameraId pipelineCamera=$pipelineCameraId " +
                "physicalCamera=$physicalCameraId generation=$generation")
    }

    private fun diagnosticEvent(component: String, name: String, details: String = "", level: String = "INFO") {
        EisPlusDiagnosticLog.event(
            component,
            name,
            "source=$diagnosticSource role=$diagnosticRole sessionCamera=$diagnosticSessionCameraId " +
                "pipelineCamera=$diagnosticPipelineCameraId physicalCamera=$diagnosticPhysicalCameraId " +
                "generation=$diagnosticGeneration $details",
            level,
        )
    }

    val captureResultsReceived: Long get() = captureResultCount.get()
    val yuvImagesReceived: Long get() = yuvImageCount.get()
    val framesDelivered: Long get() = dequeuedFrameCount.get()

    fun diagnosticSummary(): String =
        "source=$diagnosticSource role=$diagnosticRole sessionCamera=$diagnosticSessionCameraId " +
            "pipelineCamera=$diagnosticPipelineCameraId physicalCamera=$diagnosticPhysicalCameraId " +
            "generation=$diagnosticGeneration running=$isRunning unavailable=$isUnavailable " +
            "failure=${lastStartFailureReason.orEmpty()} captureResults=${captureResultCount.get()} " +
            "yuvImages=${yuvImageCount.get()} delivered=${dequeuedFrameCount.get()} pending=$pendingFrameCount"

    /** Camera2Engine uses this only for a confirmed video-stabilization HAL conflict. */
    @Volatile var onRuntimeUnavailable: (() -> Unit)? = null

    init {
        coordinator.onEnhancedStabilizationUnavailable = {
            diagnosticEvent("PIPELINE", "RUNTIME_UNAVAILABLE", "HAL reported video stabilization remained active after OFF request", "ERROR")
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
        dequeuedFrameCount.set(0L)
        captureResultCount.set(0L)
        yuvImageCount.set(0L)
        diagnosticEvent("PIPELINE", "START_REQUEST", "cameraId=$cameraId")
        lastStartFailureReason = supportFailureReason(characteristics, timingCharacteristics)
            ?: if (width <= 0 || height <= 0) "no matching positive-sized YUV output" else null
        if (lastStartFailureReason != null) {
            isUnavailable = true
            diagnosticEvent("PIPELINE", "START_REJECTED", lastStartFailureReason.orEmpty(), "ERROR")
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
                    val imageCount = yuvImageCount.incrementAndGet()
                    if (imageCount <= 3L || imageCount % 30L == 0L) {
                        diagnosticEvent(
                            "IMAGE_READER",
                            "YUV_IMAGE_RECEIVED",
                            "count=$imageCount tsNs=${image.timestampNs} size=${image.width}x${image.height}",
                        )
                    }
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
                diagnosticEvent("PIPELINE", "SESSION_START_FAILED", lastStartFailureReason.orEmpty(), "ERROR")
                stop()
                isUnavailable = true
                Log.w("VideoEisPlusPipeline", "Photon EIS+ unavailable: $lastStartFailureReason")
                onUnavailable()
                false
            } else {
                lastStartFailureReason = null
                isUnavailable = false
                diagnosticEvent("PIPELINE", "SESSION_ACTIVE", "cameraId=$cameraId size=${width}x$height")
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
            diagnosticEvent("PIPELINE", "INITIALIZATION_EXCEPTION", "${t.javaClass.simpleName}: ${t.message}", "ERROR")
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
        if (!isRunning) {
            diagnosticEvent("CAPTURE", "RESULT_IGNORED", "reason=pipeline_not_running generation=$generation", "WARN")
            return
        }
        val count = captureResultCount.incrementAndGet()
        if (count <= 3L || count % 30L == 0L) {
            val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
            val activePhysicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            val requestedVideoStab = request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
            val requestedOis = request.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
            diagnosticEvent(
                "CAPTURE",
                "RESULT_RECEIVED",
                "count=$count tsNs=$timestampNs outputPhysical=$outputPhysicalCameraId " +
                    "activePhysical=$activePhysicalId videoStabRequest=$requestedVideoStab " +
                    "oisRequest=$requestedOis generation=$generation",
                if (timestampNs == null) "WARN" else "INFO",
            )
        }
        coordinator.submitCaptureResult(result, generation, request, outputPhysicalCameraId)    }

    fun dequeueFrame(): StabilizationFrame? {
        val frame = session?.dequeueFrame()
        if (frame != null) {
            val deliveredCount = dequeuedFrameCount.incrementAndGet()
            if (deliveredCount <= 3L || deliveredCount % 30L == 0L) {
                diagnosticEvent("PIPELINE", "FRAME_DELIVERED",
                    "count=$deliveredCount timestampNs=${frame.timestampNs} transformApplied=${frame.transform != null} image=${frame.image.width}x${frame.image.height}",
                    if (frame.transform != null) "INFO" else "WARN",
                )
            }
        }
        return frame
    }

    fun stop() {
        diagnosticEvent(
            "PIPELINE", "STOP",
            "running=$isRunning unavailable=$isUnavailable deliveredFrames=${dequeuedFrameCount.get()} failure=${lastStartFailureReason.orEmpty()}",
        )
        try { session?.stop() } catch (error: Throwable) {
            diagnosticEvent("PIPELINE", "SESSION_STOP_EXCEPTION", error.toString(), "WARN")
        }
        session = null
        val reader = imageReader
        imageReader = null
        if (reader != null) {
            try { coordinator.discardStabilizationImages(reader) } catch (_: Throwable) {}
            try { reader.close() } catch (error: Throwable) {
                diagnosticEvent("PIPELINE", "IMAGE_READER_CLOSE_EXCEPTION", error.toString(), "WARN")
            }
        }
    }
}
