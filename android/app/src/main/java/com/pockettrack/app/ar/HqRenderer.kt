package com.pockettrack.app.ar

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20

/**
 * Draws the high-resolution camera stream (a SurfaceTexture in sensor orientation) upright and
 * with the digital-zoom crop, into whatever surface is current (the video encoder).
 */
class HqRenderer {
    var textureId = -1
        private set
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private val quad = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv = GlUtil.floatBuffer(8)

    fun create() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = GlUtil.program(
            """
            uniform mat4 u_TexMatrix;
            attribute vec4 a_Position;
            attribute vec4 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = (u_TexMatrix * a_TexCoord).xy;
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
            """.trimIndent()
        )
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "u_TexMatrix")
    }

    /** Binds [st] to our texture in this GL context. */
    fun attach(st: SurfaceTexture) {
        try { st.detachFromGLContext() } catch (_: Exception) {}
        st.attachToGLContext(textureId)
    }

    /**
     * [rotation]: clockwise degrees that make the texture upright on the display. Camera2 already
     * rotates SurfaceTexture streams upright for the natural orientation, so this is minus the
     * display rotation. [zoom] crops about the centre.
     */
    fun draw(texMatrix: FloatArray, rotation: Int, zoom: Float) {
        // For each output corner: upright image coords (v up), zoom crop, then back into the
        // texture's coords by undoing the rotation. texMatrix handles the buffer's own transform.
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        uv.position(0)
        for (i in 0 until 4) {
            val u = 0.5f + (corners[i * 2] - 0.5f) / zoom
            val v = 0.5f + (corners[i * 2 + 1] - 0.5f) / zoom
            when ((rotation % 360 + 360) % 360) {
                90 -> { uv.put(1f - v); uv.put(u) }
                180 -> { uv.put(1f - u); uv.put(1f - v) }
                270 -> { uv.put(v); uv.put(1f - u) }
                else -> { uv.put(u); uv.put(v) }
            }
        }
        quad.position(0)
        uv.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}
