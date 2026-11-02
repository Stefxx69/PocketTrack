package com.pockettrack.app.replay

import com.google.ar.core.Pose
import com.pockettrack.app.ar.BackgroundRenderer
import com.pockettrack.app.ar.GlUtil
import com.pockettrack.app.data.RecordingStore
import java.io.File
import java.nio.FloatBuffer
import org.json.JSONObject

/** A recording's track.json, loaded for the replay viewer (anchor space, ARCore axes). */
class TrackData(dir: File) {
    val title: String
    val width: Int
    val height: Int
    val fps: Int
    val projection = FloatArray(16)
    val frameCount: Int
    /** Per frame: px, py, pz, qx, qy, qz, qw. */
    private val poses: FloatArray
    /** Per frame digital zoom (1 = none). */
    private val zooms: FloatArray
    /** Per frame tracking quality (TrackRecorder.QUALITY_*). */
    val quality: IntArray
    /** Trim range in frames (inclusive), or -1 if not trimmed. Changed by the replay screen. */
    var trimStart = -1
    var trimEnd = -1
    /** Cube keys: frame, x, y, z, yaw, sizeX, sizeY, sizeZ. */
    private val keys: List<FloatArray>
    /** x, y, z, confidence per point. */
    val points: FloatBuffer
    val video = File(dir, "video.mp4")

    init {
        val t = JSONObject(File(dir, "track.json").readText())
        val info = RecordingStore.load(dir)
        title = info?.title ?: t.optString("name", dir.name)
        if (info != null && info.trimmed) {
            trimStart = info.trimStart
            trimEnd = info.trimEnd
        }
        val v = t.getJSONObject("video")
        width = v.getInt("width")
        height = v.getInt("height")
        fps = v.optInt("fps", 30)
        val p = t.getJSONObject("camera").getJSONArray("projection")
        for (i in 0 until 16) projection[i] = p.getDouble(i).toFloat()

        val frames = t.getJSONArray("frames")
        frameCount = frames.length()
        poses = FloatArray(frameCount * 7)
        zooms = FloatArray(frameCount) { 1f }
        quality = IntArray(frameCount) { 1 }
        for (i in 0 until frameCount) {
            val f = frames.getJSONArray(i)
            for (k in 0 until 7) poses[i * 7 + k] = f.getDouble(k + 1).toFloat()
            quality[i] = f.optInt(8, 1)
            if (f.length() > 9) zooms[i] = f.getDouble(9).toFloat() // older scans have no zoom
        }

        val k = t.getJSONObject("object").getJSONArray("keys")
        keys = (0 until k.length()).map { i ->
            val a = k.getJSONArray(i)
            FloatArray(8) { j -> a.getDouble(j).toFloat() }
        }.ifEmpty { listOf(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f)) }

        val pts = t.optJSONArray("points")
        val n = pts?.length() ?: 0
        points = GlUtil.floatBuffer(maxOf(n, 1) * 4)
        for (i in 0 until n) {
            val a = pts!!.getJSONArray(i)
            for (j in 0 until 4) points.put(a.getDouble(j).toFloat())
        }
        points.position(0)
        points.limit(n * 4)
    }

    val firstUsed: Int get() = if (trimStart >= 0) trimStart.coerceIn(0, frameCount - 1) else 0
    val lastUsed: Int get() = if (trimEnd >= 0) trimEnd.coerceIn(0, frameCount - 1) else frameCount - 1

    fun frameAt(ptsUs: Long): Int = ((ptsUs * fps + 500_000L) / 1_000_000L).toInt().coerceIn(0, frameCount - 1)

    /** View matrix (inverse camera pose) for [frame]. */
    fun viewMatrix(frame: Int, out: FloatArray) {
        val o = frame.coerceIn(0, frameCount - 1) * 7
        Pose(
            floatArrayOf(poses[o], poses[o + 1], poses[o + 2]),
            floatArrayOf(poses[o + 3], poses[o + 4], poses[o + 5], poses[o + 6]),
        ).inverse().toMatrix(out, 0)
    }

    /** Projection for [frame], including that frame's digital zoom. */
    fun projectionAt(frame: Int, out: FloatArray) {
        System.arraycopy(projection, 0, out, 0, 16)
        BackgroundRenderer.zoomProjection(out, zooms[frame.coerceIn(0, frameCount - 1)])
    }

    /** The cube at [frame]: x, y, z, yaw, sizeX, sizeY, sizeZ. */
    fun cubeAt(frame: Int): FloatArray {
        val key = keys.lastOrNull { it[0] <= frame } ?: keys.first()
        return key.copyOfRange(1, 8)
    }
}
