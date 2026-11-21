package com.thorhud.volume

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Shared look for the settings pages: titled cards, switches, sliders, back arrow. */
abstract class SettingsPage : Activity() {

    protected val dp by lazy { resources.displayMetrics.density }

    /** Vertical column centred with a max width (the Thor's top screen is wide). */
    protected fun pageColumn(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, (8 * dp).toInt(), p, p)
        }
        val outer = FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, (640 * dp).toInt()), -2, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(ScrollView(this).apply { addView(outer) })
        return col
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------- little view helpers ----------

    /** A titled card grouping related settings. */
    protected fun section(title: String, vararg children: View) = LinearLayout(this).apply {
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
    protected fun segmented(a: String, b: String, onSelect: (Int) -> Unit) = LinearLayout(this).apply {
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

    protected fun selectSegment(seg: LinearLayout, index: Int) {
        for (i in 0 until seg.childCount) {
            val t = seg.getChildAt(i) as TextView
            val on = i == index
            t.setTextColor(if (on) Color.BLACK else Color.WHITE)
            t.background = if (on) GradientDrawable().apply { cornerRadius = 100 * dp; setColor(Color.WHITE) } else null
        }
    }

    protected fun Switch.setCheckedSilently(v: Boolean) {
        @Suppress("UNCHECKED_CAST") val l = tag as? (Boolean) -> Unit
        setOnCheckedChangeListener(null)
        isChecked = v
        setOnCheckedChangeListener { _, on -> l?.invoke(on) }
    }

    protected fun switch(text: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        textSize = 16f
        tag = onChange
        val v = (10 * dp).toInt()
        setPadding(0, v, 0, v)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    protected fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        alpha = 0.6f
        setPadding(0, 0, 0, (6 * dp).toInt())
    }

    protected fun valueText() = TextView(this).apply {
        textSize = 14f
        minWidth = (56 * dp).toInt()
        gravity = Gravity.END
    }

    protected fun swatchLp() = LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt()).apply {
        setMargins(0, 0, (10 * dp).toInt(), 0)
    }

    protected fun slider(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    protected fun sliderRow(label: String, bar: SeekBar, value: TextView?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val v = (6 * dp).toInt()
        setPadding(0, v, 0, v)
        addView(TextView(context).apply { text = label; textSize = 16f; minWidth = (110 * dp).toInt() })
        addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(value)
    }


    protected fun labeledRow(label: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = (6 * dp).toInt()
        setPadding(0, p, 0, p)
        addView(TextView(context).apply { text = label; textSize = 16f; minWidth = (110 * dp).toInt() })
        addView(v, LinearLayout.LayoutParams(0, -2, 1f))
    }
}
