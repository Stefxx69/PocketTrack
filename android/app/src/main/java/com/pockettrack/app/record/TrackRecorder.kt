package com.pockettrack.app.record

import android.os.Build
import com.google.ar.core.Pose
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import org.json.JSONObject

/**
 * Collects one recording: the clean camera video (+ microphone audio) plus, per video frame, the
 * camera pose relative to the floor anchor and a tracking-quality code. Also keeps keyframes of
 * the cube whenever it is moved, and a sparse point cloud of ARCore feature points (tracking
 * markers in Blender).
 *
 * Coordinates are ARCore's: right-handed, metres, +Y up. The camera looks down -Z with +Y up
 * on the image (same convention as a Blender camera). The Blender add-on converts to Z-up.
 */
class TrackRecorder(
    val dir: File,
    val name: String,
    width: Int,
    height: Int,
    val fps: Int,
    private val projection: FloatArray,
    private val anchorWorld: Pose,
    private val audio: AudioCapture?,
) {
    private val writer = Mp4Writer(File(dir, "video.mp4"), if (audio != null) 2 else 1)
    val encoder = VideoEncoder(width, height, fps, writer)
    val hasAudio = audio != null
    private val width = width
    private val height = height
    private val createdAt = Date()

    private val frames = ArrayList<FloatArray>(4096)
    private var firstTimestampNs = -1L
    private val objectKeys = ArrayList<FloatArray>()
    private var lastKey: FloatArray? = null
    private val points = HashMap<Int, FloatArray>()
    /** ARCore Depth points, one per 3 cm voxel (anchor space): voxel key -> x, y, z, hits. */
    private val depth = HashMap<Long, FloatArray>()
    val depthCount: Int get() = depth.size

    init {
        audio?.start(writer)
    }

    val frameCount: Int get() = frames.size

    /** Duration in seconds based on the camera timestamps. */
    val durationSec: Float
        get() = if (frames.isEmpty()) 0f else frames.last()[0]

    /**
     * Encodes one camera frame and records its pose. [draw] renders the image. It may run more
     * than once: when the camera skipped frames, the gap is filled with copies so the video keeps
     * real-time speed and stays in sync with the audio (copies are marked [QUALITY_WEAK]).
     *
     * [camInAnchor] is the display-oriented camera pose in anchor space, [quality] a QUALITY_ code,
     * [zoom] the digital zoom (the stored projection is the 1x one; zoom scales it about the image
     * centre) and [cube] is (x, y, z, yaw, sizeX, sizeY, sizeZ) in anchor space.
     */
    fun recordFrame(
        timestampNs: Long, camInAnchor: Pose, quality: Int, cube: FloatArray, zoom: Float, draw: () -> Unit,
    ) {
        if (firstTimestampNs < 0) {
            firstTimestampNs = timestampNs
            audio?.videoStartNs = timestampNs
        }
        val expected = ((timestampNs - firstTimestampNs) * fps + 500_000_000L) / 1_000_000_000L
        var copies = (expected - frames.size).toInt().coerceIn(0, MAX_FILL)
        while (copies-- > 0) {
            encoder.encodeFrame(frames.size, draw)
            addFrame(timestampNs, camInAnchor, if (quality == QUALITY_GOOD) QUALITY_WEAK else quality, cube, zoom)
        }
        encoder.encodeFrame(frames.size, draw)
        addFrame(timestampNs, camInAnchor, quality, cube, zoom)
    }

    private fun addFrame(timestampNs: Long, camInAnchor: Pose, quality: Int, cube: FloatArray, zoom: Float) {
        val t = (timestampNs - firstTimestampNs) / 1e9f
        val q = camInAnchor.rotationQuaternion
        frames += floatArrayOf(
            t, camInAnchor.tx(), camInAnchor.ty(), camInAnchor.tz(),
            q[0], q[1], q[2], q[3], quality.toFloat(), zoom
        )
        val last = lastKey
        if (last == null || cube.indices.any { abs(cube[it] - last[it]) > 1e-4f }) {
            val key = FloatArray(8)
            key[0] = (frames.size - 1).toFloat()
            System.arraycopy(cube, 0, key, 1, 7)
            objectKeys += key
            lastKey = cube.copyOf()
        }
    }

    /** Adds feature points (already in anchor space): id -> (x, y, z, confidence). */
    fun addPoint(id: Int, x: Float, y: Float, z: Float, confidence: Float) {
        val existing = points[id]
        if (existing != null) {
            existing[0] = x; existing[1] = y; existing[2] = z
            existing[3] = maxOf(existing[3], confidence)
        } else if (points.size < MAX_POINTS) {
            points[id] = floatArrayOf(x, y, z, confidence)
        }
    }

    /** Adds one depth sample (anchor space). Nearby samples are averaged into 3 cm voxels. */
    fun addDepthPoint(x: Float, y: Float, z: Float) {
        val key = (kotlin.math.floor(x / VOXEL).toLong() and 0x1FFFFF) or
            ((kotlin.math.floor(y / VOXEL).toLong() and 0x1FFFFF) shl 21) or
            ((kotlin.math.floor(z / VOXEL).toLong() and 0x1FFFFF) shl 42)
        val v = depth[key]
        if (v != null) {
            val n = v[3] + 1f
            v[0] += (x - v[0]) / n; v[1] += (y - v[1]) / n; v[2] += (z - v[2]) / n
            v[3] = n
        } else if (depth.size < MAX_DEPTH_POINTS) {
            depth[key] = floatArrayOf(x, y, z, 1f)
        }
    }

    /** Stops audio and video and closes the MP4. GL thread. */
    fun finish() {
        try { audio?.stop() } catch (_: Exception) {}
        try { encoder.finish() } finally { writer.stop() }
    }

    /** Writes track.json and meta.json. Safe to call from any thread after [finish]. */
    fun writeFiles(title: String) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(createdAt)

        // Intrinsics in pixels (origin top-left) derived from the GL projection matrix,
        // which ARCore computed for exactly this image aspect.
        val fx = projection[0] * width / 2f
        val fy = projection[5] * height / 2f
        val cx = (1f - projection[8]) * width / 2f
        val cy = (1f + projection[9]) * height / 2f

        File(dir, "track.json").bufferedWriter().use { w ->
            fun f(v: Float) = String.format(Locale.US, "%.6f", v)
            fun arr(v: FloatArray, from: Int = 0) = v.drop(from).joinToString(",") { f(it) }
            fun q(s: String) = "\"" + s.replace("\"", "") + "\""
            val aq = anchorWorld.rotationQuaternion
            w.write("{\n")
            w.write("${q("format")}:${q("pockettrack")},${q("version")}:1,\n")
            w.write("${q("name")}:${q(name)},${q("created")}:${q(iso)},")
            w.write("${q("device")}:${q(Build.MANUFACTURER + " " + Build.MODEL)},\n")
            w.write("${q("coordinates")}:${q("arcore: right-handed, metres, +Y up, poses relative to floor anchor")},\n")
            w.write(
                "${q("video")}:{${q("file")}:${q("video.mp4")},${q("width")}:$width,${q("height")}:$height," +
                    "${q("fps")}:$fps,${q("frame_count")}:${frames.size},${q("audio")}:$hasAudio},\n"
            )
            w.write("${q("camera")}:{${q("fx")}:${f(fx)},${q("fy")}:${f(fy)},${q("cx")}:${f(cx)},${q("cy")}:${f(cy)},${q("projection")}:[${arr(projection)}]},\n")
            w.write("${q("anchor_world")}:[${f(anchorWorld.tx())},${f(anchorWorld.ty())},${f(anchorWorld.tz())},${arr(aq)}],\n")
            w.write("${q("object")}:{${q("type")}:${q("cube")},${q("origin")}:${q("bottom_center")},")
            w.write("${q("key_layout")}:[${listOf("frame", "x", "y", "z", "yaw", "size_x", "size_y", "size_z").joinToString(",") { q(it) }}],${q("keys")}:[\n")
            objectKeys.forEachIndexed { i, k ->
                w.write("[${k[0].toInt()},${arr(k, 1)}]")
                w.write(if (i < objectKeys.size - 1) ",\n" else "\n")
            }
            w.write("]},\n")
            w.write("${q("frame_layout")}:[${listOf("t", "px", "py", "pz", "qx", "qy", "qz", "qw", "tracking", "zoom").joinToString(",") { q(it) }}],\n")
            w.write("${q("tracking_codes")}:{${q("0")}:${q("lost")},${q("1")}:${q("good")},${q("2")}:${q("weak")}},\n")
            w.write("${q("frames")}:[\n")
            frames.forEachIndexed { i, fr ->
                w.write("[${f(fr[0])},${arr(fr.copyOfRange(1, 8))},${fr[8].toInt()},${String.format(Locale.US, "%.4f", fr[9])}]")
                w.write(if (i < frames.size - 1) ",\n" else "\n")
            }
            w.write("],\n")
            w.write("${q("points")}:[\n")
            val pts = points.values.filter { it[3] >= MIN_POINT_CONFIDENCE }
            pts.forEachIndexed { i, p ->
                w.write("[${arr(p)}]")
                w.write(if (i < pts.size - 1) ",\n" else "\n")
            }
            w.write("]\n}\n")
        }

        val meta = JSONObject()
            .put("name", name)
            .put("title", title)
            .put("created", iso)
            .put("frames", frames.size)
            .put("duration", String.format(Locale.US, "%.2f", durationSec).toDouble())
            .put("width", width)
            .put("height", height)
            .put("fps", fps)
            .put("audio", hasAudio)
        // Depth points: little-endian float32 x, y, z per point (anchor space, ARCore axes).
        // Voxels seen only once are mostly noise, so they're left out.
        val keep = depth.values.filter { it[3] >= 2f }
        if (keep.isNotEmpty()) {
            val buf = java.nio.ByteBuffer.allocate(keep.size * 12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (p in keep) { buf.putFloat(p[0]); buf.putFloat(p[1]); buf.putFloat(p[2]) }
            File(dir, "depth.bin").writeBytes(buf.array())
        }
        meta.put("depth_points", keep.size)
        File(dir, "meta.json").writeText(meta.toString() + "\n")
    }

    companion object {
        private const val MAX_POINTS = 6000
        private const val MIN_POINT_CONFIDENCE = 0.3f
        private const val VOXEL = 0.03f
        private const val MAX_DEPTH_POINTS = 400_000
        /** Longest camera hiccup (in frames) that gets filled with copies. */
        private const val MAX_FILL = 15

        /** Per-frame tracking quality, the "tracking" column of track.json. */
        const val QUALITY_LOST = 0
        const val QUALITY_GOOD = 1
        const val QUALITY_WEAK = 2
    }
}
