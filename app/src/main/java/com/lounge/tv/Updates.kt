package com.lounge.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * "Update available" from GitHub. Every build publishes a number (version.txt) next to the app; if it is bigger than
 * the number built into this app, a newer version exists. Installing downloads the app file and hands it to Android's installer.
 */
object Updates {
    const val BASE = "https://github.com/gnadersfam/TheLounge/releases/download/latest/"
    const val APK_ADDRESS = BASE + "TheLounge.apk"
    private const val CHECK_EVERY = 6 * 3600L

    @Volatile var latest: Int = 0
        private set
    @Volatile var checking = false
        private set

    fun current(act: Activity): Int = try {
        @Suppress("DEPRECATION")
        act.packageManager.getPackageInfo(act.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    fun available(act: Activity): Boolean {
        val l = if (latest > 0) latest else Store.long(act, "upd_latest", 0L).toInt()
        val cur = current(act)
        return cur > 0 && l > cur
    }

    /** Looks at GitHub (at most every few hours) without bothering anyone. */
    fun checkInBackground(act: Activity, force: Boolean = false, done: (() -> Unit)? = null) {
        val last = Store.long(act, "upd_checked", 0L)
        val now = System.currentTimeMillis() / 1000
        if (!force && now - last < CHECK_EVERY) { done?.let { Bg.ui(it) }; return }
        if (checking) return
        checking = true
        Bg.run {
            try {
                val t = Net.text(BASE + "version.txt", timeoutMs = 15000).trim()
                val n = t.toIntOrNull()
                if (n != null) {
                    latest = n
                    Store.putLong(act, "upd_latest", n.toLong())
                    Store.putLong(act, "upd_checked", now)
                }
            } catch (e: Exception) {
                // no internet or GitHub unreachable: try again next time
            } finally {
                checking = false
            }
            done?.let { Bg.ui(it) }
        }
    }

    fun shutdown() {}

    /** Downloads the newest app and opens Android's installer. [status] is told what is happening (on screen). */
    fun install(act: Activity, status: (String) -> Unit) {
        status("Downloading the update...")
        Bg.run {
            try {
                val dir = File(act.cacheDir, "updates")
                dir.mkdirs()
                val file = File(dir, "TheLounge.apk")
                val bytes = Net.bytes(APK_ADDRESS, timeoutMs = 120000)
                FileOutputStream(file).use { it.write(bytes) }
                Bg.ui {
                    try {
                        val uri: Uri = FileProvider.getUriForFile(act, "com.lounge.tv.files", file)
                        val i = Intent(Intent.ACTION_VIEW)
                        i.setDataAndType(uri, "application/vnd.android.package-archive")
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        act.startActivity(i)
                        status("Choose Install on the next screen.")
                    } catch (e: Exception) {
                        status("Could not open the installer. Use Downloader with $APK_ADDRESS")
                    }
                }
            } catch (e: Exception) {
                Bg.ui { status("Download failed (${e.message}). Try again, or use Downloader with $APK_ADDRESS") }
            }
        }
    }
}
