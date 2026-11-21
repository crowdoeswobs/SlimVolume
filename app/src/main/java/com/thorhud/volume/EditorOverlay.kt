package com.thorhud.volume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Emulator-style position editor, drawn as an overlay on top of whatever is on screen.
 * Only moves the bar (and between screens); style lives on the Customize bar page.
 * Nothing is saved until Done.
 */
class EditorOverlay(private val service: VolumeHudService, private val onClosed: () -> Unit) {

    private val dp = service.resources.displayMetrics.density
    private var cfg = HudPrefs.load(service)

    private var wm: WindowManager? = null
    private var root: EditRoot? = null
    private lateinit var ctx: Context
    private lateinit var pill: PillView
    private lateinit var guides: GuideView
    private lateinit var hint: TextView
    private lateinit var scaler: ScaleGestureDetector
    /** True once the size was pinched, so Done saves it and style edits don't overwrite it. */
    private var sized = false
    private var pinching = false
    private val hintText = "Drag to move · pinch to resize"
    private var screenW = 0
    private var screenH = 0

    /** Style edits made on the Customize page (e.g. on the other screen) show up live here. */
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (root == null) return@OnSharedPreferenceChangeListener
        val latest = HudPrefs.load(service)
        // Take the new style, keep the position being edited.
        cfg = latest.copy(cx = cfg.cx, cy = cfg.cy, displayId = cfg.displayId).let {
            if (sized) it.copy(lengthDp = cfg.lengthDp, thicknessDp = cfg.thicknessDp) else it
        }
        applyStyle()
        placePill()
    }

    fun open() {
        HudPrefs.prefs(service).registerOnSharedPreferenceChangeListener(prefListener)
        attach(cfg.displayId)
    }

    fun close() {
        HudPrefs.prefs(service).unregisterOnSharedPreferenceChangeListener(prefListener)
        detach()
        onClosed()
    }

    private fun applyStyle() {
        pill.vertical = cfg.vertical
        pill.color = cfg.fill
        pill.rim = cfg.rim
    }

    // ---------- window ----------

    private fun attach(displayId: Int) {
        detach()
        val dm = service.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
        cfg = cfg.copy(displayId = display.displayId)

        val base = service.overlayContext(display)
        ctx = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault)
        wm = base.getSystemService(WindowManager::class.java)
        val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
        screenW = size.x; screenH = size.y

        guides = GuideView(ctx)
        pill = PillView(ctx).apply { level = 0.6f }
        applyStyle()
        hint = TextView(ctx).apply {
            text = hintText
            textSize = 12f
            setTextColor(Color.argb(170, 255, 255, 255))
        }
        val r = EditRoot(ctx).apply {
            // Dark, but the screen underneath still shows through.
            setBackgroundColor(Color.argb(185, 14, 14, 18))
            addView(guides, FrameLayout.LayoutParams(-1, -1))
            addView(buildToolbar(display.displayId), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (6 * dp).toInt()
            })
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (48 * dp).toInt()
            })
            addView(pill)
            hitTest = { x, y -> pillHit(x, y) }
            alpha = 0f
        }
        root = r
        scaler = ScaleGestureDetector(ctx, pinchListener).apply { isQuickScaleEnabled = false }
        setupDrag(r)
        placePill()

        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm!!.addView(r, lp)
        r.animate().alpha(1f).setDuration(180).start()
    }

    private fun detach() {
        root?.let { r -> try { wm?.removeViewImmediate(r) } catch (_: Exception) {} }
        root = null
    }

    // ---------- toolbar ----------

    private fun buildToolbar(displayId: Int): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val p = (4 * dp).toInt()
        setPadding(p, p, p, p)
        background = round(Color.argb(230, 30, 30, 34))
        addView(tiny("Cancel") { close() })
        addView(tiny("Reset") {
            val d = HudPrefs.DEFAULT
            cfg = cfg.copy(cx = d.cx, cy = d.cy)
            placePill(); bump()
        })
        if (otherDisplay(displayId) != null) addView(tiny("Switch screen") { switchScreen() })
        addView(tiny("Done", primary = true) { done() })
    }

    private fun tiny(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 12f
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        val h = (12 * dp).toInt(); val v = (5 * dp).toInt()
        setPadding(h, v, h, v)
        background = round(if (primary) Color.WHITE else Color.argb(45, 255, 255, 255))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            setMargins((2 * dp).toInt(), 0, (2 * dp).toInt(), 0)
        }
    }

    // ---------- the bar ----------

    private fun placePill() {
        val l = computeLayout(cfg, screenW, screenH, dp)
        val lp = pill.layoutParams
        if (lp == null || lp.width != l.pill.width() || lp.height != l.pill.height()) {
            pill.layoutParams = FrameLayout.LayoutParams(l.pill.width(), l.pill.height())
        }
        pill.x = l.pill.left.toFloat()
        pill.y = l.pill.top.toFloat()
        // Don't let edge back-swipes steal the drag.
        root?.systemGestureExclusionRects = listOf(Rect(l.pill).apply { inset(-(32 * dp).toInt(), -(32 * dp).toInt()) })
    }

    private fun pillHit(x: Float, y: Float): Boolean {
        val slop = 28 * dp // generous grab area around a thin bar
        val w = pill.layoutParams?.width ?: 0
        val h = pill.layoutParams?.height ?: 0
        return x >= pill.x - slop && x <= pill.x + w + slop && y >= pill.y - slop && y <= pill.y + h + slop
    }

    /** Pinch: spread along the bar = length, across it = thickness. */
    private val pinchListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinching = true
            pill.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Ignore an axis while the fingers are nearly lined up on it; tiny spans give wild ratios.
            val minSpan = 40 * dp
            fun ratio(cur: Float, prev: Float) = if (cur > minSpan && prev > minSpan) cur / prev else 1f
            val rx = ratio(d.currentSpanX, d.previousSpanX)
            val ry = ratio(d.currentSpanY, d.previousSpanY)
            val rLen = if (cfg.vertical) ry else rx
            val rThick = if (cfg.vertical) rx else ry
            cfg = cfg.copy(
                lengthDp = (cfg.lengthDp * rLen).coerceIn(HudPrefs.LENGTH_MIN, HudPrefs.LENGTH_MAX),
                thicknessDp = (cfg.thicknessDp * rThick).coerceIn(HudPrefs.THICK_MIN, HudPrefs.THICK_MAX),
            )
            sized = true
            hint.text = "Length ${cfg.lengthDp.toInt()}dp  ·  Thickness ${cfg.thicknessDp.toInt()}dp"
            placePill()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            pinching = false
            hint.postDelayed({ if (!pinching) hint.text = hintText }, 1500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(r: EditRoot) {
        var offX = 0f; var offY = 0f
        var dragging = false
        r.setOnTouchListener { _, e ->
            scaler.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = pillHit(e.x, e.y)
                    if (dragging) {
                        offX = e.x - (pill.x + pill.width / 2f)
                        offY = e.y - (pill.y + pill.height / 2f)
                        pill.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Second finger: it's a pinch now, stop dragging.
                    if (dragging) { dragging = false; guides.show(false, false) }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging || pinching || e.pointerCount > 1) return@setOnTouchListener true
                    var cx = e.x - offX
                    var cy = e.y - offY
                    val snap = 18 * dp
                    val snapV = abs(cx - screenW / 2f) < snap
                    val snapH = abs(cy - screenH / 2f) < snap
                    if (snapV) cx = screenW / 2f
                    if (snapH) cy = screenH / 2f
                    guides.show(snapV, snapH)
                    cfg = cfg.copy(cx = (cx / screenW).coerceIn(0f, 1f), cy = (cy / screenH).coerceIn(0f, 1f))
                    placePill()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    guides.show(false, false)
                    // Store the clamped centre so what you see is what you get.
                    val l = computeLayout(cfg, screenW, screenH, dp)
                    cfg = cfg.copy(cx = l.pill.exactCenterX() / screenW, cy = l.pill.exactCenterY() / screenH)
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(OvershootInterpolator(2.5f)).start()
                }
            }
            true
        }
    }

    private fun bump() {
        pill.animate().cancel()
        pill.scaleX = 1f; pill.scaleY = 1f
        pill.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).setInterpolator(DecelerateInterpolator())
            .withEndAction { pill.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start() }
            .start()
    }

    // ---------- actions ----------

    private fun otherDisplay(current: Int): Display? {
        val dm = service.getSystemService(DisplayManager::class.java)
        return dm.displays.firstOrNull { it.displayId != current && it.state != Display.STATE_OFF }
            ?: dm.displays.firstOrNull { it.displayId != current }
    }

    private fun switchScreen() {
        val other = otherDisplay(cfg.displayId) ?: return
        attach(other.displayId) // keeps cx/cy, so the bar lands in the same relative spot
    }

    private fun done() {
        // Save only the position on top of whatever style is current.
        var out = HudPrefs.load(service).copy(cx = cfg.cx, cy = cfg.cy, displayId = cfg.displayId)
        if (sized) out = out.copy(lengthDp = cfg.lengthDp.toInt().toFloat(), thicknessDp = cfg.thicknessDp.toInt().toFloat())
        HudPrefs.save(service, out)
        close()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ service.preview() }, 250)
    }

    private fun round(color: Int) = GradientDrawable().apply {
        cornerRadius = 100 * dp
        setColor(color)
    }
}

/** Takes the touch only when it starts on the bar, so the toolbar keeps working. */
private class EditRoot(ctx: Context) : FrameLayout(ctx) {
    var hitTest: (Float, Float) -> Boolean = { _, _ -> false }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> hitTest(e.x, e.y)
        MotionEvent.ACTION_POINTER_DOWN -> true // pinch anywhere
        else -> false
    }
}

/** Faint centre lines that appear when the bar snaps to the middle. */
private class GuideView(ctx: Context) : View(ctx) {
    private var v = false
    private var h = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }

    fun show(vertical: Boolean, horizontal: Boolean) {
        if (vertical != v || horizontal != h) { v = vertical; h = horizontal; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        if (v) c.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        if (h) c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
    }
}
