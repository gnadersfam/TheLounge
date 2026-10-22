package com.lounge.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/** A small centred menu or search box that sits over a page. While it is open the page behind ignores the remote. */
class Popup(private val act: MainActivity, private val host: FrameLayout, private val behind: ViewGroup) {
    private var returnFocus: View? = null

    val shown: Boolean get() = host.visibility == View.VISIBLE

    private fun show(content: View) {
        returnFocus = behind.findFocus()
        host.removeAllViews()
        host.setBackgroundColor(0xDD07030F.toInt())
        host.isClickable = true
        host.addView(content, FrameLayout.LayoutParams(Skin.dp(act, 420), -2, Gravity.CENTER))
        host.visibility = View.VISIBLE
        behind.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    fun hide() {
        if (!shown) return
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(host.windowToken, 0)
        host.visibility = View.GONE
        host.removeAllViews()
        behind.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        returnFocus?.requestFocus()
    }

    private fun card(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = Skin.card(act, 18)
        setPadding(Skin.dp(act, 20), Skin.dp(act, 16), Skin.dp(act, 20), Skin.dp(act, 14))
    }

    fun choices(heading: String, items: List<Pair<String, () -> Unit>>) {
        val c = card()
        c.addView(Skin.oneLine(Skin.text(act, heading, 16f, Skin.CYAN, true)).apply { setPadding(0, 0, 0, Skin.dp(act, 10)) })
        var first: View? = null
        for ((label, action) in items) {
            val v = Skin.choice(act, label) { action() }
            c.addView(v, Skin.gapParams(act, bottomDp = 6))
            if (first == null) first = v
        }
        show(c)
        first?.requestFocus()
    }

    fun search(heading: String, initial: String, onGo: (String) -> Unit) {
        val c = card()
        c.addView(Skin.text(act, heading, 16f, Skin.CYAN, true).apply { setPadding(0, 0, 0, Skin.dp(act, 10)) })
        val et = EditText(act).apply {
            setText(initial)
            setSingleLine()
            Skin.setSize(this, 18f)
            setTextColor(Skin.TEXT)
            setHintTextColor(Skin.DIM)
            hint = "Type here"
            background = Skin.card(act, 12)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(Skin.dp(act, 14), Skin.dp(act, 10), Skin.dp(act, 14), Skin.dp(act, 10))
        }
        fun go(text: String) {
            hide()
            onGo(text.trim())
        }
        et.setOnEditorActionListener { _, _, _ ->
            go(et.text.toString())
            true
        }
        c.addView(et, Skin.gapParams(act, bottomDp = 10))
        c.addView(Skin.button(act, "Search") { go(et.text.toString()) }, Skin.gapParams(act, -1, -2, 6))
        c.addView(Skin.button(act, "Clear search") { go("") }, Skin.gapParams(act, -1, -2, 0))
        show(c)
        et.requestFocus()
    }
}
