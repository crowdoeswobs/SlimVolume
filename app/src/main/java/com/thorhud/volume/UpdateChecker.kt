package com.thorhud.volume

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub repo's latest release. If its tag (e.g. "v1.1") is newer than this
 * build's versionName, offers to download the attached .apk and hands it to the installer.
 */
class UpdateChecker(private val activity: Activity) {

    data class Release(val version: String, val notes: String, val apkUrl: String, val pageUrl: String)

    private val main = Handler(Looper.getMainLooper())

    /** @param manual true when the user pressed the button: also report "up to date" and errors. */
    fun check(manual: Boolean, onDone: (() -> Unit)? = null) {
        Thread {
            val result = runCatching { fetchLatest() }
            main.post {
                onDone?.invoke()
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { rel ->
                    when {
                        rel == null -> if (manual) toast("No releases published yet")
                        isNewer(rel.version, BuildConfig.VERSION_NAME) -> offer(rel)
                        manual -> toast("You're up to date (v${BuildConfig.VERSION_NAME})")
                    }
                }.onFailure { if (manual) toast("Couldn't check for updates: ${it.message ?: "no connection"}") }
            }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SlimVolume-Updater")
        try {
            if (conn.responseCode == 404) return null // repo has no releases yet
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a.optString("browser_download_url"); break
                }
            }
            return Release(
                version = json.optString("tag_name").trimStart('v', 'V'),
                notes = json.optString("body").trim(),
                apkUrl = apk ?: "",
                pageUrl = json.optString("html_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun offer(rel: Release) {
        val msg = buildString {
            append("Version ${rel.version} is available (you have ${BuildConfig.VERSION_NAME}).")
            if (rel.notes.isNotEmpty()) append("\n\n").append(rel.notes.take(1200))
        }
        val b = AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(msg)
            .setNegativeButton("Later", null)
        if (rel.apkUrl.isNotEmpty()) b.setPositiveButton("Download & install") { _, _ -> download(rel) }
        else b.setPositiveButton("Open release page") { _, _ ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.pageUrl)))
        }
        b.show()
    }

    private fun download(rel: Release) {
        // Android asks once per app before it may install APKs.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast("Allow Slim Volume to install updates, then tap Download again")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
            return
        }
        val dm = activity.getSystemService(DownloadManager::class.java)
        val name = "thor-slim-volume-v${rel.version}.apk"
        activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(name)?.delete()
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(rel.apkUrl))
            .setTitle("Slim Volume ${rel.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name))
        toast("Downloading update…")

        val ctx = activity.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                ctx.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(ctx, "Update download failed", Toast.LENGTH_LONG).show(); return
                }
                ctx.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    companion object {
        /** "1.10" > "1.9", "1.0.1" > "1.0". Non-numeric parts count as 0. */
        fun isNewer(remote: String, local: String): Boolean {
            val r = remote.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val l = local.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
