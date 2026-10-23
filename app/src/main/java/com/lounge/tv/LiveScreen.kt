package com.lounge.tv

import android.content.Context
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.IOException

/**
 * Live TV: category panel on the left, preview and programme details on top, the TV guide grid underneath.
 * Long-press OK on a category for Move / Hide. Your order and hidden list are saved on the Firestick.
 */
class LiveScreen(act: MainActivity) : Screen(act), MenuKeyHandler {

    private companion object {
        const val ALL_KEY = "*"
        const val SEARCH_KEY = "?search"
        const val HIDDEN_KEY = "?hidden"
        const val BACK_KEY = "?back"
        const val TWELVE_HOURS = 12 * 3600L
    }

    private val panel = CategoryPanel(act)
    private val guide = GuideView(act)
    private val surface = SurfaceView(act)
    private val hint = Skin.text(act, "Live preview", 14f, Skin.MUTED)
    private val title = Skin.oneLine(Skin.text(act, "", 28f, Skin.TEXT, true))
    private val timeText = Skin.text(act, "", 16f, Skin.TEXT)
    private val leftText = Skin.text(act, "", 16f, Skin.MUTED)
    private val bar = BarView(act)
    private val desc = Skin.text(act, "", 16f, Skin.MUTED)
    private val catLabel = Skin.oneLine(Skin.text(act, "", 15f, Skin.CYAN, true))
    private val status = Skin.oneLine(Skin.text(act, "", 14f, Skin.AMBER))
    private val root = LinearLayout(act)
    private val overlay = FrameLayout(act)

    private var all: List<Chan> = emptyList()
    private var byCat: Map<String, List<Chan>> = emptyMap()
    private var defaultOrder: List<String> = emptyList()
    private var fullOrder: List<String> = emptyList()
    private var hidden = HashSet<String>()
    private var showHidden = false
    private var currentKey = ALL_KEY
    private var query = ""
    private var epg: EpgData = EpgData.EMPTY
    private var chanBuilt = 0L
    private var loading = false
    private var loadedLocal = false

    private var player: ExoPlayer? = null
    private val previewJob = Runnable { startPreview() }
    private val saveLastJob = Runnable { saveLast() }
    private var overlayReturnFocus: View? = null

    init {
        root.orientation = LinearLayout.HORIZONTAL
        root.addView(panel, LinearLayout.LayoutParams(Skin.dp(act, 270), -1))

        val main = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Skin.dp(act, 22), Skin.dp(act, 18), Skin.dp(act, 18), Skin.dp(act, 8))
        }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val previewBox = FrameLayout(act).apply { setBackgroundColor(0xFF120A22.toInt()) }
        previewBox.addView(surface, FrameLayout.LayoutParams(-1, -1))
        previewBox.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        top.addView(previewBox, LinearLayout.LayoutParams(Skin.dp(act, 300), Skin.dp(act, 169)))

        val details = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Skin.dp(act, 20), 0, 0, 0)
        }
        details.addView(title)
        val timeRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Skin.dp(act, 6), 0, Skin.dp(act, 6))
        }
        timeRow.addView(timeText)
        timeRow.addView(bar, LinearLayout.LayoutParams(Skin.dp(act, 110), Skin.dp(act, 6)).apply { leftMargin = Skin.dp(act, 12); rightMargin = Skin.dp(act, 12) })
        timeRow.addView(leftText)
        details.addView(timeRow)
        desc.maxLines = 4
        desc.ellipsize = android.text.TextUtils.TruncateAt.END
        details.addView(desc, LinearLayout.LayoutParams(-1, 0, 1f))
        details.addView(catLabel)
        top.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        main.addView(top, LinearLayout.LayoutParams(-1, Skin.dp(act, 169)))
        main.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Skin.dp(act, 4) })
        main.addView(guide, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Skin.dp(act, 6) })
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        addView(root, LayoutParams(-1, -1))

        overlay.visibility = View.GONE
        overlay.setBackgroundColor(0xDD07030F.toInt())
        overlay.isClickable = true
        addView(overlay, LayoutParams(-1, -1))

        panel.onPick = { e -> picked(e) }
        panel.onLong = { e -> showCategoryMenu(e) }
        panel.onMoved = { order -> moved(order) }
        guide.onSelect = { _, ch, prog -> selected(ch, prog) }
        guide.onPlay = { row -> play(row) }
        showDetails(null, null)
    }

    // ------------------------------------------------------------------ page life
    override fun onShow() {
        if (!Store.hasLogin(act)) {
            act.open("pair")
            return
        }
        if (!loadedLocal) loadLocal()
        refresh(false)
        schedulePreview()
    }

    override fun onHide() {
        releasePreview()
        hideOverlay()
    }

    override fun onActivityPause() {
        releasePreview()
    }

    override fun onActivityResume() {
        if (visibility != View.VISIBLE) return
        if (Session.channels === guide.rows && Session.channels.isNotEmpty()) guide.select(Session.index)
        schedulePreview()
    }

    override fun onKeysChanged() {
        loadedLocal = false
        all = emptyList()
        epg = EpgData.EMPTY
        chanBuilt = 0L
        if (visibility == View.VISIBLE) onShow()
    }

    override fun focusFirst() {
        if (guide.rows.isNotEmpty()) guide.requestFocus() else panel.focusSelected()
    }

    override fun onBack(): Boolean {
        if (overlay.visibility == View.VISIBLE) {
            hideOverlay()
            return true
        }
        if (panel.moving) return true
        if (showHidden) {
            showHidden = false
            refreshPanel()
            return true
        }
        return false
    }

    override fun onMenuKey(): Boolean {
        showMainMenu()
        return true
    }

    // ------------------------------------------------------------------ loading
    private fun loadLocal() {
        loadedLocal = true
        val cached = Store.loadChannels(act)
        if (cached != null) {
            chanBuilt = cached.first
            applyChannels(cached.second)
        }
        Bg.run {
            val d = EpgData.fromJson(Store.loadJson(act, "live_epg"))
            Bg.ui {
                epg = d
                Session.epg = d
                guide.epg = d
            }
        }
    }

    private fun applyChannels(list: List<Chan>) {
        all = list.map { if (it.cat.isEmpty()) it.copy(cat = "Other") else it }
        byCat = all.groupBy { it.cat }
        defaultOrder = Filter.categoryOrder(all)
        val cfg = Store.loadJson(act, "cat_cfg").obj()
        fullOrder = Cats.fullOrder(defaultOrder, cfg["order"].arr().map { it.str() })
        hidden = HashSet(cfg["hidden"].arr().map { it.str() })
        val last = Store.str(act, "last_cat", ALL_KEY)
        currentKey = if (last == ALL_KEY || byCat.containsKey(last)) last else ALL_KEY
        if (currentKey in hidden) currentKey = ALL_KEY
        refreshPanel()
        selectKey(currentKey, true)
    }

    private fun refresh(force: Boolean) {
        if (loading) return
        loading = true
        Bg.run {
            try {
                val xt = Store.xtream(act) ?: throw IOException("Please pair your IPTV login first.")
                Session.xt = xt
                val now = System.currentTimeMillis() / 1000
                var chans = all
                if (force || all.isEmpty() || now - chanBuilt > TWELVE_HOURS) {
                    Bg.ui { setStatus(if (all.isEmpty()) "Loading channels... this can take up to a minute the first time." else "Updating channels...") }
                    val info = xt.login()
                    Store.putStr(act, "acct_exp", info["exp_date"].str())
                    Store.putStr(act, "acct_status", info["status"].str())
                    Store.putStr(act, "acct_conn", info["max_connections"].str())
                    val cats = xt.categories()
                    val tidy = Filter.tidy(xt.englishChannels(cats))
                    if (tidy.isEmpty()) throw IOException("No English channels were found in the list.")
                    Store.saveChannels(act, tidy)
                    chanBuilt = now
                    Bg.ui { applyChannels(tidy) }
                    chans = tidy
                }
                val wanted = chans.map { it.epg }.filter { it.isNotEmpty() }.toHashSet()
                if (wanted.isNotEmpty() && (force || epg.built == 0L || now - epg.built > TWELVE_HOURS)) {
                    Bg.ui { setStatus("Updating TV guide...") }
                    val (stream, close) = xt.openGuide()
                    try {
                        val data = Epg.read(stream, wanted, now) { n -> Bg.ui { setStatus("Reading TV guide... ($n programmes)") } }
                        Store.saveJson(act, "live_epg", data.toJson())
                        Bg.ui {
                            epg = data
                            Session.epg = data
                            guide.epg = data
                            selected(guide.selectedChannel(), guide.selectedProg())
                        }
                    } finally {
                        close()
                    }
                }
                Bg.ui { setStatus("") }
            } catch (e: Exception) {
                val xt = Session.xt
                val msg = xt?.redact(e.message ?: "unknown error") ?: (e.message ?: "unknown error")
                Bg.ui { setStatus("Couldn't update: $msg  (press Menu, then Refresh, to try again)") }
            } finally {
                loading = false
            }
        }
    }

    private fun setStatus(t: String) {
        status.text = t
        status.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ categories
    private fun channelsFor(key: String): List<Chan> = when {
        key == ALL_KEY -> all
        key.startsWith("?q:") -> {
            val q = key.substring(3)
            all.filter { it.name.contains(q, ignoreCase = true) }
        }
        else -> byCat[key] ?: emptyList()
    }

    private fun refreshPanel() {
        val e = ArrayList<CategoryPanel.Entry>()
        e.add(CategoryPanel.Entry(SEARCH_KEY, "Search channels...", 0, CategoryPanel.SEARCH))
        if (showHidden) {
            e.add(CategoryPanel.Entry(BACK_KEY, "< Back to categories", 0, CategoryPanel.BACK))
            for (c in fullOrder) if (c in hidden) e.add(CategoryPanel.Entry(c, c, byCat[c]?.size ?: 0, CategoryPanel.CAT, true))
        } else {
            e.add(CategoryPanel.Entry(ALL_KEY, "All channels", all.size, CategoryPanel.ALL))
            for (c in Cats.visible(fullOrder, hidden)) e.add(CategoryPanel.Entry(c, c, byCat[c]?.size ?: 0, CategoryPanel.CAT))
            if (hidden.isNotEmpty()) e.add(CategoryPanel.Entry(HIDDEN_KEY, "Hidden (${hidden.size})", 0, CategoryPanel.HIDDEN_HEADER, true))
        }
        panel.setEntries(e, currentKey)
    }

    private fun selectKey(key: String, restoreChannel: Boolean) {
        currentKey = key
        if (!key.startsWith("?")) Store.putStr(act, "last_cat", key)
        val rows = channelsFor(key)
        guide.rows = rows
        catLabelFor(key)
        if (restoreChannel && rows.isNotEmpty()) {
            val last = Store.str(act, "last_chan", "")
            val idx = rows.indexOfFirst { it.id == last }
            guide.select(if (idx >= 0) idx else 0)
        } else if (rows.isNotEmpty()) {
            guide.select(0)
        } else {
            showDetails(null, null)
        }
    }

    private fun catLabelFor(key: String) {
        catLabel.text = when {
            key == ALL_KEY -> "ALL CHANNELS"
            key.startsWith("?q:") -> "SEARCH: " + key.substring(3).uppercase()
            else -> key
        }
    }

    private fun picked(e: CategoryPanel.Entry) {
        when (e.kind) {
            CategoryPanel.SEARCH -> showSearch()
            CategoryPanel.BACK -> {
                showHidden = false
                refreshPanel()
                panel.focusKey(HIDDEN_KEY)
            }
            CategoryPanel.HIDDEN_HEADER -> {
                showHidden = true
                refreshPanel()
                panel.focusKey(BACK_KEY)
            }
            else -> {
                selectKey(e.key, false)
                refreshPanel()
                guide.requestFocus()
            }
        }
    }

    private fun showCategoryMenu(e: CategoryPanel.Entry) {
        val isHidden = e.key in hidden
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (!isHidden) {
            choices.add(Pair("Move") {
                hideOverlay()
                panel.startMove(e.key)
            })
            choices.add(Pair("Hide") {
                hidden.add(e.key)
                saveConfig()
                if (currentKey == e.key) selectKey(ALL_KEY, false)
                hideOverlay()
                refreshPanel()
            })
        } else {
            choices.add(Pair("Show again") {
                hidden.remove(e.key)
                saveConfig()
                if (hidden.isEmpty()) showHidden = false
                hideOverlay()
                refreshPanel()
            })
        }
        choices.add(Pair("Reset order") {
            fullOrder = defaultOrder
            saveConfig()
            hideOverlay()
            refreshPanel()
        })
        choices.add(Pair("Cancel") { hideOverlay() })
        showChoices(e.label, choices)
    }

    private fun moved(newVisible: List<String>) {
        val it = newVisible.iterator()
        val out = ArrayList<String>()
        for (c in fullOrder) {
            if (c in hidden || !it.hasNext()) out.add(c) else out.add(it.next())
        }
        fullOrder = out
        saveConfig()
        refreshPanel()
    }

    private fun saveConfig() {
        Store.saveJson(act, "cat_cfg", mapOf("order" to fullOrder, "hidden" to hidden.toList()))
    }

    /** Settings uses this to put the categories back in the provider's order. */
    fun resetOrder() {
        fullOrder = defaultOrder
        saveConfig()
        refreshPanel()
    }

    // ------------------------------------------------------------------ guide events
    private fun selected(ch: Chan?, prog: EpgProg?) {
        showDetails(ch, prog)
        if (ch != null) {
            Bg.cancel(saveLastJob)
            Bg.uiDelayed(1500, saveLastJob)
        }
        schedulePreview()
    }

    private fun saveLast() {
        guide.selectedChannel()?.let { Store.putStr(act, "last_chan", it.id) }
    }

    private fun showDetails(ch: Chan?, prog: EpgProg?) {
        if (ch == null) {
            title.text = if (all.isEmpty()) "The Lounge" else "No channels here"
            timeText.text = ""
            leftText.text = ""
            bar.pct = 0
            desc.text = ""
            return
        }
        if (prog == null) {
            title.text = ch.name
            timeText.text = ""
            leftText.text = ""
            bar.pct = 0
            desc.text = if (ch.epg.isEmpty()) "No guide information for this channel." else "No programme information right now."
            return
        }
        val now = System.currentTimeMillis() / 1000
        title.text = prog.title
        timeText.text = Skin.hhmm(prog.start) + " - " + Skin.hhmm(prog.stop)
        val len = Math.max(1L, prog.stop - prog.start)
        if (prog.start <= now && now < prog.stop) {
            bar.pct = (((now - prog.start) * 100) / len).toInt()
            leftText.text = ((prog.stop - now + 59) / 60).toString() + " min left"
        } else {
            bar.pct = 0
            leftText.text = if (prog.start > now) "starts in " + ((prog.start - now + 59) / 60) + " min" else "finished"
        }
        desc.text = prog.desc.ifEmpty { ch.name }
    }

    private fun play(row: Int) {
        releasePreview()
        saveLast()
        act.playLive(guide.rows, row)
    }

    // ------------------------------------------------------------------ preview
    private fun schedulePreview() {
        Bg.cancel(previewJob)
        if (!Store.bool(act, "preview", true) || visibility != View.VISIBLE) return
        player?.stop()
        hint.text = "Live preview"
        hint.visibility = View.VISIBLE
        Bg.uiDelayed(1500, previewJob)
    }

    private fun startPreview() {
        if (visibility != View.VISIBLE || !Store.bool(act, "preview", true)) return
        val ch = guide.selectedChannel() ?: return
        val xt = Session.xt ?: Store.xtream(act) ?: return
        var p = player
        if (p == null) {
            val factory = DefaultHttpDataSource.Factory()
                .setUserAgent(Xtream.STREAM_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
            p = ExoPlayer.Builder(act).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
            p.setVideoSurfaceView(surface)
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    hint.visibility = View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    hint.text = "Preview not available"
                    hint.visibility = View.VISIBLE
                }
            })
            player = p
        }
        p.volume = if (Store.bool(act, "preview_sound", true)) 1f else 0f
        p.setMediaItem(MediaItem.fromUri(xt.streamUrl(ch.id, Store.ext(act))))
        p.prepare()
        p.playWhenReady = true
    }

    private fun releasePreview() {
        Bg.cancel(previewJob)
        player?.release()
        player = null
        hint.text = "Live preview"
        hint.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ overlays (menus and search)
    private fun showOverlay(content: View) {
        overlayReturnFocus = findFocus()
        overlay.removeAllViews()
        overlay.addView(content, FrameLayout.LayoutParams(Skin.dp(act, 420), -2, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun hideOverlay() {
        if (overlay.visibility != View.VISIBLE) return
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlayReturnFocus?.requestFocus()
    }

    private fun showChoices(heading: String, choices: List<Pair<String, () -> Unit>>) {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(act, 18)
            isFocusable = false
            setPadding(Skin.dp(act, 20), Skin.dp(act, 16), Skin.dp(act, 20), Skin.dp(act, 14))
        }
        card.addView(Skin.oneLine(Skin.text(act, heading, 16f, Skin.CYAN, true)).apply { setPadding(0, 0, 0, Skin.dp(act, 10)) })
        var first: View? = null
        for ((label, action) in choices) {
            val c = Skin.choice(act, label) { action() }
            card.addView(c, Skin.gapParams(act, bottomDp = 6))
            if (first == null) first = c
        }
        showOverlay(card)
        first?.requestFocus()
    }

    private fun showMainMenu() {
        if (overlay.visibility == View.VISIBLE) return
        showChoices(
            "Live TV",
            listOf(
                Pair("Search channels") { hideOverlay(); showSearch() },
                Pair("Refresh channels and guide") { hideOverlay(); refresh(true) },
                Pair("Reset category order") { hideOverlay(); resetOrder() },
                Pair("Close") { hideOverlay() }
            )
        )
    }

    private fun showSearch() {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(act, 18)
            setPadding(Skin.dp(act, 20), Skin.dp(act, 16), Skin.dp(act, 20), Skin.dp(act, 14))
        }
        card.addView(Skin.text(act, "Search channels", 16f, Skin.CYAN, true).apply { setPadding(0, 0, 0, Skin.dp(act, 10)) })
        val et = EditText(act).apply {
            setText(query)
            setSingleLine()
            Skin.setSize(this, 18f)
            setTextColor(Skin.TEXT)
            setHintTextColor(Skin.DIM)
            hint = "Type a channel name"
            background = Skin.card(act, 12)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(Skin.dp(act, 14), Skin.dp(act, 10), Skin.dp(act, 14), Skin.dp(act, 10))
        }
        fun go() {
            query = et.text.toString().trim()
            hideOverlay()
            if (query.isEmpty()) {
                selectKey(ALL_KEY, false)
            } else {
                selectKey("?q:$query", false)
                guide.requestFocus()
            }
            refreshPanel()
        }
        et.setOnEditorActionListener { _, _, _ ->
            go()
            true
        }
        card.addView(et, Skin.gapParams(act, bottomDp = 10))
        card.addView(Skin.button(act, "Search") { go() }, Skin.gapParams(act, -1, -2, 6))
        card.addView(Skin.button(act, "Clear search") { et.setText(""); go() }, Skin.gapParams(act, -1, -2, 0))
        showOverlay(card)
        et.requestFocus()
    }
}
