package com.pockettrack.app

import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.google.android.material.button.MaterialButton
import com.pockettrack.app.ar.GlUtil
import com.pockettrack.app.ar.OverlayRenderer
import com.pockettrack.app.data.RecordingStore
import com.pockettrack.app.replay.QualityStripView
import com.pockettrack.app.replay.TrackData
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * Plays a recording (with sound) with its track drawn on top: a preview of what Blender will show.
 * ExoPlayer decodes into a SurfaceTexture; it reports each frame's presentation time together
 * with the time it releases the frame to the surface (which becomes the SurfaceTexture
 * timestamp), so each drawn frame uses exactly its own recorded camera pose.
 */
class ReplayActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var seek: SeekBar
    private lateinit var frameLabel: TextView
    private lateinit var playButton: MaterialButton
    private lateinit var qualityStrip: QualityStripView
    private lateinit var track: TrackData

    private var player: ExoPlayer? = null // main thread only
    /** Frame release time (= SurfaceTexture timestamp) -> presentation time, from ExoPlayer. */
    private val releaseToPts = ConcurrentHashMap<Long, Long>()
    private val frameAvailable = AtomicBoolean(false)
    @Volatile private var loopRequested = false

    // UI -> GL
    @Volatile private var playing = true
    @Volatile private var showFootage = true
    @Volatile private var showObject = true
    @Volatile private var showPoints = true
    @Volatile private var showFloor = true
    @Volatile private var userSeeking = false

    // GL thread
    private val overlay = OverlayRenderer()
    private var videoTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var currentFrame = 0
    private var lastUiNs = 0L
    private var surfaceW = 1
    private var surfaceH = 1
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private val quad = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadUv = GlUtil.floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return })
        track = try {
            TrackData(dir)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this scan: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Vertical scans replay in portrait so they fill the screen.
        requestedOrientation = if (track.height > track.width) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_replay)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        findViewById<TextView>(R.id.title).text = track.title
        findViewById<View>(R.id.btnExit).setOnClickListener { finish() }

        playButton = findViewById(R.id.btnPlay)
        playButton.setOnClickListener {
            playing = !playing
            player?.playWhenReady = playing
            playButton.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        seek = findViewById(R.id.seek)
        frameLabel = findViewById(R.id.frameLabel)
        seek.max = track.frameCount - 1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) seekToFrame(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                player?.playWhenReady = false // scrub frame by frame
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                player?.playWhenReady = playing
            }
        })

        qualityStrip = findViewById(R.id.qualityStrip)
        // Line the strip up with the seek bar's track (the bar is inset by its thumb padding).
        qualityStrip.setPadding(seek.paddingLeft, 0, seek.paddingRight, 0)
        refreshStrip()
        findViewById<View>(R.id.btnTrim).setOnClickListener { showTrimMenu(it, dir) }
        currentFrame = track.firstUsed

        toggle(R.id.btnFootage, "Footage", { showFootage }) { showFootage = it }
        toggle(R.id.btnObject, "Object", { showObject }) { showObject = it }
        toggle(R.id.btnPoints, "Points", { showPoints }) { showPoints = it }
        toggle(R.id.btnFloor, "Floor", { showFloor }) { showFloor = it }

        createPlayer()

        surfaceView = findViewById(R.id.surface)
        surfaceView.preserveEGLContextOnPause = false
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    // setSeekParameters / setVideoFrameMetadataListener are Media3 "unstable" APIs (stable in practice).
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun createPlayer() {
        val p = ExoPlayer.Builder(this).build()
        p.setSeekParameters(SeekParameters.EXACT)
        p.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            releaseToPts[releaseTimeNs] = presentationTimeUs
            if (releaseToPts.size > 120) {
                val cutoff = releaseTimeNs - 2_000_000_000L
                releaseToPts.keys.removeIf { it < cutoff }
            }
        }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) seekToFrame(track.firstUsed) // loop
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(track.video)))
        p.prepare()
        player = p
        seekToFrame(track.firstUsed)
        p.playWhenReady = playing
    }

    /** Exact seek to a recorded frame (frames are stamped frame / fps). Main thread. */
    private fun seekToFrame(frame: Int) {
        val f = frame.coerceIn(0, track.frameCount - 1)
        // Round up so the frame at or before that time is exactly this one.
        player?.seekTo((f * 1000L + track.fps - 1) / track.fps)
    }

    private fun refreshStrip() = qualityStrip.setData(track.quality, track.trimStart, track.trimEnd)

    /** Non-destructive trim: start/end at the current frame; saved to the scan's meta.json. */
    private fun showTrimMenu(anchor: View, dir: File) {
        val frame = seek.progress
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Start here (frame ${frame + 1})")
            menu.add(0, 2, 1, "End here (frame ${frame + 1})")
            if (track.trimStart >= 0) menu.add(0, 3, 2, "Clear trim")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        track.trimStart = frame
                        if (track.trimEnd < frame) track.trimEnd = track.frameCount - 1
                    }
                    2 -> {
                        track.trimEnd = frame
                        if (track.trimStart < 0 || track.trimStart > frame) track.trimStart = 0
                    }
                    3 -> { track.trimStart = -1; track.trimEnd = -1 }
                }
                val range = if (track.trimStart >= 0) track.trimStart..track.trimEnd else null
                Thread { RecordingStore.setTrim(dir, range) }.start()
                refreshStrip()
                true
            }
            show()
        }
    }

    private fun toggle(id: Int, label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val b = findViewById<MaterialButton>(id)
        fun refresh() { b.isChecked = get() }
        refresh()
        b.setOnClickListener { set(!get()); refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::surfaceView.isInitialized) surfaceView.onResume()
        player?.playWhenReady = playing
    }

    override fun onPause() {
        super.onPause()
        if (!::surfaceView.isInitialized) return
        player?.playWhenReady = false
        player?.clearVideoSurface()
        // The player renders into a SurfaceTexture of this GL context; free it before it goes.
        val latch = CountDownLatch(1)
        surfaceView.queueEvent { releaseVideo(); latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)
        surfaceView.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseVideo()
        overlay.create()
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

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        videoTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val st = SurfaceTexture(videoTex)
        st.setOnFrameAvailableListener({ frameAvailable.set(true) }, Handler(Looper.getMainLooper()))
        surfaceTexture = st
        val surface = Surface(st)
        videoSurface = surface
        runOnUiThread { player?.setVideoSurface(surface) }
    }

    private fun releaseVideo() {
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    /** Presentation time of the frame now in the SurfaceTexture, from its release timestamp. */
    private fun ptsFor(releaseNs: Long): Long? {
        releaseToPts[releaseNs]?.let { return it }
        // Tolerate tiny rounding differences between the two clocks' reports.
        var best: Long? = null
        var bestDiff = 3_000_000L
        for ((k, v) in releaseToPts) {
            val d = kotlin.math.abs(k - releaseNs)
            if (d < bestDiff) { bestDiff = d; best = v }
        }
        return best
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val st = surfaceTexture
        if (st != null && frameAvailable.getAndSet(false)) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            ptsFor(st.timestamp)?.let { currentFrame = track.frameAt(it) }
            // Loop within the trim range.
            if (playing && !userSeeking && currentFrame > track.lastUsed && !loopRequested) {
                loopRequested = true
                runOnUiThread {
                    seekToFrame(track.firstUsed)
                    loopRequested = false
                }
            }
        }

        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glClearColor(0.06f, 0.06f, 0.07f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Letterbox to the video's aspect, as in Blender's camera view.
        val aspect = track.width.toFloat() / track.height
        val vpW: Int
        val vpH: Int
        if (surfaceW.toFloat() / surfaceH > aspect) {
            vpH = surfaceH; vpW = (surfaceH * aspect).roundToInt()
        } else {
            vpW = surfaceW; vpH = (surfaceW / aspect).roundToInt()
        }
        GLES20.glViewport((surfaceW - vpW) / 2, (surfaceH - vpH) / 2, vpW, vpH)

        if (showFootage && st != null) {
            drawVideo()
        } else {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor((surfaceW - vpW) / 2, (surfaceH - vpH) / 2, vpW, vpH)
            GLES20.glClearColor(0.16f, 0.17f, 0.19f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        track.viewMatrix(currentFrame, view)
        track.projectionAt(currentFrame, proj)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        if (showFloor) overlay.drawFloorGrid(identity, view, proj)
        if (showPoints) overlay.drawPoints(track.points, view, proj)
        if (showObject) overlay.drawCube(identity, track.cubeAt(currentFrame), view, proj, recording = false)

        if (now - lastUiNs > 100_000_000L) {
            lastUiNs = now
            val f = currentFrame
            runOnUiThread {
                if (!userSeeking) seek.progress = f
                val s = f / track.fps
                frameLabel.text = String.format(
                    Locale.US, "%d:%02d  frame %d/%d", s / 60, s % 60, f + 1, track.frameCount
                )
            }
        }
    }

    private fun drawVideo() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        quad.position(0)
        quadUv.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadUv)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    companion object {
        const val EXTRA_DIR = "dir"
        private const val TAG = "PocketTrack"
    }
}
