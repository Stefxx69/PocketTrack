package com.pockettrack.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.pockettrack.app.ar.BackgroundRenderer
import com.pockettrack.app.ar.CubeState
import com.pockettrack.app.ar.HqRenderer
import com.pockettrack.app.ar.OverlayRenderer
import com.pockettrack.app.ar.RecordableConfigChooser
import com.pockettrack.app.ar.SharedCam
import com.pockettrack.app.data.RecordingStore
import com.pockettrack.app.record.AudioCapture
import com.pockettrack.app.record.TrackRecorder
import com.pockettrack.app.record.VideoEncoder
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The capture screen: calibrate (ARCore finds the floor), place and adjust the cube, record.
 *
 * Threads: UI callbacks only write the @Volatile inputs, the touch queue and the cube (under its
 * lock). Everything ARCore/GL related, including recording, happens on the GL thread.
 */
class CaptureActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var hud: TextView
    private lateinit var hint: TextView
    private lateinit var recordButton: View
    private lateinit var pointsButton: MaterialButton
    private lateinit var planesButton: MaterialButton
    private lateinit var floorLockButton: MaterialButton
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector

    private var session: Session? = null
    private var installRequested = false
    private var permissionRequested = false
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.containsKey(Manifest.permission.CAMERA) && result[Manifest.permission.CAMERA] != true) {
                fail("PocketTrack needs the camera to track.")
            }
            refreshToggles()
        }

    /** Our own camera control (ARCore shared camera) with the extra 4K stream; null = plain ARCore. */
    private var sharedCam: SharedCam? = null
    private var cameraOpen = false
    /** False until ARCore is resumed (with the shared camera that happens once frames flow). */
    @Volatile private var arResumed = false

    // --- GL thread state ---
    private val background = BackgroundRenderer()
    private val overlay = OverlayRenderer()
    private var textureBound = false
    private var displayRotation = Surface.ROTATION_90
    /** Camera image size (landscape); also the display geometry and the video size. */
    private var imageW = 1920
    private var imageH = 1080
    /** Camera texture size, independent of orientation. */
    private var texLong = 1920
    private var texShort = 1080
    private var cameraFps = 30
    private var surfaceW = 1
    private var surfaceH = 1
    private var vpX = 0
    private var vpY = 0
    private var vpW = 1
    private var vpH = 1
    @Volatile private var geometryDirty = true

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val anchorMatrix = FloatArray(16)
    private val tmpVp = FloatArray(16)
    private val tmpInv = FloatArray(16)

    private var anchor: Anchor? = null
    private var dragging = false
    private var dragOffX = 0f
    private var dragOffZ = 0f
    private var lastDrawNs = 0L
    private var recorder: TrackRecorder? = null
    private var lastRecordedTs = 0L
    private var lastCamInAnchor: Pose? = null
    /** Whether the running recording uses the 4K stream (decided when it starts). */
    private var recordingHq = false
    private val hq = HqRenderer()
    private var hqAttached = false
    private val hqMatrix = FloatArray(16)
    /** Latched 4K frame waiting for the ARCore frame with the same timestamp, or -1. */
    private var hqHeldTs = -1L
    private val pendingFrames = ArrayDeque<PendingFrame>()
    private var hqMatched = 0
    private var hqDropped = 0
    /** Recent sensor timestamps of ARCore frames, to check that 4K frames can be paired. */
    private val recentSensorTs = LongArray(30)
    private var recentSensorCount = 0
    private var hqSyncHits = 0
    private var hqSyncTries = 0
    /** 4K is only offered once its frames have been seen to pair with ARCore's. */
    @Volatile private var hqVerified = false
    /** ARCore frames since the last 4K match while recording (watchdog). */
    private var framesWithoutHq = 0
    private var depthFrameCounter = 0
    private var lastHudNs = 0L

    // --- written by the UI thread ---
    @Volatile private var moveX = 0f
    @Volatile private var moveY = 0f
    @Volatile private var moveZ = 0f
    @Volatile private var rotateDir = 0f
    @Volatile private var showPoints = true
    @Volatile private var showPlanes = true
    @Volatile private var floorLock = true
    @Volatile private var recordToggleRequested = false
    @Volatile private var resetRequested = false
    @Volatile private var isRecording = false
    /** Vertical (portrait) capture mode; remembered between sessions. */
    private var vertical = false
    private lateinit var orientationButton: MaterialButton
    /** Digital zoom the user picked; the GL thread eases [zoom] towards it. */
    @Volatile private var zoomTarget = 1f
    private var zoom = 1f // GL thread
    private lateinit var zoomButton: MaterialButton
    private lateinit var qualityButton: MaterialButton
    private lateinit var micButton: MaterialButton
    /** Record the 4K stream (when the phone has one) rather than ARCore's 1080p image. */
    @Volatile private var wantHq = true
    @Volatile private var micOn = true
    /** ARCore Depth while recording (optional; uses extra battery). */
    @Volatile private var depthOn = false
    private var depthSupported = false
    @Volatile private var depthConfigDirty = false
    private lateinit var depthButton: MaterialButton
    private val touches = ConcurrentLinkedQueue<TouchEvent>()
    private var scrolling = false

    private val cube = CubeState()
    private var pendingScale = 1f // guarded by cube

    private data class TouchEvent(val type: Int, val x: Float = 0f, val y: Float = 0f)

    /** An ARCore frame's recording data, kept until its 4K twin arrives. */
    private class PendingFrame(
        val ts: Long, val camInAnchor: Pose, val quality: Int, val cube: FloatArray, val zoom: Float,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vertical = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_VERTICAL, false)
        requestedOrientation = orientationFor(vertical)
        setContentView(R.layout.activity_capture)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        surfaceView = findViewById(R.id.surface)
        hud = findViewById(R.id.hud)
        hint = findViewById(R.id.hint)
        recordButton = findViewById(R.id.btnRecord)
        pointsButton = findViewById(R.id.btnPoints)
        planesButton = findViewById(R.id.btnPlanes)
        floorLockButton = findViewById(R.id.btnFloorLock)
        orientationButton = findViewById(R.id.btnOrientation)
        zoomButton = findViewById(R.id.btnZoom)
        qualityButton = findViewById(R.id.btnQuality)
        micButton = findViewById(R.id.btnMic)
        depthButton = findViewById(R.id.btnDepth)
        getSharedPreferences(PREFS, MODE_PRIVATE).let {
            wantHq = it.getBoolean(PREF_HQ, true)
            micOn = it.getBoolean(PREF_MIC, true)
            depthOn = it.getBoolean(PREF_DEPTH, false)
        }

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(RecordableConfigChooser())
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        setupGestures()
        setupButtons()
        applyOrientationLayout(vertical)
        hint.setText(R.string.hint_calibrate)
    }

    // ---------------------------------------------------------------- UI

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                synchronized(cube) { pendingScale *= detector.scaleFactor }
                return true
            }
        })
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                touches.add(TouchEvent(TAP, e.x, e.y))
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress || e2.pointerCount > 1) return false
                if (!scrolling) {
                    scrolling = true
                    touches.add(TouchEvent(DRAG_START, e1?.x ?: e2.x, e1?.y ?: e2.y))
                }
                touches.add(TouchEvent(DRAG, e2.x, e2.y))
                return true
            }
        })
        surfaceView.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            gestureDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (scrolling) touches.add(TouchEvent(DRAG_END))
                    scrolling = false
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hold(id: Int, onChange: (Boolean) -> Unit) {
        findViewById<View>(id).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> onChange(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onChange(false)
            }
            false
        }
    }

    private fun setupButtons() {
        hold(R.id.btnFwd) { moveZ = if (it) 1f else 0f }
        hold(R.id.btnBack) { moveZ = if (it) -1f else 0f }
        hold(R.id.btnLeft) { moveX = if (it) -1f else 0f }
        hold(R.id.btnRight) { moveX = if (it) 1f else 0f }
        hold(R.id.btnUp) { moveY = if (it) 1f else 0f }
        hold(R.id.btnDown) { moveY = if (it) -1f else 0f }
        hold(R.id.btnRotL) { rotateDir = if (it) 1f else 0f }
        hold(R.id.btnRotR) { rotateDir = if (it) -1f else 0f }

        // Leaving stops (and saves) a running recording via onPause.
        findViewById<View>(R.id.btnExit).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPlace).setOnClickListener { touches.add(TouchEvent(CENTER)) }
        // Tap: show/hide the 1×–3× slider. Long-press: back to 1×. Works while recording too.
        val zoomPanel = findViewById<View>(R.id.zoomPanel)
        val zoomSlider = findViewById<SeekBar>(R.id.zoomSlider)
        zoomSlider.max = ((MAX_ZOOM - 1f) * 100).roundToInt()
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    zoomTarget = 1f + progress / 100f
                    refreshToggles()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        zoomButton.setOnClickListener {
            zoomPanel.visibility = if (zoomPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        zoomButton.setOnLongClickListener {
            zoomTarget = 1f
            zoomSlider.progress = 0
            refreshToggles()
            true
        }
        findViewById<View>(R.id.btnSize).setOnClickListener { showSizeDialog() }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            if (isRecording) toast("Stop recording first") else resetRequested = true
        }
        recordButton.setOnClickListener { recordToggleRequested = true }

        orientationButton.setOnClickListener {
            if (isRecording) {
                toast("Stop recording first")
                return@setOnClickListener
            }
            // The AR session (and the placed cube) carry on through the rotation.
            vertical = !vertical
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_VERTICAL, vertical).apply()
            requestedOrientation = orientationFor(vertical)
            refreshToggles()
        }
        qualityButton.setOnClickListener {
            if (isRecording) {
                toast("Stop recording first")
            } else if (sharedCam?.hqSize == null || (!hqVerified && hqSyncTries >= 300)) {
                toast("This phone can't record 4K while tracking, so scans are 1080p")
            } else if (!hqVerified) {
                toast("Checking that 4K lines up with tracking… move the phone a little and try again")
            } else {
                wantHq = !wantHq
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HQ, wantHq).apply()
            }
            refreshToggles()
        }
        micButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                micOn = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            } else {
                micOn = !micOn
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_MIC, micOn).apply()
            refreshToggles()
        }
        depthButton.setOnClickListener {
            if (isRecording) {
                toast("Stop recording first")
            } else if (!depthSupported) {
                toast("This phone doesn't support ARCore Depth")
            } else {
                depthOn = !depthOn
                depthConfigDirty = true
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_DEPTH, depthOn).apply()
                if (depthOn) toast("Depth on: recordings also capture rough room geometry")
            }
            refreshToggles()
        }
        pointsButton.setOnClickListener { showPoints = !showPoints; refreshToggles() }
        planesButton.setOnClickListener { showPlanes = !showPlanes; refreshToggles() }
        floorLockButton.setOnClickListener { floorLock = !floorLock; refreshToggles() }
        refreshToggles()
    }

    private fun orientationFor(vertical: Boolean) =
        if (vertical) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout(newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    /** In portrait the hint moves above the movement pad so it doesn't cover the back button / HUD. */
    private fun applyOrientationLayout(portrait: Boolean) {
        val dp = resources.displayMetrics.density
        val lp = hint.layoutParams as android.widget.FrameLayout.LayoutParams
        if (portrait) {
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            lp.topMargin = 0
            lp.bottomMargin = (232 * dp).toInt()
        } else {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            lp.topMargin = (10 * dp).toInt()
            lp.bottomMargin = 0
        }
        hint.layoutParams = lp

        // The three overlay toggles: a row in landscape, a column down the right edge in portrait
        // (a row would run into the status pill on a narrow screen).
        val row = findViewById<LinearLayout>(R.id.toggleRow)
        row.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (i in 1 until row.childCount) {
            val clp = row.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            clp.marginStart = if (portrait) 0 else (8 * dp).toInt()
            clp.topMargin = if (portrait) (8 * dp).toInt() else 0
            row.getChildAt(i).layoutParams = clp
        }
    }

    private fun refreshToggles() {
        orientationButton.text = if (vertical) "Vertical" else "Landscape"
        orientationButton.setIconResource(if (vertical) R.drawable.ic_portrait else R.drawable.ic_landscape)
        zoomButton.text = String.format(Locale.US, "%.1f×", zoomTarget).replace(".0×", "×")
        pointsButton.isChecked = showPoints
        planesButton.isChecked = showPlanes
        floorLockButton.isChecked = floorLock
        val hqSize = sharedCam?.hqSize
        val hqLabel = if (hqSize != null && maxOf(hqSize.width, hqSize.height) < 3840) {
            "${minOf(hqSize.width, hqSize.height)}p"
        } else "4K"
        val hqUsable = hqSize != null && hqVerified
        // Greyed-out "4K" = this phone can't (or hasn't yet been checked to) record it.
        qualityButton.text = if (hqUsable && !wantHq) "1080p" else hqLabel
        qualityButton.alpha = if (hqUsable) 1f else 0.38f
        depthButton.isChecked = depthOn && depthSupported
        depthButton.alpha = if (depthSupported) 1f else 0.38f
        micButton.isChecked = micOn && ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showSizeDialog() {
        val snap = synchronized(cube) { cube.snapshot() }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
        }
        val names = arrayOf("Width (X)", "Height (Y)", "Depth (Z)")
        for (axis in 0..2) {
            val label = TextView(this)
            val bar = SeekBar(this)
            bar.max = MAX_CM - MIN_CM
            bar.progress = ((snap[4 + axis] * 100).roundToInt().coerceIn(MIN_CM, MAX_CM)) - MIN_CM
            label.text = "${names[axis]}: ${bar.progress + MIN_CM} cm"
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val cm = progress + MIN_CM
                    label.text = "${names[axis]}: $cm cm"
                    if (fromUser) synchronized(cube) { cube.setSize(axis, cm / 100f) }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            layout.addView(label)
            layout.addView(bar)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Cube size")
            .setView(layout)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset to 50 cm") { _, _ -> synchronized(cube) { cube.resetSize() } }
            .show()
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED
                ) {
                    installRequested = true
                    return
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    if (permissionRequested) {
                        fail("PocketTrack needs the camera to track.")
                    } else {
                        permissionRequested = true
                        // The microphone is optional: without it scans are recorded silent.
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                    return
                }
                session = createSession()
            } catch (e: UnavailableException) {
                fail("ARCore is not available on this device: ${e.javaClass.simpleName}")
                return
            } catch (e: Exception) {
                fail("Could not start ARCore: ${e.message}")
                return
            }
        }
        val s = session!!
        val sc = sharedCam
        if (sc != null) {
            if (!cameraOpen) {
                cameraOpen = true
                sc.open(
                    onActive = {
                        try {
                            s.resume()
                            arResumed = true
                        } catch (e: Exception) {
                            runOnUiThread { fail("Could not start tracking: ${e.message}") }
                        }
                    },
                    onError = { msg -> runOnUiThread { fail(msg) } },
                )
            }
        } else {
            try {
                s.resume()
                arResumed = true
            } catch (e: CameraNotAvailableException) {
                session = null
                fail("Camera not available. Close other camera apps and try again.")
                return
            }
        }
        surfaceView.onResume()
        geometryDirty = true
        refreshToggles()
    }

    private fun createSession(): Session {
        // Shared camera gives us a 4K stream next to ARCore's; fall back to plain ARCore if the
        // device or ARCore version can't do it.
        val s = try {
            Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA))
        } catch (e: Exception) {
            Log.w(TAG, "Shared camera unavailable", e)
            Session(this)
        }
        // Biggest camera texture up to 1080p; at equal size prefer 30 fps over 60 (lighter to encode).
        val configs = s.getSupportedCameraConfigs(
            CameraConfigFilter(s).setFacingDirection(CameraConfig.FacingDirection.BACK)
        )
        val best = configs
            .filter { max(it.textureSize.width, it.textureSize.height) <= 1920 && it.fpsRange.upper >= 24 }
            .maxWithOrNull(compareBy({ it.textureSize.width * it.textureSize.height }, { -it.fpsRange.upper }))
        if (best != null) s.cameraConfig = best

        val size = s.cameraConfig.textureSize
        texLong = max(size.width, size.height)
        texShort = min(size.width, size.height)
        imageW = if (vertical) texShort else texLong
        imageH = if (vertical) texLong else texShort
        cameraFps = s.cameraConfig.fpsRange.upper.coerceIn(24, 60)

        depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        s.configure(sessionConfig(s))
        textureBound = false
        val shared = try { s.sharedCamera } catch (e: Exception) { null }
        sharedCam = if (shared != null) {
            try {
                SharedCam(this, s, texLong, texShort)
            } catch (e: Exception) {
                Log.w(TAG, "Shared camera setup failed", e)
                null
            }
        } else null
        if (sharedCam == null && shared != null) {
            // A shared-camera session without our camera control gets no frames: use a plain one.
            val config = s.cameraConfig
            s.close()
            return Session(this).also { plain ->
                plain.cameraConfig = config
                depthSupported = plain.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                plain.configure(sessionConfig(plain))
            }
        }
        return s
    }

    private fun sessionConfig(s: Session) = Config(s).apply {
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        updateMode = Config.UpdateMode.BLOCKING // one draw per camera frame -> one video frame each
        lightEstimationMode = Config.LightEstimationMode.DISABLED
        focusMode = Config.FocusMode.FIXED      // keeps the focal length constant for the solve
        depthMode = if (depthOn && depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            if (isRecording) {
                // Finish the file on the GL thread before it is paused.
                val latch = CountDownLatch(1)
                surfaceView.queueEvent { stopRecording(); latch.countDown() }
                latch.await(5, TimeUnit.SECONDS)
            }
            surfaceView.onPause()
            arResumed = false
            session?.pause()
            sharedCam?.close()
            cameraOpen = false
        }
    }

    override fun onDestroy() {
        sharedCam?.release()
        sharedCam = null
        session?.close()
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background.create()
        overlay.create()
        hq.create()
        hqAttached = false
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        geometryDirty = true
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_90
        else windowManager.defaultDisplay.rotation

    /** Letterboxes the camera image (aspect imageW:imageH) inside the surface. */
    private fun computeViewport() {
        val aspect = imageW.toFloat() / imageH
        if (surfaceW.toFloat() / surfaceH > aspect) {
            vpH = surfaceH; vpW = (surfaceH * aspect).roundToInt()
            vpX = (surfaceW - vpW) / 2; vpY = 0
        } else {
            vpW = surfaceW; vpH = (surfaceW / aspect).roundToInt()
            vpX = 0; vpY = (surfaceH - vpH) / 2
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glClearColor(0.06f, 0.06f, 0.07f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        if (!textureBound) {
            s.setCameraTextureName(background.textureId)
            textureBound = true
        }
        val hqTex = sharedCam?.hqTexture
        if (hqTex != null && !hqAttached) {
            try {
                hq.attach(hqTex)
                hqAttached = true
            } catch (e: Exception) {
                Log.w(TAG, "4K texture attach failed", e)
            }
        }
        if (geometryDirty) {
            geometryDirty = false
            displayRotation = currentRotation()
            // Display geometry = the full camera image (turned upright in vertical mode), so nothing
            // is cropped and the projection matrix describes exactly the pixels we record.
            val portrait = surfaceH > surfaceW
            imageW = if (portrait) texShort else texLong
            imageH = if (portrait) texLong else texShort
            s.setDisplayGeometry(displayRotation, imageW, imageH)
            computeViewport()
        }

        if (!arResumed) return // the shared camera is still starting up
        if (depthConfigDirty) {
            depthConfigDirty = false
            try {
                s.configure(sessionConfig(s))
            } catch (e: Exception) {
                Log.w(TAG, "Depth config failed", e)
            }
        }
        val frame: Frame = try {
            s.update()
        } catch (e: Exception) {
            Log.e(TAG, "session.update failed", e)
            return
        }
        // Must run before the timestamp check: hasDisplayGeometryChanged() is true for one frame
        // only, and that frame can arrive before the camera has produced an image.
        // Ease towards the chosen zoom so zooming while recording is smooth (and keyed per frame).
        val target = zoomTarget
        zoom = if (abs(target - zoom) < 0.005f) target else zoom + (target - zoom) * 0.2f
        background.update(frame, zoom)
        if (frame.timestamp == 0L) return
        val camera = frame.camera

        val now = System.nanoTime()
        val dt = if (lastDrawNs == 0L) 0f else ((now - lastDrawNs) / 1e9f).coerceAtMost(0.1f)
        lastDrawNs = now

        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            camera.getProjectionMatrix(proj, 0, NEAR, FAR)
            BackgroundRenderer.zoomProjection(proj, zoom)
            camera.getViewMatrix(view, 0)
        }

        if (resetRequested) {
            resetRequested = false
            if (recorder == null) {
                anchor?.detach()
                anchor = null
                synchronized(cube) { cube.resetPosition() }
            }
        }
        if (anchor?.trackingState == TrackingState.STOPPED) anchor = null

        if (tracking) processInput(s, frame, camera, dt) else touches.clear()

        if (recordToggleRequested) {
            recordToggleRequested = false
            if (recorder == null) startRecording(camera) else stopRecording()
        }

        val a = anchor
        val anchorTracking = a != null && a.trackingState == TrackingState.TRACKING
        if (a != null) a.pose.toMatrix(anchorMatrix, 0)
        val cubeSnap = synchronized(cube) { cube.snapshot() }

        // The camera's own capture timestamp. ARCore's frame.timestamp is on a different clock,
        // so this is what 4K frames (and the microphone) are matched against.
        val sensorTs = sensorTimestamp(frame)
        if (sensorTs != recentSensorTs[(recentSensorCount + 29) % 30]) {
            recentSensorTs[recentSensorCount % 30] = sensorTs
            recentSensorCount++
        }

        val pointCloud = frame.acquirePointCloud()
        try {
            // 1) Video: clean camera image only, once per new camera frame.
            val rec = recorder
            val pointCount = pointCloud.points.remaining() / 4
            if (rec != null && a != null && frame.timestamp != lastRecordedTs) {
                lastRecordedTs = frame.timestamp
                try {
                    val anchorInv = a.pose.inverse()
                    val camInAnchor = anchorInv.compose(camera.displayOrientedPose)
                    val quality = frameQuality(tracking && anchorTracking, pointCount, camInAnchor)
                    if (recordingHq) {
                        recordHqFrame(rec, PendingFrame(sensorTs, camInAnchor, quality, cubeSnap, zoom))
                        if (rec.frameCount == 0 && ++framesWithoutHq > HQ_WATCHDOG_FRAMES) {
                            // 4K frames aren't pairing on this phone after all: restart in 1080p.
                            Log.w(TAG, "4K never paired (held=$hqHeldTs, last ARCore=$sensorTs); falling back")
                            hqVerified = false
                            discardRecording(rec)
                            toast("4K didn't sync on this phone – recording in 1080p instead")
                            runOnUiThread { refreshToggles() }
                            startRecording(camera, forceArImage = true)
                        }
                    } else {
                        rec.recordFrame(sensorTs, camInAnchor, quality, cubeSnap, zoom) { background.draw() }
                    }
                    if (tracking && rec.frameCount % 3 == 0) collectPoints(rec, pointCloud, anchorInv)
                    if (tracking && depthOn && depthSupported && ++depthFrameCounter % DEPTH_EVERY == 0) {
                        collectDepth(rec, frame, camera, anchorInv)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recording failed", e)
                    toast("Recording failed: ${e.message}")
                    stopRecording()
                }
            }
            if (!recordingHq || recorder == null) {
                // Keep taking 4K frames off the queue so the camera never stalls, and check that
                // they pair with ARCore's frames before 4K is offered.
                if (hqTex != null && hqAttached && sharedCam?.hqFrameAvailable == true) {
                    sharedCam?.hqFrameAvailable = false
                    hqTex.updateTexImage()
                    checkHqSync(hqTex.timestamp)
                }
            }

            // 2) Screen.
            GLES20.glViewport(vpX, vpY, vpW, vpH)
            background.draw()
            val planes = s.getAllTrackables(Plane::class.java)
            if (tracking) {
                if (showPlanes) overlay.drawPlanes(planes, view, proj)
                if (showPoints) overlay.drawPoints(pointCloud.points, view, proj)
                if (anchorTracking) overlay.drawCube(anchorMatrix, cubeSnap, view, proj, recorder != null)
            }

            if (now - lastHudNs > 250_000_000L) {
                lastHudNs = now
                updateHud(camera, planes, pointCount)
            }
        } finally {
            pointCloud.close()
        }
    }

    private fun collectPoints(rec: TrackRecorder, pc: com.google.ar.core.PointCloud, anchorInv: Pose) {
        val pts = pc.points
        val ids = pc.ids
        val n = pts.remaining() / 4
        val p = FloatArray(3)
        for (i in 0 until n) {
            val conf = pts.get(i * 4 + 3)
            if (conf < 0.3f) continue
            p[0] = pts.get(i * 4); p[1] = pts.get(i * 4 + 1); p[2] = pts.get(i * 4 + 2)
            val local = anchorInv.transformPoint(p)
            rec.addPoint(ids.get(i), local[0], local[1], local[2], conf)
        }
    }

    /**
     * Tracking quality for one frame: lost (no tracking), weak (few feature points, or a big jump
     * in position/rotation since the last frame, typical of motion blur) or good.
     */
    private fun frameQuality(tracking: Boolean, pointCount: Int, camInAnchor: Pose): Int {
        val last = lastCamInAnchor
        lastCamInAnchor = camInAnchor
        if (!tracking) return TrackRecorder.QUALITY_LOST
        if (pointCount < MIN_GOOD_POINTS) return TrackRecorder.QUALITY_WEAK
        if (last != null) {
            val dx = camInAnchor.tx() - last.tx()
            val dy = camInAnchor.ty() - last.ty()
            val dz = camInAnchor.tz() - last.tz()
            val q1 = camInAnchor.rotationQuaternion
            val q2 = last.rotationQuaternion
            val dot = abs(q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]).coerceAtMost(1f)
            val angleDeg = Math.toDegrees(2.0 * kotlin.math.acos(dot.toDouble()))
            if (sqrt(dx * dx + dy * dy + dz * dz) > MAX_STEP_M || angleDeg > MAX_STEP_DEG) {
                return TrackRecorder.QUALITY_WEAK
            }
        }
        return TrackRecorder.QUALITY_GOOD
    }

    /**
     * Turns ARCore's raw depth image (with confidence) into anchor-space points. The depth image
     * is laid out like the CPU camera image, so it's unprojected with that image's intrinsics and
     * the physical camera pose (both in sensor orientation).
     */
    private fun collectDepth(rec: TrackRecorder, frame: Frame, camera: Camera, anchorInv: Pose) {
        val depthImage = try { frame.acquireRawDepthImage16Bits() } catch (e: Exception) { return }
        val confImage = try {
            frame.acquireRawDepthConfidenceImage()
        } catch (e: Exception) {
            depthImage.close()
            return
        }
        try {
            val dw = depthImage.width
            val dh = depthImage.height
            val intr = camera.imageIntrinsics
            val dims = intr.imageDimensions
            val sx = dw.toFloat() / dims[0]
            val sy = dh.toFloat() / dims[1]
            val fx = intr.focalLength[0] * sx
            val fy = intr.focalLength[1] * sy
            val cx = intr.principalPoint[0] * sx
            val cy = intr.principalPoint[1] * sy
            val toAnchor = anchorInv.compose(camera.pose)
            val dPlane = depthImage.planes[0]
            val cPlane = confImage.planes[0]
            val dBuf = dPlane.buffer.order(java.nio.ByteOrder.nativeOrder())
            val cBuf = cPlane.buffer
            val p = FloatArray(3)
            for (v in 0 until dh) {
                for (u in 0 until dw) {
                    val conf = cBuf.get(v * cPlane.rowStride + u * cPlane.pixelStride).toInt() and 0xFF
                    if (conf < MIN_DEPTH_CONFIDENCE) continue
                    val mm = dBuf.getShort(v * dPlane.rowStride + u * dPlane.pixelStride).toInt() and 0xFFFF
                    if (mm == 0 || mm > MAX_DEPTH_MM) continue
                    val d = mm / 1000f
                    // Camera frame: +X right, +Y up (image readout), looking down -Z.
                    p[0] = (u - cx) / fx * d
                    p[1] = -(v - cy) / fy * d
                    p[2] = -d
                    val a = toAnchor.transformPoint(p)
                    rec.addDepthPoint(a[0], a[1], a[2])
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Depth sample failed", e)
        } finally {
            depthImage.close()
            confImage.close()
        }
    }

    private fun sensorTimestamp(frame: Frame): Long = try {
        frame.imageMetadata.getLong(ImageMetadata.SENSOR_TIMESTAMP)
    } catch (e: Exception) {
        frame.timestamp
    }

    /** Counts how many 4K frames have an ARCore twin; 4K is offered after a clear majority. */
    private fun checkHqSync(hqTs: Long) {
        if (hqVerified || hqSyncTries >= 300) return
        hqSyncTries++
        val n = minOf(recentSensorCount, 30)
        var best = Long.MAX_VALUE
        for (i in 0 until n) best = minOf(best, abs(recentSensorTs[i] - hqTs))
        if (best < 1_000_000L) hqSyncHits++
        if (hqSyncTries == 1 || hqSyncTries % 60 == 0) {
            Log.i(TAG, "4K sync check: ${hqSyncHits}/${hqSyncTries} paired, nearest offset ${best / 1000} us")
        }
        if (hqSyncTries >= 20 && hqSyncHits * 10 >= hqSyncTries * 8) {
            hqVerified = true
            Log.i(TAG, "4K pairing verified")
            runOnUiThread { refreshToggles() }
        }
    }

    /** Throws away a recording that produced nothing usable. GL thread. */
    private fun discardRecording(rec: TrackRecorder) {
        recorder = null
        isRecording = false
        recordingHq = false
        try { rec.finish() } catch (_: Exception) {}
        rec.dir.deleteRecursively()
    }

    /**
     * Pairs ARCore frames with 4K frames by sensor timestamp (both come from the same capture).
     * The 4K frame may arrive a little before or after ARCore's; unmatched ones are dropped, so
     * every encoded frame has exactly its own pose.
     */
    private fun recordHqFrame(rec: TrackRecorder, pending: PendingFrame) {
        pendingFrames.addLast(pending)
        while (pendingFrames.size > 10) pendingFrames.removeFirst()
        val sc = sharedCam ?: return
        val st = sc.hqTexture ?: return
        if (hqHeldTs < 0 && sc.hqFrameAvailable) {
            sc.hqFrameAvailable = false
            st.updateTexImage()
            st.getTransformMatrix(hqMatrix)
            hqHeldTs = st.timestamp
        }
        if (hqHeldTs < 0) return
        val index = pendingFrames.indexOfFirst { abs(it.ts - hqHeldTs) < 1_000_000L }
        if (index >= 0) {
            val p = pendingFrames[index]
            repeat(index + 1) { pendingFrames.removeFirst() }
            // Android already turns this stream upright for the phone's natural (portrait)
            // orientation, so only the screen's rotation needs undoing.
            val rotation = (360 - displayDegrees()) % 360
            rec.recordFrame(p.ts, p.camInAnchor, p.quality, p.cube, p.zoom) { hq.draw(hqMatrix, rotation, p.zoom) }
            hqMatched++
            framesWithoutHq = 0
            hqHeldTs = -1
        } else if (pendingFrames.isEmpty() || hqHeldTs < pendingFrames.first().ts ||
            hqHeldTs - pendingFrames.last().ts > 200_000_000L
        ) {
            // Older than anything ARCore still has, or implausibly far ahead: drop it.
            hqDropped++
            hqHeldTs = -1
        } // else: the 4K frame is slightly ahead; keep it until ARCore's frame arrives
    }

    private fun displayDegrees() = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    // ---------------------------------------------------------------- input (GL thread)

    private fun processInput(s: Session, frame: Frame, camera: Camera, dt: Float) {
        while (true) {
            val ev = touches.poll() ?: break
            when (ev.type) {
                TAP -> placeOrMove(s, frame, camera, ev.x, ev.y, fromCenter = false)
                CENTER -> placeOrMove(s, frame, camera, surfaceW / 2f, surfaceH / 2f, fromCenter = true)
                DRAG_START -> {
                    val a = anchor ?: continue
                    val img = viewToImage(ev.x, ev.y) ?: continue
                    val (cx, cy, cz) = synchronized(cube) { Triple(cube.x, cube.y, cube.z) }
                    val hit = rayToAnchorPlane(a, img[0], img[1], cy) ?: continue
                    dragOffX = cx - hit[0]
                    dragOffZ = cz - hit[1]
                    dragging = true
                }
                DRAG -> {
                    if (!dragging) continue
                    val a = anchor ?: continue
                    val img = viewToImage(ev.x, ev.y) ?: continue
                    val h = synchronized(cube) { cube.y }
                    val hit = rayToAnchorPlane(a, img[0], img[1], h) ?: continue
                    synchronized(cube) { cube.x = hit[0] + dragOffX; cube.z = hit[1] + dragOffZ }
                }
                DRAG_END -> dragging = false
            }
        }

        val a = anchor ?: return
        if (a.trackingState != TrackingState.TRACKING) return
        val camInAnchor = a.pose.inverse().compose(camera.displayOrientedPose)
        synchronized(cube) {
            if (pendingScale != 1f) {
                cube.scale(pendingScale)
                pendingScale = 1f
            }
            val mx = moveX; val my = moveY; val mz = moveZ; val rot = rotateDir
            if (mx != 0f || my != 0f || mz != 0f || rot != 0f) {
                // Move relative to where the phone is looking, projected onto the floor.
                val zAxis = camInAnchor.zAxis
                val xAxis = camInAnchor.xAxis
                val fwd = flatNormalize(-zAxis[0], -zAxis[2], 0f, -1f)
                val right = flatNormalize(xAxis[0], xAxis[2], 1f, 0f)
                val dx = camInAnchor.tx() - cube.x
                val dy = camInAnchor.ty() - (cube.y + cube.sizeY / 2)
                val dz = camInAnchor.tz() - cube.z
                val speed = (sqrt(dx * dx + dy * dy + dz * dz) * 0.5f).coerceIn(0.1f, 1.5f) // m/s
                cube.x += (right[0] * mx + fwd[0] * mz) * speed * dt
                cube.z += (right[1] * mx + fwd[1] * mz) * speed * dt
                cube.y += my * speed * dt
                cube.yaw += rot * (Math.PI.toFloat() / 2f) * dt // 90°/s
            }
            // Collision lock: the bottom of the cube can't sink through the floor.
            if (floorLock && cube.y < 0f) cube.y = 0f
        }
    }

    private fun flatNormalize(x: Float, z: Float, fallbackX: Float, fallbackZ: Float): FloatArray {
        val len = sqrt(x * x + z * z)
        return if (len < 1e-3f) floatArrayOf(fallbackX, fallbackZ) else floatArrayOf(x / len, z / len)
    }

    /** First tap places the anchor on a detected floor; later taps slide the cube there. */
    private fun placeOrMove(s: Session, frame: Frame, camera: Camera, x: Float, y: Float, fromCenter: Boolean) {
        val img = viewToImage(x, y) ?: return
        val a = anchor
        if (a == null) {
            // ARCore hit-tests the unzoomed image: map the zoomed pixel back out from the centre.
            val hx = imageW / 2f + (img[0] - imageW / 2f) / zoom
            val hy = imageH / 2f + (img[1] - imageH / 2f) / zoom
            val floorHits = frame.hitTest(hx, hy).filter {
                val t = it.trackable
                t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    t.trackingState == TrackingState.TRACKING
            }
            // Prefer the mapped part of the floor, but a floor continues past what ARCore has
            // scanned so far, so also accept the plane's extension within a few metres.
            val hit = floorHits.firstOrNull { (it.trackable as Plane).isPoseInPolygon(it.hitPose) }
                ?: floorHits.firstOrNull { it.distance < 5f }
            if (hit == null) {
                if (fromCenter) toast(getString(R.string.hint_no_floor_center))
                return
            }
            val hp = hit.hitPose
            val cp = camera.pose
            // Anchor stands on the floor, +Z (the cube's front) facing the phone.
            val yaw = atan2(cp.tx() - hp.tx(), cp.tz() - hp.tz())
            anchor = s.createAnchor(
                Pose(floatArrayOf(hp.tx(), hp.ty(), hp.tz()), floatArrayOf(0f, sin(yaw / 2), 0f, cos(yaw / 2)))
            )
            synchronized(cube) { cube.resetPosition() }
        } else {
            val h = synchronized(cube) { cube.y }
            val hit = rayToAnchorPlane(a, img[0], img[1], h) ?: return
            synchronized(cube) { cube.x = hit[0]; cube.z = hit[1] }
        }
    }

    /** View pixel -> camera image pixel (the letterboxed area), or null outside it. */
    private fun viewToImage(x: Float, y: Float): FloatArray? {
        val top = surfaceH - vpY - vpH
        val u = (x - vpX) / vpW
        val v = (y - top) / vpH
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        return floatArrayOf(u * imageW, v * imageH)
    }

    /** Casts a ray through an image pixel and intersects it with the plane y = [height] in anchor space. Returns (x, z). */
    private fun rayToAnchorPlane(a: Anchor, ix: Float, iy: Float, height: Float): FloatArray? {
        val ndcX = ix / imageW * 2f - 1f
        val ndcY = 1f - iy / imageH * 2f
        Matrix.multiplyMM(tmpVp, 0, proj, 0, view, 0)
        if (!Matrix.invertM(tmpInv, 0, tmpVp, 0)) return null
        fun unproject(z: Float): FloatArray {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, tmpInv, 0, floatArrayOf(ndcX, ndcY, z, 1f), 0)
            return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
        }
        val inv = a.pose.inverse()
        val near = inv.transformPoint(unproject(-1f))
        val far = inv.transformPoint(unproject(1f))
        val dy = far[1] - near[1]
        if (abs(dy) < 1e-6f) return null
        val t = (height - near[1]) / dy
        if (t <= 0f || t > 1f) return null
        return floatArrayOf(near[0] + t * (far[0] - near[0]), near[2] + t * (far[2] - near[2]))
    }

    // ---------------------------------------------------------------- recording (GL thread)

    private fun startRecording(camera: Camera, forceArImage: Boolean = false) {
        val a = anchor
        if (a == null || camera.trackingState != TrackingState.TRACKING) {
            toast(getString(R.string.place_first))
            return
        }
        val (name, dir) = RecordingStore.newRecordingDir(this)
        try {
            val projection = FloatArray(16)
            camera.getProjectionMatrix(projection, 0, NEAR, FAR)
            // 4K when the shared camera has the stream attached.
            val sc = sharedCam
            val hqSize = sc?.hqSize
            recordingHq = !forceArImage && wantHq && hqVerified && hqSize != null && hqAttached && sc.hqTexture != null
            val (srcW, srcH) = if (recordingHq && sc != null && hqSize != null) {
                val rot = (sc.sensorOrientation - displayDegrees() + 360) % 360
                if (rot == 90 || rot == 270) hqSize.height to hqSize.width else hqSize.width to hqSize.height
            } else imageW to imageH
            // Same aspect as the display geometry; smaller only if the encoder can't do full size.
            val (encW, encH) = VideoEncoder.supportedSize(srcW, srcH)
            val audio = if (micOn && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) AudioCapture.create(sc?.timestampsBootTime ?: true) else null
            recorder = TrackRecorder(dir, name, encW, encH, cameraFps, projection, a.pose, audio)
            pendingFrames.clear()
            hqHeldTs = -1
            hqMatched = 0
            hqDropped = 0
            framesWithoutHq = 0
            lastCamInAnchor = null
            Log.i(TAG, "Recording ${encW}x$encH (${if (recordingHq) "4K stream" else "ARCore image"}), audio=${audio != null}")
            lastRecordedTs = 0L
            isRecording = true
            runOnUiThread { recordButton.isSelected = true; recordButton.contentDescription = "Stop recording" }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording", e)
            dir.deleteRecursively()
            toast("Could not start recording: ${e.message}")
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        isRecording = false
        runOnUiThread { recordButton.isSelected = false; recordButton.contentDescription = "Record" }
        if (recordingHq) Log.i(TAG, "4K frames matched $hqMatched, dropped $hqDropped")
        recordingHq = false
        try {
            rec.finish()
        } catch (e: Exception) {
            Log.e(TAG, "Encoder finish failed", e)
        }
        if (rec.frameCount == 0) {
            rec.dir.deleteRecursively()
            toast("Nothing was recorded – keep the cube's area tracked (status dot green) while recording")
            return
        }
        Thread {
            try {
                val title = RecordingStore.uniqueTitle(this, RecordingStore.DEFAULT_TITLE)
                rec.writeFiles(title)
                RecordingStore.ensureThumbnail(rec.dir)
                runOnUiThread { askForName(rec.dir, title, rec.frameCount) }
            } catch (e: Exception) {
                Log.e(TAG, "Writing track failed", e)
                toast("Saving failed: ${e.message}")
            }
        }.start()
    }

    /** The scan is already saved as [defaultTitle]; this only renames it. */
    private fun askForName(dir: java.io.File, defaultTitle: String, frames: Int) {
        if (isFinishing || isDestroyed) {
            Toast.makeText(applicationContext, "Saved “$defaultTitle”", Toast.LENGTH_SHORT).show()
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            setText(defaultTitle)
            selectAll()
            isSingleLine = true
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Name this scan")
            .setMessage("$frames frames recorded.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val wanted = input.text.toString()
                Thread {
                    val title = try {
                        RecordingStore.setTitle(this, dir, wanted)
                    } catch (e: Exception) {
                        defaultTitle
                    }
                    toast("Saved “$title”")
                }.start()
            }
            .setNeutralButton("Discard") { _, _ -> confirmDiscard(dir, defaultTitle, frames) }
            .show()
        input.requestFocus()
    }

    /** "Are you sure?" before throwing a fresh scan away; "No" goes back to naming it. */
    private fun confirmDiscard(dir: java.io.File, defaultTitle: String, frames: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard this scan?")
            .setMessage("The video and track are deleted from the phone. This can't be undone.")
            .setCancelable(false)
            .setPositiveButton("Discard") { _, _ ->
                Thread {
                    dir.deleteRecursively()
                    toast("Scan discarded")
                }.start()
            }
            .setNegativeButton("Keep it") { _, _ -> askForName(dir, defaultTitle, frames) }
            .show()
    }

    // ---------------------------------------------------------------- HUD

    private fun updateHud(camera: Camera, planes: Collection<Plane>, pointCount: Int) {
        val tracking = camera.trackingState == TrackingState.TRACKING
        val floors = planes.count {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }

        val rec = recorder
        // Status pill: coloured dot + state, then a dimmer line of numbers.
        val (dotColor, state) = when {
            rec != null -> R.color.rec_red to String.format(
                Locale.US, "REC %02d:%02d", rec.durationSec.toInt() / 60, rec.durationSec.toInt() % 60
            )
            tracking && floors > 0 -> R.color.ok_green to "Tracking"
            tracking -> R.color.warn_amber to "Finding floor"
            else -> R.color.rec_red to "Lost"
        }
        val hq4k = sharedCam?.hqSize != null && hqVerified && wantHq && (rec == null || recordingHq)
        val res = if (hq4k) "4K" else "${minOf(imageW, imageH)}p"
        var details = String.format(Locale.US, "%s · %d fps · %.1f× · %d pts", res, cameraFps, zoom, pointCount)
        if (rec != null && depthOn && depthSupported) {
            details += String.format(Locale.US, " · depth %.1fk", rec.depthCount / 1000f)
        }
        val hudText = SpannableStringBuilder().apply {
            append("● ", ForegroundColorSpan(ContextCompat.getColor(this@CaptureActivity, dotColor)), 0)
            append(state)
            append("\n")
            append(details, ForegroundColorSpan(0xB3FFFFFF.toInt()), 0)
            setSpan(RelativeSizeSpan(0.85f), length - details.length, length, 0)
        }
        val hintText = when {
            !tracking -> failureText(camera.trackingFailureReason)
            rec != null -> String.format(Locale.US, "Recording · %d frames", rec.frameCount)
            anchor == null && floors == 0 -> getString(R.string.hint_calibrate)
            anchor == null -> getString(R.string.hint_place)
            else -> getString(R.string.hint_adjust)
        }
        runOnUiThread {
            hud.text = hudText
            hint.text = hintText
        }
    }

    private fun failureText(reason: TrackingFailureReason): String = getString(
        when (reason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.fail_light
            TrackingFailureReason.EXCESSIVE_MOTION -> R.string.fail_motion
            TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.fail_features
            TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.fail_camera
            TrackingFailureReason.NONE -> R.string.hint_calibrate
            else -> R.string.fail_generic
        }
    )

    private companion object {
        const val TAG = "PocketTrack"
        const val PREFS = "capture"
        const val PREF_VERTICAL = "vertical"
        const val PREF_HQ = "hq"
        const val PREF_MIC = "mic"
        const val PREF_DEPTH = "depth"
        /** Sample depth on every Nth camera frame (~6 per second). */
        const val DEPTH_EVERY = 5
        /** Raw depth confidence is 0-255; keep the confident half. */
        const val MIN_DEPTH_CONFIDENCE = 128
        const val MAX_DEPTH_MM = 5000
        /** Fewer tracked points than this in view = weak tracking. */
        const val MIN_GOOD_POINTS = 30
        /** Per-frame camera jumps above these (about 2.4 m/s or 240 deg/s at 30 fps) = weak tracking. */
        const val MAX_STEP_M = 0.08f
        const val MAX_STEP_DEG = 8.0
        /** ARCore frames (~1.5 s) a 4K recording may go without a single paired frame. */
        const val HQ_WATCHDOG_FRAMES = 45
        const val MAX_ZOOM = 3f
        const val NEAR = 0.05f
        const val FAR = 100f
        const val MIN_CM = 2
        const val MAX_CM = 300

        const val TAP = 0
        const val CENTER = 1
        const val DRAG_START = 2
        const val DRAG = 3
        const val DRAG_END = 4
    }
}
