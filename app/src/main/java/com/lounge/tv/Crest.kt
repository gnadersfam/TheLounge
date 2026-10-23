package com.lounge.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/** A round badge with a team's initials in a colour picked from the name (same name = same colour). */
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

    fun badge(c: Context, name: String, sizeDp: Int): TextView =
        TextView(c).apply {
            text = initials(name)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            Skin.setSize(this, sizeDp * 0.38f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(name)) }
        }
}
