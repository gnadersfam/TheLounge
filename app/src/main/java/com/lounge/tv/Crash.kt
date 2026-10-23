package com.lounge.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the app alive when something small goes wrong, and writes down what happened (Settings > Check my setup).
 * A problem on a background thread is logged and ignored. A problem on the screen thread is logged and the
 * screen carries on instead of throwing you back to the Fire TV home screen.
 */
object Crash {
    private var installed = false
    private var app: Context? = null

    fun install(c: Context) {
        app = c.applicationContext
        if (installed) return
        installed = true
        val main = Looper.getMainLooper()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("Thread ${t.name}", e)
            // anything that reaches here is off the main loop (the main loop is guarded below), so just carry on
        }
        Handler(main).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (e: Throwable) {
                    log("Screen", e)
                }
            }
        }
    }

    private fun file(): File? = app?.let { File(it.filesDir, "error.log") }

    @Synchronized
    fun log(where: String, e: Throwable) {
        try {
            val f = file() ?: return
            if (f.exists() && f.length() > 60_000) {
                // keep it small: drop the older half
                val t = f.readText()
                f.writeText(t.substring(t.length / 2))
            }
            val stamp = SimpleDateFormat("d MMM HH:mm:ss", Locale.UK).format(Date())
            val trace = e.stackTrace.take(6).joinToString("\n    ") { it.toString() }
            f.appendText("[$stamp] $where: ${e.javaClass.simpleName}: ${e.message}\n    $trace\n")
        } catch (_: Throwable) {
        }
    }

    /** The last few problems, newest last. Empty text when there are none. */
    fun recent(maxChars: Int = 1500): String {
        return try {
            val t = file()?.takeIf { it.exists() }?.readText() ?: ""
            if (t.length > maxChars) t.substring(t.length - maxChars) else t
        } catch (_: Throwable) {
            ""
        }
    }

    fun count(): Int = try {
        file()?.takeIf { it.exists() }?.readLines()?.count { it.startsWith("[") } ?: 0
    } catch (_: Throwable) {
        0
    }

    fun clear() {
        try { file()?.delete() } catch (_: Throwable) {}
    }
}
