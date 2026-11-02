package com.pockettrack.app.ar

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * The placeable proxy cube, in anchor space (metres, +Y up). The origin is the centre of the
 * cube's bottom face, so y = 0 means "standing on the floor". Guard access with synchronized(this).
 */
class CubeState {
    var x = 0f
    var y = 0f
    var z = 0f
    var yaw = 0f
    var sizeX = DEFAULT_SIZE
    var sizeY = DEFAULT_SIZE
    var sizeZ = DEFAULT_SIZE

    fun resetPosition() {
        x = 0f; y = 0f; z = 0f; yaw = 0f
    }

    fun resetSize() {
        sizeX = DEFAULT_SIZE; sizeY = DEFAULT_SIZE; sizeZ = DEFAULT_SIZE
    }

    fun scale(factor: Float) {
        sizeX = (sizeX * factor).coerceIn(MIN_SIZE, MAX_SIZE)
        sizeY = (sizeY * factor).coerceIn(MIN_SIZE, MAX_SIZE)
        sizeZ = (sizeZ * factor).coerceIn(MIN_SIZE, MAX_SIZE)
    }

    fun setSize(axis: Int, metres: Float) {
        val v = metres.coerceIn(MIN_SIZE, MAX_SIZE)
        when (axis) {
            0 -> sizeX = v
            1 -> sizeY = v
            else -> sizeZ = v
        }
    }

    /** (x, y, z, yaw, sizeX, sizeY, sizeZ) */
    fun snapshot() = floatArrayOf(x, y, z, yaw, sizeX, sizeY, sizeZ)

    companion object {
        const val DEFAULT_SIZE = 0.5f
        const val MIN_SIZE = 0.02f
        const val MAX_SIZE = 10f
    }
}

/**
 * Picks an RGBA8888 + depth config that is also "recordable", so the same EGL context can render
 * into a MediaCodec input surface. Falls back to a normal config if the device has none.
 */
class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val base = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        )
        val attempts = listOf(
            base + intArrayOf(EGL_RECORDABLE_ANDROID, 1, EGL10.EGL_NONE),
            base + intArrayOf(EGL10.EGL_NONE),
        )
        for (attribs in attempts) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) {
                configs[0]?.let { return it }
            }
        }
        throw IllegalArgumentException("No suitable EGL config")
    }

    private companion object {
        const val EGL_OPENGL_ES2_BIT = 4
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
