package com.thorhud.volume

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var a11yBtn: Button
    private lateinit var enabledSw: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val title = TextView(this).apply { text = "Slim Volume"; textSize = 26f }
        status = TextView(this).apply { textSize = 15f; setPadding(0, (6 * dp).toInt(), 0, (12 * dp).toInt()) }

        a11yBtn = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        enabledSw = Switch(this).apply {
            text = "Use Slim Volume"
            textSize = 17f
            isChecked = HudPrefs.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> HudPrefs.setEnabled(this@MainActivity, on); refresh() }
        }
        val bootSw = Switch(this).apply {
            text = "Start on boot"
            textSize = 17f
            isChecked = HudPrefs.startOnBoot(this@MainActivity)
            setOnCheckedChangeListener { _, on -> HudPrefs.setStartOnBoot(this@MainActivity, on) }
        }
        val customize = Button(this).apply {
            text = "Customize bar"
            setOnClickListener { startActivity(Intent(this@MainActivity, BarSettingsActivity::class.java)) }
        }
        val preview = Button(this).apply {
            text = "Preview bar"
            setOnClickListener { VolumeHudService.instance?.preview() }
        }

        val updater = UpdateChecker(this)
        val updateBtn = Button(this).apply {
            text = "Check for updates"
            setOnClickListener {
                isEnabled = false
                text = "Checking…"
                updater.check(manual = true) { isEnabled = true; text = "Check for updates" }
            }
        }
        val version = TextView(this).apply {
            text = "Version ${BuildConfig.VERSION_NAME}"
            textSize = 13f
            alpha = 0.6f
            gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        // Quiet check on open: only speaks up if there's something newer.
        if (savedInstanceState == null) updater.check(manual = false)

        val wide = LinearLayout.LayoutParams((420 * dp).toInt(), -2).apply { topMargin = (6 * dp).toInt() }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(status)
            addView(a11yBtn, wide)
            addView(enabledSw, wide)
            addView(bootSw, wide)
            addView(customize, wide)
            addView(preview, wide)
            addView(updateBtn, wide)
            addView(version)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val me = "$packageName/${VolumeHudService::class.java.name}"
        val running = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.equals(me, true) || it.equals("$packageName/.VolumeHudService", true) }
        a11yBtn.visibility = if (running) android.view.View.GONE else android.view.View.VISIBLE
        status.text = when {
            !running -> "Step 1: turn on \"Slim Volume\" under Accessibility → Downloaded apps."
            HudPrefs.isEnabled(this) -> "On. Press the volume buttons."
            else -> "Paused. The stock volume panel is used."
        }
    }
}
