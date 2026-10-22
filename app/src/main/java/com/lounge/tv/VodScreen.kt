package com.lounge.tv

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException

/** Movies or Shows (English only, from your IPTV): genres on the left, a poster grid, episodes for a series. */
class VodScreen(act: MainActivity, private val series: Boolean) : Screen(act), MenuKeyHandler {

    private companion object {
        const val RECENT = "?recent"
        const val ALL = "*"
        const val SORT = "?sort"
        const val SEARCH = "?search"
        const val TWELVE_HOURS = 12 * 3600L
        val SORTS = listOf("Newest added", "Highest rated", "Newest release", "A - Z")
    }

    private val kind = if (series) "series" else "movie"
    private val cacheName = if (series) "vod_series" else "vod_movies"
    private val panel = CategoryPanel(act)
    private val grid = RecyclerView(act)
    private val adapter = PosterAdapter()
    private val dTitle = Skin.oneLine(Skin.text(act, "", 24f, Skin.TEXT, true))
    private val dMeta = Skin.oneLine(Skin.text(act, "", 15f, Skin.CYAN, true))
    private val dPlot = Skin.text(act, "", 15f, Skin.MUTED)
    private val status = Skin.oneLine(Skin.text(act, "", 14f, Skin.AMBER))
    private val overlay = FrameLayout(act)
    private val root = LinearLayout(act)
    private val popup = Popup(act, overlay, root)
    private val episodeView = FrameLayout(act)

    private var items: List<VodItem> = emptyList()
    private var genreKey = ALL
    private var sortIdx = 0
    private var query = ""
    private var loaded = false
    private var loading = false
    private var built = 0L
    private var shownItems: List<PosterRow> = emptyList()

    /** A card in the grid: either a title from the list, or a "continue watching" entry. */
    private class PosterRow(val item: VodItem?, val hist: Hist?, val title: String, val icon: String)

    init {
        root.orientation = LinearLayout.HORIZONTAL
        root.addView(panel, LinearLayout.LayoutParams(Skin.dp(act, 230), -1))
        val main = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Skin.dp(act, 20), Skin.dp(act, 16), Skin.dp(act, 14), Skin.dp(act, 6))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        head.addView(dTitle)
        head.addView(dMeta)
        dPlot.maxLines = 2
        dPlot.ellipsize = android.text.TextUtils.TruncateAt.END
        head.addView(dPlot)
        main.addView(head, LinearLayout.LayoutParams(-1, Skin.dp(act, 92)))
        main.addView(status)
        grid.layoutManager = GridLayoutManager(act, 5)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.setPadding(0, Skin.dp(act, 6), 0, Skin.dp(act, 12))
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        main.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        addView(root, LayoutParams(-1, -1))

        episodeView.visibility = View.GONE
        episodeView.setBackgroundColor(Skin.BG)
        addView(episodeView, LayoutParams(-1, -1))

        overlay.visibility = View.GONE
        addView(overlay, LayoutParams(-1, -1))

        panel.onPick = { e -> picked(e) }
        showHead(null)
    }

    // ------------------------------------------------------------------ page life
    override fun onShow() {
        if (!Store.hasLogin(act)) {
            act.open("pair")
            return
        }
        if (!loaded) loadLocal()
        refresh(false)
        if (genreKey == RECENT) rebuildGrid()
    }

    override fun onKeysChanged() {
        loaded = false
        items = emptyList()
        built = 0L
        if (visibility == View.VISIBLE) onShow()
    }

    override fun onActivityResume() {
        if (visibility == View.VISIBLE && genreKey == RECENT) rebuildGrid()
    }

    override fun focusFirst() {
        if (episodeView.visibility == View.VISIBLE) {
            episodeView.getChildAt(0)?.requestFocus()
        } else if (shownItems.isNotEmpty()) {
            grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: panel.focusSelected() }
        } else {
            panel.focusSelected()
        }
    }

    override fun onBack(): Boolean {
        if (popup.shown) {
            popup.hide()
            return true
        }
        if (panel.moving) return true
        if (episodeView.visibility == View.VISIBLE) {
            episodeView.visibility = View.GONE
            root.visibility = View.VISIBLE
            grid.post { focusFirst() }
            return true
        }
        return false
    }

    override fun onMenuKey(): Boolean {
        popup.choices(
            if (series) "Shows" else "Movies",
            listOf(
                Pair("Search") { popup.hide(); askSearch() },
                Pair("Refresh the list") { popup.hide(); refresh(true) },
                Pair("Close") { popup.hide() }
            )
        )
        return true
    }

    // ------------------------------------------------------------------ loading
    private fun rowToItem(r: Any?): VodItem? {
        val a = r.arr()
        if (a.size < 9) return null
        return VodItem(
            a[0].str(), a[1].str(), a[2].str(), a[3].str(), a[4].num(), a[5].int(), a[6].long(), a[7].str(), kind,
            a[8].str(), a.getOrNull(9).str()
        )
    }

    private fun loadLocal() {
        loaded = true
        status.text = "Loading..."
        Bg.run {
            val o = Store.loadJson(act, cacheName).obj()
            val list = o["rows"].arr().mapNotNull { rowToItem(it) }
            val b = o["built"].long()
            Bg.ui {
                built = b
                if (list.isNotEmpty()) applyItems(list)
                status.text = if (list.isEmpty()) "Loading ${if (series) "shows" else "movies"}... the first time can take a minute or two." else ""
            }
        }
    }

    private fun refresh(force: Boolean) {
        if (loading) return
        loading = true
        Bg.run {
            try {
                val now = System.currentTimeMillis() / 1000
                // wait until the first local read has finished so we do not compare against an empty list
                var waited = 0
                while (!loaded && waited < 20) { Thread.sleep(50); waited++ }
                if (!force && items.isNotEmpty() && now - built < TWELVE_HOURS) return@run
                val xt = Store.xtream(act) ?: throw IOException("Please pair your IPTV login first.")
                Session.xt = xt
                Bg.ui { if (items.isEmpty()) status.text = "Loading ${if (series) "shows" else "movies"}... the first time can take a minute or two." else status.text = "Updating..." }
                xt.login()
                val list = if (series) xt.series() else xt.movies()
                if (list.isEmpty()) throw IOException("No English titles were found.")
                val rows = list.map { listOf(it.id, it.name, it.cat, it.tag, it.rating, it.year, it.added, it.ext, it.icon, it.plot) }
                Store.saveJson(act, cacheName, mapOf("built" to now, "rows" to rows))
                Bg.ui {
                    built = now
                    applyItems(list)
                    status.text = ""
                }
            } catch (e: Exception) {
                val xt = Session.xt
                val msg = xt?.redact(e.message ?: "unknown error") ?: (e.message ?: "unknown error")
                Bg.ui { status.text = "Couldn't load: $msg  (press Menu, then Refresh)" }
            } finally {
                loading = false
            }
        }
    }

    private fun applyItems(list: List<VodItem>) {
        items = list
        refreshPanel()
        rebuildGrid()
    }

    // ------------------------------------------------------------------ panel and grid
    private fun refreshPanel() {
        val counts = HashMap<String, Int>()
        for (it in items) {
            val g = Vod.genreOf(it.cat)
            counts[g] = (counts[g] ?: 0) + 1
        }
        val e = ArrayList<CategoryPanel.Entry>()
        e.add(CategoryPanel.Entry(SEARCH, if (query.isEmpty()) "Search..." else "Search: $query", 0, CategoryPanel.SEARCH))
        e.add(CategoryPanel.Entry(SORT, "Sort: " + SORTS[sortIdx], 0, CategoryPanel.SEARCH))
        e.add(CategoryPanel.Entry(RECENT, "Continue watching", 0, CategoryPanel.ALL))
        e.add(CategoryPanel.Entry(ALL, "All", items.size, CategoryPanel.ALL))
        for (g in counts.keys.sortedBy { it.lowercase() }) e.add(CategoryPanel.Entry("g:$g", g, counts[g] ?: 0, CategoryPanel.ALL))
        panel.setEntries(e, genreKey)
    }

    private fun picked(e: CategoryPanel.Entry) {
        when (e.key) {
            SEARCH -> askSearch()
            SORT -> {
                sortIdx = (sortIdx + 1) % SORTS.size
                refreshPanel()
                rebuildGrid()
                panel.focusKey(SORT)
            }
            else -> {
                genreKey = e.key
                refreshPanel()
                rebuildGrid()
                grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
    }

    private fun askSearch() {
        popup.search(if (series) "Search shows" else "Search movies", query) { q ->
            query = q
            genreKey = ALL
            refreshPanel()
            rebuildGrid()
            panel.focusKey(SEARCH)
        }
    }

    private fun rebuildGrid() {
        if (genreKey == RECENT) {
            shownItems = recentRows()
        } else {
            var l = items.asSequence()
            if (genreKey.startsWith("g:")) {
                val g = genreKey.substring(2)
                l = l.filter { Vod.genreOf(it.cat) == g }
            }
            if (query.isNotEmpty()) l = l.filter { it.name.contains(query, ignoreCase = true) }
            val sorted = when (sortIdx) {
                0 -> l.sortedByDescending { it.added }
                1 -> l.sortedByDescending { it.rating }
                2 -> l.sortedByDescending { it.year }
                else -> l.sortedBy { Vod.displayName(it.name).lowercase() }
            }
            shownItems = sorted.take(2500).map { PosterRow(it, null, Vod.displayName(it.name), it.icon) }.toList()
        }
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
        if (shownItems.isEmpty()) {
            showHead(null)
            dTitle.text = if (genreKey == RECENT) "Nothing to continue yet" else if (items.isEmpty()) "" else "No matches"
        }
    }

    private fun recentRows(): List<PosterRow> {
        val byId = HashMap<String, VodItem>()
        for (it in items) byId[it.id] = it
        val out = ArrayList<PosterRow>()
        for (h in History.all(act)) {
            if (series && h.kind != "episode") continue
            if (!series && h.kind != "movie") continue
            if (!h.resumable()) continue
            val item = if (series) byId[h.seriesId] else byId[h.id]
            out.add(PosterRow(item, h, h.title, item?.icon ?: ""))
        }
        return out
    }

    private fun showHead(r: PosterRow?) {
        if (r == null) {
            dTitle.text = if (series) "Shows" else "Movies"
            dMeta.text = ""
            dPlot.text = ""
            return
        }
        val it = r.item
        dTitle.text = r.title
        if (r.hist != null) {
            dMeta.text = "Continue  " + r.hist.percent() + "% watched"
            dPlot.text = r.hist.sub
        } else if (it != null) {
            val bits = ArrayList<String>()
            if (it.year > 0) bits.add(it.year.toString())
            if (it.rating > 0) bits.add("★ " + it.rating)
            bits.add(Vod.genreOf(it.cat))
            dMeta.text = bits.joinToString("  ·  ")
            dPlot.text = it.plot
        }
    }

    private fun open(r: PosterRow) {
        val xt = Session.xt ?: Store.xtream(act) ?: return
        Session.xt = xt
        val h = r.hist
        if (series) {
            if (h != null) {
                playEpisode(xt, h.id, h.ext, h.title, h.seriesId, h.sub)
            } else {
                r.item?.let { showEpisodes(it) }
            }
        } else {
            val it = r.item
            val id = it?.id ?: h?.id ?: return
            val ext = it?.ext ?: h?.ext ?: "mp4"
            val title = r.title
            val key = "movie:$id"
            val old = History.find(act, key)
            val resume = if (old != null && old.resumable()) old.pos else 0L
            History.save(act, Hist(key, "movie", title, it?.let { x -> Vod.genreOf(x.cat) } ?: (old?.sub ?: ""), id, ext, resume, old?.dur ?: 0L, System.currentTimeMillis() / 1000))
            act.playVod(title, xt.movieUrl(id, ext), key, resume)
        }
    }

    private fun playEpisode(xt: Xtream, id: String, ext: String, title: String, seriesId: String, sub: String) {
        val key = "ep:$id"
        val old = History.find(act, key)
        val resume = if (old != null && old.resumable()) old.pos else 0L
        History.save(act, Hist(key, "episode", title, sub, id, ext, resume, old?.dur ?: 0L, System.currentTimeMillis() / 1000, seriesId))
        act.playVod(title, xt.episodeUrl(id, ext), key, resume)
    }

    // ------------------------------------------------------------------ episodes
    private fun showEpisodes(s: VodItem) {
        val xt = Session.xt ?: Store.xtream(act) ?: return
        episodeView.removeAllViews()
        episodeView.visibility = View.VISIBLE
        root.visibility = View.GONE
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Skin.dp(act, 40), Skin.dp(act, 24), Skin.dp(act, 30), Skin.dp(act, 14))
        }
        box.addView(Skin.oneLine(Skin.text(act, Vod.displayName(s.name), 28f, Skin.TEXT, true)))
        box.addView(Skin.text(act, "Loading episodes...", 16f, Skin.MUTED).apply { tag = "loading" })
        episodeView.addView(box, LayoutParams(-1, -1))
        Bg.run {
            try {
                val eps = xt.episodes(s.id)
                Bg.ui { fillEpisodes(box, s, xt, eps) }
            } catch (e: Exception) {
                val msg = xt.redact(e.message ?: "unknown error")
                Bg.ui {
                    (box.findViewWithTag<TextView>("loading"))?.text = "Couldn't load episodes: $msg"
                }
            }
        }
    }

    private fun fillEpisodes(box: LinearLayout, s: VodItem, xt: Xtream, eps: List<Episode>) {
        box.removeView(box.findViewWithTag<View>("loading"))
        if (eps.isEmpty()) {
            box.addView(Skin.text(act, "No episodes were listed for this show.", 17f, Skin.MUTED))
            return
        }
        val seasons = eps.map { it.season }.distinct().sorted()
        val seasonRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val seasonScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
        seasonScroll.addView(seasonRow)
        box.addView(seasonScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Skin.dp(act, 10); bottomMargin = Skin.dp(act, 10) })
        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(listBox)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun fill(season: Int) {
            listBox.removeAllViews()
            var focus: View? = null
            for (e in eps.filter { it.season == season }) {
                val key = "ep:" + e.id
                val h = History.find(act, key)
                val label = "E" + e.num + "   " + e.title.ifBlank { "Episode ${e.num}" }
                val sub = when {
                    h != null && h.percent() >= 95 -> "Watched"
                    h != null && h.resumable() -> "Continue  " + h.percent() + "%"
                    else -> e.plot
                }
                val title = Vod.displayName(s.name) + "  S" + season + " E" + e.num
                val row = Skin.choice(act, label, sub) { playEpisode(xt, e.id, e.ext, title, s.id, "S$season E${e.num}  " + e.title) }
                listBox.addView(row, Skin.gapParams(act, bottomDp = 6))
                if (focus == null || (h != null && h.resumable())) focus = row
            }
            focus?.requestFocus()
        }
        var firstBtn: View? = null
        for (sn in seasons) {
            val b = Skin.button(act, "Season $sn") { fill(sn) }
            seasonRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 10) })
            if (firstBtn == null) firstBtn = b
        }
        // open on the season you were last watching, else the first
        val last = eps.lastOrNull { History.find(act, "ep:" + it.id)?.resumable() == true }
        val startSeason = last?.season ?: seasons.first()
        fill(startSeason)
    }

    // ------------------------------------------------------------------ grid adapter
    private inner class PosterVH(val card: FrameLayout, val img: ImageView, val name: TextView, val badge: TextView, val bar: BarView) :
        RecyclerView.ViewHolder(card)

    private inner class PosterAdapter : RecyclerView.Adapter<PosterVH>() {
        override fun getItemCount(): Int = shownItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterVH {
            val c = parent.context
            val card = FrameLayout(c).apply {
                background = Skin.card(c, 12)
                isFocusable = true
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(-1, Skin.dp(c, 236))
                setPadding(Skin.dp(c, 5), Skin.dp(c, 5), Skin.dp(c, 5), Skin.dp(c, 5))
            }
            val img = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Skin.CARD_DIM)
            }
            card.addView(img, FrameLayout.LayoutParams(-1, Skin.dp(c, 170)))
            val name = Skin.text(c, "", 14f, Skin.TEXT, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = Skin.dp(c, 2) })
            val badge = Skin.text(c, "", 12f, Skin.AMBER, true).apply {
                setBackgroundColor(0xCC07030F.toInt())
                setPadding(Skin.dp(c, 6), Skin.dp(c, 2), Skin.dp(c, 6), Skin.dp(c, 2))
            }
            card.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            val bar = BarView(c)
            card.addView(bar, FrameLayout.LayoutParams(-1, Skin.dp(c, 4), Gravity.TOP).apply { topMargin = Skin.dp(c, 164) })
            return PosterVH(card, img, name, badge, bar)
        }

        override fun onBindViewHolder(h: PosterVH, position: Int) {
            val r = shownItems[position]
            h.name.text = r.title
            val rating = r.item?.rating ?: 0.0
            h.badge.text = if (rating > 0) "★ $rating" else ""
            h.badge.visibility = if (rating > 0) View.VISIBLE else View.GONE
            h.bar.pct = r.hist?.percent() ?: 0
            h.bar.visibility = if (r.hist != null) View.VISIBLE else View.GONE
            Img.load(h.img, r.icon, 300, 440)
            h.card.setOnClickListener { open(r) }
            h.card.setOnFocusChangeListener { _, focused -> if (focused) showHead(r) }
        }
    }
}
