package com.pockettrack.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pockettrack.app.data.RecordingInfo
import com.pockettrack.app.data.RecordingStore
import com.pockettrack.app.transfer.PcTarget
import com.pockettrack.app.transfer.PcTransfer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Library: a grid of scans. Tap a card to replay it, ⋮ for sending / exporting / renaming / deleting. */
class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private val adapter = Adapter(
        onOpen = { openReplay(it) },
        onMenu = { info, anchor -> showMenu(info, anchor) },
        loadThumb = { info, view -> loadThumbnail(info, view) },
    )
    private val io = Executors.newSingleThreadExecutor()
    private val thumbIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = ConcurrentHashMap<String, Bitmap>()

    /** The recording waiting for a QR scan result. */
    private var pendingSend: RecordingInfo? = null
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val info = pendingSend ?: return@registerForActivityResult
        pendingSend = null
        val raw = result.contents ?: return@registerForActivityResult // cancelled
        val target = PcTransfer.parseQr(raw)
        if (target == null) toast("That QR code isn't from the PocketTrack Blender add-on")
        else send(info, target)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter
        findViewById<View>(R.id.fabNew).setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdown()
        thumbIo.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        io.execute {
            val items = RecordingStore.list(this)
            main.post {
                adapter.items = items
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.count).text = when (items.size) {
                    0 -> "Phone camera tracking for Blender"
                    1 -> "1 scan"
                    else -> "${items.size} scans"
                }
            }
        }
    }

    private fun openReplay(info: RecordingInfo) {
        startActivity(Intent(this, ReplayActivity::class.java).putExtra(ReplayActivity.EXTRA_DIR, info.dir.path))
    }

    private fun loadThumbnail(info: RecordingInfo, view: ImageView) {
        view.tag = info.id
        thumbs[info.id]?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        thumbIo.execute {
            val file = RecordingStore.ensureThumbnail(info.dir) ?: return@execute
            val bmp = BitmapFactory.decodeFile(file.path) ?: return@execute
            thumbs[info.id] = bmp
            main.post { if (view.tag == info.id) view.setImageBitmap(bmp) }
        }
    }

    private fun showMenu(info: RecordingInfo, anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.recording_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_send_qr -> scanQr(info)
                    R.id.action_send_manual -> askAddress(info)
                    R.id.action_export -> exportZip(info)
                    R.id.action_share -> shareZip(info)
                    R.id.action_rename -> rename(info)
                    R.id.action_delete -> confirmDelete(info)
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- send to PC

    private fun scanQr(info: RecordingInfo) {
        pendingSend = info
        // Runs inside PocketTrack, so Android asks for camera access here if it hasn't been granted.
        qrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the QR code in Blender (PocketTrack panel → Receive from phone)")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun askAddress(info: RecordingInfo) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val address = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.20:8765"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pin = EditText(this).apply {
            hint = "PIN shown in Blender"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(address)
            addView(pin)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Send to PC")
            .setMessage("In Blender: N-panel → PocketTrack → Receive from phone.")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val target = PcTransfer.parseManual(address.text.toString(), pin.text.toString())
                if (target == null) toast("Enter an address and PIN") else send(info, target)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun send(info: RecordingInfo, target: PcTarget) {
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val status = TextView(this).apply { text = "Looking for your PC…" }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(status)
            addView(progress)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Sending “${info.title}”")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Close", null)
            .show()

        io.execute {
            try {
                val host = PcTransfer.findReachableHost(target)
                    ?: throw IllegalStateException(
                        "Couldn't reach Blender at ${target.hosts.joinToString()}:${target.port}.\n\n" +
                            "• Phone and PC must be on the same Wi-Fi\n" +
                            "• Allow Blender through the Windows firewall (private networks)\n" +
                            "• Check the receiver is still running in Blender"
                    )
                var lastUi = 0L
                PcTransfer.send(info, target, host) { file, sent, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 100 || sent == total) {
                        lastUi = now
                        main.post {
                            status.text = String.format(
                                Locale.US, "%s  %.1f / %.1f MB", file, sent / 1e6, total / 1e6
                            )
                            progress.progress = if (total > 0) (sent * 1000 / total).toInt() else 0
                        }
                    }
                }
                main.post {
                    status.text = "Done ✓  It's importing into Blender now."
                    progress.progress = 1000
                }
            } catch (e: Exception) {
                main.post { status.text = "Failed: ${e.message}" }
            }
        }
    }

    // ---------------------------------------------------------------- files

    private fun exportZip(info: RecordingInfo) {
        toast("Saving zip…")
        io.execute {
            val msg = try {
                "Saved to ${RecordingStore.exportToDownloads(this, info)}"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
            main.post {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Export")
                    .setMessage("$msg\n\nCopy it to your PC (USB, cloud, etc.), then in Blender use File → Import → PocketTrack.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun shareZip(info: RecordingInfo) {
        toast("Preparing zip…")
        io.execute {
            try {
                val uri = RecordingStore.zipForSharing(this, info)
                main.post {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, info.title))
                }
            } catch (e: Exception) {
                toast("Share failed: ${e.message}")
            }
        }
    }

    private fun rename(info: RecordingInfo) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(info.title)
            selectAll()
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename scan")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val wanted = input.text.toString()
                io.execute {
                    try {
                        RecordingStore.setTitle(this, info.dir, wanted)
                    } catch (e: Exception) {
                        toast("Rename failed: ${e.message}")
                    }
                    main.post { refresh() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun confirmDelete(info: RecordingInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete “${info.title}”?")
            .setMessage("The video and track are removed from the phone. Copies already sent to your PC stay there.")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    RecordingStore.delete(info)
                    thumbs.remove(info.id)
                    main.post { refresh() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ---------------------------------------------------------------- grid

    private class Adapter(
        val onOpen: (RecordingInfo) -> Unit,
        val onMenu: (RecordingInfo, View) -> Unit,
        val loadThumb: (RecordingInfo, ImageView) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        var items: List<RecordingInfo> = emptyList()
            @Suppress("NotifyDataSetChanged")
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val duration: TextView = v.findViewById(R.id.duration)
            val title: TextView = v.findViewById(R.id.title)
            val meta: TextView = v.findViewById(R.id.meta)
            val menu: ImageButton = v.findViewById(R.id.menu)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = items[position]
            holder.title.text = r.title
            val s = r.usedDurationSec.toInt()
            val vertical = r.height > r.width
            holder.duration.text = String.format(Locale.US, "%s%d:%02d", if (r.trimmed) "✂ " else "", s / 60, s % 60)
            holder.meta.text = String.format(
                Locale.US, "%s · %dp · %d fps", if (vertical) "Vertical" else "Landscape",
                minOf(r.width, r.height), r.fps
            )
            loadThumb(r, holder.thumb)
            holder.itemView.setOnClickListener { onOpen(r) }
            holder.menu.setOnClickListener { onMenu(r, it) }
        }
    }
}
