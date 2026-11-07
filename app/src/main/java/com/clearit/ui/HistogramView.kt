package com.clearit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.clearit.engine.Histogram
import kotlin.math.max
import kotlin.math.sqrt

/** RGB + luma histogram of the edited preview (square-root scaled so small bins stay visible). */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var histogram: Histogram? = null
        set(value) { field = value; invalidate() }

    private val background = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val channelPaints = listOf(
        Color.argb(150, 255, 70, 70),
        Color.argb(150, 70, 255, 70),
        Color.argb(150, 80, 140, 255),
    ).map { c -> Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL } }
    private val lumaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = histogram ?: return
        val w = width.toFloat()
        val ht = height.toFloat()
        canvas.drawRect(0f, 0f, w, ht, background)
        val channels = listOf(h.red, h.green, h.blue)
        var peak = 1.0
        for (c in channels + listOf(h.luma)) for (i in 1 until 255) peak = max(peak, sqrt(c[i].toDouble()))
        channels.forEachIndexed { index, data -> drawCurve(canvas, data, peak, w, ht, channelPaints[index], fill = true) }
        drawCurve(canvas, h.luma, peak, w, ht, lumaPaint, fill = false)
    }

    private fun drawCurve(canvas: Canvas, data: IntArray, peak: Double, w: Float, h: Float, paint: Paint, fill: Boolean) {
        path.reset()
        if (fill) path.moveTo(0f, h)
        for (i in 0 until 256) {
            val x = i / 255f * w
            val y = h - (sqrt(data[i].toDouble()) / peak).coerceAtMost(1.0).toFloat() * h
            if (!fill && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            path.lineTo(w, h)
            path.close()
        }
        canvas.drawPath(path, paint)
    }
}
