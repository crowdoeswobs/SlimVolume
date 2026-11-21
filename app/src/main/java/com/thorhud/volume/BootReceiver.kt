package com.thorhud.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android restarts enabled accessibility services on boot by itself, so
 * "start on boot" really means: after a reboot, should the bar be active
 * or paused (stock panel) until you switch it on in the app?
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        HudPrefs.setEnabled(context, HudPrefs.startOnBoot(context))
    }
}
