package com.pockettrack.app.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * A MediaMuxer shared by the video and audio encoders (which run on different threads).
 * MediaMuxer can only start once every track is added, so samples that arrive earlier are
 * kept until then.
 */
class Mp4Writer(file: File, private val expectedTracks: Int) {
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var added = 0
    private var started = false
    private var stopped = false
    private var samples = 0
    private val pending = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val track = muxer.addTrack(format)
        if (++added == expectedTracks) {
            muxer.start()
            started = true
            for ((t, buf, info) in pending) muxer.writeSampleData(t, buf, info)
            samples += pending.size
            pending.clear()
        }
        return track
    }

    @Synchronized
    fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped) return
        if (started) {
            muxer.writeSampleData(track, buffer, info)
            samples++
        } else {
            val copy = ByteBuffer.allocateDirect(info.size)
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            copy.put(buffer)
            copy.flip()
            val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending += Triple(track, copy, i)
        }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        if (started && samples > 0) {
            try { muxer.stop() } catch (_: Exception) {}
        }
        muxer.release()
    }
}
