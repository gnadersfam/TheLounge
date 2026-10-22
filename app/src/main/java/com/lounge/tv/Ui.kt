package com.lounge.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small helpers so the screens are built the same way (big text, clear focus outline for the remote). */
object Ui {
    const val BG = "#0A0E1A"
    const val TEXT = "#EAF0FF"
    const val MUTED = "#B4C0E0"
    const val ACCENT = "#5CE1E6"

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    private fun shape(c: Context, fill: String, stroke: String, strokeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(c, 14).toFloat()
            setStroke(dp(c, strokeDp), Color.parseColor(stroke))
        }

    /** Dark card that gets a bright white outline when the remote focuses it. */
    fun cardBg(c: Context): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(c, "#1B2748", "#FFFFFF", 3))
        addState(intArrayOf(), shape(c, "#131A2E", "#243052", 1))
    }

    fun text(c: Context, s: String, sp: Float, color: String, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = sp
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(c: Context, label: String, onClick: () -> Unit): Button =
        Button(c).apply {
            text = label
            setAllCaps(false)
            textSize = 20f
            setTextColor(Color.parseColor(TEXT))
            background = cardBg(c)
            setPadding(dp(c, 24), dp(c, 10), dp(c, 24), dp(c, 10))
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ts * 1000))

    /** "Now: X (13:00-14:00)   Next: 14:00 Y" from a short guide, plus how far through "now" we are (0-100). */
    fun nowNext(progs: List<Prog>): Pair<String, Int> {
        val now = System.currentTimeMillis() / 1000
        val cur = progs.firstOrNull { it.start <= now && now < it.stop }
        val next = progs.firstOrNull { it.start > now }
        val sb = StringBuilder()
        var pct = 0
        if (cur != null) {
            sb.append("Now: ").append(cur.title).append("  (").append(hhmm(cur.start)).append("-").append(hhmm(cur.stop)).append(")")
            val len = (cur.stop - cur.start).coerceAtLeast(1)
            pct = (((now - cur.start) * 100) / len).toInt().coerceIn(0, 100)
        } else {
            sb.append("Nothing listed right now")
        }
        if (next != null) sb.append("     Next: ").append(hhmm(next.start)).append("  ").append(next.title)
        return Pair(sb.toString(), pct)
    }
}
