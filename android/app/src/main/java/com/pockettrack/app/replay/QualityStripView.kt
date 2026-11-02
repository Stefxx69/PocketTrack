package com.pockettrack.app.replay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.pockettrack.app.R
import com.pockettrack.app.record.TrackRecorder

/**
 * A thin bar under the scrub bar: one colour per frame for tracking quality (green good, amber
 * weak, red lost), with the parts outside the trim range dimmed. Set horizontal padding to match
 * the SeekBar so frames line up with the thumb.
 */
class QualityStripView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val good = ContextCompat.getColor(context, R.color.ok_green)
    private val weak = ContextCompat.getColor(context, R.color.warn_amber)
    private val lost = ContextCompat.getColor(context, R.color.rec_red)
    private val paint = Paint()
    private val dim = Paint().apply { color = 0xB3000000.toInt() }
    private val edge = Paint().apply { color = ContextCompat.getColor(context, R.color.accent) }

    private var quality = IntArray(0)
    private var trimStart = -1
    private var trimEnd = -1

    fun setData(quality: IntArray, trimStart: Int, trimEnd: Int) {
        this.quality = quality
        this.trimStart = trimStart
        this.trimEnd = trimEnd
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = quality.size
        if (n == 0) return
        val left = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        // Runs of equal quality as single rectangles.
        var start = 0
        for (i in 1..n) {
            if (i == n || quality[i] != quality[start]) {
                paint.color = when (quality[start]) {
                    TrackRecorder.QUALITY_LOST -> lost
                    TrackRecorder.QUALITY_WEAK -> weak
                    else -> good
                }
                canvas.drawRect(left + w * start / n, top, left + w * i / n, bottom, paint)
                start = i
            }
        }
        if (trimStart >= 0 && trimEnd >= trimStart) {
            val x0 = left + w * trimStart / n
            val x1 = left + w * (trimEnd + 1) / n
            canvas.drawRect(left, top, x0, bottom, dim)
            canvas.drawRect(x1, top, left + w, bottom, dim)
            val e = resources.displayMetrics.density * 2
            canvas.drawRect(x0 - e / 2, top - e, x0 + e / 2, bottom + e, edge)
            canvas.drawRect(x1 - e / 2, top - e, x1 + e / 2, bottom + e, edge)
        }
    }
}
