package com.lounge.tv

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The category list on the left of the guide. OK picks a category, a long press on OK opens the small menu
 * (handled by the page), and in "move" mode Up/Down slide the category, OK drops it, Back cancels.
 */
class CategoryPanel(ctx: Context) : LinearLayout(ctx) {

    class Entry(val key: String, val label: String, val count: Int, val kind: Int, val dim: Boolean = false)

    companion object {
        const val SEARCH = 0
        const val ALL = 1
        const val CAT = 2
        const val HIDDEN_HEADER = 3
        const val BACK = 4
    }

    var onPick: ((Entry) -> Unit)? = null
    var onLong: ((Entry) -> Unit)? = null

    /** Called when a move is dropped, with the categories in their new order. */
    var onMoved: ((List<String>) -> Unit)? = null

    var moving = false
        private set

    private val scroll = ScrollView(ctx)
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private var entries: List<Entry> = emptyList()
    private var selectedKey = ""
    private var moveKey = ""
    private var moveStartOrder: List<Entry> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Skin.PANEL)
        setPadding(Skin.dp(ctx, 10), Skin.dp(ctx, 16), Skin.dp(ctx, 10), Skin.dp(ctx, 10))
        addView(Skin.text(ctx, "CATEGORIES", 13f, Skin.DIM, true).apply { setPadding(Skin.dp(ctx, 12), 0, 0, Skin.dp(ctx, 8)) })
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = false
        scroll.addView(list, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    fun setEntries(newEntries: List<Entry>, selected: String) {
        entries = newEntries
        selectedKey = selected
        rebuild(null)
    }

    private fun rebuild(focusKey: String?) {
        val hadFocus = hasFocus()
        val keep = focusKey ?: (findFocus()?.tag as? String)
        list.removeAllViews()
        var focusView: View? = null
        for (e in entries) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Skin.row(context)
                isFocusable = true
                isClickable = true
                tag = e.key
                setPadding(Skin.dp(context, 12), Skin.dp(context, 8), Skin.dp(context, 12), Skin.dp(context, 8))
                isSelected = (e.key == selectedKey && e.kind != SEARCH) || (moving && e.key == moveKey)
            }
            val color = when {
                moving && e.key == moveKey -> Skin.MAGENTA
                e.dim -> Skin.DIM
                e.kind == CAT || e.kind == ALL -> Skin.TEXT
                else -> Skin.CYAN
            }
            val label = Skin.oneLine(Skin.text(context, e.label, 16f, color, e.key == selectedKey || (moving && e.key == moveKey)))
            row.addView(label, LayoutParams(0, -2, 1f))
            if (e.count > 0) row.addView(Skin.text(context, e.count.toString(), 13f, Skin.DIM).apply { setPadding(Skin.dp(context, 8), 0, 0, 0) })
            row.setOnClickListener { if (!moving) onPick?.invoke(e) }
            if (e.kind == CAT || e.kind == ALL) {
                row.setOnLongClickListener {
                    if (!moving && e.kind == CAT) onLong?.invoke(e)
                    true
                }
            }
            list.addView(row, LayoutParams(-1, Skin.dp(context, 36)).apply { bottomMargin = Skin.dp(context, 2) })
            if (keep != null && e.key == keep) focusView = row
        }
        if (hadFocus || focusKey != null) (focusView ?: list.getChildAt(0))?.requestFocus()
    }

    fun focusKey(key: String) {
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v.tag == key) {
                v.requestFocus()
                return
            }
        }
        list.getChildAt(0)?.requestFocus()
    }

    fun focusSelected() = focusKey(selectedKey)

    // ------------------------------------------------------------------ move mode
    fun startMove(key: String) {
        moving = true
        moveKey = key
        moveStartOrder = entries
        rebuild(key)
    }

    private fun stopMove() {
        moving = false
    }

    private fun step(dir: Int) {
        val first = entries.indexOfFirst { it.kind == CAT }
        val last = entries.indexOfLast { it.kind == CAT }
        val i = entries.indexOfFirst { it.key == moveKey }
        val j = i + dir
        if (i < 0 || j < first || j > last) return
        val out = entries.toMutableList()
        val x = out.removeAt(i)
        out.add(j, x)
        entries = out
        rebuild(moveKey)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!moving) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 || event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> step(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> step(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    stopMove()
                    val order = entries.filter { it.kind == CAT }.map { it.key }
                    rebuild(moveKey)
                    onMoved?.invoke(order)
                }
                KeyEvent.KEYCODE_BACK -> {
                    stopMove()
                    entries = moveStartOrder
                    rebuild(moveKey)
                }
            }
        }
        return true // while moving, nothing else reacts to the remote
    }
}
