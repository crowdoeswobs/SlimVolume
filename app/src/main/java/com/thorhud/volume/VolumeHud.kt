package com.thorhud.volume

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewPropertyAnimator
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout

/** Owns the overlay window: slide in, bump on each step, slide out when idle. */
class VolumeHud(private val service: VolumeHudService, private val audio: AudioManager) {

    private companion object {
        const val NUDGE_DP = 5f       // how far a press pushes the bar toward the screen centre
        const val TOUCH_GROW_DP = 3f  // iOS mode: extra width while a finger is on the bar
        const val NUDGE_OUT_MS = 70L  // push-out time; a tap always gets the full push
    }

    private val dp = service.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    private var cfg = HudPrefs.load(service)
    private var lay: HudLayout? = null

    // Rebuilt whenever the target screen changes.
    private var wm: WindowManager? = null
    private var pill: PillView? = null
    private var root: FrameLayout? = null
    private var holder: FrameLayout? = null
    private var boundDisplayId = -1

    private var attached = false
    private var visible = false
    private var dragging = false
    private var levelAnim: ValueAnimator? = null

    // iOS mode: the bar's rect (window coords) when wide vs slim, and which one we're in.
    private var wideRect = Rect()
    private var slimRect = Rect()
    private val curRect = Rect()
    private var wide = false
    private var widthAnim: ValueAnimator? = null
    private var touchGrew = false

    /** Stays on screen (no auto-hide) while the Customize page is open. */
    private var pinned = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cfg = HudPrefs.load(service)
        if (!attached) return@OnSharedPreferenceChangeListener
        val display = targetDisplay()
        if (display.displayId != boundDisplayId) {
            // Moved to the other screen: rebuild there.
            val level = pill?.level ?: 0f
            detachNow()
            if (pinned && show()) pill?.level = level
        } else {
            // Same screen: restyle/resize in place, no slide animation.
            applyLayout(display)
            try { wm?.updateViewLayout(root, windowParams(lay!!)) } catch (_: Exception) {}
        }
    }

    fun setPinned(on: Boolean, level: Float, immediate: Boolean = false) {
        pinned = on
        if (on) {
            handler.removeCallbacks(hideRunnable)
            if (wide) { wide = false; if (attached) animatePillRect(slimRect) }
            if (show()) animateLevel(level)
        } else if (immediate) {
            handler.removeCallbacks(hideRunnable)
            hide()
        } else {
            scheduleHide()
        }
    }

    init {
        HudPrefs.prefs(service).registerOnSharedPreferenceChangeListener(prefListener)
    }

    /**
     * @param smoothMs when > 0 (button held), glide the fill linearly over this long so
     * consecutive steps blend into one continuous slide.
     */
    fun onStep(volume: Int, max: Int, hitLimit: Boolean, up: Boolean, smoothMs: Long = 0) {
        val target = if (max > 0) volume.toFloat() / max else 0f
        val wasVisible = visible
        if (cfg.iosMode && !wasVisible && !pinned) wide = true
        if (!show()) return
        if (!wasVisible && wide) setPillRect(wideRect)
        else if (cfg.iosMode && wide) { wide = false; animatePillRect(slimRect) }
        animateLevel(target, smoothMs)
        if (hitLimit) stretch(up)
        scheduleHide()
    }

    // ---------- held button: continuous glide ----------

    /** A hold was detected: stay up, and in iOS mode drop to the slim width like a second press. */
    fun holdStart() {
        handler.removeCallbacks(hideRunnable)
        levelAnim?.cancel()
        if (cfg.iosMode && wide) { wide = false; animatePillRect(slimRect) }
    }

    /** Fill position driven frame-by-frame by the hold glide. */
    fun holdLevel(f: Float) {
        levelAnim?.cancel()
        pill?.level = f
    }

    /** Hold finished: settle onto [target], stretch if it ran into 0% / 100%. */
    fun holdEnd(target: Float, hitLimit: Boolean, up: Boolean) {
        animateLevel(target)
        if (hitLimit) stretch(up)
        scheduleHide()
    }

    // ---------- press nudge (iOS-style) ----------

    private var pressedAt = 0L
    private val springBack = Runnable {
        holder?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(300)
            ?.setInterpolator(OvershootInterpolator(3f))?.start()
    }

    /** Button went down: snap the bar toward the middle of the screen and hold it there. */
    fun press() {
        val h = holder ?: return
        val l = lay ?: return
        handler.removeCallbacks(springBack)
        val d = NUDGE_DP * dp
        // Opposite of the slide-in direction = towards the screen centre.
        val tx = if (cfg.vertical) -Math.signum(l.hiddenTx) * d else 0f
        val ty = if (cfg.vertical) 0f else -Math.signum(l.hiddenTy) * d
        pressedAt = android.os.SystemClock.uptimeMillis()
        h.animate().cancel()
        h.animate().translationX(tx).translationY(ty).setDuration(NUDGE_OUT_MS)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** Button released: bounce back (after the push-out has fully played, so taps are visible). */
    fun release() {
        handler.removeCallbacks(springBack)
        val left = NUDGE_OUT_MS - (android.os.SystemClock.uptimeMillis() - pressedAt)
        if (left > 0) handler.postDelayed(springBack, left) else springBack.run()
    }

    // ---------- window plumbing ----------

    private fun targetDisplay(): Display {
        val dm = service.getSystemService(DisplayManager::class.java)
        return dm.getDisplay(cfg.displayId) ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun ensureViews(display: Display) {
        if (root != null && boundDisplayId == display.displayId) return
        // AccessibilityService hands its overlay token to display contexts it creates,
        // which is what lets us put TYPE_ACCESSIBILITY_OVERLAY on the bottom screen.
        val ctx: Context = service.overlayContext(display)
        wm = ctx.getSystemService(WindowManager::class.java)
        val p = PillView(ctx)
        pill = p
        val h = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            addView(p)
        }
        holder = h
        root = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            addView(h, FrameLayout.LayoutParams(-1, -1))
        }
        boundDisplayId = display.displayId
        setupTouch()
    }

    private fun applyLayout(display: Display) {
        val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
        // In iOS mode the window is laid out for the wide bar; the slim one sits inside it.
        val layCfg = if (cfg.iosMode) cfg.copy(thicknessDp = maxOf(cfg.thicknessDp, HudPrefs.IOS_WIDE_DP)) else cfg
        val l = computeLayout(layCfg, size.x, size.y, dp)
        lay = l
        val p = pill!!
        p.vertical = cfg.vertical
        p.color = cfg.fill
        p.rim = cfg.rim
        wideRect = Rect(l.pill).apply { offset(-l.window.left, -l.window.top) }
        slimRect = if (cfg.iosMode) slimInside(wideRect, l) else Rect(wideRect)
        widthAnim?.cancel()
        setPillRect(if (cfg.iosMode && wide) wideRect else slimRect)
        // Keep the system back-swipe from stealing touches meant for the bar.
        root!!.systemGestureExclusionRects = listOf(Rect(0, 0, l.window.width(), l.window.height()))
    }

    private fun windowParams(l: HudLayout) = WindowManager.LayoutParams(
        l.window.width(),
        l.window.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = l.window.left
        y = l.window.top
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** Slim bar inside the wide one, hugging the screen edge it slides in from. */
    private fun slimInside(w: Rect, l: HudLayout): Rect {
        val t = (cfg.thicknessDp * dp).toInt()
        return if (cfg.vertical) {
            val left = when {
                l.nearEdge && l.hiddenTx < 0 -> w.left
                l.nearEdge && l.hiddenTx > 0 -> w.right - t
                else -> w.centerX() - t / 2
            }
            Rect(left, w.top, left + t, w.bottom)
        } else {
            val top = when {
                l.nearEdge && l.hiddenTy < 0 -> w.top
                l.nearEdge && l.hiddenTy > 0 -> w.bottom - t
                else -> w.centerY() - t / 2
            }
            Rect(w.left, top, w.right, top + t)
        }
    }

    private fun setPillRect(r: Rect) {
        curRect.set(r)
        val p = pill ?: return
        p.layoutParams = FrameLayout.LayoutParams(r.width(), r.height()).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = r.left
            topMargin = r.top
        }
    }

    private fun animatePillRect(to: Rect) {
        widthAnim?.cancel()
        val from = Rect(curRect)
        val target = Rect(to)
        widthAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                setPillRect(Rect(
                    lerp(from.left, target.left, f), lerp(from.top, target.top, f),
                    lerp(from.right, target.right, f), lerp(from.bottom, target.bottom, f)))
            }
            start()
        }
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    /** @return false if the window couldn't be added. */
    private fun show(): Boolean {
        if (!attached) {
            val display = targetDisplay()
            ensureViews(display)
            applyLayout(display)
            try {
                wm!!.addView(root, windowParams(lay!!))
            } catch (e: Exception) {
                android.util.Log.e("SlimVolume", "addView failed on display ${display.displayId}", e)
                return false
            }
            attached = true
            visible = false
        }
        if (visible) return true
        visible = true
        val p = pill!!
        val l = lay!!
        p.animate().cancel()
        p.scaleX = 1f; p.scaleY = 1f
        p.resetPivot()
        p.translationX = l.hiddenTx
        p.translationY = l.hiddenTy
        p.alpha = 0f
        p.animate()
            .translationX(0f).translationY(0f)
            .alpha(1f)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
        return true
    }

    private fun hide() {
        if (!visible || dragging || pinned) return
        visible = false
        val p = pill ?: return
        val l = lay ?: return
        p.animate()
            .translationX(l.hiddenTx).translationY(l.hiddenTy)
            .alpha(0f)
            .setDuration(280)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction { if (!visible) detachNow() }
            .start()
    }

    private fun detachNow() {
        widthAnim?.cancel()
        holder?.animate()?.cancel()
        holder?.translationX = 0f
        holder?.translationY = 0f
        if (attached) {
            try { wm?.removeViewImmediate(root) } catch (_: Exception) {}
            attached = false
        }
        visible = false
        dragging = false
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, cfg.hideMs.toLong())
    }

    // ---------- animation ----------

    private fun animateLevel(target: Float, smoothMs: Long = 0) {
        val p = pill ?: return
        levelAnim?.cancel()
        levelAnim = ValueAnimator.ofFloat(p.level, target).apply {
            duration = if (smoothMs > 0) smoothMs else 160
            interpolator = if (smoothMs > 0) LinearInterpolator() else DecelerateInterpolator()
            addUpdateListener { p.level = it.animatedValue as Float }
            start()
        }
    }

    /** Scale along the bar's thickness / length, whichever way it's oriented. */
    private fun ViewPropertyAnimator.scaleTL(thick: Float, len: Float): ViewPropertyAnimator =
        if (cfg.vertical) scaleX(thick).scaleY(len) else scaleX(len).scaleY(thick)

    private fun centerPivot(p: PillView) = p.resetPivot()

    /** Small "pulse" every time the volume changes. */
    private fun bump() {
        val p = pill ?: return
        if (dragging) return
        centerPivot(p)
        p.scaleX = 1f; p.scaleY = 1f
        p.animate().scaleTL(1.3f, 1.035f).setDuration(70)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                p.animate().scaleTL(1f, 1f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
    }

    /** Rubber-band stretch when you push past 0% or 100%. */
    private fun stretch(up: Boolean) {
        val p = pill ?: return
        if (dragging) return
        val w = p.layoutParams.width.toFloat()
        val h = p.layoutParams.height.toFloat()
        if (cfg.vertical) { p.pivotX = w / 2f; p.pivotY = if (up) h else 0f }
        else { p.pivotY = h / 2f; p.pivotX = if (up) 0f else w }
        p.scaleX = 1f; p.scaleY = 1f
        p.animate().scaleTL(0.85f, 1.08f).setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                p.animate().scaleTL(1f, 1f).setDuration(320)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .withEndAction { p.resetPivot() }.start()
            }.start()
    }

    // ---------- touch ----------

    /** Drag on (or near) the bar to set volume directly, like iOS. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        root!!.setOnTouchListener { _, e ->
            val l = lay ?: return@setOnTouchListener false
            val p = pill ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!visible) return@setOnTouchListener false
                    dragging = true
                    handler.removeCallbacks(hideRunnable)
                    centerPivot(p)
                    if (cfg.iosMode) {
                        // iOS mode: whichever width it's at (slim or wide), grow just TOUCH_GROW_DP while held.
                        touchGrew = false
                        val r = if (wide) wideRect else slimRect
                        val t = (if (cfg.vertical) r.width() else r.height()).toFloat()
                        val grow = if (t > 0) (t + TOUCH_GROW_DP * dp) / t else 1f
                        p.animate().scaleTL(grow, 1.03f).setDuration(150)
                            .setInterpolator(DecelerateInterpolator()).start()
                    } else {
                        p.animate().scaleTL(1.6f, 1.03f).setDuration(150)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }
                    setFromTouch(e.x, e.y, l)
                }
                MotionEvent.ACTION_MOVE -> if (dragging) setFromTouch(e.x, e.y, l)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        if (cfg.iosMode && touchGrew) { wide = false; animatePillRect(slimRect) }
                        touchGrew = false
                        p.animate().scaleTL(1f, 1f).setDuration(260)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                        scheduleHide()
                    }
                }
            }
            true
        }
    }

    private fun setFromTouch(x: Float, y: Float, l: HudLayout) {
        // Touch coords are window-relative; convert to the bar's own axis.
        val left = (l.pill.left - l.window.left).toFloat()
        val top = (l.pill.top - l.window.top).toFloat()
        val frac = if (cfg.vertical) 1f - (y - top) / l.pill.height()
        else (x - left) / l.pill.width()
        val f = frac.coerceIn(0f, 1f)
        val stream = service.activeStream()
        val max = audio.getStreamMaxVolume(stream)
        val vol = Math.round(f * max)
        if (vol != audio.getStreamVolume(stream)) {
            audio.setStreamVolume(stream, vol, 0)
            service.haptics.tick(service, vol, max)
        }
        levelAnim?.cancel()
        pill?.level = f
    }

    fun destroy() {
        HudPrefs.prefs(service).unregisterOnSharedPreferenceChangeListener(prefListener)
        handler.removeCallbacksAndMessages(null)
        levelAnim?.cancel()
        detachNow()
    }
}
