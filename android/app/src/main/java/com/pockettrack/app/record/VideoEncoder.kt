package com.pockettrack.app.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import kotlin.math.roundToInt

/**
 * H.264 encoder fed through an input Surface that shares the GLSurfaceView's EGL context.
 * All methods must be called on the GL thread.
 *
 * Frames are stamped at a constant rate (index / fps), so video frame N is exactly track frame N
 * in Blender. TrackRecorder fills dropped camera frames so the video still runs in real time
 * (and matches the audio). Real timestamps live in track.json.
 */
class VideoEncoder(val width: Int, val height: Int, private val fps: Int, private val writer: Mp4Writer) {
    private val codec: MediaCodec
    private val inputSurface: Surface
    private val info = MediaCodec.BufferInfo()
    private var track = -1

    private val eglDisplay = EGL14.eglGetCurrentDisplay()
    private val eglContext = EGL14.eglGetCurrentContext()
    private val eglSurface: EGLSurface

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.25f).toInt().coerceIn(4_000_000, 40_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()

        check(eglContext != EGL14.EGL_NO_CONTEXT) { "VideoEncoder must be created on the GL thread" }
        val surface = EGL14.eglCreateWindowSurface(
            eglDisplay, currentConfig(), inputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            codec.release()
            throw IllegalStateException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        eglSurface = surface
    }

    /** Finds the EGLConfig of the current context (the GLSurfaceView picks a recordable one). */
    private fun currentConfig(): EGLConfig {
        val id = IntArray(1)
        EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0,
            configs, 0, 1, num, 0
        )
        return configs[0] ?: throw IllegalStateException("No EGLConfig for id ${id[0]}")
    }

    /**
     * Renders one frame into the encoder. [draw] issues the GL calls (viewport is already set);
     * afterwards the screen surface is made current again.
     */
    fun encodeFrame(frameIndex: Int, draw: () -> Unit) {
        val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        android.opengl.GLES20.glViewport(0, 0, width, height)
        draw()
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frameIndex * 1_000_000_000L / fps)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        EGL14.eglMakeCurrent(eglDisplay, drawSurface, readSurface, eglContext)
        drain(false)
    }

    fun finish() {
        try {
            drain(true)
        } finally {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            inputSurface.release()
        }
    }

    companion object {
        /**
         * The largest size with the same aspect as [width] x [height] that the device's H.264
         * encoder accepts. Some encoders only take ≤1088 px tall, which rules out 1080x1920
         * portrait; then we fall back to 810x1440, 720x1280, ...
         */
        fun supportedSize(width: Int, height: Int): Pair<Int, Int> {
            val caps = try {
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                } finally {
                    codec.release()
                }
            } catch (e: Exception) {
                return width to height
            }
            for (scale in floatArrayOf(1f, 0.75f, 2f / 3f, 0.5f)) {
                val w = (width * scale / 2).roundToInt() * 2
                val h = (height * scale / 2).roundToInt() * 2
                if (caps.isSizeSupported(w, h)) return w to h
            }
            return width to height
        }
    }

    private fun drain(endOfStream: Boolean) {
        if (endOfStream) codec.signalEndOfInputStream()
        var idleTries = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleTries > 300) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (track < 0) track = writer.addTrack(codec.outputFormat)
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buffer != null && info.size > 0 && track >= 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        writer.write(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
