package com.pockettrack.app.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/**
 * Draws the ARCore camera image as a full-screen quad. The same draw call is used for the
 * screen and for the video encoder surface, so the recorded footage matches the preview 1:1.
 */
class BackgroundRenderer {
    var textureId = -1
        private set

    private val quadCoords = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords = GlUtil.floatBuffer(8)
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0

    fun create() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = GlUtil.program(
            """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
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
        uTexture = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    private val zoomedQuad = GlUtil.floatBuffer(8)
    private var lastZoom = -1f

    /**
     * Recomputes the UV mapping when ARCore reports a display geometry change or the digital
     * zoom changes. Zoom maps the screen to the centre 1/zoom of the camera image.
     */
    fun update(frame: Frame, zoom: Float) {
        if (frame.hasDisplayGeometryChanged() || zoom != lastZoom) {
            lastZoom = zoom
            val s = 1f / zoom
            zoomedQuad.position(0)
            zoomedQuad.put(floatArrayOf(-s, -s, s, -s, -s, s, s, s))
            zoomedQuad.position(0)
            texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, zoomedQuad,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
        }
    }

    companion object {
        /** Applies digital zoom to a GL projection matrix (scales clip-space x and y). */
        fun zoomProjection(proj: FloatArray, zoom: Float) {
            proj[0] *= zoom; proj[8] *= zoom   // row 0
            proj[5] *= zoom; proj[9] *= zoom   // row 1
        }
    }

    fun draw() {
        quadCoords.position(0)
        texCoords.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}
