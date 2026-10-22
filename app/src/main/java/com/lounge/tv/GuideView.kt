package com.lounge.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.view.KeyEvent
import android.view.View

/**
 * The TV guide grid: channels down the left, time across the top, programmes as blocks, a cyan "now" line.
 * It draws itself and handles the remote's arrows (Up/Down = channel, Left/Right = programme, OK = watch).
 */
class GuideView(ctx: Context) : View(ctx) {

    var rows: List<Chan> = emptyList()
        set(v) {
            field = v
            selRow = 0
            firstRow = 0
            invalidate()
        }
    var epg: EpgData = EpgData.EMPTY
        set(v) {
            field = v
            invalidate()
        }

    /** (row, channel, programme under the cursor) every time the cursor moves. */
    var onSelect: ((Int, Chan?, EpgProg?) -> Unit)? = null
    var onPlay: ((Int) -> Unit)? = null

    var selRow = 0
        private set
    private var selTime = nowSec()
    private var winStart = floor30(nowSec() - 1200)
    private var firstRow = 0

    private val span = 5400L // 90 minutes on screen
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val progPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 30000)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        labelPaint.typeface = Typeface.DEFAULT_BOLD
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, 30000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    // ------------------------------------------------------------------ helpers
    private fun nowSec() = System.currentTimeMillis() / 1000
    private fun floor30(t: Long) = t - (t % 1800)
    private fun u(v: Float) = Skin.dpf(context, v)

    private val headerH get() = u(26f)
    private val rowH get() = u(40f)
    private val gap get() = u(3f)
    private fun labelW() = width * 0.27f
    private fun visibleRows(): Int = Math.max(1, ((height - headerH) / (rowH + gap)).toInt())

    private fun progAt(row: Int, t: Long): EpgProg? {
        val ch = rows.getOrNull(row) ?: return null
        if (ch.epg.isEmpty()) return null
        return epg.at(ch.epg, t)
    }

    fun selectedChannel(): Chan? = rows.getOrNull(selRow)
    fun selectedProg(): EpgProg? = progAt(selRow, selTime)

    /** Jump to a channel (used when coming back from full screen). */
    fun select(row: Int) {
        if (rows.isEmpty()) return
        selRow = row.coerceIn(0, rows.size - 1)
        selTime = nowSec()
        winStart = floor30(selTime - 1200)
        ensureVisible()
        fireSelect()
        invalidate()
    }

    private fun ensureVisible() {
        val vr = visibleRows()
        if (selRow < firstRow) firstRow = selRow
        if (selRow >= firstRow + vr) firstRow = selRow - vr + 1
        if (firstRow < 0) firstRow = 0
        if (selTime < winStart) winStart = floor30(selTime)
        else if (selTime > winStart + span - 900) winStart = floor30(selTime - 1800)
    }

    private fun fireSelect() {
        onSelect?.invoke(selRow, rows.getOrNull(selRow), progAt(selRow, selTime))
    }

    // ------------------------------------------------------------------ keys
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (rows.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> return moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> return moveRow(1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> return moveRow(-visibleRows())
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> return moveRow(visibleRows())
            KeyEvent.KEYCODE_DPAD_LEFT -> return moveProg(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return moveProg(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event != null && event.repeatCount == 0) onPlay?.invoke(selRow)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveRow(delta: Int): Boolean {
        val n = (selRow + delta).coerceIn(0, rows.size - 1)
        // at the very top/bottom a single press is left to the system; a page jump is simply used up
        if (n == selRow) return Math.abs(delta) > 1
        selRow = n
        ensureVisible()
        fireSelect()
        invalidate()
        return true
    }

    private fun moveProg(dir: Int): Boolean {
        val ch = rows.getOrNull(selRow) ?: return false
        val list = if (ch.epg.isEmpty()) emptyList() else epg.forChannel(ch.epg)
        val now = nowSec()
        val cur = progAt(selRow, selTime)
        if (dir < 0) {
            // Left from the programme that is on now goes back to the category list
            if (cur == null || (cur.start <= now && now < cur.stop)) return false
            val prev = list.lastOrNull { it.start < cur.start } ?: return false
            if (prev.stop < now - 1800) return false
            selTime = prev.start
        } else {
            val nxt = if (cur != null) list.firstOrNull { it.start >= cur.stop } else list.firstOrNull { it.start > selTime }
            if (nxt == null) return true
            selTime = nxt.start
        }
        ensureVisible()
        fireSelect()
        invalidate()
        return true
    }

    // ------------------------------------------------------------------ drawing
    private fun chipColors(name: String): IntArray {
        val pal = intArrayOf(
            0xFFFF3DDB.toInt(), 0xFF8A4DFF.toInt(), 0xFF19F0FF.toInt(), 0xFF2A6BFF.toInt(),
            0xFFFF8A3D.toInt(), 0xFF3DDC97.toInt()
        )
        val h = Math.abs(name.hashCode())
        return intArrayOf(pal[h % pal.size], pal[(h / 7 + 1) % pal.size])
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val lw = labelW()
        val tlW = w - lw
        val pps = tlW / span
        val winEnd = winStart + span
        val now = nowSec()
        val focused = hasFocus()

        // ---- header with the time labels
        timePaint.color = Skin.MUTED
        timePaint.textSize = u(13f)
        labelPaint.textSize = u(15f)
        labelPaint.color = Skin.CYAN
        canvas.drawText(Skin.dayDate(now) + ", " + Skin.hhmm(now), 0f, headerH * 0.75f, labelPaint)
        var t = winStart
        while (t <= winEnd) {
            val x = lw + (t - winStart) * pps
            canvas.drawText(Skin.hhmm(t), x + u(2f), headerH * 0.72f, timePaint)
            t += 1800
        }
        fill.shader = null
        fill.color = Skin.LINE
        canvas.drawRect(0f, headerH - u(1f), w, headerH, fill)

        // ---- rows
        val vr = visibleRows()
        var y = headerH + u(4f)
        var i = firstRow
        while (i < rows.size && i < firstRow + vr + 1 && y < h) {
            drawRow(canvas, i, y, lw, pps, winEnd, now, focused)
            y += rowH + gap
            i++
        }

        // ---- the "now" line
        if (now in winStart..winEnd) {
            val x = lw + (now - winStart) * pps
            fill.shader = null
            fill.color = Skin.CYAN
            canvas.drawRect(x - u(1f), headerH, x + u(1f), h, fill)
        }
    }

    private fun drawRow(canvas: Canvas, i: Int, top: Float, lw: Float, pps: Float, winEnd: Long, now: Long, focused: Boolean) {
        val ch = rows[i]
        val sel = i == selRow
        val bottom = top + rowH

        // channel number, chip and name
        labelPaint.textSize = u(14f)
        labelPaint.color = if (sel) Skin.CYAN else Skin.DIM
        val num = (i + 1).toString()
        canvas.drawText(num, u(26f) - labelPaint.measureText(num), top + rowH * 0.64f, labelPaint)
        val cc = chipColors(ch.name)
        val chipL = u(32f)
        val chipW = u(34f)
        fill.shader = LinearGradient(chipL, top, chipL + chipW, bottom, cc[0], cc[1], Shader.TileMode.CLAMP)
        rect.set(chipL, top + rowH * 0.2f, chipL + chipW, bottom - rowH * 0.2f)
        canvas.drawRoundRect(rect, u(5f), u(5f), fill)
        fill.shader = null
        labelPaint.textSize = u(16f)
        labelPaint.color = if (sel) Skin.CYAN else Skin.TEXT
        val nameX = chipL + chipW + u(10f)
        val nm = TextUtils.ellipsize(ch.name, labelPaint, lw - nameX - u(6f), TextUtils.TruncateAt.END)
        canvas.drawText(nm.toString(), nameX, top + rowH * 0.66f, labelPaint)

        // programme blocks
        val list = if (ch.epg.isEmpty()) emptyList() else epg.forChannel(ch.epg)
        val tlLeft = lw
        val tlRight = lw + (winEnd - winStart) * pps
        progPaint.textSize = u(15f)
        var any = false
        for (p in list) {
            if (p.stop <= winStart || p.start >= winEnd) continue
            any = true
            val x1 = lw + (Math.max(p.start, winStart) - winStart) * pps
            val x2 = lw + (Math.min(p.stop, winEnd) - winStart) * pps - u(3f)
            if (x2 - x1 < u(4f)) continue
            rect.set(x1, top, x2, bottom)
            val isSel = sel && selTime >= p.start && selTime < p.stop
            drawBlock(canvas, rect, p.title, isSel, focused, p.start <= now && now < p.stop)
        }
        if (!any) {
            rect.set(tlLeft, top, tlRight - u(3f), bottom)
            val isSel = sel
            drawBlock(canvas, rect, "No information", isSel, focused, false, dim = true)
        }
    }

    private fun drawBlock(canvas: Canvas, r: RectF, title: String, selected: Boolean, focused: Boolean, airing: Boolean, dim: Boolean = false) {
        val rad = u(8f)
        if (selected && focused) {
            // soft glow: two wider, fainter outlines, then a bright fill
            stroke.strokeWidth = u(6f)
            stroke.color = (Skin.MAGENTA and 0x00FFFFFF) or (0x44 shl 24)
            rect.inset(-u(3f), -u(3f))
            canvas.drawRoundRect(rect, rad + u(3f), rad + u(3f), stroke)
            rect.inset(u(3f), u(3f))
            fill.shader = LinearGradient(r.left, 0f, Math.max(r.right, r.left + 1f), 0f, Skin.MAGENTA, Skin.VIOLET, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, rad, rad, fill)
            fill.shader = null
            progPaint.color = 0xFF12002B.toInt()
            progPaint.typeface = Typeface.DEFAULT_BOLD
        } else if (selected) {
            fill.color = Skin.CARD_FOCUS
            canvas.drawRoundRect(r, rad, rad, fill)
            stroke.strokeWidth = u(2f)
            stroke.color = Skin.MAGENTA
            canvas.drawRoundRect(r, rad, rad, stroke)
            progPaint.color = Skin.TEXT
            progPaint.typeface = Typeface.DEFAULT_BOLD
        } else if (dim) {
            fill.color = Skin.CARD_DIM
            canvas.drawRoundRect(r, rad, rad, fill)
            progPaint.color = Skin.DIM
            progPaint.typeface = Typeface.DEFAULT
        } else {
            fill.color = Skin.CARD
            canvas.drawRoundRect(r, rad, rad, fill)
            stroke.strokeWidth = u(1f)
            stroke.color = if (airing) 0xFF5A3A98.toInt() else Skin.LINE
            canvas.drawRoundRect(r, rad, rad, stroke)
            progPaint.color = Skin.TEXT
            progPaint.typeface = Typeface.DEFAULT
        }
        canvas.save()
        canvas.clipRect(r)
        canvas.drawText(title, r.left + u(10f), r.top + r.height() * 0.66f, progPaint)
        canvas.restore()
    }
}
