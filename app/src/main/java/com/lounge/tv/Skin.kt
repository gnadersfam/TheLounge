package com.lounge.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The "Electric" look: neon magenta and cyan on near-black, glowing outlines for whatever the remote is on. */
object Skin {
    const val BG = 0xFF07030F.toInt()
    const val PANEL = 0xFF0E0820.toInt()
    const val RAIL = 0xFF080414.toInt()
    const val CARD = 0xFF1A1030.toInt()
    const val CARD_DIM = 0xFF0F0A1E.toInt()
    const val CARD_FOCUS = 0xFF2A1450.toInt()
    const val LINE = 0xFF3A2468.toInt()
    const val CYAN = 0xFF19F0FF.toInt()
    const val MAGENTA = 0xFFFF3DDB.toInt()
    const val VIOLET = 0xFF8A4DFF.toInt()
    const val TEXT = 0xFFF5EEFF.toInt()
    const val MUTED = 0xFFC9B8F0.toInt()
    const val DIM = 0xFFA593D1.toInt()
    const val AMBER = 0xFFF2B84B.toInt()
    const val GREEN = 0xFF3DDC97.toInt()
    const val RED = 0xFFFF5C7A.toInt()

    /**
     * Sizes are written for a screen 1280 units wide and scale with the real screen width, so the layout looks
     * the same on a 720p or 1080p Fire TV whatever density it reports. (At 1080p one unit is 1.5 pixels.)
     */
    fun dp(c: Context, v: Int): Int = Math.round(v * c.resources.displayMetrics.widthPixels / 1280f)
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.widthPixels / 1280f
    fun setSize(t: TextView, size: Float) = t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dpf(t.context, size))

    private fun box(c: Context, fill: Int, stroke: Int, strokeDp: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(c, strokeDp), stroke)
        }

    private fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    /** Glowing outline: a wide faint magenta ring under a crisp cyan one. */
    fun glow(c: Context, radiusDp: Int = 14, fill: Int = CARD_FOCUS): Drawable {
        val outer = box(c, Color.TRANSPARENT, withAlpha(MAGENTA, 0x66), 7, radiusDp + 3)
        val inner = box(c, fill, CYAN, 3, radiusDp)
        val d = LayerDrawable(arrayOf<Drawable>(outer, inner))
        val g = dp(c, 3)
        d.setLayerInset(1, g, g, g, g)
        return d
    }

    /** Dark card that glows when the remote is on it. */
    fun card(c: Context, radiusDp: Int = 14, rest: Int = CARD, restStroke: Int = LINE): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), glow(c, radiusDp))
            addState(intArrayOf(android.R.attr.state_selected), box(c, CARD_FOCUS, MAGENTA, 2, radiusDp))
            addState(intArrayOf(), box(c, rest, restStroke, 1, radiusDp))
        }

    /** Flat row (no border at rest) that glows when focused. */
    fun row(c: Context, radiusDp: Int = 12): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), glow(c, radiusDp))
            addState(intArrayOf(android.R.attr.state_selected), box(c, CARD, MAGENTA, 2, radiusDp))
            addState(intArrayOf(), box(c, Color.TRANSPARENT, Color.TRANSPARENT, 0, radiusDp))
        }

    fun text(c: Context, s: String, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setSize(this, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun oneLine(t: TextView): TextView = t.apply {
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
    }

    fun button(c: Context, label: String, onClick: () -> Unit): Button =
        Button(c).apply {
            text = label
            isAllCaps = false
            setSize(this, 17f)
            setTextColor(TEXT)
            background = card(c)
            setPadding(dp(c, 24), dp(c, 10), dp(c, 24), dp(c, 10))
            isFocusable = true
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

    /** A focusable one-line choice row (used in lists and menus). */
    fun choice(c: Context, label: String, sub: String = "", onClick: () -> Unit): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = card(c)
            isFocusable = true
            isClickable = true
            setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12))
            addView(oneLine(text(c, label, 17f, TEXT, true)))
            if (sub.isNotEmpty()) addView(oneLine(text(c, sub, 13f, MUTED)))
            setOnClickListener { onClick() }
        }

    fun gapParams(c: Context, w: Int = -1, h: Int = -2, bottomDp: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { bottomMargin = dp(c, bottomDp) }

    fun hhmm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ts * 1000))
    fun dayTime(ts: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(ts * 1000))
    fun dayDate(ts: Long): String = SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(ts * 1000))

    fun center(v: View, w: Int, h: Int): android.widget.FrameLayout.LayoutParams =
        android.widget.FrameLayout.LayoutParams(w, h, Gravity.CENTER)
}
