package com.pockettrack.app.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log

/**
 * Microphone -> AAC, written into the recording's MP4 next to the video.
 *
 * The video is stamped as frame / fps from its first frame, and dropped camera frames are filled,
 * so video time = real time since that first frame. Audio is placed on the same clock: every
 * chunk gets its capture time (from AudioRecord's boot-time timestamps), minus [videoStartNs].
 * Audio from before the first video frame is dropped.
 *
 * [cameraUsesBootTime]: whether camera timestamps count from boot (SENSOR_INFO_TIMESTAMP_SOURCE
 * REALTIME) or are monotonic; the audio clock is picked to match.
 */
class AudioCapture private constructor(
    private val record: AudioRecord,
    private val cameraUsesBootTime: Boolean,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var writer: Mp4Writer? = null
    private var track = -1
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Camera timestamp of the first video frame; audio before it is dropped. */
    @Volatile var videoStartNs = -1L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start(writer: Mp4Writer) {
        this.writer = writer
        codec.start()
        record.startRecording()
        running = true
        thread = Thread({ loop() }, "PocketTrackAudio").apply { start() }
    }

    /** Stops and flushes. Call before the writer is stopped. */
    fun stop() {
        running = false
        thread?.join(2000)
        try { record.stop() } catch (_: Exception) {}
        record.release()
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    private fun nowNs() = if (cameraUsesBootTime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    private fun loop() {
        val pcm = ByteArray(CHUNK_BYTES)
        val ts = AudioTimestamp()
        val timebase = if (cameraUsesBootTime) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
        var framesRead = 0L
        var lastPtsUs = -1L
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val chunkFrames = n / 2
                // Capture time of this chunk's first sample.
                val startNs = if (record.getTimestamp(ts, timebase) == AudioRecord.SUCCESS) {
                    ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / RATE
                } else {
                    nowNs() - chunkFrames * 1_000_000_000L / RATE
                }
                framesRead += chunkFrames
                val t0 = videoStartNs
                if (t0 < 0 || startNs < t0) continue
                val ptsUs = (startNs - t0) / 1000
                if (ptsUs <= lastPtsUs) continue
                lastPtsUs = ptsUs
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    buf.clear()
                    buf.put(pcm, 0, n)
                    codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                }
                drain(info, false)
            }
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, lastPtsUs.coerceAtLeast(0) + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(info, true)
        } catch (e: Exception) {
            Log.e("PocketTrack", "Audio capture failed", e)
        }
    }

    private fun drain(info: MediaCodec.BufferInfo, untilEos: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos || ++idle > 50) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (track < 0) track = writer!!.addTrack(codec.outputFormat)
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !config && track >= 0) writer!!.write(track, buf, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val RATE = 48_000
        private const val CHUNK_BYTES = 2048 * 2 // 2048 mono 16-bit samples ≈ 43 ms

        /** Null if the microphone can't be opened (no permission, in use, ...). */
        @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
        fun create(cameraUsesBootTime: Boolean): AudioCapture? = try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES) * 4
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                AudioCapture(record, cameraUsesBootTime)
            }
        } catch (e: Exception) {
            Log.w("PocketTrack", "No microphone", e)
            null
        }
    }
}
