package com.lounge.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** A thin glowing progress bar (0-100). */
class BarView(ctx: Context) : View(ctx) {
    var pct: Int = 0
        set(v) {
            field = v.coerceIn(0, 100)
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        paint.shader = null
        paint.color = 0xFF2A1A4A.toInt()
        r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        if (pct > 0) {
            r.set(0f, 0f, Math.max(h, w * pct / 100f), h)
            paint.shader = LinearGradient(0f, 0f, w, 0f, Skin.CYAN, Skin.MAGENTA, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, h / 2, h / 2, paint)
        }
    }
}
