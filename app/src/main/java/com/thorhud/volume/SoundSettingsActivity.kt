package com.thorhud.volume

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** "Sound" page: click sounds for the volume buttons. */
class SoundSettingsActivity : SettingsPage() {

    private lateinit var soundSw: Switch
    private lateinit var upPick: TextView
    private lateinit var downPick: TextView
    private lateinit var soundVolBar: SeekBar
    private lateinit var soundVolVal: TextView
    private lateinit var followSw: Switch
    private var previewPlayer: SoundPlayer? = null

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    /** Plays a sound the same way the service would, at the current settings. */
    private fun previewSound(id: String?) {
        val follow = SoundBank.followVolume(this)
        if (previewPlayer?.followVolume != follow) {
            previewPlayer?.release()
            previewPlayer = SoundPlayer(this, follow)
        }
        previewPlayer?.play(id, SoundBank.volume(this) / 100f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sound"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val col = pageColumn()

        soundSw = switch("Sound effects") { on -> SoundBank.setEnabled(this, on) }
        soundVolVal = valueText()
        soundVolBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) SoundBank.setVolume(this@SoundSettingsActivity, p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                // Let go: play the up sound so you hear the new level.
                override fun onStopTrackingTouch(sb: SeekBar) {
                    previewSound(SoundBank.selected(this@SoundSettingsActivity, up = true))
                }
            })
        }
        followSw = switch("Follow console volume") { on ->
            SoundBank.setFollowVolume(this, on)
            previewSound(SoundBank.selected(this, up = true))
        }
        upPick = dropdown(up = true)
        downPick = dropdown(up = false)

        col.addView(section("Sound effects",
            soundSw,
            hint("Plays only when you press the volume up or down buttons. Dragging the bar stays silent."),
            sliderRow("Volume", soundVolBar, soundVolVal),
            followSw,
        ))
        col.addView(section("Sounds",
            labeledRow("Up sound", upPick),
            labeledRow("Down sound", downPick),
            Button(this).apply {
                text = "Import sound"
                setOnClickListener {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                    }, REQ_IMPORT)
                }
            },
        ))
        refresh()
    }

    override fun onStart() {
        super.onStart()
        HudPrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
    }

    override fun onStop() {
        HudPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onStop()
    }

    private fun refresh() {
        val on = SoundBank.enabled(this)
        soundSw.setCheckedSilently(on)
        followSw.setCheckedSilently(SoundBank.followVolume(this))
        soundVolBar.progress = SoundBank.volume(this)
        soundVolVal.text = "${SoundBank.volume(this)}%"
        upPick.text = SoundBank.nameOf(this, SoundBank.selected(this, up = true)) + "  ▾"
        downPick.text = SoundBank.nameOf(this, SoundBank.selected(this, up = false)) + "  ▾"
    }

    override fun onDestroy() {
        previewPlayer?.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        SoundBank.import(this, uri)
            .onSuccess { item ->
                Toast.makeText(this, "Added \"${item.name}\"", Toast.LENGTH_SHORT).show()
                previewSound(item.id)
                refresh()
            }
            .onFailure { Toast.makeText(this, it.message ?: "Couldn't import that file", Toast.LENGTH_LONG).show() }
    }

    // ---------- sound dropdowns ----------

    private fun dropdown(up: Boolean): TextView = TextView(this).apply {
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        val h = (14 * dp).toInt(); val v = (9 * dp).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.argb(40, 255, 255, 255)) }
        isClickable = true
        setOnClickListener { showSoundList(this, up) }
    }

    private fun showSoundList(anchor: View, up: Boolean) {
        val popup = ListPopupWindow(this)
        val items = SoundBank.all(this)
        val current = SoundBank.selected(this, up)
        popup.anchorView = anchor
        popup.width = anchor.width
        popup.isModal = true
        popup.setBackgroundDrawable(GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.rgb(40, 42, 48)) })
        popup.setAdapter(object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
                val item = items[i]
                return LinearLayout(this@SoundSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val h = (14 * dp).toInt()
                    setPadding(h, 0, (4 * dp).toInt(), 0)
                    minimumHeight = (46 * dp).toInt()
                    addView(TextView(context).apply {
                        text = item.name
                        textSize = 15f
                        setTextColor(if (item.id == current) Color.rgb(120, 170, 255) else Color.WHITE)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (item.custom) addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_trash)
                        val p = (11 * dp).toInt()
                        setPadding(p, p, p, p)
                        isClickable = true
                        contentDescription = "Remove ${item.name}"
                        setOnClickListener { popup.dismiss(); confirmDelete(item) }
                    }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
                }
            }
        })
        popup.setOnItemClickListener { _, _, i, _ ->
            SoundBank.select(this, up, items[i].id)
            previewSound(items[i].id)
            popup.dismiss()
            refresh()
        }
        popup.show()
    }

    private fun confirmDelete(item: SoundItem) {
        AlertDialog.Builder(this)
            .setTitle("Remove \"${item.name}\"?")
            .setMessage("It will be removed from both lists.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                SoundBank.delete(this, item.id)
                previewPlayer?.prune(SoundBank.all(this).map { it.id }.toSet())
                refresh()
            }
            .show()
    }

    private companion object {
        const val REQ_IMPORT = 41
    }
}
