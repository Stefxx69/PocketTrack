package com.pockettrack.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class RecordingInfo(
    val dir: File,
    /** Folder name (PT_yyyyMMdd_HHmmss). Stable, safe for URLs and file systems. */
    val id: String,
    /** The name the user gave the scan, e.g. "New scan (1)". */
    val title: String,
    val frames: Int,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Int,
    val sizeBytes: Long,
    /** Trim range in frames (inclusive), or -1 when the scan isn't trimmed. */
    val trimStart: Int = -1,
    val trimEnd: Int = -1,
) {
    val trimmed: Boolean get() = trimStart >= 0 && trimEnd >= trimStart
    /** Length in seconds of what will be used (the trim range if set). */
    val usedDurationSec: Double
        get() = if (trimmed && fps > 0) (trimEnd - trimStart + 1).toDouble() / fps else durationSec

    val files: List<File> get() = FILES.map { File(dir, it) }.filter { it.isFile }
    val thumbnail: File get() = File(dir, RecordingStore.THUMB)

    companion object {
        /** Everything a recording consists of; the PC receiver accepts exactly these names. */
        val FILES = listOf("track.json", "meta.json", "video.mp4", "depth.bin")
    }
}

/** Recordings live in the app's external files dir: Android/data/com.pockettrack.app/files/recordings/<id>/ */
object RecordingStore {
    const val DEFAULT_TITLE = "New scan"
    const val THUMB = "thumb.jpg"

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "recordings").apply { mkdirs() }

    fun newRecordingDir(ctx: Context): Pair<String, File> {
        val id = "PT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(root(ctx), id)
        dir.mkdirs()
        return id to dir
    }

    fun list(ctx: Context): List<RecordingInfo> =
        root(ctx).listFiles()
            ?.filter { File(it, "meta.json").isFile && File(it, "video.mp4").isFile }
            ?.mapNotNull { load(it) }
            ?.sortedByDescending { it.id }
            ?: emptyList()

    fun load(dir: File): RecordingInfo? = try {
        val meta = JSONObject(File(dir, "meta.json").readText())
        RecordingInfo(
            dir = dir,
            id = dir.name,
            title = meta.optString("title").ifBlank { meta.optString("name", dir.name) },
            frames = meta.optInt("frames"),
            durationSec = meta.optDouble("duration"),
            width = meta.optInt("width"),
            height = meta.optInt("height"),
            fps = meta.optInt("fps", 30),
            sizeBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L,
            trimStart = meta.optInt("trim_start", -1),
            trimEnd = meta.optInt("trim_end", -1),
        )
    } catch (e: Exception) {
        null
    }

    /**
     * [wanted] if nobody else uses it, otherwise "[wanted] (1)", "(2)", ...
     * [exceptId] is the recording being renamed, so it doesn't clash with itself.
     */
    fun uniqueTitle(ctx: Context, wanted: String, exceptId: String? = null): String {
        val base = wanted.trim().ifEmpty { DEFAULT_TITLE }
        val taken = list(ctx).filter { it.id != exceptId }.map { it.title.lowercase(Locale.ROOT) }.toSet()
        if (base.lowercase(Locale.ROOT) !in taken) return base
        var n = 1
        while ("$base ($n)".lowercase(Locale.ROOT) in taken) n++
        return "$base ($n)"
    }

    /** Stores the title in meta.json (Blender reads it from there too). Returns the final, unique title. */
    fun setTitle(ctx: Context, dir: File, wanted: String): String {
        val title = uniqueTitle(ctx, wanted, exceptId = dir.name)
        val file = File(dir, "meta.json")
        val meta = JSONObject(file.readText())
        meta.put("title", title)
        file.writeText(meta.toString() + "\n")
        return title
    }

    /**
     * Non-destructive trim: the video and track stay complete, meta.json just records which frames
     * to use (Blender sets its frame range from it). Pass null to clear.
     */
    fun setTrim(dir: File, range: IntRange?) {
        val file = File(dir, "meta.json")
        val meta = JSONObject(file.readText())
        if (range == null) {
            meta.remove("trim_start")
            meta.remove("trim_end")
        } else {
            meta.put("trim_start", range.first)
            meta.put("trim_end", range.last)
        }
        file.writeText(meta.toString() + "\n")
    }

    /** Grabs a frame from the video as a small JPEG for the library grid. */
    fun ensureThumbnail(dir: File): File? {
        val thumb = File(dir, THUMB)
        if (thumb.isFile) return thumb
        val video = File(dir, "video.mp4")
        if (!video.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.path)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) * 1000
            val frame = retriever.getFrameAtTime(durationUs / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val w = 480
            val h = (frame.height * w.toFloat() / frame.width).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(frame, w, h, true)
            thumb.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (small !== frame) small.recycle()
            frame.recycle()
            thumb
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    fun delete(info: RecordingInfo) {
        info.dir.deleteRecursively()
    }

    /** File-system-safe version of the title for exported zips. */
    private fun zipName(info: RecordingInfo): String {
        val safe = info.title.replace(Regex("[^A-Za-z0-9 _().-]"), "_").trim().ifEmpty { info.id }
        return "$safe.zip"
    }

    /** Writes <id>/track.json, meta.json, video.mp4 into a zip. The mp4 is already compressed, so store-level only. */
    fun writeZip(info: RecordingInfo, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (f in info.files) {
                zip.putNextEntry(ZipEntry("${info.id}/${f.name}"))
                f.inputStream().use { it.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Saves <title>.zip to Download/PocketTrack (visible over USB and in any file manager).
     * Returns a human-readable location.
     */
    fun exportToDownloads(ctx: Context, info: RecordingInfo): String {
        val fileName = zipName(info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketTrack")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { writeZip(info, it) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "Download/PocketTrack/$fileName"
        } else {
            // Android 8/9: public Downloads needs a storage permission, so use the app's own folder.
            val dir = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.outputStream().use { writeZip(info, it) }
            return file.absolutePath
        }
    }

    /** Builds a zip in the cache and returns a content:// Uri for the share sheet. */
    fun zipForSharing(ctx: Context, info: RecordingInfo): Uri {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, zipName(info))
        file.outputStream().use { writeZip(info, it) }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    }
}
