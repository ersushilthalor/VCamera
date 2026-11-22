package com.example.camera.engine

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType
import com.example.camera.stabilization.MgcEisYuvRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Provides two independent, continuously active Camera2 recording input [Surface]s
 * ([mainRecorderSurface] and [ultraWideRecorderSurface]) that feed a single uninterrupted
 * downstream video encoder ([MediaRecorder], [CinemaSoftwareRecordingEngine], or [CustomVideoPipelineRecorder]).
 *
 * This enables true 0ms Main <-> Ultra-Wide lens switching during active video recording:
 * - Neither CameraCaptureSession nor CameraDevice is closed or reconfigured during a switch.
 * - Neither MediaRecorder nor MediaCodec is stopped, reset, or detached.
 * - Both cameras stream continuously at the active recording resolution, FPS, and dynamic range,
 *   while [setActiveSource] atomically switches which Camera2 stream is written into the encoder
 *   with a strictly monotonic presentation timestamp timeline.
 */
class DualCameraRecordingRelay internal constructor(
    private val encoderTargetSurface: Surface,
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    private val fps: Int,
    private val is10Bit: Boolean = false,
    initialSource: PreviewStreamSource = PreviewStreamSource.MAIN,
    private val cinemaRecorder: CinemaSoftwareRecordingEngine? = null,
    private val sensorOrientation: Int = 90,
    private val deviceRotation: Int = 0,
    private val isFront: Boolean = false,
    private val previewTargetSurface: Surface? = null,
    private val previewWidth: Int = 0,
    private val previewHeight: Int = 0,
    initialAdjustments: com.example.camera.model.VideoAdjustments? = null,
    private val initialSpecialVideoPiPEnabled: Boolean = false,
    private val secondarySensorOrientation: Int = sensorOrientation,
    private val secondaryEncoderTargetSurface: Surface? = null,
    private val onSpecialVideoTimelineStarted: ((Long) -> Unit)? = null,
    private val eisPlusPipeline: VideoEisPlusPipeline? = null
) {
    companion object {
        private const val TAG = "DualCamRecRelay"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES3_BIT = 0x0040
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
    }

    @Volatile
    var currentVideoAdjustments: com.example.camera.model.VideoAdjustments = initialAdjustments ?: com.example.camera.model.VideoAdjustments()
        private set

    fun updateVideoAdjustments(adjustments: com.example.camera.model.VideoAdjustments) {
        currentVideoAdjustments = adjustments
    }

    @Volatile
    private var specialVideoPiPEnabled: Boolean = initialSpecialVideoPiPEnabled

    /** Toggle the Ultra-Wide inset baked into encoder frames. */
    fun setSpecialVideoPiPEnabled(enabled: Boolean) {
        specialVideoPiPEnabled = enabled
    }

    @Volatile
    var activeSource: PreviewStreamSource = initialSource
        private set

    private val isReleased = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    val switchCount = AtomicInteger(0)
    val relayedFrameCount = AtomicLong(0L)

    @Volatile
    var cameraSensorOrientation: Int = sensorOrientation
        private set
    private val activeDeviceRotation: Int = deviceRotation
    @Volatile
    var isFrontFacing: Boolean = isFront
        private set
    @Volatile
    var stabilizationOffsetX: Float = 0f
        private set
    @Volatile
    var stabilizationOffsetY: Float = 0f
        private set
    @Volatile
    var stabilizationCropScale: Float = 1.0f
        private set

    fun updateCameraFacing(isFront: Boolean, sensorOrientation: Int) {
        isFrontFacing = isFront
        cameraSensorOrientation = sensorOrientation
        pendingClockSyncOnSwitch = true
        switchCount.incrementAndGet()
        cinemaRecorder?.updateCameraFacing(isFront, sensorOrientation)
        Log.i(TAG, "[RECORDING_RELAY] Updated camera facing: isFront=$isFront, sensorOrientation=$sensorOrientation")
    }

    fun updateStabilizationOffset(offsetX: Float, offsetY: Float, cropScale: Float) {
        stabilizationOffsetX = offsetX
        stabilizationOffsetY = offsetY
        stabilizationCropScale = cropScale.coerceIn(1.0f, 1.15f)
    }

    private val matrixValues = FloatArray(9)
    private val localTexMatrix = FloatArray(16)
    private val cachedMatrix2d = android.graphics.Matrix()

    // Delegated or Relay GL resources
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var eglPreviewSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var eglSecondaryRecordSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private val secondaryRecordingEnabled = AtomicBoolean(false)
    private val specialVideoRecordingTimeline = SpecialVideoRecordingTimeline()
    private val hasLoggedSpecialVideoFrameWait = AtomicBoolean(false)
    private val hasNotifiedSpecialVideoTimelineStarted = AtomicBoolean(false)
    @Volatile private var lastUltraWideOutputPtsNs: Long = -1L
    val ultraWideRecordedFrameCount = AtomicLong(0L)
    private var programId: Int = 0
    private var passthroughProgramId: Int = 0
    private var eisPlusRenderer: MgcEisYuvRenderer? = null
    private var eisPlusProgramId: Int = 0
    private var eisPlusPassthroughProgramId: Int = 0
    private var eisPlusVadJHandles: VideoAdjustmentsPipeline.VideoAdjustmentsShaderHandles? = null
    private var eisPlusUMvpHandle: Int = -1
    private var eisPlusUStHandle: Int = -1
    private var eisPlusAPositionHandle: Int = -1
    private var eisPlusATexCoordHandle: Int = -1
    private var eisPlusSamplerHandle: Int = -1
    private val eisPlusIdentityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var vadjHandles: VideoAdjustmentsPipeline.VideoAdjustmentsShaderHandles? = null
    private var mainOesTexId: Int = 0
    private var ultraWideOesTexId: Int = 0
    private var vboVertexBufferId: Int = 0
    private var vboTexCoordBufferId: Int = 0

    val isRelayPreviewActive: Boolean
        get() = (eglPreviewSurface != null && eglPreviewSurface != EGL14.EGL_NO_SURFACE)

    fun hasPreviewSurface(): Boolean = isRelayPreviewActive

    /**
     * Attach the UI preview surface only after Camera2 has configured the recording session.
     * Until that session replaces the old preview session, Camera2 still owns this Surface as
     * its producer and EGL must not try to connect to the same BufferQueue.
     */
    fun attachPreviewTargetSurface(onAttached: (Boolean) -> Unit) {
        val handler = glHandler
        if (handler == null) {
            onAttached(false)
            return
        }
        if (!handler.post {
            var attached = false
            try {
                val display = eglDisplay
                val config = eglConfig
                val target = previewTargetSurface
                if (!isReleased.get() && display != null && display != EGL14.EGL_NO_DISPLAY &&
                    config != null && target != null && target.isValid
                ) {
                    val existing = eglPreviewSurface
                    if (existing != null && existing != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, existing)
                    }
                    val created = EGL14.eglCreateWindowSurface(
                        display,
                        config,
                        target,
                        intArrayOf(EGL14.EGL_NONE),
                        0,
                    )
                    if (created != null && created != EGL14.EGL_NO_SURFACE) {
                        eglPreviewSurface = created
                        attached = true
                        Log.i(TAG, "Attached EGL preview surface after Camera2 handoff")
                    } else {
                        eglPreviewSurface = EGL14.EGL_NO_SURFACE
                        Log.w(
                            TAG,
                            "EGL preview attach failed after Camera2 handoff: error=0x" +
                                Integer.toHexString(EGL14.eglGetError()),
                        )
                    }
                } else {
                    Log.w(TAG, "EGL preview attach skipped: relay/context/target is unavailable")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "EGL preview attach failed: ${t.message}", t)
                eglPreviewSurface = EGL14.EGL_NO_SURFACE
            }
            try {
                onAttached(attached)
            } catch (t: Throwable) {
                Log.w(TAG, "EGL preview attach callback failed: ${t.message}", t)
            }
        }) {
            onAttached(false)
        }
    }

    /** Schedule a timestamp-matched MGC frame on the relay's owning GL thread. */
    fun notifyEisPlusFrameAvailable() {
        val handler = glHandler ?: return
        if (!handler.post {
                try {
                    renderEisPlusFrames()
                } catch (t: Throwable) {
                    Log.e(TAG, "MGC EIS+ relay render failed; keeping GL thread alive: ${t.message}", t)
                }
            }
        ) {
            Log.w(TAG, "Unable to schedule timestamp-matched MGC EIS+ frame")
        }
    }

    fun hasSecondaryRecordingSurface(): Boolean {
        val surface = eglSecondaryRecordSurface
        return secondaryEncoderTargetSurface?.isValid == true && surface != null && surface != EGL14.EGL_NO_SURFACE
    }

    private var uMVPMatrixHandle: Int = -1
    private var uSTMatrixHandle: Int = -1
    private var aPositionHandle: Int = -1
    private var aTextureCoordHandle: Int = -1
    private var sTextureHandle: Int = -1

    private var ptUMVPMatrixHandle: Int = -1
    private var ptUSTMatrixHandle: Int = -1
    private var ptAPositionHandle: Int = -1
    private var ptATextureCoordHandle: Int = -1
    private var ptSTextureHandle: Int = -1

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private val mainStMatrix = FloatArray(16)
    private val ultraWideStMatrix = FloatArray(16)
    private val relayTexMatrix = FloatArray(16)
    private val renderTexMatrix = FloatArray(16)
    private val renderPreviewTexMatrix = FloatArray(16)
    private val ultraWidePiPTexMatrix = FloatArray(16)
    @Volatile private var hasUltraWideFrame: Boolean = false
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var mainSurfaceTexture: SurfaceTexture? = null
    private var ultraWideSurfaceTexture: SurfaceTexture? = null

    private var internalMainSurface: Surface? = null
    private var internalUltraWideSurface: Surface? = null

    @Volatile
    var mainBufferW: Int = max(bufferWidth, bufferHeight).coerceAtLeast(320)
        private set
    @Volatile
    var mainBufferH: Int = min(bufferWidth, bufferHeight).coerceAtLeast(240)
        private set
    @Volatile
    var ultraWideBufferW: Int = max(bufferWidth, bufferHeight).coerceAtLeast(320)
        private set
    @Volatile
    var ultraWideBufferH: Int = min(bufferWidth, bufferHeight).coerceAtLeast(240)
        private set

    // Monotonic timeline synchronization state
    private var lastOutputPtsNs: Long = -1L
    private var pauseStartNs: Long = 0L
    private var totalPausedDurationNs: Long = 0L
    private var streamClockOffsetNs: Long = 0L
    @Volatile
    private var pendingClockSyncOnSwitch: Boolean = false
    private val isStreamingActive = AtomicBoolean(true)
    @Volatile private var recordingStartMonotonicNs: Long = 0L

    @Volatile
    var isRelayReady: Boolean = false
        private set

    private var isDelegatingToNativeGlRecorder: Boolean = false

    val mainRecorderSurface: Surface
        get() = internalMainSurface ?: encoderTargetSurface

    val ultraWideRecorderSurface: Surface
        get() = internalUltraWideSurface ?: encoderTargetSurface

    init {
        prepare()
    }

    fun startRecording() {
        lastOutputPtsNs = -1L
        recordingStartMonotonicNs = 0L
        specialVideoRecordingTimeline.reset()
        hasLoggedSpecialVideoFrameWait.set(false)
        hasNotifiedSpecialVideoTimelineStarted.set(false)
        streamClockOffsetNs = 0L
        pendingClockSyncOnSwitch = false
        totalPausedDurationNs = 0L
        lastUltraWideOutputPtsNs = -1L
        ultraWideRecordedFrameCount.set(0L)
        secondaryRecordingEnabled.set(secondaryEncoderTargetSurface?.isValid == true)
        isStreamingActive.set(true)
        Log.i(TAG, "[RECORDING_RELAY] Streaming to encoder active")
    }

    fun stopRecording() {
        secondaryRecordingEnabled.set(false)
        isStreamingActive.set(false)
        recordingStartMonotonicNs = 0L
        specialVideoRecordingTimeline.reset()
        hasLoggedSpecialVideoFrameWait.set(false)
        hasNotifiedSpecialVideoTimelineStarted.set(false)
        Log.i(TAG, "[RECORDING_RELAY] Streaming to encoder stopped (relayedFrames=${relayedFrameCount.get()})")
    }

    fun hasRelayedFrames(): Boolean = relayedFrameCount.get() > 0L

    fun flush(timeoutMs: Long = 500L) {
        val handler = glHandler ?: return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                val display = eglDisplay
                val surface = eglSurface
                if (display != null && display != EGL14.EGL_NO_DISPLAY &&
                    surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    GLES20.glFinish()
                }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}
    }

    private fun prepare() {
        // 1. If CinemaSoftwareRecordingEngine or CustomVideoPipelineRecorder is active and provides
        // native dual-OES input surfaces on its own GL thread, delegate directly (zero extra GL pass).
        if (cinemaRecorder != null) {
            val mainSurf = cinemaRecorder.getMainInputSurface()
            val uwSurf = cinemaRecorder.getUltraWideInputSurface()
            if (mainSurf != null && uwSurf != null && mainSurf !== uwSurf) {
                cinemaRecorder.setActiveStreamSource(activeSource)
                internalMainSurface = mainSurf
                internalUltraWideSurface = uwSurf
                isDelegatingToNativeGlRecorder = true
                isRelayReady = true
                Log.i(TAG, "Delegated dual-camera recording surfaces to CinemaSoftwareRecordingEngine (source=$activeSource)")
                return
            }
        }

        val isRobolectric = Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
            Build.MODEL.contains("robolectric", ignoreCase = true) ||
            Build.HARDWARE.contains("robolectric", ignoreCase = true)

        if (isRobolectric) {
            val mainSt = SurfaceTexture(1).apply {
                setDefaultBufferSize(mainBufferW, mainBufferH)
            }
            val uwSt = SurfaceTexture(2).apply {
                setDefaultBufferSize(ultraWideBufferW, ultraWideBufferH)
            }
            mainSurfaceTexture = mainSt
            ultraWideSurfaceTexture = uwSt
            internalMainSurface = Surface(mainSt)
            internalUltraWideSurface = Surface(uwSt)
            isRelayReady = true
            return
        }

        val thread = HandlerThread("DualCameraRecordingRelayGL").apply { start() }
        val handler = Handler(thread.looper)
        glThread = thread
        glHandler = handler

        val latch = CountDownLatch(1)
        var setupError: Throwable? = null

        handler.post {
            try {
                setupEglRelay()
                isRelayReady = true
            } catch (t: Throwable) {
                setupError = t
                Log.w(TAG, "Hardware EGL recording relay init failed, falling back to single encoder surface: ${t.message}")
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(2500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}

        if (setupError != null || !isRelayReady) {
            releaseGlResourcesOnly()
            internalMainSurface = encoderTargetSurface
            internalUltraWideSurface = null
            isRelayReady = false
        }
    }

    /**
     * Atomically switches the active camera stream feeding the video encoder (0 ms latency).
     * Neither camera session nor the video encoder is stopped or reconfigured.
     */
    fun setActiveSource(source: PreviewStreamSource) {
        // During Special Video the primary encoder is permanently the main lens;
        // the ultra-wide OES stream is written to its own encoder surface.
        if (secondaryEncoderTargetSurface?.isValid == true && source == PreviewStreamSource.ULTRAWIDE) {
            if (activeSource != PreviewStreamSource.MAIN) {
                activeSource = PreviewStreamSource.MAIN
                cinemaRecorder?.setActiveStreamSource(PreviewStreamSource.MAIN)
            }
            return
        }
        if (activeSource != source) {
            activeSource = source
            pendingClockSyncOnSwitch = true
            switchCount.incrementAndGet()
            cinemaRecorder?.setActiveStreamSource(source)
            Log.i(TAG, "[RECORDING_RELAY] Switched active recording stream to $source (switches=${switchCount.get()})")
        } else {
            cinemaRecorder?.setActiveStreamSource(source)
        }
    }

    fun getRecorderSurfaceForLens(lens: LensInfo?): Surface {
        val isUw = lens?.lensType == LensType.ULTRAWIDE
        return if (isUw) {
            internalUltraWideSurface ?: encoderTargetSurface
        } else {
            internalMainSurface ?: encoderTargetSurface
        }
    }

    fun hasDistinctUltraWideSurface(): Boolean {
        val mainSurf = internalMainSurface
        val uwSurf = internalUltraWideSurface
        return isRelayReady && mainSurf != null && uwSurf != null && mainSurf !== uwSurf && uwSurf.isValid
    }

    fun updateInputBufferSize(isUltraWide: Boolean, width: Int, height: Int) {
        val w = max(width, height).coerceAtLeast(320)
        val h = min(width, height).coerceAtLeast(240)
        if (isUltraWide) {
            ultraWideBufferW = w
            ultraWideBufferH = h
            try { ultraWideSurfaceTexture?.setDefaultBufferSize(w, h) } catch (_: Throwable) {}
            cinemaRecorder?.updateUltraWideBufferSize(w, h)
        } else {
            mainBufferW = w
            mainBufferH = h
            try { mainSurfaceTexture?.setDefaultBufferSize(w, h) } catch (_: Throwable) {}
        }
    }

    fun pause() {
        if (isPaused.compareAndSet(false, true)) {
            pauseStartNs = System.nanoTime()
        }
    }

    fun resume() {
        if (isPaused.compareAndSet(true, false)) {
            val delta = System.nanoTime() - pauseStartNs
            if (delta > 0L) {
                totalPausedDurationNs += delta
            }
        }
    }

    private fun setupEglRelay() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }
        eglDisplay = display

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        var configChosen = false

        if (is10Bit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attribs10Bit = intArrayOf(
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_RENDERABLE_TYPE, if (eisPlusPipeline != null) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            configChosen = EGL14.eglChooseConfig(display, attribs10Bit, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0
        }

        if (!configChosen) {
            val attribs8Bit = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, if (eisPlusPipeline != null) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            configChosen = EGL14.eglChooseConfig(display, attribs8Bit, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0
            if (!configChosen) {
                val fallbackAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, if (eisPlusPipeline != null) EGL_OPENGL_ES3_BIT else EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )
                configChosen = EGL14.eglChooseConfig(display, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0
            }
            if (!configChosen || configs[0] == null) {
                throw IllegalStateException("eglChooseConfig failed")
            }
        }
        val chosenConfig = configs[0] ?: throw IllegalStateException("EGLConfig is null")
        eglConfig = chosenConfig

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, if (eisPlusPipeline != null) 3 else 2,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(display, chosenConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext failed")
        }
        eglContext = context

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val surface = EGL14.eglCreateWindowSurface(display, chosenConfig, encoderTargetSurface, surfaceAttribs, 0)
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed")
        }
        eglSurface = surface

        if (secondaryEncoderTargetSurface != null && secondaryEncoderTargetSurface.isValid) {
            val secondarySurface = EGL14.eglCreateWindowSurface(display, chosenConfig, secondaryEncoderTargetSurface, surfaceAttribs, 0)
            if (secondarySurface == null || secondarySurface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed for Special Video ultra-wide encoder")
            }
            eglSecondaryRecordSurface = secondarySurface
        }

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }

        val vertexShader = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """.trimIndent()

        val passthroughFragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        passthroughProgramId = createProgram(vertexShader, passthroughFragmentShader)
        ptUMVPMatrixHandle = GLES20.glGetUniformLocation(passthroughProgramId, "uMVPMatrix")
        ptUSTMatrixHandle = GLES20.glGetUniformLocation(passthroughProgramId, "uSTMatrix")
        ptAPositionHandle = GLES20.glGetAttribLocation(passthroughProgramId, "aPosition")
        ptATextureCoordHandle = GLES20.glGetAttribLocation(passthroughProgramId, "aTextureCoord")
        ptSTextureHandle = GLES20.glGetUniformLocation(passthroughProgramId, "sTexture")

        val fragmentShader = VideoAdjustmentsPipeline.GLES_VIDEO_ADJUSTMENTS_FRAGMENT_SHADER

        programId = createProgram(vertexShader, fragmentShader)
        vadjHandles = VideoAdjustmentsPipeline.VideoAdjustmentsShaderHandles(programId)
        uMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        sTextureHandle = GLES20.glGetUniformLocation(programId, "sTexture")

        if (eisPlusPipeline != null) {
            val eisVertexShader = """
                #version 300 es
                uniform mat4 uMVPMatrix;
                uniform mat4 uSTMatrix;
                in vec4 aPosition;
                in vec4 aTextureCoord;
                out vec2 vTextureCoord;
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
                }
            """.trimIndent()
            val eisPassFragment = """
                #version 300 es
                precision mediump float;
                in vec2 vTextureCoord;
                uniform sampler2D sTexture;
                out vec4 fragColor;
                void main() { fragColor = texture(sTexture, vTextureCoord); }
            """.trimIndent()
            val eisAdjustFragment = VideoAdjustmentsPipeline.GLES_VIDEO_ADJUSTMENTS_FRAGMENT_SHADER
                .replace("#extension GL_OES_EGL_image_external : require", "#version 300 es")
                .replace("varying vec2 vTextureCoord;", "in vec2 vTextureCoord;")
                .replace("uniform samplerExternalOES sTexture;", "uniform sampler2D sTexture;")
                .replace("texture2D(", "texture(")
                .replace("gl_FragColor", "fragColor")
                .replace("void main() {", "out vec4 fragColor;\nvoid main() {")
            eisPlusPassthroughProgramId = createProgram(eisVertexShader, eisPassFragment)
            eisPlusProgramId = createProgram(eisVertexShader, eisAdjustFragment)
            eisPlusVadJHandles = if (eisPlusProgramId != 0) {
                VideoAdjustmentsPipeline.VideoAdjustmentsShaderHandles(eisPlusProgramId)
            } else null
            eisPlusUMvpHandle = GLES30.glGetUniformLocation(eisPlusProgramId, "uMVPMatrix")
            eisPlusUStHandle = GLES30.glGetUniformLocation(eisPlusProgramId, "uSTMatrix")
            eisPlusAPositionHandle = GLES30.glGetAttribLocation(eisPlusProgramId, "aPosition")
            eisPlusATexCoordHandle = GLES30.glGetAttribLocation(eisPlusProgramId, "aTextureCoord")
            eisPlusSamplerHandle = GLES30.glGetUniformLocation(eisPlusProgramId, "sTexture")
            eisPlusRenderer = MgcEisYuvRenderer(TAG)
            if (eisPlusProgramId == 0 || eisPlusPassthroughProgramId == 0) {
                throw IllegalStateException("Could not create GLES 3 MGC EIS+ shaders")
            }
        }

        vertexBuffer = ByteBuffer.allocateDirect(4 * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        -1.0f, -1.0f, 0.0f,
                         1.0f, -1.0f, 0.0f,
                        -1.0f,  1.0f, 0.0f,
                         1.0f,  1.0f, 0.0f
                    )
                )
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        0.0f, 0.0f,
                        1.0f, 0.0f,
                        0.0f, 1.0f,
                        1.0f, 1.0f
                    )
                )
                position(0)
            }

        // Hardware-accelerated GPU VBOs for zero CPU-to-GPU memory copies during 4K recording
        val vbos = IntArray(2)
        GLES20.glGenBuffers(2, vbos, 0)
        vboVertexBufferId = vbos[0]
        vboTexCoordBufferId = vbos[1]
        if (vboVertexBufferId != 0 && vboTexCoordBufferId != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertexBufferId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 4 * 3 * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboTexCoordBufferId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 4 * 2 * 4, texCoordBuffer, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        Matrix.setIdentityM(mvpMatrix, 0)
        // Standard vertical flip from OpenGL bottom-left texture origin to ANativeWindow top-left sensor buffer origin
        Matrix.setIdentityM(relayTexMatrix, 0)
        relayTexMatrix[5] = -1.0f
        relayTexMatrix[13] = 1.0f

        val texIds = IntArray(2)
        GLES20.glGenTextures(2, texIds, 0)
        mainOesTexId = texIds[0]
        ultraWideOesTexId = texIds[1]

        for (texId in texIds) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        val mainSt = SurfaceTexture(mainOesTexId).apply {
            setDefaultBufferSize(mainBufferW, mainBufferH)
            setOnFrameAvailableListener({
                onFrameAvailable(isUltraWide = false)
            }, glHandler)
        }
        val uwSt = SurfaceTexture(ultraWideOesTexId).apply {
            setDefaultBufferSize(ultraWideBufferW, ultraWideBufferH)
            setOnFrameAvailableListener({
                onFrameAvailable(isUltraWide = true)
            }, glHandler)
        }

        mainSurfaceTexture = mainSt
        ultraWideSurfaceTexture = uwSt
        internalMainSurface = Surface(mainSt)
        internalUltraWideSurface = Surface(uwSt)
    }

    private fun onFrameAvailable(isUltraWide: Boolean) {
        if (isReleased.get()) return
        val st = if (isUltraWide) ultraWideSurfaceTexture else mainSurfaceTexture
        if (st == null) return

        val sourceTexMatrix = if (isUltraWide) ultraWideStMatrix else mainStMatrix
        try {
            // Drain and cache both camera streams on the relay GL thread. The inactive stream
            // must keep updating so the next encoded main-camera frame can include a fresh PiP.
            st.updateTexImage()
            st.getTransformMatrix(sourceTexMatrix)
            if (isUltraWide) hasUltraWideFrame = true
        } catch (_: Throwable) {
            return
        }

        if (isPaused.get() || !isStreamingActive.get()) return

        // The OES callback only drains the camera surface. Timestamp-matched YUV frames
        // drive the viewfinder and encoder while MGC EIS+ is running.
        if (!isUltraWide && eisPlusPipeline?.isRunning == true) return

        val rawTimestampNs = st.timestamp.takeIf { it > 0L } ?: System.nanoTime()
        val isSpecialDualRecording = secondaryRecordingEnabled.get()
        val specialFramePtsNs = if (isSpecialDualRecording) {
            specialVideoRecordingTimeline.onFrameAvailable(isUltraWide, rawTimestampNs)
        } else {
            null
        }

        // Ultra-wide callbacks only refresh the cached texture. The MAIN stream is the
        // master clock and drives both encoder surfaces, so both MP4s receive the same
        // frame sequence and exactly the same presentation timestamps.
        if (isSpecialDualRecording && isUltraWide) return

        val recordingStartNs = if (isSpecialDualRecording) {
            specialVideoRecordingTimeline.getRecordingStartTimestampNs()
        } else {
            if (recordingStartMonotonicNs <= 0L) {
                recordingStartMonotonicNs = rawTimestampNs
            }
            recordingStartMonotonicNs.takeIf { it > 0L }
        } ?: run {
            if (isSpecialDualRecording && hasLoggedSpecialVideoFrameWait.compareAndSet(false, true)) {
                Log.i(TAG, "[SPECIAL_VIDEO] Waiting for first frame from both main and ultra-wide cameras")
            }
            return
        }
        if (isSpecialDualRecording && specialFramePtsNs == null) return

        if (recordingStartMonotonicNs != recordingStartNs) {
            recordingStartMonotonicNs = recordingStartNs
        }
        if (!isSpecialDualRecording && rawTimestampNs < recordingStartNs) return

        if (isSpecialDualRecording && hasLoggedSpecialVideoFrameWait.getAndSet(false)) {
            Log.i(TAG, "[SPECIAL_VIDEO] Both camera streams are ready; shared timeline started at $recordingStartNs ns")
        }
        if (isSpecialDualRecording && hasNotifiedSpecialVideoTimelineStarted.compareAndSet(false, true)) {
            try {
                onSpecialVideoTimelineStarted?.invoke(recordingStartNs)
            } catch (t: Throwable) {
                Log.w(TAG, "[SPECIAL_VIDEO] Shared timeline callback failed: ${t.message}", t)
            }
        }

        val expectedSource = if (isUltraWide) PreviewStreamSource.ULTRAWIDE else PreviewStreamSource.MAIN
        if (activeSource != expectedSource) {
            return
        }

        if (!encoderTargetSurface.isValid) return

        try {

            // Monotonic hardware-aligned presentation timestamp pacing
            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            var candidatePtsNs = if (isSpecialDualRecording) {
                (specialFramePtsNs ?: 0L) - totalPausedDurationNs
            } else {
                rawTimestampNs - recordingStartNs - totalPausedDurationNs
            }
            if (!isSpecialDualRecording && pendingClockSyncOnSwitch) {
                pendingClockSyncOnSwitch = false
                if (lastOutputPtsNs > 0L) {
                    val expectedNext = lastOutputPtsNs + frameIntervalNs
                    if (kotlin.math.abs(candidatePtsNs - expectedNext) > 150_000_000L) {
                        streamClockOffsetNs += (expectedNext - candidatePtsNs)
                        candidatePtsNs = expectedNext
                    }
                }
            }
            if (!isSpecialDualRecording) candidatePtsNs += streamClockOffsetNs
            if (candidatePtsNs < 0L) candidatePtsNs = 0L

            if (lastOutputPtsNs >= 0L && candidatePtsNs <= lastOutputPtsNs) {
                candidatePtsNs = lastOutputPtsNs + 1_000_000L.coerceAtLeast(frameIntervalNs / 4)
            }
            lastOutputPtsNs = candidatePtsNs

            val display = eglDisplay
            val surface = eglSurface
            val context = eglContext
            if (display != null && surface != null && context != null &&
                display != EGL14.EGL_NO_DISPLAY && surface != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT) {
                if (EGL14.eglGetCurrentContext() != context || EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != surface) {
                    EGL14.eglMakeCurrent(display, surface, surface, context)
                }

                val outW = bufferWidth
                val outH = bufferHeight
                GLES20.glViewport(0, 0, outW, outH)

                val isDefaultAdjustments = currentVideoAdjustments.isDefault
                val activeProg = if (isDefaultAdjustments && passthroughProgramId != 0) passthroughProgramId else programId
                GLES20.glUseProgram(activeProg)

                computeCameraTexMatrix(
                    stMatrix = sourceTexMatrix,
                    isFront = isFrontFacing,
                    sensorOrientation = cameraSensorOrientation,
                    deviceRotation = activeDeviceRotation,
                    viewportWidth = outW,
                    viewportHeight = outH,
                    camBufferWidth = if (isUltraWide) ultraWideBufferW else mainBufferW,
                    camBufferHeight = if (isUltraWide) ultraWideBufferH else mainBufferH,
                    outMatrix = renderTexMatrix
                )

                val uMvp = if (activeProg == passthroughProgramId) ptUMVPMatrixHandle else uMVPMatrixHandle
                val uSt = if (activeProg == passthroughProgramId) ptUSTMatrixHandle else uSTMatrixHandle
                val aPos = if (activeProg == passthroughProgramId) ptAPositionHandle else aPositionHandle
                val aTex = if (activeProg == passthroughProgramId) ptATextureCoordHandle else aTextureCoordHandle
                val sTex = if (activeProg == passthroughProgramId) ptSTextureHandle else sTextureHandle

                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
                GLES20.glUniformMatrix4fv(uSt, 1, false, renderTexMatrix, 0)
                GLES20.glUniform1i(sTex, 0)

                val frameTimeSec = (candidatePtsNs.toFloat() / 1_000_000_000f)
                val handles = vadjHandles
                if (activeProg == programId && handles != null) {
                    VideoAdjustmentsPipeline.bindUniforms(
                        handles = handles,
                        adjustments = currentVideoAdjustments,
                        width = outW,
                        height = outH,
                        timeSec = frameTimeSec
                    )
                }

                val activeTexId = if (isUltraWide) ultraWideOesTexId else mainOesTexId
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, activeTexId)

                val vb = vertexBuffer
                val tb = texCoordBuffer
                if (vboVertexBufferId != 0 && vboTexCoordBufferId != 0 && aPos >= 0 && aTex >= 0) {
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertexBufferId)
                    GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
                    GLES20.glEnableVertexAttribArray(aPos)

                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboTexCoordBufferId)
                    GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, 0)
                    GLES20.glEnableVertexAttribArray(aTex)

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    GLES20.glDisableVertexAttribArray(aPos)
                    GLES20.glDisableVertexAttribArray(aTex)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                } else if (vb != null && tb != null && aPos >= 0 && aTex >= 0) {
                    vb.position(0)
                    GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vb)
                    GLES20.glEnableVertexAttribArray(aPos)

                    tb.position(0)
                    GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tb)
                    GLES20.glEnableVertexAttribArray(aTex)

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    GLES20.glDisableVertexAttribArray(aPos)
                    GLES20.glDisableVertexAttribArray(aTex)
                }

                // Special Video: composite the cached Ultra-Wide stream into the final encoded frame.
                // This is deliberately done on the encoder EGL surface (not just in Compose), so the
                // inset is present in the saved MP4 and remains synchronized with the main frame clock.
                if (specialVideoPiPEnabled && !isUltraWide && hasUltraWideFrame && ultraWideOesTexId != 0) {
                    val shortEdge = min(outW, outH).coerceAtLeast(16)
                    val border = (shortEdge / 540).coerceAtLeast(2)
                    val pipHeight = (shortEdge * 0.42f).toInt().coerceAtLeast(16)
                    val pipWidth = (pipHeight * 9f / 16f).toInt().coerceAtLeast(8)
                    val margin = (shortEdge * 0.025f).toInt().coerceAtLeast(border * 2)
                    val outerLeft = (outW - margin - pipWidth).coerceAtLeast(0)
                    val outerBottom = (outH - margin - pipHeight).coerceAtLeast(0)
                    val outerWidth = min(pipWidth, outW - outerLeft)
                    val outerHeight = min(pipHeight, outH - outerBottom)
                    val insetLeft = outerLeft + border
                    val insetBottom = outerBottom + border
                    val insetWidth = (outerWidth - border * 2).coerceAtLeast(2)
                    val insetHeight = (outerHeight - border * 2).coerceAtLeast(2)

                    // Draw a thin, high-contrast frame behind the inset.
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glScissor(outerLeft, outerBottom, outerWidth, outerHeight)
                    GLES20.glClearColor(1f, 1f, 1f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

                    GLES20.glViewport(insetLeft, insetBottom, insetWidth, insetHeight)
                    GLES20.glUseProgram(activeProg)
                    computeCameraTexMatrix(
                        stMatrix = ultraWideStMatrix,
                        isFront = false,
                        sensorOrientation = cameraSensorOrientation,
                        deviceRotation = activeDeviceRotation,
                        viewportWidth = insetWidth,
                        viewportHeight = insetHeight,
                        camBufferWidth = ultraWideBufferW,
                        camBufferHeight = ultraWideBufferH,
                        outMatrix = ultraWidePiPTexMatrix
                    )
                    GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
                    GLES20.glUniformMatrix4fv(uSt, 1, false, ultraWidePiPTexMatrix, 0)
                    GLES20.glUniform1i(sTex, 0)
                    if (activeProg == programId && handles != null) {
                        VideoAdjustmentsPipeline.bindUniforms(
                            handles = handles,
                            adjustments = currentVideoAdjustments,
                            width = insetWidth,
                            height = insetHeight,
                            timeSec = frameTimeSec
                        )
                    }
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ultraWideOesTexId)
                    if (vboVertexBufferId != 0 && vboTexCoordBufferId != 0 && aPos >= 0 && aTex >= 0) {
                        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertexBufferId)
                        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
                        GLES20.glEnableVertexAttribArray(aPos)
                        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboTexCoordBufferId)
                        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, 0)
                        GLES20.glEnableVertexAttribArray(aTex)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        GLES20.glDisableVertexAttribArray(aPos)
                        GLES20.glDisableVertexAttribArray(aTex)
                        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                    } else if (vb != null && tb != null && aPos >= 0 && aTex >= 0) {
                        vb.position(0)
                        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vb)
                        GLES20.glEnableVertexAttribArray(aPos)
                        tb.position(0)
                        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tb)
                        GLES20.glEnableVertexAttribArray(aTex)
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        GLES20.glDisableVertexAttribArray(aPos)
                        GLES20.glDisableVertexAttribArray(aTex)
                    }
                    GLES20.glViewport(0, 0, outW, outH)
                }

                EGLExt.eglPresentationTimeANDROID(display, surface, candidatePtsNs)
                val swapped = EGL14.eglSwapBuffers(display, surface)
                if (swapped) {
                    relayedFrameCount.incrementAndGet()
                    CameraPerformanceMonitor.onRecordingFrame()

                    // Mirror the SAME cached ultra-wide frame on every accepted MAIN frame.
                    // Passing the already-normalized primary PTS avoids a second, drifting clock.
                    if (isSpecialDualRecording && hasUltraWideFrame && ultraWideOesTexId != 0) {
                        renderUltraWideSecondaryFrame(candidatePtsNs)
                    }
                } else {
                    Log.w(TAG, "eglSwapBuffers returned false")
                }

                // Split identical GPU processed frame directly to Preview Surface
                val prevSurf = eglPreviewSurface
                if (prevSurf != null && prevSurf != EGL14.EGL_NO_SURFACE) {
                    try {
                        if (EGL14.eglMakeCurrent(display, prevSurf, prevSurf, context)) {
                            val pW = if (previewWidth > 0) previewWidth else outW
                            val pH = if (previewHeight > 0) previewHeight else outH
                            GLES20.glViewport(0, 0, pW, pH)
                            GLES20.glUseProgram(activeProg)

                            computeCameraTexMatrix(
                                stMatrix = sourceTexMatrix,
                                isFront = isFrontFacing,
                                sensorOrientation = cameraSensorOrientation,
                                deviceRotation = activeDeviceRotation,
                                viewportWidth = pW,
                                viewportHeight = pH,
                                camBufferWidth = if (isUltraWide) ultraWideBufferW else mainBufferW,
                                camBufferHeight = if (isUltraWide) ultraWideBufferH else mainBufferH,
                                outMatrix = renderPreviewTexMatrix
                            )

                            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
                            GLES20.glUniformMatrix4fv(uSt, 1, false, renderPreviewTexMatrix, 0)
                            GLES20.glUniform1i(sTex, 0)

                            if (activeProg == programId && handles != null) {
                                VideoAdjustmentsPipeline.bindUniforms(
                                    handles = handles,
                                    adjustments = currentVideoAdjustments,
                                    width = pW,
                                    height = pH,
                                    timeSec = frameTimeSec
                                )
                            }

                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, activeTexId)

                            if (vb != null && tb != null && aPos >= 0 && aTex >= 0) {
                                vb.position(0)
                                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vb)
                                GLES20.glEnableVertexAttribArray(aPos)

                                tb.position(0)
                                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tb)
                                GLES20.glEnableVertexAttribArray(aTex)

                                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                                GLES20.glDisableVertexAttribArray(aPos)
                                GLES20.glDisableVertexAttribArray(aTex)
                            }

                            EGL14.eglSwapBuffers(display, prevSurf)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error swapping preview EGL surface: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error relaying recording frame: ${t.message}")
        }
    }


    private fun renderEisPlusFrames() {
        val pipeline = eisPlusPipeline ?: return
        val display = eglDisplay ?: return
        val context = eglContext ?: return
        val encoderSurface = eglSurface ?: return
        if (!pipeline.isRunning || isReleased.get() || display == EGL14.EGL_NO_DISPLAY ||
            context == EGL14.EGL_NO_CONTEXT || encoderSurface == EGL14.EGL_NO_SURFACE
        ) return

        while (true) {
            val pending = pipeline.dequeueFrame() ?: break
            if (activeSource != PreviewStreamSource.MAIN || isPaused.get() || !isStreamingActive.get()) {
                pending.image.close()
                continue
            }
            val renderer = eisPlusRenderer
            if (renderer == null) {
                pending.image.close()
                Log.e(TAG, "MGC EIS+ renderer is unavailable; dropping matched frame")
                continue
            }
            val frame = renderer.render(pending) ?: continue

            val sourceTimestampNs = frame.timestampNs
            if (recordingStartMonotonicNs <= 0L) recordingStartMonotonicNs = sourceTimestampNs
            if (sourceTimestampNs < recordingStartMonotonicNs) continue
            val frameIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
            var presentationNs = sourceTimestampNs - recordingStartMonotonicNs -
                totalPausedDurationNs + streamClockOffsetNs
            if (presentationNs < 0L) presentationNs = 0L
            if (lastOutputPtsNs >= 0L && presentationNs <= lastOutputPtsNs) {
                presentationNs = lastOutputPtsNs + 1_000_000L.coerceAtLeast(frameIntervalNs / 4)
            }

            val encoded = renderEisPlusOutput(
                display, context, encoderSurface, frame, presentationNs, bufferWidth, bufferHeight
            )
            if (!encoded) continue
            lastOutputPtsNs = presentationNs
            relayedFrameCount.incrementAndGet()
            CameraPerformanceMonitor.onRecordingFrame()

            val preview = eglPreviewSurface
            if (preview != null && preview != EGL14.EGL_NO_SURFACE) {
                val pw = if (previewWidth > 0) previewWidth else bufferWidth
                val ph = if (previewHeight > 0) previewHeight else bufferHeight
                renderEisPlusOutput(display, context, preview, frame, presentationNs, pw, ph)
            }
        }
    }

    private fun renderEisPlusOutput(
        display: EGLDisplay,
        context: EGLContext,
        targetSurface: EGLSurface,
        frame: MgcEisYuvRenderer.Frame,
        presentationNs: Long,
        outputWidth: Int,
        outputHeight: Int
    ): Boolean {
        if (targetSurface == EGL14.EGL_NO_SURFACE ||
            !EGL14.eglMakeCurrent(display, targetSurface, targetSurface, context)
        ) return false

        val useAdjustments = !currentVideoAdjustments.isDefault
        val activeProgram = if (useAdjustments) eisPlusProgramId else eisPlusPassthroughProgramId
        if (activeProgram == 0) return false
        GLES30.glViewport(0, 0, outputWidth.coerceAtLeast(2), outputHeight.coerceAtLeast(2))
        GLES30.glUseProgram(activeProgram)

        computeCameraTexMatrix(
            stMatrix = eisPlusIdentityMatrix,
            isFront = isFrontFacing,
            sensorOrientation = cameraSensorOrientation,
            deviceRotation = activeDeviceRotation,
            viewportWidth = outputWidth,
            viewportHeight = outputHeight,
            camBufferWidth = frame.width,
            camBufferHeight = frame.height,
            outMatrix = renderTexMatrix
        )

        val uMvp = if (useAdjustments) eisPlusUMvpHandle else GLES30.glGetUniformLocation(activeProgram, "uMVPMatrix")
        val uSt = if (useAdjustments) eisPlusUStHandle else GLES30.glGetUniformLocation(activeProgram, "uSTMatrix")
        val aPos = if (useAdjustments) eisPlusAPositionHandle else GLES30.glGetAttribLocation(activeProgram, "aPosition")
        val aTex = if (useAdjustments) eisPlusATexCoordHandle else GLES30.glGetAttribLocation(activeProgram, "aTextureCoord")
        val sampler = if (useAdjustments) eisPlusSamplerHandle else GLES30.glGetUniformLocation(activeProgram, "sTexture")
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(uSt, 1, false, renderTexMatrix, 0)
        GLES30.glUniform1i(sampler, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.textureId)

        if (useAdjustments) {
            eisPlusVadJHandles?.let {
                VideoAdjustmentsPipeline.bindUniforms(
                    handles = it,
                    adjustments = currentVideoAdjustments,
                    width = outputWidth,
                    height = outputHeight,
                    timeSec = presentationNs.toFloat() / 1_000_000_000f
                )
            }
        }

        if (vboVertexBufferId != 0 && vboTexCoordBufferId != 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboVertexBufferId)
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboTexCoordBufferId)
            GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glEnableVertexAttribArray(aTex)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(aPos)
            GLES30.glDisableVertexAttribArray(aTex)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        } else {
            val vb = vertexBuffer ?: return false
            val tb = texCoordBuffer ?: return false
            vb.position(0)
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 0, vb)
            GLES30.glEnableVertexAttribArray(aPos)
            tb.position(0)
            GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, tb)
            GLES30.glEnableVertexAttribArray(aTex)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(aPos)
            GLES30.glDisableVertexAttribArray(aTex)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        EGLExt.eglPresentationTimeANDROID(display, targetSurface, presentationNs)
        return EGL14.eglSwapBuffers(display, targetSurface)
    }

    /**
     * Writes each ultra-wide OES frame to the independent Special Video encoder surface.
     * This is deliberately separate from the main encoder's active-source switching path.
     */
    private fun renderUltraWideSecondaryFrame(framePresentationTimeNs: Long) {
        val display = eglDisplay ?: return
        val surface = eglSecondaryRecordSurface ?: return
        val context = eglContext ?: return
        val primarySurface = eglSurface ?: EGL14.EGL_NO_SURFACE
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE ||
            context == EGL14.EGL_NO_CONTEXT || primarySurface == EGL14.EGL_NO_SURFACE) return

        // This is already the primary encoder's normalized PTS. Never independently
        // clamp or offset the companion stream: both encoders must receive the exact same value.
        val presentationTimeNs = framePresentationTimeNs.coerceAtLeast(0L)
        if (lastUltraWideOutputPtsNs >= 0L && presentationTimeNs <= lastUltraWideOutputPtsNs) {
            Log.w(TAG, "[SPECIAL_VIDEO] Skipping non-monotonic companion PTS=$presentationTimeNs last=$lastUltraWideOutputPtsNs")
            return
        }

        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Log.w(TAG, "Unable to make Special Video ultra-wide encoder surface current")
                return
            }

            val outW = bufferWidth.coerceAtLeast(2)
            val outH = bufferHeight.coerceAtLeast(2)
            GLES20.glViewport(0, 0, outW, outH)

            val isDefaultAdjustments = currentVideoAdjustments.isDefault
            val activeProgram = if (isDefaultAdjustments && passthroughProgramId != 0) passthroughProgramId else programId
            GLES20.glUseProgram(activeProgram)

            computeCameraTexMatrix(
                stMatrix = ultraWideStMatrix,
                isFront = false,
                sensorOrientation = secondarySensorOrientation,
                deviceRotation = activeDeviceRotation,
                viewportWidth = outW,
                viewportHeight = outH,
                camBufferWidth = ultraWideBufferW,
                camBufferHeight = ultraWideBufferH,
                outMatrix = renderTexMatrix
            )

            val uMvp = if (activeProgram == passthroughProgramId) ptUMVPMatrixHandle else uMVPMatrixHandle
            val uSt = if (activeProgram == passthroughProgramId) ptUSTMatrixHandle else uSTMatrixHandle
            val aPos = if (activeProgram == passthroughProgramId) ptAPositionHandle else aPositionHandle
            val aTex = if (activeProgram == passthroughProgramId) ptATextureCoordHandle else aTextureCoordHandle
            val sTex = if (activeProgram == passthroughProgramId) ptSTextureHandle else sTextureHandle

            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uSt, 1, false, renderTexMatrix, 0)
            GLES20.glUniform1i(sTex, 0)

            val frameTimeSec = presentationTimeNs.toFloat() / 1_000_000_000f
            val handles = vadjHandles
            if (activeProgram == programId && handles != null) {
                VideoAdjustmentsPipeline.bindUniforms(
                    handles = handles,
                    adjustments = currentVideoAdjustments,
                    width = outW,
                    height = outH,
                    timeSec = frameTimeSec
                )
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ultraWideOesTexId)

            val vb = vertexBuffer
            val tb = texCoordBuffer
            if (vboVertexBufferId != 0 && vboTexCoordBufferId != 0 && aPos >= 0 && aTex >= 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertexBufferId)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboTexCoordBufferId)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aTex)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            } else if (vb != null && tb != null && aPos >= 0 && aTex >= 0) {
                vb.position(0)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vb)
                GLES20.glEnableVertexAttribArray(aPos)
                tb.position(0)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tb)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aTex)
            }

            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs)
            if (EGL14.eglSwapBuffers(display, surface)) {
                lastUltraWideOutputPtsNs = presentationTimeNs
                ultraWideRecordedFrameCount.incrementAndGet()
            } else {
                Log.w(TAG, "Special Video ultra-wide encoder eglSwapBuffers failed")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Special Video ultra-wide frame render failed: ${t.message}")
        } finally {
            try {
                EGL14.eglMakeCurrent(display, primarySurface, primarySurface, context)
            } catch (_: Throwable) {}
        }
    }

    internal fun computeCameraTexMatrix(
        stMatrix: FloatArray,
        isFront: Boolean,
        sensorOrientation: Int,
        deviceRotation: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        camBufferWidth: Int,
        camBufferHeight: Int,
        outMatrix: FloatArray
    ) {
        val m0 = stMatrix[0]
        val m1 = stMatrix[1]
        val m4 = stMatrix[4]
        val m5 = stMatrix[5]

        val offDiag = kotlin.math.abs(m1) + kotlin.math.abs(m4)
        val diag = kotlin.math.abs(m0) + kotlin.math.abs(m5)
        val isStRotated90 = offDiag > diag

        val det = m0 * m5 - m1 * m4
        val isIdentitySt = kotlin.math.abs(m0 - 1f) < 1e-4f &&
            kotlin.math.abs(m5 - 1f) < 1e-4f &&
            offDiag < 1e-4f &&
            kotlin.math.abs(stMatrix[13]) < 1e-4f
        val isStMirrored = !isIdentitySt && (offDiag + diag > 0.1f) && (det > 0f)

        val normRot = ((deviceRotation % 360) + 360) % 360
        val isLandscapeTarget = (normRot == 90 || normRot == 270)
        val isSensorSwappedInPortrait = (sensorOrientation == 90 || sensorOrientation == 270)
        val isSwapped = isSensorSwappedInPortrait xor isLandscapeTarget

        val camLong = maxOf(camBufferWidth, camBufferHeight).toFloat().coerceAtLeast(1f)
        val camShort = minOf(camBufferWidth, camBufferHeight).toFloat().coerceAtLeast(1f)
        val uprightCamW = if (isSwapped) camShort else camLong
        val uprightCamH = if (isSwapped) camLong else camShort
        val camAspect = uprightCamW / uprightCamH

        val targetAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (targetAspect > camAspect) {
            scaleX = 1.0f
            scaleY = camAspect / targetAspect
        } else {
            scaleX = targetAspect / camAspect
            scaleY = 1.0f
        }

        val (effScaleY, netRotationDeg) = if (!isStRotated90) {
            if (!isFront) {
                when (normRot) {
                    0 -> (-scaleY) to 90f
                    180 -> (-scaleY) to -90f
                    else -> scaleY to 0f
                }
            } else {
                when (normRot) {
                    0 -> (-scaleY) to -90f
                    180 -> (-scaleY) to 90f
                    else -> scaleY to 0f
                }
            }
        } else {
            when (normRot) {
                90 -> scaleY to -90f
                270 -> scaleY to 90f
                180 -> scaleY to 180f
                else -> scaleY to 0f
            }
        }

        cachedMatrix2d.reset()
        cachedMatrix2d.postTranslate(-0.5f, -0.5f)
        val effectiveScaleX = scaleX
        val effectiveScaleY = effScaleY
        cachedMatrix2d.postScale(effectiveScaleX, effectiveScaleY)
        if (netRotationDeg != 0f) {
            cachedMatrix2d.postRotate(netRotationDeg)
        }
        if (isFront != isStMirrored) {
            cachedMatrix2d.postScale(-1.0f, 1.0f)
        }
        val finalShiftX = 0f
        val finalShiftY = 0f
        cachedMatrix2d.postTranslate(0.5f + finalShiftX, 0.5f + finalShiftY)

        cachedMatrix2d.getValues(matrixValues)
        localTexMatrix[0] = matrixValues[0]; localTexMatrix[1] = matrixValues[3]; localTexMatrix[2] = 0f; localTexMatrix[3] = 0f
        localTexMatrix[4] = matrixValues[1]; localTexMatrix[5] = matrixValues[4]; localTexMatrix[6] = 0f; localTexMatrix[7] = 0f
        localTexMatrix[8] = 0f;              localTexMatrix[9] = 0f;              localTexMatrix[10] = 1f; localTexMatrix[11] = 0f
        localTexMatrix[12] = matrixValues[2]; localTexMatrix[13] = matrixValues[5]; localTexMatrix[14] = 0f; localTexMatrix[15] = 1f

        Matrix.multiplyMM(outMatrix, 0, stMatrix, 0, localTexMatrix, 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $info")
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $info")
        }
        return shader
    }

    private fun releaseGlResourcesOnly() {
        val handler = glHandler
        if (handler != null) {
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    try { mainSurfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
                    try { ultraWideSurfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
                    if (passthroughProgramId != 0) {
                        GLES20.glDeleteProgram(passthroughProgramId)
                        passthroughProgramId = 0
                    }
                    eisPlusRenderer?.release()
                    eisPlusRenderer = null
                    if (eisPlusProgramId != 0) {
                        GLES30.glDeleteProgram(eisPlusProgramId)
                        eisPlusProgramId = 0
                    }
                    if (eisPlusPassthroughProgramId != 0) {
                        GLES30.glDeleteProgram(eisPlusPassthroughProgramId)
                        eisPlusPassthroughProgramId = 0
                    }
                    if (programId != 0) {
                        GLES20.glDeleteProgram(programId)
                        programId = 0
                    }
                    val texToDelete = intArrayOf(mainOesTexId, ultraWideOesTexId).filter { it != 0 }.toIntArray()
                    if (texToDelete.isNotEmpty()) {
                        GLES20.glDeleteTextures(texToDelete.size, texToDelete, 0)
                    }
                    mainOesTexId = 0
                    ultraWideOesTexId = 0
                    if (vboVertexBufferId != 0 || vboTexCoordBufferId != 0) {
                        GLES20.glDeleteBuffers(2, intArrayOf(vboVertexBufferId, vboTexCoordBufferId), 0)
                        vboVertexBufferId = 0
                        vboTexCoordBufferId = 0
                    }

                    val display = eglDisplay
                    if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                        try {
                            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        } catch (_: Throwable) {}
                        val surface = eglSurface
                        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                            try { EGL14.eglDestroySurface(display, surface) } catch (_: Throwable) {}
                            eglSurface = EGL14.EGL_NO_SURFACE
                        }
                        val secondarySurface = eglSecondaryRecordSurface
                        if (secondarySurface != null && secondarySurface != EGL14.EGL_NO_SURFACE) {
                            try { EGL14.eglDestroySurface(display, secondarySurface) } catch (_: Throwable) {}
                            eglSecondaryRecordSurface = EGL14.EGL_NO_SURFACE
                        }
                        val prevSurface = eglPreviewSurface
                        if (prevSurface != null && prevSurface != EGL14.EGL_NO_SURFACE) {
                            try { EGL14.eglDestroySurface(display, prevSurface) } catch (_: Throwable) {}
                            eglPreviewSurface = EGL14.EGL_NO_SURFACE
                        }
                        val context = eglContext
                        if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                            try { EGL14.eglDestroyContext(display, context) } catch (_: Throwable) {}
                            eglContext = EGL14.EGL_NO_CONTEXT
                        }
                        try { EGL14.eglTerminate(display) } catch (_: Throwable) {}
                        eglDisplay = EGL14.EGL_NO_DISPLAY
                    }
                    eglConfig = null
                } catch (_: Throwable) {
                } finally {
                    latch.countDown()
                }
            }
            try { latch.await(800, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        }
        try { glThread?.quitSafely() } catch (_: Throwable) {}
        glThread = null
        glHandler = null
    }

    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        isRelayReady = false
        if (isDelegatingToNativeGlRecorder) {
            internalMainSurface = null
            internalUltraWideSurface = null
            return
        }
        releaseGlResourcesOnly()
        try {
            if (internalMainSurface != null && internalMainSurface !== encoderTargetSurface) {
                internalMainSurface?.release()
            }
        } catch (_: Throwable) {}
        internalMainSurface = null

        try {
            if (internalUltraWideSurface != null && internalUltraWideSurface !== encoderTargetSurface) {
                internalUltraWideSurface?.release()
            }
        } catch (_: Throwable) {}
        internalUltraWideSurface = null

        try { mainSurfaceTexture?.release() } catch (_: Throwable) {}
        mainSurfaceTexture = null

        try { ultraWideSurfaceTexture?.release() } catch (_: Throwable) {}
        ultraWideSurfaceTexture = null
    }
}
