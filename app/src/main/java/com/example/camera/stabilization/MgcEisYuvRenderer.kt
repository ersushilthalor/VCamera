package com.example.camera.stabilization

import android.media.Image
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer

/**
 * Uploads the exact Camera2 YUV frame matched by Photon MGC and applies its 12-strip
 * rolling-shutter homography mesh. Orientation and final aspect crop stay in VCamera's
 * existing presentation pass.
 */
internal class MgcEisYuvRenderer(private val tag: String = "MgcEisYuvRenderer") {
    data class Frame(val timestampNs: Long, val textureId: Int, val width: Int, val height: Int)

    private val mesh = MgcEisMesh()
    private val droppedFrameHomographies = FloatArray(STABILIZATION_ROW_COUNT * 9).also { values ->
        repeat(STABILIZATION_ROW_COUNT) { row ->
            val offset = row * 9
            values[offset] = 1f
            values[offset + 4] = 1f
            values[offset + 8] = 0.9f
        }
    }

    private var programId = 0
    private var positionLocation = -1
    private var textureCoordinateLocation = -1
    private var ySamplerLocation = -1
    private var cbSamplerLocation = -1
    private var crSamplerLocation = -1
    private var imageSizeLocation = -1
    private var cbSizeLocation = -1
    private var crSizeLocation = -1
    private var cbStepLocation = -1
    private var crStepLocation = -1

    private var yTextureId = 0
    private var cbTextureId = 0
    private var crTextureId = 0
    private var outputTextureId = 0
    private var outputFramebufferId = 0
    private var width = 0
    private var height = 0
    private var cbWidth = 0
    private var crWidth = 0
    private var chromaHeight = 0

    /** Consumes and closes the frame's retained Image on every success/failure path. */
    fun render(frame: StabilizationFrame): Frame? {
        val image = frame.image.image
        return try {
            if (image.planes.size < 3 || image.width <= 0 || image.height <= 0) return null
            val cbPlane = image.planes[1]
            val crPlane = image.planes[2]
            val logicalChromaWidth = (image.width + 1) / 2
            val logicalChromaHeight = (image.height + 1) / 2
            val requestedCbWidth = (logicalChromaWidth - 1) * cbPlane.pixelStride + 1
            val requestedCrWidth = (logicalChromaWidth - 1) * crPlane.pixelStride + 1
            if (!ensure(image.width, image.height, requestedCbWidth, requestedCrWidth, logicalChromaHeight)) {
                return null
            }
            if (!uploadPlane(image.planes[0], yTextureId, image.width, image.height, "Y") ||
                !uploadPlane(cbPlane, cbTextureId, requestedCbWidth, logicalChromaHeight, "Cb") ||
                !uploadPlane(crPlane, crTextureId, requestedCrWidth, logicalChromaHeight, "Cr")
            ) return null

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
            GLES30.glViewport(0, 0, image.width, image.height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(programId)
            bindTexture(0, yTextureId, ySamplerLocation)
            bindTexture(1, cbTextureId, cbSamplerLocation)
            bindTexture(2, crTextureId, crSamplerLocation)
            GLES30.glUniform2i(imageSizeLocation, image.width, image.height)
            GLES30.glUniform2i(cbSizeLocation, requestedCbWidth, logicalChromaHeight)
            GLES30.glUniform2i(crSizeLocation, requestedCrWidth, logicalChromaHeight)
            GLES30.glUniform1i(cbStepLocation, cbPlane.pixelStride)
            GLES30.glUniform1i(crStepLocation, crPlane.pixelStride)
            mesh.draw(
                positionLocation,
                textureCoordinateLocation,
                frame.transform?.rowHomographies ?: droppedFrameHomographies
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            repeat(3) { unit ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES31.glMemoryBarrier(
                GLES31.GL_FRAMEBUFFER_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
            )
            Frame(frame.timestampNs, outputTextureId, image.width, image.height)
        } catch (t: Throwable) {
            Log.w(tag, "MGC YUV frame dropped: ${t.message}", t)
            null
        } finally {
            frame.image.close()
        }
    }

    fun release() {
        val textures = intArrayOf(yTextureId, cbTextureId, crTextureId, outputTextureId)
            .filter { it != 0 }.toIntArray()
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
        if (outputFramebufferId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
        if (programId != 0) GLES30.glDeleteProgram(programId)
        mesh.release()
        resetAfterContextLoss()
    }

    fun resetAfterContextLoss() {
        programId = 0
        positionLocation = -1
        textureCoordinateLocation = -1
        ySamplerLocation = -1
        cbSamplerLocation = -1
        crSamplerLocation = -1
        imageSizeLocation = -1
        cbSizeLocation = -1
        crSizeLocation = -1
        cbStepLocation = -1
        crStepLocation = -1
        yTextureId = 0
        cbTextureId = 0
        crTextureId = 0
        outputTextureId = 0
        outputFramebufferId = 0
        width = 0
        height = 0
        cbWidth = 0
        crWidth = 0
        chromaHeight = 0
        mesh.resetAfterContextLoss()
    }

    private fun ensure(
        requestedWidth: Int,
        requestedHeight: Int,
        requestedCbWidth: Int,
        requestedCrWidth: Int,
        requestedChromaHeight: Int
    ): Boolean {
        if (programId != 0 && width == requestedWidth && height == requestedHeight &&
            cbWidth == requestedCbWidth && crWidth == requestedCrWidth &&
            chromaHeight == requestedChromaHeight
        ) return true

        release()
        val vertex = MgcEisGlUtils.compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = MgcEisGlUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
            return false
        }
        programId = MgcEisGlUtils.linkProgram(vertex, fragment)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (programId == 0) return false

        positionLocation = GLES30.glGetAttribLocation(programId, "aPosition")
        textureCoordinateLocation = GLES30.glGetAttribLocation(programId, "aTexCoord")
        ySamplerLocation = GLES30.glGetUniformLocation(programId, "uY")
        cbSamplerLocation = GLES30.glGetUniformLocation(programId, "uCb")
        crSamplerLocation = GLES30.glGetUniformLocation(programId, "uCr")
        imageSizeLocation = GLES30.glGetUniformLocation(programId, "uImageSize")
        cbSizeLocation = GLES30.glGetUniformLocation(programId, "uCbSize")
        crSizeLocation = GLES30.glGetUniformLocation(programId, "uCrSize")
        cbStepLocation = GLES30.glGetUniformLocation(programId, "uCbStep")
        crStepLocation = GLES30.glGetUniformLocation(programId, "uCrStep")
        mesh.initialize()

        yTextureId = createTexture(requestedWidth, requestedHeight, GLES30.GL_R8)
        cbTextureId = createTexture(requestedCbWidth, requestedChromaHeight, GLES30.GL_R8)
        crTextureId = createTexture(requestedCrWidth, requestedChromaHeight, GLES30.GL_R8)
        outputTextureId = createTexture(requestedWidth, requestedHeight, GLES30.GL_RGBA8)
        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        outputFramebufferId = fb[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, outputTextureId, 0
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete || yTextureId == 0 || cbTextureId == 0 || crTextureId == 0 || outputTextureId == 0) {
            Log.e(tag, "Unable to allocate MGC EIS frame buffers ${requestedWidth}x${requestedHeight}")
            return false
        }
        width = requestedWidth
        height = requestedHeight
        cbWidth = requestedCbWidth
        crWidth = requestedCrWidth
        chromaHeight = requestedChromaHeight
        return true
    }

    private fun createTexture(w: Int, h: Int, format: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        if (id[0] == 0) return 0
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val pixelFormat = if (format == GLES30.GL_RGBA8) GLES30.GL_RGBA else GLES30.GL_RED
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, format, w, h, 0, pixelFormat, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return id[0]
    }

    private fun uploadPlane(plane: Image.Plane, textureId: Int, uploadWidth: Int, uploadHeight: Int, label: String): Boolean {
        if (plane.pixelStride <= 0 || plane.rowStride < uploadWidth || uploadWidth <= 0 || uploadHeight <= 0) {
            Log.w(tag, "Invalid $label plane row=${plane.rowStride} pixel=${plane.pixelStride}")
            return false
        }
        val buffer = plane.buffer.duplicate().apply { position(0) }
        val required = (uploadHeight - 1).toLong() * plane.rowStride + uploadWidth
        if (required > buffer.limit().toLong()) {
            Log.w(tag, "$label plane short: required=$required available=${buffer.limit()}")
            return false
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, uploadWidth, uploadHeight,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return true
    }

    private fun bindTexture(unit: Int, texture: Int, sampler: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(sampler, unit)
    }

    private companion object {
        val VERTEX_SHADER = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """.trimIndent()
        val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            uniform sampler2D uY;
            uniform sampler2D uCb;
            uniform sampler2D uCr;
            uniform ivec2 uImageSize;
            uniform ivec2 uCbSize;
            uniform ivec2 uCrSize;
            uniform int uCbStep;
            uniform int uCrStep;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                ivec2 p = clamp(ivec2(vTexCoord * vec2(uImageSize)), ivec2(0), uImageSize - ivec2(1));
                ivec2 cbP = clamp(ivec2((p.x / 2) * uCbStep, p.y / 2), ivec2(0), uCbSize - ivec2(1));
                ivec2 crP = clamp(ivec2((p.x / 2) * uCrStep, p.y / 2), ivec2(0), uCrSize - ivec2(1));
                float y = texelFetch(uY, p, 0).r;
                float cb = texelFetch(uCb, cbP, 0).r - 0.5;
                float cr = texelFetch(uCr, crP, 0).r - 0.5;
                vec3 rgb = vec3(
                    y + 1.402 * cr,
                    y - 0.344136 * cb - 0.714136 * cr,
                    y + 1.772 * cb
                );
                fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
