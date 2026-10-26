package com.lounge.tv

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale

/** Performance: how full the memory and storage are, and how warm the Fire TV is. Refreshes every few seconds while open. */
class PerfScreen(act: MainActivity) : Screen(act) {

    /** A thin rounded bar. Either filled to a fraction, or a colour strip with a marker (for temperature). */
    private class Bar(c: Context, private val strip: Boolean) : View(c) {
        var frac = 0f
        var color = Color.WHITE
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val rad = h / 2f
            val f = frac.coerceIn(0f, 1f)
            if (strip) {
                p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Skin.GREEN, Skin.AMBER, Skin.RED), null, Shader.TileMode.CLAMP)
                r.set(0f, 0f, w, h)
                canvas.drawRoundRect(r, rad, rad, p)
                p.shader = null
                p.color = Color.WHITE
                val x = (w * f).coerceIn(4f, w - 4f)
                r.set(x - 4f, -3f, x + 4f, h + 3f)
                canvas.drawRoundRect(r, 4f, 4f, p)
            } else {
                p.shader = null
                p.color = Skin.LINE
                r.set(0f, 0f, w, h)
                canvas.drawRoundRect(r, rad, rad, p)
                if (f > 0f) {
                    p.color = color
                    r.set(0f, 0f, Math.max(h, w * f), h)
                    canvas.drawRoundRect(r, rad, rad, p)
                }
            }
        }
    }

    private inner class Card(title: String, strip: Boolean = false) {
        val box = LinearLayout(act)
        val big = Skin.text(act, "...", 54f, Skin.TEXT, true)
        val bar = Bar(act, strip)
        val sub = Skin.text(act, "", 15f, Skin.MUTED)
        val tag = Skin.text(act, "", 17f, Skin.TEXT, true)

        init {
            box.orientation = LinearLayout.VERTICAL
            box.background = Skin.card(act, 18)
            box.setPadding(Skin.dp(act, 24), Skin.dp(act, 20), Skin.dp(act, 24), Skin.dp(act, 20))
            box.addView(Skin.text(act, title, 15f, Skin.MUTED, true).apply { letterSpacing = 0.12f })
            box.addView(big, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Skin.dp(act, 6) })
            box.addView(bar, LinearLayout.LayoutParams(-1, Skin.dp(act, 16)).apply { topMargin = Skin.dp(act, 12) })
            box.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Skin.dp(act, 14) })
            box.addView(tag, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Skin.dp(act, 4) })
        }

        fun set(bigText: String, frac: Float, color: Int, subText: String, tagText: String) {
            big.text = bigText
            bar.frac = frac
            bar.color = color
            bar.invalidate()
            sub.text = subText
            tag.text = tagText
            tag.setTextColor(color)
        }
    }

    private class Reading(
        val ramUsed: Long, val ramTotal: Long,
        val diskFree: Long, val diskTotal: Long,
        val tempC: Double?, val tempWord: String
    )

    private val ram = Card("MEMORY (RAM)")
    private val disk = Card("STORAGE")
    private val temp = Card("TEMPERATURE", strip = true)

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            Bg.uiDelayed(3000, this)
        }
    }

    init {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Skin.dp(act, 40), Skin.dp(act, 26), Skin.dp(act, 34), Skin.dp(act, 30))
        col.addView(Skin.text(act, "PERFORMANCE", 40f, Skin.TEXT, true).apply { letterSpacing = 0.03f })
        col.addView(Skin.text(act, "THIS FIRESTICK", 19f, Skin.CYAN, true).apply {
            setPadding(0, Skin.dp(act, 16), 0, Skin.dp(act, 10))
        })
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        for (c in listOf(ram, disk, temp)) {
            row.addView(c.box, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Skin.dp(act, 14) })
        }
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        col.addView(Skin.text(act, "Updates every few seconds. Press Back to return to the menu.", 14f, Skin.MUTED).apply {
            setPadding(Skin.dp(act, 4), Skin.dp(act, 18), 0, 0)
        })
        addView(col, LayoutParams(-1, -1))
        ram.set("...", 0f, Skin.MUTED, "", "")
        disk.set("...", 0f, Skin.MUTED, "", "")
        temp.set("...", 0.3f, Skin.MUTED, "", "")
    }

    override fun onShow() {
        Bg.cancel(tick)
        tick.run()
    }

    override fun onHide() = Bg.cancel(tick)

    override fun onActivityPause() = Bg.cancel(tick)

    override fun onActivityResume() {
        if (visibility == View.VISIBLE) {
            Bg.cancel(tick)
            tick.run()
        }
    }

    /** Nothing here needs the remote, so the focus stays on the menu. */
    override fun focusFirst() {}

    override fun crumb(): String = "performance"

    private fun refresh() {
        Bg.run {
            val r = read()
            Bg.ui { show(r) }
        }
    }

    private fun read(): Reading {
        val mi = ActivityManager.MemoryInfo()
        (act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        var free = 0L
        var total = 0L
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            total = st.totalBytes
            free = st.availableBytes
        } catch (e: Throwable) {
            Crash.log("Storage reading", e)
        }
        var t: Double? = null
        try { t = readCpuTemp() } catch (e: Throwable) { }
        var word = ""
        if (t == null && Build.VERSION.SDK_INT >= 29) {
            try {
                val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
                word = when (pm.currentThermalStatus) {
                    0 -> "Cool"
                    1 -> "Warm"
                    2 -> "Warm"
                    3 -> "Hot"
                    else -> "Very hot"
                }
            } catch (e: Throwable) { }
        }
        return Reading(mi.totalMem - mi.availMem, mi.totalMem, free, total, t, word)
    }

    /** Looks at the system's thermal sensors. Prefers one named for the processor; otherwise the warmest sensible one. */
    private fun readCpuTemp(): Double? {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        var best: Double? = null
        var bestIsCpu = false
        for (z in zones) {
            val raw = try { File(z, "temp").readText().trim().toDouble() } catch (e: Throwable) { continue }
            val v = if (raw > 1000) raw / 1000.0 else raw
            if (v < 15 || v > 120) continue
            val type = try { File(z, "type").readText().trim().lowercase(Locale.ROOT) } catch (e: Throwable) { "" }
            if (type.contains("battery") || type.contains("usb") || type.contains("charger")) continue
            val isCpu = type.contains("cpu") || type.contains("soc") || type.contains("cluster") || type.contains("core")
            if (best == null || (isCpu && !bestIsCpu) || (isCpu == bestIsCpu && v > best)) {
                best = v
                bestIsCpu = isCpu
            }
        }
        return best
    }

    private fun gb(b: Long): String = String.format(Locale.UK, "%.1f GB", b / 1073741824.0)

    private fun show(r: Reading) {
        if (r.ramTotal > 0) {
            val f = r.ramUsed.toFloat() / r.ramTotal
            val pct = Math.round(f * 100)
            val (col, word) = if (f < 0.70f) Skin.GREEN to "Looking good" else if (f < 0.85f) Skin.AMBER to "Fairly busy" else Skin.RED to "Very busy"
            ram.set("$pct%", f, col, "${gb(r.ramUsed)} used of ${gb(r.ramTotal)}", word)
        }
        if (r.diskTotal > 0) {
            val used = r.diskTotal - r.diskFree
            val f = used.toFloat() / r.diskTotal
            val pct = Math.round(f * 100)
            val (col, word) = if (f < 0.80f) Skin.GREEN to "Plenty of space" else if (f < 0.92f) Skin.AMBER to "Getting full" else Skin.RED to "Nearly full"
            disk.set("$pct%", f, col, "${gb(r.diskFree)} free of ${gb(r.diskTotal)}", word)
        }
        val t = r.tempC
        if (t != null) {
            val (col, word) = if (t < 60) Skin.GREEN to "Nice and cool" else if (t < 75) Skin.AMBER to "Getting warm" else Skin.RED to "Running hot"
            temp.set(Math.round(t).toString() + "°C", ((t - 30) / 60.0).toFloat(), col, "Cool  -  Warm  -  Hot", word)
        } else if (r.tempWord.isNotEmpty()) {
            val f = when (r.tempWord) { "Cool" -> 0.2f; "Warm" -> 0.5f; "Hot" -> 0.8f; else -> 0.95f }
            val col = when (r.tempWord) { "Cool" -> Skin.GREEN; "Warm" -> Skin.AMBER; else -> Skin.RED }
            temp.set(r.tempWord, f, col, "This Fire TV only shares a rough level", "")
        } else {
            temp.set("Not shared", 0f, Skin.MUTED, "This Fire TV does not let apps read its temperature", "")
        }
    }
}
