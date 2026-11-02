package com.pockettrack.app.ar

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    fun floatBuffer(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun floatBuffer(data: FloatArray): FloatBuffer =
        floatBuffer(data.size).apply { put(data); position(0) }

    fun program(vertexSrc: String, fragmentSrc: String): Int {
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: $log")
        }
        return prog
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $log")
        }
        return s
    }
}

/** A single-colour shader shared by the point cloud, planes, lines and the cube shadow. */
class FlatShader {
    private var program = 0
    private var aPosition = 0
    private var uMvp = 0
    private var uColor = 0
    private var uPointSize = 0

    fun create() {
        program = GlUtil.program(
            """
            uniform mat4 u_Mvp;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_Mvp * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
            }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
            """.trimIndent()
        )
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        uPointSize = GLES20.glGetUniformLocation(program, "u_PointSize")
    }

    /**
     * Draws [count] vertices from [vertices]. [components] is the number of floats per vertex
     * (3 for xyz, 4 for ARCore's xyz+confidence); only xyz is used.
     */
    fun draw(
        mode: Int, vertices: FloatBuffer, components: Int, count: Int,
        mvp: FloatArray, r: Float, g: Float, b: Float, a: Float, pointSize: Float = 1f
    ) {
        if (count <= 0) return
        GLES20.glUseProgram(program)
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPosition, components, GLES20.GL_FLOAT, false, components * 4, vertices)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glUniform1f(uPointSize, pointSize)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(aPosition)
    }
}
