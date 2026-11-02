package com.pockettrack.app.transfer

import com.pockettrack.app.data.RecordingInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** Where the Blender add-on is listening, as encoded in its QR code. */
data class PcTarget(val hosts: List<String>, val port: Int, val pin: String)

/**
 * Wi-Fi transfer to the Blender add-on's receiver (plain HTTP on the local network, guarded by
 * the one-time PIN shown in Blender). Protocol:
 *   GET  /ping                         -> 200 if the PIN is right
 *   POST /upload?rec=NAME&file=FILE    -> raw file body
 *   POST /done?rec=NAME                -> Blender imports the recording
 * The PIN travels in the X-PocketTrack-Pin header.
 */
object PcTransfer {
    private const val PIN_HEADER = "X-PocketTrack-Pin"

    /** QR payload: {"pt":1,"h":["192.168.1.20",...],"p":8765,"k":"123456"} */
    fun parseQr(raw: String): PcTarget? = try {
        val o = JSONObject(raw)
        if (o.optInt("pt") != 1) null
        else {
            val hostsJson = o.getJSONArray("h")
            val hosts = (0 until hostsJson.length()).map { hostsJson.getString(it) }
            PcTarget(hosts, o.getInt("p"), o.getString("k"))
        }
    } catch (e: Exception) {
        null
    }

    /** Parses "192.168.1.20:8765" (port optional) typed by the user. */
    fun parseManual(address: String, pin: String): PcTarget? {
        val a = address.trim().removePrefix("http://").trimEnd('/')
        if (a.isEmpty() || pin.isBlank()) return null
        val host = a.substringBefore(':')
        val port = a.substringAfter(':', "8765").toIntOrNull() ?: return null
        return PcTarget(listOf(host), port, pin.trim())
    }

    /** Returns the first host that answers /ping with the right PIN. */
    fun findReachableHost(target: PcTarget): String? {
        for (host in target.hosts) {
            try {
                val c = open("http://$host:${target.port}/ping", target.pin, "GET", 1500)
                val ok = c.responseCode == 200
                c.disconnect()
                if (ok) return host
            } catch (_: IOException) {
            }
        }
        return null
    }

    fun send(
        info: RecordingInfo,
        target: PcTarget,
        host: String,
        progress: (fileName: String, sent: Long, total: Long) -> Unit,
    ) {
        val base = "http://$host:${target.port}"
        val rec = URLEncoder.encode(info.id, "UTF-8")
        val files = info.files
        val total = files.sumOf { it.length() }
        var sentBefore = 0L
        for (file in files) {
            val c = open("$base/upload?rec=$rec&file=${URLEncoder.encode(file.name, "UTF-8")}", target.pin, "POST", 5000)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(file.length())
            c.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        progress(file.name, sentBefore + sent, total)
                    }
                }
            }
            check(c.responseCode == 200) { "PC refused ${file.name}: HTTP ${c.responseCode}" }
            c.disconnect()
            sentBefore += file.length()
        }
        val done = open("$base/done?rec=$rec", target.pin, "POST", 5000)
        done.doOutput = true
        done.setFixedLengthStreamingMode(0)
        done.outputStream.close()
        check(done.responseCode == 200) { "PC did not accept the recording: HTTP ${done.responseCode}" }
        done.disconnect()
    }

    private fun open(url: String, pin: String, method: String, connectTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            this.connectTimeout = connectTimeout
            readTimeout = 60_000
            setRequestProperty(PIN_HEADER, pin)
        }
}
