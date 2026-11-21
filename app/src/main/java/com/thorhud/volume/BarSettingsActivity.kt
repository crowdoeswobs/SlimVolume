package com.thorhud.volume

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** "Customize bar" page: grouped style settings with a live preview. Changes apply immediately. */
class BarSettingsActivity : Activity() {

    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var cfg: HudConfig

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cfg = HudPrefs.load(this)
        refresh()
    }

    private lateinit var orientSeg: LinearLayout
    private lateinit var rimSw: Switch
    private lateinit var iosSw: Switch
    private lateinit var hapticSw: Switch
    private lateinit var swatchRow: LinearLayout
    private lateinit var customSwatch: CustomSwatch
    private lateinit var lengthBar: SeekBar
    private lateinit var thickBar: SeekBar
    private lateinit var hideBar: SeekBar
    private lateinit var holdBar: SeekBar
    private lateinit var holdVal: TextView
    private lateinit var lengthVal: TextView
    private lateinit var thickVal: TextView
    private lateinit var hideVal: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Customize bar"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        cfg = HudPrefs.load(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, (8 * dp).toInt(), p, p)
        }

        // ---- Position ----
        orientSeg = segmented("Vertical", "Horizontal") { i -> set(cfg.copy(vertical = i == 0)) }
        col.addView(section("Position",
            Button(this).apply {
                text = "Edit position"
                setOnClickListener {
                    val svc = VolumeHudService.instance
                    if (svc == null) Toast.makeText(this@BarSettingsActivity,
                        "Turn on Slim Volume in Accessibility first", Toast.LENGTH_SHORT).show()
                    else svc.startEditor()
                }
            },
            orientSeg,
        ))

        // ---- Color ----
        swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            HudPrefs.SWATCHES.forEach { c ->
                addView(View(context).apply {
                    tag = c
                    isClickable = true
                    setOnClickListener { set(cfg.copy(color = c, customOn = false)) }
                }, swatchLp())
            }
            customSwatch = CustomSwatch(context).apply {
                isClickable = true
                setOnClickListener { showCustomColorDialog() }
            }
            addView(customSwatch, swatchLp())
        }
        col.addView(section("Color",
            swatchRow,
        ))

        // ---- Appearance ----
        rimSw = switch("Embossed edge") { on -> set(cfg.copy(rim = on)) }
        lengthVal = valueText(); thickVal = valueText(); hideVal = valueText()
        lengthBar = slider((HudPrefs.LENGTH_MAX - HudPrefs.LENGTH_MIN).toInt(), 0) { p -> set(cfg.copy(lengthDp = HudPrefs.LENGTH_MIN + p)) }
        thickBar = slider((HudPrefs.THICK_MAX - HudPrefs.THICK_MIN).toInt(), 0) { p -> set(cfg.copy(thicknessDp = HudPrefs.THICK_MIN + p)) }
        col.addView(section("Appearance",
            rimSw,
            sliderRow("Length", lengthBar, lengthVal),
            sliderRow("Thickness", thickBar, thickVal),
        ))

        // ---- Behavior ----
        hideBar = slider(95, 0) { p -> set(cfg.copy(hideMs = (p + 5) * 100)) }
        holdVal = valueText()
        // Right = faster: progress 0 is the slowest interval.
        holdBar = slider(HudPrefs.HOLD_SLOWEST_MS - HudPrefs.HOLD_FASTEST_MS, 0) { p ->
            set(cfg.copy(holdIntervalMs = HudPrefs.HOLD_SLOWEST_MS - p))
        }
        iosSw = switch("iOS mode") { on -> set(cfg.copy(iosMode = on)) }
        hapticSw = switch("Haptics") { on -> HudPrefs.setHaptics(this, on) }
        col.addView(section("Behavior",
            hapticSw,
            iosSw,
            hint("First press shows a wide bar; pressing again slims it down to your thickness."),
            sliderRow("Hide after", hideBar, hideVal),
            sliderRow("Hold speed", holdBar, holdVal),
        ))

        col.addView(Button(this).apply {
            text = "Reset style"
            setOnClickListener {
                val d = HudPrefs.DEFAULT
                // Keep where the bar is; reset only how it looks.
                set(cfg.copy(vertical = d.vertical, rim = d.rim, color = d.color, customOn = false,
                    lengthDp = d.lengthDp, thicknessDp = d.thicknessDp, hideMs = d.hideMs, iosMode = d.iosMode,
                    holdIntervalMs = d.holdIntervalMs))
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (16 * dp).toInt() })

        // Centre the column with a sensible max width on the wide screen.
        val outer = FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, (640 * dp).toInt()), -2, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(ScrollView(this).apply { addView(outer) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Position may have changed in the overlay editor.
        cfg = HudPrefs.load(this)
        refresh()
        VolumeHudService.instance?.setSettingsOpen(true)
    }

    override fun onPause() {
        VolumeHudService.instance?.setSettingsOpen(false)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        HudPrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStop() {
        HudPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------- custom colour popup ----------

    private fun showCustomColorDialog() {
        val hsv = FloatArray(3).also { Color.colorToHSV(cfg.customColor, it) }
        val sample = PillView(this).apply { level = 0.65f; vertical = false; rim = cfg.rim; color = cfg.customColor }
        var picked = cfg.customColor

        lateinit var hue: SeekBar
        lateinit var sat: SeekBar
        lateinit var bri: SeekBar
        val update = {
            picked = Color.HSVToColor(0xF0, floatArrayOf(hue.progress.toFloat(), sat.progress / 100f, bri.progress / 100f))
            sample.color = picked
        }
        hue = slider(360, hsv[0].toInt()) { update() }
        sat = slider(100, (hsv[1] * 100).toInt()) { update() }
        bri = slider(100, (hsv[2] * 100).toInt()) { update() }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * dp).toInt()
            setPadding(p, (12 * dp).toInt(), p, 0)
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.rgb(44, 46, 52)) }
                addView(sample, FrameLayout.LayoutParams((220 * dp).toInt(), (16 * dp).toInt(), Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, (80 * dp).toInt()).apply { bottomMargin = (10 * dp).toInt() })
            addView(sliderRow("Hue", hue, null))
            addView(sliderRow("Saturation", sat, null))
            addView(sliderRow("Brightness", bri, null))
        }

        AlertDialog.Builder(this)
            .setTitle("Custom color")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use color") { _, _ -> set(cfg.copy(customColor = picked, customOn = true)) }
            .show()
    }

    // ---------- state ----------

    private fun set(c: HudConfig) {
        // Re-read so we never clobber a position saved by the editor meanwhile.
        val latest = HudPrefs.load(this)
        cfg = c.copy(cx = latest.cx, cy = latest.cy, displayId = latest.displayId)
        HudPrefs.save(this, cfg)
        refresh()
    }

    private fun refresh() {
        selectSegment(orientSeg, if (cfg.vertical) 0 else 1)
        rimSw.setCheckedSilently(cfg.rim)
        iosSw.setCheckedSilently(cfg.iosMode)
        hapticSw.setCheckedSilently(HudPrefs.haptics(this))

        for (i in 0 until swatchRow.childCount) {
            val v = swatchRow.getChildAt(i)
            val c = v.tag as? Int ?: continue
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (!cfg.customOn && c == cfg.color) setStroke((3 * dp).toInt(), Color.WHITE)
                else setStroke((1 * dp).toInt(), Color.argb(80, 255, 255, 255))
            }
        }
        customSwatch.color = cfg.customColor
        customSwatch.chosen = cfg.customOn

        lengthBar.progress = (cfg.lengthDp - HudPrefs.LENGTH_MIN).toInt()
        thickBar.progress = (cfg.thicknessDp - HudPrefs.THICK_MIN).toInt()
        hideBar.progress = cfg.hideMs / 100 - 5
        lengthVal.text = "${cfg.lengthDp.toInt()}dp"
        thickVal.text = "${cfg.thicknessDp.toInt()}dp"
        hideVal.text = "%.1fs".format(cfg.hideMs / 1000f)
        holdBar.progress = HudPrefs.HOLD_SLOWEST_MS - cfg.holdIntervalMs
        holdVal.text = "%.0f/s".format(1000f / cfg.holdIntervalMs)
    }

    // ---------- little view helpers ----------

    /** A titled card grouping related settings. */
    private fun section(title: String, vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() }
        addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(Color.rgb(120, 170, 255))
            setPadding((4 * dp).toInt(), 0, 0, (6 * dp).toInt())
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * dp).toInt()
            setPadding(p, (6 * dp).toInt(), p, (6 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.argb(28, 255, 255, 255)) }
            children.forEach { addView(it) }
        })
    }

    /** Pill split into halves; the selected half is filled. */
    private fun segmented(a: String, b: String, onSelect: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val p = (3 * dp).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(Color.argb(40, 255, 255, 255)) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = (10 * dp).toInt(); bottomMargin = (8 * dp).toInt()
        }
        listOf(a, b).forEachIndexed { i, label ->
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                val v = (9 * dp).toInt()
                setPadding(0, v, 0, v)
                isClickable = true
                setOnClickListener { onSelect(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun selectSegment(seg: LinearLayout, index: Int) {
        for (i in 0 until seg.childCount) {
            val t = seg.getChildAt(i) as TextView
            val on = i == index
            t.setTextColor(if (on) Color.BLACK else Color.WHITE)
            t.background = if (on) GradientDrawable().apply { cornerRadius = 100 * dp; setColor(Color.WHITE) } else null
        }
    }

    private fun Switch.setCheckedSilently(v: Boolean) {
        @Suppress("UNCHECKED_CAST") val l = tag as? (Boolean) -> Unit
        setOnCheckedChangeListener(null)
        isChecked = v
        setOnCheckedChangeListener { _, on -> l?.invoke(on) }
    }

    private fun switch(text: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        textSize = 16f
        tag = onChange
        val v = (10 * dp).toInt()
        setPadding(0, v, 0, v)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        alpha = 0.6f
        setPadding(0, 0, 0, (6 * dp).toInt())
    }

    private fun valueText() = TextView(this).apply {
        textSize = 14f
        minWidth = (56 * dp).toInt()
        gravity = Gravity.END
    }

    private fun swatchLp() = LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt()).apply {
        setMargins(0, 0, (10 * dp).toInt(), 0)
    }

    private fun slider(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun sliderRow(label: String, bar: SeekBar, value: TextView?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val v = (6 * dp).toInt()
        setPadding(0, v, 0, v)
        addView(TextView(context).apply { text = label; textSize = 16f; minWidth = (110 * dp).toInt() })
        addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(value)
    }
}
