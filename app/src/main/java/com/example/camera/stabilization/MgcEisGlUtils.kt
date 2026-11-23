package com.example.camera.stabilization

import android.opengl.GLES30
import android.util.Log

internal object MgcEisGlUtils {
    fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) return 0
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == GLES30.GL_TRUE) return shader
        Log.e("MgcEisGlUtils", "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
        GLES30.glDeleteShader(shader)
        return 0
    }

    fun linkProgram(vertex: Int, fragment: Int): Int {
        val program = GLES30.glCreateProgram()
        if (program == 0) return 0
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == GLES30.GL_TRUE) return program
        Log.e("MgcEisGlUtils", "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
        GLES30.glDeleteProgram(program)
        return 0
    }
}
