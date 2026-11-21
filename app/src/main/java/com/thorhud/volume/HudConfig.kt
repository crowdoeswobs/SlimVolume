package com.thorhud.volume

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Where the bar lives. cx/cy are the bar's centre as a fraction of the screen,
 * so it survives resolution/rotation changes. 0 or 1 = hugging that edge.
 */
data class HudConfig(
    val vertical: Boolean,
    val cx: Float,
    val cy: Float,
    val lengthDp: Float,
    val thicknessDp: Float,
    val displayId: Int = 0,
    val color: Int = HudPrefs.WHITE,
    val rim: Boolean = true,
    val hideMs: Int = 1500,
    val customOn: Boolean = false,
    val customColor: Int = 0xFF7AF2C8.toInt(),
    /** First press shows a wide bar that slims down on the next press, like iOS. */
    val iosMode: Boolean = false,
    /** Time between steps while a volume button is held. Lower = faster. */
    val holdIntervalMs: Int = 90,
) {
    /** The colour actually drawn. */
    val fill: Int get() = if (customOn) customColor else color
}

object HudPrefs {
    private const val NAME = "hud"
    const val WHITE = 0xF0FFFFFF.toInt()

    /** Fill colours offered in the customizer. */
    val SWATCHES = intArrayOf(
        WHITE,
        0xFF0A84FF.toInt(), // blue
        0xFF30D158.toInt(), // green
        0xFFFFD60A.toInt(), // yellow
        0xFFFF9F0A.toInt(), // orange
        0xFFFF375F.toInt(), // pink
        0xFFBF5AF2.toInt(), // purple
        0xFF64D2FF.toInt(), // cyan
    )
    // Right edge, vertically centred, top screen: where the Thor's stock volume panel sits.
    val DEFAULT = HudConfig(vertical = true, cx = 1f, cy = 0.5f, lengthDp = 160f, thicknessDp = 14f)

    const val LENGTH_MIN = 60f
    const val LENGTH_MAX = 500f
    const val THICK_MIN = 4f
    const val THICK_MAX = 48f
    /** Width of the bar on the first press in iOS mode. */
    const val IOS_WIDE_DP = 38f
    const val HOLD_FASTEST_MS = 30
    const val HOLD_SLOWEST_MS = 250

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): HudConfig = prefs(ctx).run {
        HudConfig(
            vertical = getBoolean("vertical", DEFAULT.vertical),
            cx = getFloat("cx", DEFAULT.cx),
            cy = getFloat("cy", DEFAULT.cy),
            lengthDp = getFloat("length", DEFAULT.lengthDp),
            thicknessDp = getFloat("thickness", DEFAULT.thicknessDp),
            displayId = getInt("display", DEFAULT.displayId),
            color = getInt("color", DEFAULT.color),
            rim = getBoolean("rim", DEFAULT.rim),
            hideMs = getInt("hide_ms", DEFAULT.hideMs),
            customOn = getBoolean("custom_on", DEFAULT.customOn),
            customColor = getInt("custom_color", DEFAULT.customColor),
            iosMode = getBoolean("ios_mode", DEFAULT.iosMode),
            holdIntervalMs = getInt("hold_interval", DEFAULT.holdIntervalMs),
        )
    }

    /** Master switch: when off the service lets the volume keys through to the stock panel. */
    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()

    fun haptics(ctx: Context) = prefs(ctx).getBoolean("haptics", true)
    fun setHaptics(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("haptics", on).apply()

    fun startOnBoot(ctx: Context) = prefs(ctx).getBoolean("start_on_boot", true)
    fun setStartOnBoot(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("start_on_boot", on).apply()

    fun save(ctx: Context, c: HudConfig) {
        prefs(ctx).edit()
            .putBoolean("vertical", c.vertical)
            .putFloat("cx", c.cx)
            .putFloat("cy", c.cy)
            .putFloat("length", c.lengthDp)
            .putFloat("thickness", c.thicknessDp)
            .putInt("display", c.displayId)
            .putInt("color", c.color)
            .putBoolean("rim", c.rim)
            .putInt("hide_ms", c.hideMs)
            .putBoolean("custom_on", c.customOn)
            .putInt("custom_color", c.customColor)
            .putBoolean("ios_mode", c.iosMode)
            .putInt("hold_interval", c.holdIntervalMs)
            .apply()
    }
}

/**
 * Screen-space geometry for a config.
 * [pill] is where the bar sits on screen; [window] is the overlay window around it
 * (padding for the bump + a bigger touch target, extended to the nearest edge
 * when the bar is close enough to slide in from it).
 * [hiddenTx]/[hiddenTy] is the translation the bar slides in from / out to.
 */
class HudLayout(val pill: Rect, val window: Rect, val hiddenTx: Float, val hiddenTy: Float, val nearEdge: Boolean)

fun computeLayout(c: HudConfig, screenW: Int, screenH: Int, density: Float): HudLayout {
    val len = (c.lengthDp * density).roundToInt()
    val thick = (c.thicknessDp * density).roundToInt()
    val pw = if (c.vertical) thick else len
    val ph = if (c.vertical) len else thick
    val edge = (16 * density).roundToInt()
    val pad = (24 * density).roundToInt()
    val shadow = 10 * density

    val left = (c.cx * screenW - pw / 2f).roundToInt().coerceIn(edge, maxOf(edge, screenW - edge - pw))
    val top = (c.cy * screenH - ph / 2f).roundToInt().coerceIn(edge, maxOf(edge, screenH - edge - ph))
    val pill = Rect(left, top, left + pw, top + ph)
    val win = Rect(pill).apply { inset(-pad, -pad) }

    var tx = 0f
    var ty = 0f
    var near = false
    val nearDist = (if (c.vertical) screenW else screenH) * 0.25f
    val farSlide = 40 * density

    if (c.vertical) {
        val toLeft = pill.exactCenterX() < screenW / 2f
        val dist = if (toLeft) pill.left else screenW - pill.right
        if (dist <= nearDist) {
            near = true
            // Close to the edge: window reaches the edge so the bar can emerge from it.
            if (toLeft) { win.left = 0; tx = -(pill.right + shadow) }
            else { win.right = screenW; tx = (screenW - pill.left + shadow) }
        } else {
            if (toLeft) { win.left -= farSlide.roundToInt(); tx = -farSlide }
            else { win.right += farSlide.roundToInt(); tx = farSlide }
        }
    } else {
        val toTop = pill.exactCenterY() < screenH / 2f
        val dist = if (toTop) pill.top else screenH - pill.bottom
        if (dist <= nearDist) {
            near = true
            if (toTop) { win.top = 0; ty = -(pill.bottom + shadow) }
            else { win.bottom = screenH; ty = (screenH - pill.top + shadow) }
        } else {
            if (toTop) { win.top -= farSlide.roundToInt(); ty = -farSlide }
            else { win.bottom += farSlide.roundToInt(); ty = farSlide }
        }
    }
    return HudLayout(pill, win, tx, ty, near)
}
