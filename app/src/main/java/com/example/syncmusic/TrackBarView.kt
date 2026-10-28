package com.example.syncmusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/**
 * One phone's track in the mixer: the centre line is the host, the dot is where this phone is
 * (right = ahead, left = behind), on a ±[RANGE_MS] scale. No dot while there's no reading.
 */
class TrackBarView(context: Context) : View(context) {
    companion object {
        const val RANGE_MS = 300L
        const val GOOD_MS = 60L
        const val FAIR_MS = 150L
    }

    /** Gap to the host in ms (null = unknown, e.g. not playing). */
    var errorMs: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt(); strokeWidth = 4 * density }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF777777.toInt(); strokeWidth = 1 * density }
    private val hostLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val pad = 8 * density
        val left = pad
        val right = width - pad
        val mid = height / 2f
        canvas.drawLine(left, mid, right, mid, track)
        // Ticks at -300, -150, +150, +300 ms; the host (0) as a bright line.
        for (ms in listOf(-RANGE_MS, -RANGE_MS / 2, RANGE_MS / 2, RANGE_MS)) {
            val x = xFor(ms, left, right)
            canvas.drawLine(x, mid - 5 * density, x, mid + 5 * density, tick)
        }
        val centre = xFor(0, left, right)
        canvas.drawLine(centre, mid - 10 * density, centre, mid + 10 * density, hostLine)
        val error = errorMs ?: return
        dot.color = when {
            abs(error) <= GOOD_MS -> 0xFF43A047.toInt()
            abs(error) <= FAIR_MS -> 0xFFFDD835.toInt()
            else -> 0xFFE53935.toInt()
        }
        canvas.drawCircle(xFor(error, left, right), mid, 7 * density, dot)
    }

    private fun xFor(ms: Long, left: Float, right: Float): Float {
        val clamped = ms.coerceIn(-RANGE_MS, RANGE_MS)
        return left + (right - left) * (clamped + RANGE_MS) / (2f * RANGE_MS)
    }
}
