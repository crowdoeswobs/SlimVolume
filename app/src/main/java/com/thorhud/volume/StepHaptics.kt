package com.thorhud.volume

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A barely-there tick per volume step, a little stronger as the volume rises.
 * Silent at 0. The Thor's motor has amplitude control but no "tick" primitives,
 * so this is a very short, very weak one-shot pulse.
 */
class StepHaptics(context: Context) {

    private companion object {
        const val PULSE_MS = 10L
        const val MIN_AMP = 1      // of 255, at the lowest audible step (the weakest possible)
        const val MAX_AMP = 25     // of 255, at full volume (~10% strength)
        const val EDGE_AMP = 20    // of 255, touch drag hitting top/bottom (~8%)
    }

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    fun tick(context: Context, volume: Int, max: Int) {
        if (volume <= 0 || max <= 0) return
        if (!HudPrefs.haptics(context)) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val frac = volume.toFloat() / max
        val amp = if (v.hasAmplitudeControl())
            (MIN_AMP + frac.pow(1.3f) * (MAX_AMP - MIN_AMP)).roundToInt().coerceIn(1, 255)
        else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(PULSE_MS, amp))
    }

    /** Touch drag reached the top or bottom of the bar: one small, fixed tick. */
    fun edgeTick(context: Context) {
        if (!HudPrefs.haptics(context)) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amp = if (v.hasAmplitudeControl()) EDGE_AMP else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(PULSE_MS, amp))
    }
}
