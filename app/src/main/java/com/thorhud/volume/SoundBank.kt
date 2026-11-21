package com.thorhud.volume

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** One entry in the up/down sound dropdowns. */
data class SoundItem(val id: String, val name: String, val custom: Boolean)

/**
 * Click sounds for the volume buttons.
 * Built-in sounds live in assets/sounds/ (id "asset:<file>"); imported ones are
 * copied into the app's private files/sounds/ (id "file:<file>"). Both lists are
 * shared by the up and the down dropdown.
 */
object SoundBank {
    private const val ASSET_DIR = "sounds"
    private const val ASSET = "asset:"
    private const val FILE = "file:"
    private const val DEFAULT_UP = "Tick.wav"
    private const val DEFAULT_DOWN = "Tock.wav"
    /** SoundPool only holds a few seconds of decoded audio, so keep imports small. */
    const val MAX_IMPORT_BYTES = 2L * 1024 * 1024

    private val AUDIO_EXT = setOf("ogg", "wav", "mp3", "m4a", "aac", "flac", "opus")

    fun customDir(ctx: Context) = File(ctx.filesDir, "sounds").apply { mkdirs() }

    fun builtIns(ctx: Context): List<SoundItem> =
        (ctx.assets.list(ASSET_DIR) ?: emptyArray())
            .filter { it.substringAfterLast('.').lowercase() in AUDIO_EXT }
            .sorted()
            .map { SoundItem(ASSET + it, prettyName(it), custom = false) }

    fun customs(ctx: Context): List<SoundItem> =
        (customDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .map { SoundItem(FILE + it.name, prettyName(it.name), custom = true) }

    fun all(ctx: Context) = builtIns(ctx) + customs(ctx)

    fun nameOf(ctx: Context, id: String?): String =
        all(ctx).firstOrNull { it.id == id }?.name ?: "None"

    private fun prettyName(file: String) = file.substringBeforeLast('.').replace('_', ' ')

    // ---------- prefs (stored with the bar's prefs so the settings page refreshes) ----------

    fun enabled(ctx: Context) = HudPrefs.prefs(ctx).getBoolean("sound_on", false)
    fun setEnabled(ctx: Context, on: Boolean) = HudPrefs.prefs(ctx).edit().putBoolean("sound_on", on).apply()

    /** Click loudness, 0..100. */
    fun volume(ctx: Context) = HudPrefs.prefs(ctx).getInt("sound_volume", 70)
    fun setVolume(ctx: Context, v: Int) = HudPrefs.prefs(ctx).edit().putInt("sound_volume", v).apply()

    /** On: clicks get quieter/louder with the console volume. Off: fixed loudness from the slider. */
    fun followVolume(ctx: Context) = HudPrefs.prefs(ctx).getBoolean("sound_follow", false)
    fun setFollowVolume(ctx: Context, on: Boolean) = HudPrefs.prefs(ctx).edit().putBoolean("sound_follow", on).apply()

    /** Chosen sound for a direction, falling back to a built-in if it's gone. */
    fun selected(ctx: Context, up: Boolean): String? {
        val all = all(ctx)
        val saved = HudPrefs.prefs(ctx).getString(if (up) "sound_up" else "sound_down", null)
        if (saved != null && all.any { it.id == saved }) return saved
        val defaults = builtIns(ctx)
        // Default pairing: Tick for up, Tock for down.
        val want = ASSET + if (up) DEFAULT_UP else DEFAULT_DOWN
        return defaults.firstOrNull { it.id == want }?.id
            ?: defaults.firstOrNull()?.id
            ?: all.firstOrNull()?.id
    }

    fun select(ctx: Context, up: Boolean, id: String) =
        HudPrefs.prefs(ctx).edit().putString(if (up) "sound_up" else "sound_down", id).apply()

    // ---------- import / delete ----------

    /** Copies a picked audio file into private storage. @return the new item, or an error message. */
    fun import(ctx: Context, uri: Uri): Result<SoundItem> = runCatching {
        var name = "Sound"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_IMPORT_BYTES) error("That file is too big. Pick a short sound (under 2 MB).")

        val safe = name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").ifBlank { "Sound" }
        val base = safe.substringBeforeLast('.')
        val ext = safe.substringAfterLast('.', "").ifBlank { "ogg" }
        val dir = customDir(ctx)
        var out = File(dir, "$base.$ext")
        var n = 2
        while (out.exists()) out = File(dir, "$base ($n).$ext").also { n++ }

        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open that file." }
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() > MAX_IMPORT_BYTES) {
            out.delete(); error("That file is too big. Pick a short sound (under 2 MB).")
        }
        SoundItem(FILE + out.name, prettyName(out.name), custom = true)
    }

    fun delete(ctx: Context, id: String) {
        if (!id.startsWith(FILE)) return
        File(customDir(ctx), id.removePrefix(FILE)).delete()
        // Anything that pointed at it falls back to a built-in.
        val p = HudPrefs.prefs(ctx)
        p.edit().apply {
            putLong("sound_rev", System.nanoTime()) // tells the service to drop its cached copy
            if (p.getString("sound_up", null) == id) remove("sound_up")
            if (p.getString("sound_down", null) == id) remove("sound_down")
        }.apply()
    }

    /** Loads [id] into [pool]. @return the pool's sound id, or 0 on failure. */
    fun load(ctx: Context, pool: SoundPool, id: String): Int = runCatching {
        when {
            id.startsWith(ASSET) -> ctx.assets.openFd("$ASSET_DIR/${id.removePrefix(ASSET)}").use { pool.load(it, 1) }
            id.startsWith(FILE) -> pool.load(File(customDir(ctx), id.removePrefix(FILE)).path, 1)
            else -> 0
        }
    }.getOrDefault(0)
}

/**
 * Plays bank sounds with low latency.
 * [followVolume] true: out on the media stream, so clicks follow the console volume.
 * false: out on the accessibility stream, which has its own level (kept at max by the
 * service), so only the sound volume slider decides how loud they are.
 */
class SoundPlayer(private val ctx: Context, val followVolume: Boolean) {
    private var vol = 1f
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (followVolume) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val loaded = HashMap<String, Int>()  // bank id -> pool id
    private val ready = HashSet<Int>()
    private val pending = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { p, sid, status ->
            if (status != 0) return@setOnLoadCompleteListener
            ready += sid
            if (pending.remove(sid)) p.play(sid, vol, vol, 1, 0, 1f)
        }
    }

    private fun poolId(id: String): Int =
        loaded[id] ?: SoundBank.load(ctx, pool, id).also { if (it != 0) loaded[id] = it }

    /** Load ahead of time so the first press isn't delayed. */
    fun preload(id: String?) { if (id != null) poolId(id) }

    /** @param volume 0..1 */
    fun play(id: String?, volume: Float) {
        val sid = poolId(id ?: return)
        if (sid == 0 || volume <= 0f) return
        // Loudness is perceived logarithmically; squaring makes the slider feel even.
        vol = (volume * volume).coerceIn(0f, 1f)
        if (sid in ready) pool.play(sid, vol, vol, 1, 0, 1f) else pending += sid
    }

    /** Drop cached sounds whose files are gone (deleted imports). */
    fun prune(valid: Set<String>) {
        loaded.keys.filter { it !in valid }.forEach { id ->
            loaded.remove(id)?.let { pool.unload(it); ready -= it; pending -= it }
        }
    }

    fun release() = pool.release()
}
