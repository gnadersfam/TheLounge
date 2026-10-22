package com.lounge.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Saves football data in the app's private folder on the Firestick. */
class AndroidPersist(private val c: Context) : Persist {
    override fun load(name: String): Any? = Store.loadJson(c, name)
    override fun save(name: String, v: Any?) = Store.saveJson(c, name, v)
}

/** One column of text in a table row. [w] is its share of the row width. */
class Cell(val text: String, val w: Float, val align: Int = Gravity.START, val color: Int = Skin.TEXT, val bold: Boolean = false)

/** Small building blocks for the football pages: headings, notes and table rows that the remote can scroll through. */
object FootUi {
    fun pct(p: Double) = "%d%%".format(Math.round(p * 100).toInt())

    fun page(c: Context): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Skin.dp(c, 22), Skin.dp(c, 16), Skin.dp(c, 18), Skin.dp(c, 30))
        }
        val sv = ScrollView(c).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(col, LinearLayout.LayoutParams(-1, -2))
        }
        return sv to col
    }

    fun h1(c: Context, s: String): TextView = Skin.text(c, s, 26f, Skin.TEXT, true).apply { setPadding(0, 0, 0, Skin.dp(c, 4)) }
    fun h2(c: Context, s: String): TextView = Skin.text(c, s, 18f, Skin.CYAN, true).apply { setPadding(0, Skin.dp(c, 14), 0, Skin.dp(c, 6)) }
    fun note(c: Context, s: String, color: Int = Skin.MUTED): TextView = Skin.text(c, s, 14f, color).apply { setPadding(0, Skin.dp(c, 2), 0, Skin.dp(c, 4)) }

    fun row(c: Context, cells: List<Cell>, size: Float = 14.5f, focusable: Boolean = true, header: Boolean = false, onClick: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Skin.dp(c, 10), Skin.dp(c, if (header) 4 else 7), Skin.dp(c, 10), Skin.dp(c, if (header) 4 else 7))
            if (!header && (focusable || onClick != null)) {
                background = Skin.row(c)
                isFocusable = true
                isClickable = onClick != null
                if (onClick != null) setOnClickListener { onClick() }
            }
        }
        for (cell in cells) {
            val t = Skin.text(c, cell.text, if (header) size - 1.5f else size, if (header) Skin.DIM else cell.color, cell.bold && !header)
            t.gravity = cell.align or Gravity.CENTER_VERTICAL
            Skin.oneLine(t)
            r.addView(t, LinearLayout.LayoutParams(0, -2, cell.w))
        }
        return r
    }

    fun buttonRow(c: Context, vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Skin.dp(c, 6), 0, Skin.dp(c, 6)) }
        for ((label, act) in items) {
            r.addView(Skin.button(c, label, act), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(c, 10) })
        }
        return r
    }

    val RIGHT = Gravity.END
    val CENTER = Gravity.CENTER_HORIZONTAL
    fun sign(v: Double, d: Int = 1) = (if (v > 0) "+" else "") + "%.${d}f".format(v)
    fun odds(o: DoubleArray?): String = o?.joinToString(" / ") { "%.2f".format(it) } ?: ""
    fun resColor(r: String) = when (r) { "W" -> Skin.GREEN; "D" -> Skin.AMBER; else -> Skin.RED }
}

/** Line chart of a team's xG (attack, solid) and xGA (defence, solid) with dashed season averages. */
class TrendChart(c: Context) : View(c) {
    var title = ""
    var xg: List<Double> = emptyList()
    var xga: List<Double> = emptyList()
    var avgF: Double? = null
    var avgA: Double? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val padL = Skin.dpf(context, 34f); val padB = Skin.dpf(context, 16f); val padT = Skin.dpf(context, 22f)
        p.style = Paint.Style.FILL
        p.color = Skin.MUTED; p.textSize = Skin.dpf(context, 13f)
        canvas.drawText(title, padL, Skin.dpf(context, 14f), p)
        if (xg.isEmpty()) return
        val all = xg + xga + listOfNotNull(avgF, avgA)
        val top = Math.max(2.0, Math.ceil(all.max() * 2) / 2.0).toFloat()
        fun y(v: Double) = padT + (h - padT - padB) * (1f - (v.toFloat() / top))
        fun x(i: Int, n: Int) = padL + (w - padL - Skin.dpf(context, 8f)) * (if (n <= 1) 0.5f else i / (n - 1f))
        p.strokeWidth = 1f; p.color = Skin.LINE
        var g = 0.0
        while (g <= top + 0.001) {
            canvas.drawLine(padL, y(g), w, y(g), p)
            p.color = Skin.DIM; p.textSize = Skin.dpf(context, 11f)
            canvas.drawText("%.1f".format(g), 2f, y(g) + 4f, p)
            p.color = Skin.LINE
            g += 0.5
        }
        fun line(vals: List<Double>, color: Int) {
            val path = Path()
            p.style = Paint.Style.STROKE; p.strokeWidth = Skin.dpf(context, 2.5f); p.color = color; p.pathEffect = null
            vals.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i, vals.size), y(v)) else path.lineTo(x(i, vals.size), y(v)) }
            canvas.drawPath(path, p)
            p.style = Paint.Style.FILL
            vals.forEachIndexed { i, v -> canvas.drawCircle(x(i, vals.size), y(v), Skin.dpf(context, 3.5f), p) }
        }
        fun dashed(v: Double?, color: Int) {
            if (v == null) return
            p.style = Paint.Style.STROKE; p.strokeWidth = Skin.dpf(context, 1.5f); p.color = color
            p.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
            canvas.drawLine(padL, y(v), w, y(v), p)
            p.pathEffect = null; p.style = Paint.Style.FILL
        }
        dashed(avgF, Skin.CYAN); dashed(avgA, Skin.MAGENTA)
        line(xg, Skin.CYAN); line(xga, Skin.MAGENTA)
        p.color = Skin.CYAN; p.textSize = Skin.dpf(context, 12f)
        canvas.drawText("xG (attack)", w - Skin.dpf(context, 200f), Skin.dpf(context, 14f), p)
        p.color = Skin.MAGENTA
        canvas.drawText("xGA (defence)", w - Skin.dpf(context, 110f), Skin.dpf(context, 14f), p)
    }
}
