package com.thorhud.volume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** The bar itself: translucent track, white fill rising from the bottom (or growing from the left). */
class PillView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 40, 40, 44)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = Color.argb(60, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 255, 255)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        setShadowLayer(8f * dp, 0f, 2f * dp, Color.argb(70, 0, 0, 0))
    }

    private val rect = RectF()
    private val clip = Path()

    var color: Int
        get() = fillPaint.color
        set(v) { fillPaint.color = v; invalidate() }

    /** The thin light edge around the bar. */
    var rim: Boolean = true
        set(v) { field = v; invalidate() }

    var vertical: Boolean = true
        set(v) { field = v; invalidate() }

    /** 0..1 */
    var level: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for setShadowLayer
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2f
        rect.set(0f, 0f, w, h)

        canvas.drawRoundRect(rect, r, r, shadowPaint)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        if (vertical) canvas.drawRect(0f, h * (1f - level), w, h, fillPaint)
        else canvas.drawRect(0f, 0f, w * level, h, fillPaint)
        canvas.restore()

        if (rim) {
            rect.inset(rimPaint.strokeWidth / 2f, rimPaint.strokeWidth / 2f)
            canvas.drawRoundRect(rect, r, r, rimPaint)
        }
    }
}
