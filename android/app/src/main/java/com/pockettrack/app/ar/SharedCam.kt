package com.pockettrack.app.ar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.google.ar.core.Session
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ARCore "shared camera": we open the camera ourselves and give ARCore its streams, plus one
 * extra high-resolution stream from the same lens for recording. Same lens and same 16:9 crop as
 * ARCore's own image, so ARCore's poses and lens data apply to it directly; frames are paired
 * by their sensor timestamps (see CaptureActivity).
 *
 * Without a suitable size the extra stream is skipped and recording uses ARCore's 1080p image.
 */
class SharedCam(context: Context, private val session: Session, textureLong: Int, textureShort: Int) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val shared = session.sharedCamera
    private val cameraId: String = session.cameraConfig.cameraId
    private val chars = manager.getCameraCharacteristics(cameraId)

    /** Clockwise rotation that makes the sensor image upright in the device's natural orientation. */
    val sensorOrientation: Int = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    /** Whether frame timestamps count from boot (else: monotonic clock). Needed to sync audio. */
    val timestampsBootTime: Boolean = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    /** Sensor-orientation size of the extra stream, or null if there is none. */
    var hqSize: Size? = pickHqSize(textureLong, textureShort)
        private set

    /** Detached until the GL thread attaches it (SurfaceTexture(false) = detached mode). */
    var hqTexture: SurfaceTexture? = null
        private set
    private var hqSurface: Surface? = null
    @Volatile var hqFrameAvailable = false

    private val thread = HandlerThread("PocketTrackCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    init {
        hqSize?.let { size ->
            val st = SurfaceTexture(false)
            st.setDefaultBufferSize(size.width, size.height)
            st.setOnFrameAvailableListener({ hqFrameAvailable = true }, handler)
            hqTexture = st
            hqSurface = Surface(st)
        }
    }

    private fun pickHqSize(texLong: Int, texShort: Int): Size? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val aspect = texLong.toFloat() / texShort
        return map.getOutputSizes(SurfaceTexture::class.java)
            ?.filter {
                val l = max(it.width, it.height)
                val s = min(it.width, it.height)
                abs(l.toFloat() / s - aspect) < 0.01f && l > texLong && l <= 3840
            }
            ?.maxByOrNull { it.width * it.height }
    }

    /**
     * Opens the camera and starts all streams. [onActive] runs on the camera thread once frames
     * flow; that's when the ARCore session may be resumed. If the device can't run the extra
     * stream alongside ARCore's, it retries without it.
     */
    @SuppressLint("MissingPermission") // the activity checks CAMERA before creating us
    fun open(onActive: () -> Unit, onError: (String) -> Unit) {
        shared.setAppSurfaces(cameraId, listOfNotNull(hqSurface))
        val callback = shared.createARDeviceStateCallback(object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                startSession(d, onActive, onError)
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                device = null
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                device = null
                onError("Camera error $error")
            }
        }, handler)
        manager.openCamera(cameraId, callback, handler)
    }

    @Suppress("DEPRECATION") // ARCore's shared-camera callbacks are built around this API
    private fun startSession(d: CameraDevice, onActive: () -> Unit, onError: (String) -> Unit) {
        val surfaces = ArrayList(shared.arCoreSurfaces)
        hqSurface?.let { surfaces += it }
        val request = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            surfaces.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, session.cameraConfig.fpsRange)
            // Fixed focus, like ARCore's own FocusMode.FIXED: keeps the focal length constant.
            val hyperfocal = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
            if (hyperfocal > 0f) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, hyperfocal)
            }
        }
        var activated = false
        val callback = shared.createARSessionStateCallback(object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(cs: CameraCaptureSession) {
                captureSession = cs
                try {
                    cs.setRepeatingRequest(request.build(), null, handler)
                } catch (e: Exception) {
                    onError("Camera request failed: ${e.message}")
                }
            }

            override fun onActive(cs: CameraCaptureSession) {
                if (!activated) {
                    activated = true
                    onActive()
                }
            }

            override fun onConfigureFailed(cs: CameraCaptureSession) {
                if (hqSurface != null) {
                    Log.w(TAG, "Camera can't run the ${hqSize} stream next to ARCore's; using 1080p")
                    dropHq()
                    d.close()
                    device = null
                    open(onActive, onError)
                } else {
                    onError("Camera configuration failed")
                }
            }
        }, handler)
        d.createCaptureSession(surfaces, callback, handler)
    }

    private fun dropHq() {
        hqSurface?.release()
        hqSurface = null
        hqTexture?.release()
        hqTexture = null
        hqSize = null
    }

    /** Stops the camera (call after the ARCore session is paused). */
    fun close() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { device?.close() } catch (_: Exception) {}
        device = null
    }

    fun release() {
        close()
        dropHq()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "PocketTrack"
    }
}
