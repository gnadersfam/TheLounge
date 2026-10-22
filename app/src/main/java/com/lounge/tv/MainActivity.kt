package com.lounge.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException

class MainActivity : Activity() {

    private lateinit var loginView: LinearLayout
    private lateinit var listView: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var countText: TextView
    private lateinit var nowText: TextView
    private lateinit var nowBar: ProgressBar
    private lateinit var serverEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var loginMsg: TextView

    private val adapter = ChannelAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var all: List<Chan> = emptyList()
    private var query = ""
    private var epgToken = 0
    private var epgJob: Runnable? = null
    private var playedFromList = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor(Ui.BG)))
        val root = FrameLayout(this)
        setContentView(root)
        buildLogin()
        buildList()
        root.addView(listView, FrameLayout.LayoutParams(-1, -1))
        root.addView(loginView, FrameLayout.LayoutParams(-1, -1))
        if (Store.hasLogin(this)) {
            showList()
            loadChannels(false)
        } else {
            showLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (playedFromList && listView.visibility == View.VISIBLE) {
            playedFromList = false
            val i = Session.index
            (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Ui.dp(this, 120))
            recycler.postDelayed({ recycler.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }, 150)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (loginView.visibility == View.VISIBLE && Store.hasLogin(this)) {
            showList()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ login screen
    private fun field(hint: String, type: Int, value: String): EditText = EditText(this).apply {
        setHint(hint)
        setText(value)
        inputType = type
        setSingleLine()
        textSize = 22f
        setTextColor(Color.parseColor(Ui.TEXT))
        setHintTextColor(Color.parseColor("#7C89AD"))
        background = Ui.cardBg(this@MainActivity)
        setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 14))
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }

    private fun buildLogin() {
        loginView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@MainActivity, 48), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 48), Ui.dp(this@MainActivity, 24))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, "The Lounge", 34f, Ui.ACCENT, true))
        col.addView(Ui.text(this, "Sign in to your IPTV service", 22f, Ui.TEXT).apply { setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 4)) })
        col.addView(Ui.text(this, "Tip: typing is much easier with the Fire TV phone app's keyboard.", 17f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(this@MainActivity, 18)) })
        serverEdit = field("Server  (e.g. http://example.com:8080)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, Store.server(this))
        userEdit = field("Username", InputType.TYPE_CLASS_TEXT, Store.user(this))
        passEdit = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, Store.pass(this))
        val gap = Ui.dp(this, 14)
        for (e in listOf(serverEdit, userEdit, passEdit)) {
            col.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        }
        loginMsg = Ui.text(this, "", 18f, "#F2B84B")
        col.addView(loginMsg)
        val save = Ui.button(this, "Save and load channels") {
            val s = serverEdit.text.toString()
            val u = userEdit.text.toString()
            val p = passEdit.text.toString()
            if (s.isBlank() || u.isBlank() || p.isBlank()) {
                loginMsg.text = "Please fill in all three boxes."
            } else {
                loginMsg.text = ""
                Store.saveLogin(this, s, u, p)
                all = emptyList()
                applyFilter()
                showList()
                loadChannels(true)
            }
        }
        col.addView(save, LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap })
        loginView.addView(col, LinearLayout.LayoutParams(Ui.dp(this, 700), -2))
    }

    private fun showLogin() {
        listView.visibility = View.GONE
        loginView.visibility = View.VISIBLE
        serverEdit.requestFocus()
    }

    private fun showList() {
        loginView.visibility = View.GONE
        listView.visibility = View.VISIBLE
        recycler.requestFocus()
    }

    // ------------------------------------------------------------------ channel list screen
    private fun buildList() {
        listView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 48), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 48), Ui.dp(this@MainActivity, 20))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(this, "Live TV", 34f, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        countText = Ui.text(this, "", 18f, Ui.MUTED)
        header.addView(countText)
        val m = Ui.dp(this, 12)
        val searchBtn = Ui.button(this, "Search") { showSearch() }
        val refreshBtn = Ui.button(this, "Refresh") { loadChannels(true) }
        val signBtn = Ui.button(this, "Sign in") { showLogin() }
        for (b: Button in listOf(searchBtn, refreshBtn, signBtn)) {
            header.addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = m })
        }
        listView.addView(header)

        status = Ui.text(this, "", 20f, "#F2B84B").apply {
            visibility = View.GONE
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
        }
        listView.addView(status)

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 10))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        listView.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        nowText = Ui.text(this, "", 20f, Ui.TEXT)
        listView.addView(nowText)
        nowBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        listView.addView(nowBar, LinearLayout.LayoutParams(-1, Ui.dp(this, 8)).apply { topMargin = Ui.dp(this@MainActivity, 8) })
    }

    private fun showSearch() {
        val et = EditText(this).apply {
            setText(query)
            setSingleLine()
            textSize = 22f
        }
        AlertDialog.Builder(this)
            .setTitle("Search channels")
            .setView(et)
            .setPositiveButton("Search") { _, _ -> query = et.text.toString().trim(); applyFilter() }
            .setNegativeButton("Clear") { _, _ -> query = ""; applyFilter() }
            .show()
    }

    private fun loadChannels(force: Boolean) {
        val xt = Store.xtream(this)
        if (xt == null) {
            showLogin()
            return
        }
        Session.xt = xt
        val cached = Store.loadChannels(this)
        if (cached != null && all.isEmpty()) {
            all = cached.second
            applyFilter()
        }
        val ageSeconds = if (cached == null) Long.MAX_VALUE else System.currentTimeMillis() / 1000 - cached.first
        if (!force && cached != null && ageSeconds < 12 * 3600) return

        status.text = if (cached == null) "Loading channels... this can take up to a minute the first time." else "Updating channels..."
        status.visibility = View.VISIBLE
        Thread {
            try {
                if (!xt.login()) throw IOException("Login was rejected. Check the server, username and password.")
                val cats = xt.categories()
                val tidy = Filter.tidy(xt.englishChannels(cats))
                if (tidy.isEmpty()) throw IOException("No English channels were found in the list.")
                Store.saveChannels(this, tidy)
                runOnUiThread {
                    if (!isFinishing) {
                        all = tidy
                        applyFilter()
                        status.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                val msg = xt.redact(e.message ?: "unknown error")
                runOnUiThread {
                    if (!isFinishing) {
                        status.text = "Couldn't load channels: $msg  (choose Refresh to try again)"
                        status.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    private fun applyFilter() {
        val items = if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        adapter.items = items
        adapter.notifyDataSetChanged()
        countText.text = if (items.isEmpty() && all.isEmpty()) "" else "${items.size} channels"
        if (items.isNotEmpty() && listView.visibility == View.VISIBLE && !recycler.hasFocus()) {
            recycler.postDelayed({ recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }, 200)
        }
    }

    private fun play(pos: Int) {
        if (pos == RecyclerView.NO_POSITION) return
        Session.channels = adapter.items
        Session.index = pos
        playedFromList = true
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun onFocused(pos: Int) {
        val ch = adapter.items.getOrNull(pos) ?: return
        epgJob?.let { handler.removeCallbacks(it) }
        nowText.text = ch.name
        nowBar.progress = 0
        val job = Runnable { fetchEpg(ch) }
        epgJob = job
        handler.postDelayed(job, 350)
    }

    private fun fetchEpg(ch: Chan) {
        val xt = Session.xt ?: return
        val token = ++epgToken
        Thread {
            val progs = try { xt.shortEpg(ch.id) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (token == epgToken && !isFinishing) {
                    val (line, pct) = Ui.nowNext(progs)
                    nowText.text = "${ch.name}   -   $line"
                    nowBar.progress = pct
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ list adapter
    private class VH(val row: LinearLayout, val tag: TextView, val name: TextView, val sub: TextView) :
        RecyclerView.ViewHolder(row)

    private inner class ChannelAdapter : RecyclerView.Adapter<VH>() {
        var items: List<Chan> = emptyList()

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.cardBg(c)
                isFocusable = true
                isClickable = true
                setPadding(Ui.dp(c, 28), Ui.dp(c, 16), Ui.dp(c, 28), Ui.dp(c, 16))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(c, 10) }
            }
            val tag = Ui.text(c, "", 20f, Ui.ACCENT, true)
            row.addView(tag, LinearLayout.LayoutParams(Ui.dp(c, 64), -2))
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val name = Ui.text(c, "", 26f, Ui.TEXT, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
            val sub = Ui.text(c, "", 17f, Ui.MUTED).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
            col.addView(name)
            col.addView(sub)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            return VH(row, tag, name, sub)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = items[position]
            h.tag.text = ch.tag
            h.name.text = ch.name
            h.sub.text = ch.cat
            h.row.setOnClickListener { play(h.bindingAdapterPosition) }
            h.row.setOnFocusChangeListener { _, focused ->
                if (focused) onFocused(h.bindingAdapterPosition)
            }
        }
    }
}
