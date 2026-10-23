package com.lounge.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Round badges: a club's real badge when we have its picture, otherwise its initials in a colour picked from the name;
 * a player's face over his initials (if the picture is missing the initials just stay).
 */
object Crest {
    fun initials(name: String): String {
        val words = name.trim().split(" ", "-").filter { it.isNotEmpty() && it[0].isLetter() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(3).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    fun color(name: String): Int {
        val h = (Math.abs(name.lowercase().hashCode()) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(h, 0.65f, 0.62f))
    }

    private fun circle(c: Context, name: String, sizeDp: Int): TextView =
        TextView(c).apply {
            text = initials(name)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            Skin.setSize(this, sizeDp * 0.38f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(name)) }
        }

    /** A club badge. */
    fun badge(c: Context, name: String, sizeDp: Int): View {
        val url = TeamLogos.url(name)
        if (url.isEmpty()) return circle(c, name, sizeDp)
        val f = FrameLayout(c)
        f.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF2F2F7.toInt()) }
        val iv = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = Skin.dp(c, sizeDp / 8 + 1)
            setPadding(p, p, p, p)
        }
        f.addView(iv, FrameLayout.LayoutParams(-1, -1))
        Img.load(iv, url, 160, 160)
        return f
    }

    /** A player's face. */
    fun face(c: Context, name: String, playerId: String, sizeDp: Int): View {
        val f = FrameLayout(c)
        f.addView(circle(c, name, sizeDp), FrameLayout.LayoutParams(-1, -1))
        val url = TeamLogos.faceUrl(playerId)
        if (url.isNotEmpty()) {
            val iv = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
                }
                clipToOutline = true
            }
            f.addView(iv, FrameLayout.LayoutParams(-1, -1))
            Img.load(iv, url, 160, 160)
        }
        return f
    }
}
