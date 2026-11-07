package com.clearit.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.clearit.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image viewer with before/after comparison:
 * - **Split mode**: a draggable divider shows "before" on the left and "after" on the right.
 * - **Press and hold** anywhere (when not in split mode) to temporarily show the original.
 * - **Pinch** to zoom (up to 8×), **drag** to pan, **double-tap** to toggle 1× / 3×.
 *
 * Both bitmaps are drawn into the same destination rectangle, so previews of different
 * resolutions (draft vs full preview) line up exactly.
 */
class CompareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var before: Bitmap? = null
        set(value) { field = value; invalidate() }

    var after: Bitmap? = null
        set(value) {
            val aspectChanged = field == null || value == null ||
                field!!.width * value.height != field!!.height * value.width
            field = value
            if (aspectChanged) resetZoom()
            invalidate()
        }

    var splitMode: Boolean = false
        set(value) { field = value; invalidate() }

    /** Listener for "showing original" state changes (to update a label in the UI). */
    var onPeekChanged: ((Boolean) -> Unit)? = null

    private var peeking = false
    private var splitFraction = 0.5f
    private var draggingDivider = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val dst = RectF()
    private val labelBefore = context.getString(R.string.label_before)
    private val labelAfter = context.getString(R.string.label_after)

    init {
        contentDescription = context.getString(R.string.cd_preview)
    }

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun computeDestination(): Boolean {
        val bmp = after ?: before ?: return false
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return false
        val fit = min(vw / bmp.width, vh / bmp.height)
        val w = bmp.width * fit * zoom
        val h = bmp.height * fit * zoom
        clampPan(w, h)
        val left = (vw - w) / 2f + panX
        val top = (vh - h) / 2f + panY
        dst.set(left, top, left + w, top + h)
        return true
    }

    private fun clampPan(w: Float, h: Float) {
        val maxX = max(0f, (w - width) / 2f)
        val maxY = max(0f, (h - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!computeDestination()) return
        val afterBmp = after
        val beforeBmp = before
        val showBefore = peeking && beforeBmp != null
        when {
            showBefore -> {
                canvas.drawBitmap(beforeBmp!!, null, dst, bitmapPaint)
                drawLabel(canvas, labelBefore, left = true)
            }
            splitMode && beforeBmp != null && afterBmp != null -> {
                canvas.drawBitmap(afterBmp, null, dst, bitmapPaint)
                val dividerX = width * splitFraction
                canvas.save()
                canvas.clipRect(0f, 0f, dividerX, height.toFloat())
                canvas.drawBitmap(beforeBmp, null, dst, bitmapPaint)
                canvas.restore()
                canvas.drawLine(dividerX, 0f, dividerX, height.toFloat(), dividerPaint)
                canvas.drawCircle(dividerX, height / 2f, 10f * density, handlePaint)
                drawLabel(canvas, labelBefore, left = true)
                drawLabel(canvas, labelAfter, left = false)
            }
            afterBmp != null -> canvas.drawBitmap(afterBmp, null, dst, bitmapPaint)
            beforeBmp != null -> canvas.drawBitmap(beforeBmp, null, dst, bitmapPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, left: Boolean) {
        val margin = 12f * density
        val x = if (left) margin else width - margin - labelPaint.measureText(text)
        canvas.drawText(text, x, height - margin, labelPaint)
    }

    // ------------------------------------------------------------------ gestures

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
            val factor = newZoom / zoom
            // Keep the focal point stationary.
            val cx = width / 2f + panX
            val cy = height / 2f + panY
            panX += (detector.focusX - cx) * (1 - factor)
            panY += (detector.focusY - cy) * (1 - factor)
            zoom = newZoom
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (draggingDivider || scaleDetector.isInProgress) return false
            panX -= distanceX
            panY -= distanceY
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.01f) {
                resetZoom()
            } else {
                val target = 3f
                val cx = width / 2f
                val cy = height / 2f
                panX = (cx - e.x) * (target - 1)
                panY = (cy - e.y) * (target - 1)
                zoom = target
                invalidate()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!splitMode && before != null) setPeeking(true)
        }
    })

    private fun setPeeking(value: Boolean) {
        if (peeking == value) return
        peeking = value
        onPeekChanged?.invoke(value)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (after == null && before == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                draggingDivider = splitMode && abs(event.x - width * splitFraction) < 32f * density
            }
            MotionEvent.ACTION_MOVE -> if (draggingDivider && event.pointerCount == 1) {
                splitFraction = (event.x / width).coerceIn(0f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> draggingDivider = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingDivider = false
                setPeeking(false)
            }
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private companion object {
        const val MAX_ZOOM = 8f
    }
}
