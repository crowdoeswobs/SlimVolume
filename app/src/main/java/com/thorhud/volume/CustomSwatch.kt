package com.thorhud.volume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.View

/** Rainbow ring with the custom colour in the middle. */
class CustomSwatch(ctx: Context) : View(ctx) {
    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var chosen = false
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy)
        ring.strokeWidth = 4 * dp
        ring.shader = SweepGradient(cx, cy, intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        fill.color = color
        c.drawCircle(cx, cy, r - ring.strokeWidth - 2 * dp, fill)
        if (chosen) {
            sel.strokeWidth = 2 * dp
            c.drawCircle(cx, cy, r - sel.strokeWidth / 2f, sel)
        }
    }
}
