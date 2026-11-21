package com.thorhud.volume

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Swallows the volume keys before the system sees them, changes the volume
 * itself (without FLAG_SHOW_UI, so the stock panel never appears) and shows
 * our slim HUD instead.
 */
class VolumeHudService : AccessibilityService() {

    private lateinit var audio: AudioManager
    private var hud: VolumeHud? = null
    val haptics by lazy { StepHaptics(this) }
    private var sounds: SoundPlayer? = null

    private val soundPrefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("sound_") == true) preloadSounds()
    }

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        hud = VolumeHud(this, audio)
        preloadSounds()
        HudPrefs.prefs(this).registerOnSharedPreferenceChangeListener(soundPrefListener)
        instance = this
    }

    private fun preloadSounds() {
        val follow = SoundBank.followVolume(this)
        if (sounds?.followVolume != follow) {
            sounds?.release()
            sounds = SoundPlayer(this, follow)
        }
        if (!follow) {
            // Fixed-loudness clicks use the accessibility stream; keep it at full so the slider decides.
            val a = AudioManager.STREAM_ACCESSIBILITY
            runCatching { audio.setStreamVolume(a, audio.getStreamMaxVolume(a), 0) }
        }
        val s = sounds ?: return
        s.prune(SoundBank.all(this).map { it.id }.toSet())
        if (!SoundBank.enabled(this)) return
        s.preload(SoundBank.selected(this, up = true))
        s.preload(SoundBank.selected(this, up = false))
    }

    /** Button click sound (buttons only; touch drags stay silent). */
    private fun clickSound(up: Boolean) {
        if (!SoundBank.enabled(this)) return
        if (activeStream() != AudioManager.STREAM_MUSIC) return // not during calls
        sounds?.play(SoundBank.selected(this, up), SoundBank.volume(this) / 100f)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return false
        }
        if (!HudPrefs.isEnabled(this)) return false // toggled off in the app: stock behaviour
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // The system's own key repeats never reach accessibility services,
                // so holding a button is handled with our own repeat timer.
                if (event.repeatCount == 0) {
                    stopRepeat()
                    heldDirection = direction
                    val moved = step(direction)
                    clickSound(direction == AudioManager.ADJUST_RAISE)
                    hud?.press()
                    // Already at 0% / 100%: one stretch is enough, don't start repeating.
                    if (moved) handler.postDelayed(repeater, REPEAT_DELAY_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                // Only the key that's being held may stop the repeat; releasing the
                // other button must not cut a hold short.
                if (heldDirection == direction || heldDirection == 0) stopRepeat()
                hud?.release()
            }
        }
        return true // consume both down and up so the system panel stays hidden
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heldDirection = 0
    private var holdAnim: android.animation.ValueAnimator? = null

    /** Fires once the button has been held past REPEAT_DELAY_MS: start the continuous glide. */
    private val repeater = Runnable { if (heldDirection != 0) startHoldGlide(heldDirection) }

    private fun holdInterval(): Long = HudPrefs.load(this).holdIntervalMs.toLong()

    /**
     * Held button: instead of firing separate steps, the fill slides at a constant speed
     * toward 0% / 100%, and the real volume (plus haptics) follows as it crosses each step.
     */
    private fun startHoldGlide(direction: Int) {
        val stream = activeStream()
        val max = audio.getStreamMaxVolume(stream)
        if (max <= 0) return
        val up = direction == AudioManager.ADJUST_RAISE
        var lastVol = audio.getStreamVolume(stream)
        val start = lastVol.toFloat() / max
        val end = if (up) 1f else 0f
        if (start == end) return
        hud?.holdStart()
        var cancelled = false
        var stepsCrossed = 0
        holdAnim = android.animation.ValueAnimator.ofFloat(start, end).apply {
            duration = (Math.abs(end - start) * max * holdInterval()).toLong()
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                hud?.holdLevel(f)
                val v = Math.round(f * max)
                if (v != lastVol) {
                    lastVol = v
                    audio.setStreamVolume(stream, v, 0)
                    // While gliding, only tick every few steps so it doesn't buzz constantly.
                    if (++stepsCrossed % HOLD_TICK_EVERY == 0) haptics.tick(this@VolumeHudService, v, max)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Ran all the way to 0% / 100% while still held: rubber-band once.
                    if (!cancelled) hud?.holdEnd(end, hitLimit = true, up = up)
                }
            })
            start()
        }
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeater)
        heldDirection = 0
        val a = holdAnim ?: return
        holdAnim = null
        if (a.isRunning) {
            a.cancel()
            // Released mid-glide: settle the fill onto the step the volume actually landed on.
            val s = activeStream()
            val max = audio.getStreamMaxVolume(s)
            hud?.holdEnd(if (max > 0) audio.getStreamVolume(s).toFloat() / max else 0f, hitLimit = false, up = true)
        }
    }

    /** One volume step. @return false if already at the limit. */
    private fun step(direction: Int): Boolean {
        val stream = activeStream()
        val before = audio.getStreamVolume(stream)
        audio.adjustStreamVolume(stream, direction, 0)
        val after = audio.getStreamVolume(stream)
        if (after != before) haptics.tick(this, after, audio.getStreamMaxVolume(stream))
        hud?.onStep(
            volume = after,
            max = audio.getStreamMaxVolume(stream),
            hitLimit = before == after,
            up = direction == AudioManager.ADJUST_RAISE,
        )
        return before != after
    }

    fun activeStream(): Int = when (audio.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    fun preview() {
        val s = activeStream()
        hud?.onStep(audio.getStreamVolume(s), audio.getStreamMaxVolume(s), hitLimit = false, up = true)
    }

    /**
     * Context whose WindowManager can add TYPE_ACCESSIBILITY_OVERLAY on [display].
     * AccessibilityService hands its overlay token to display contexts it creates.
     */
    fun overlayContext(display: Display): Context =
        if (display.displayId == Display.DEFAULT_DISPLAY) this else createDisplayContext(display)

    private var editor: EditorOverlay? = null

    fun startEditor() {
        if (editor != null) return
        editor = EditorOverlay(this) { editor = null; updatePin() }
        updatePin() // hide the live bar so it doesn't double up with the editor's
        editor?.open()
    }

    private var settingsOpen = false

    /** Customize page visible: keep the real bar on screen as a live preview. */
    fun setSettingsOpen(open: Boolean) {
        settingsOpen = open
        updatePin()
    }

    private fun updatePin() {
        val s = activeStream()
        val max = audio.getStreamMaxVolume(s)
        val level = if (max > 0) audio.getStreamVolume(s).toFloat() / max else 0f
        val on = settingsOpen && editor == null
        hud?.setPinned(on, level, immediate = editor != null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        editor?.close()
        HudPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(soundPrefListener)
        sounds?.release()
        sounds = null
        hud?.destroy()
        hud = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: VolumeHudService? = null
        private const val REPEAT_DELAY_MS = 350L    // hold this long before repeating
        private const val HOLD_TICK_EVERY = 3       // haptic on every Nth step while holding
    }
}
