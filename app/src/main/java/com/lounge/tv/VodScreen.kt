package com.lounge.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

private val FOUR_K = Regex("(?i)(\\b4k\\b|\\buhd\\b|2160)")

/** Movies or Shows (English only, from your IPTV): genres on the left, a poster grid, episodes for a series. */
class VodScreen(act: MainActivity, private val series: Boolean) : Screen(act), MenuKeyHandler {

    private companion object {
        const val GENRES = "?genres"
        const val FORYOU = "?foryou"
        const val RECENT = "?recent"
        const val WATCHED = "?watched"
        const val ALL = "*"
        const val SORT = "?sort"
        const val SEARCH = "?search"
        const val TWELVE_HOURS = 12 * 3600L
        val SORTS = listOf("Newest added", "Highest rated", "Newest release", "A - Z")
    }

    private val kind = if (series) "series" else "movie"
    private val cacheName = if (series) "vod_series" else "vod_movies"
    private val chipRow = LinearLayout(act)
    private val chipViews = HashMap<String, TextView>()
    private val genreScroll = ScrollView(act)
    private val genreBox = LinearLayout(act)
    private val content = FrameLayout(act)
    private var pendingSeries = ""
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
    private var genreKey = GENRES
    private var sortIdx = 0
    private var query = ""
    private var loaded = false
    private var loading = false
    private var built = 0L
    private var shownItems: List<PosterRow> = emptyList()
    private var forYou: List<VodItem> = emptyList()
    private var forYouLearned = false

    /** A card in the grid: either a title from the list, or a "continue watching" entry. */
    private class PosterRow(val item: VodItem?, val hist: Hist?, val title: String, val icon: String, val next: Boolean = false)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Skin.dp(act, 40), Skin.dp(act, 22), Skin.dp(act, 30), Skin.dp(act, 6))
        root.addView(Skin.text(act, if (series) "SHOWS" else "MOVIES", 40f, Skin.TEXT, true).apply { letterSpacing = 0.03f })
        chipRow.orientation = LinearLayout.HORIZONTAL
        val chipScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        root.addView(chipScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Skin.dp(act, 8); bottomMargin = Skin.dp(act, 6) })
        addChip(GENRES, "Genres")
        addChip(SEARCH, "Search")
        addChip(SORT, "Sort: " + SORTS[0])
        addChip(RECENT, "Continue watching")
        addChip(WATCHED, "Recently watched")

        val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        head.addView(dTitle)
        head.addView(dMeta)
        dPlot.maxLines = 2
        dPlot.ellipsize = android.text.TextUtils.TruncateAt.END
        head.addView(dPlot)
        root.addView(head, LinearLayout.LayoutParams(-1, Skin.dp(act, 92)))
        root.addView(status)

        grid.layoutManager = GridLayoutManager(act, 6)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.clipToPadding = false
        grid.setPadding(0, Skin.dp(act, 6), 0, Skin.dp(act, 12))
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        content.addView(grid, LayoutParams(-1, -1))
        genreBox.orientation = LinearLayout.VERTICAL
        genreBox.setPadding(Skin.dp(act, 4), Skin.dp(act, 4), Skin.dp(act, 4), Skin.dp(act, 20))
        genreScroll.isVerticalScrollBarEnabled = false
        genreScroll.addView(genreBox, LayoutParams(-1, -2))
        content.addView(genreScroll, LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(root, LayoutParams(-1, -1))

        episodeView.visibility = View.GONE
        episodeView.setBackgroundColor(Skin.BG)
        addView(episodeView, LayoutParams(-1, -1))

        overlay.visibility = View.GONE
        addView(overlay, LayoutParams(-1, -1))

        showHead(null)
        markChips()
    }

    private fun addChip(key: String, label: String) {
        val t = Skin.text(act, label, 17f, Skin.TEXT, true).apply {
            background = Skin.card(act, 20)
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setPadding(Skin.dp(act, 18), Skin.dp(act, 8), Skin.dp(act, 18), Skin.dp(act, 8))
            setOnClickListener { chipPicked(key) }
        }
        chipViews[key] = t
        chipRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 8) })
    }

    private fun markChips() {
        for ((k, v) in chipViews) v.isSelected = k == genreKey || (k == GENRES && (genreKey.startsWith("g:") || genreKey == FORYOU))
        chipViews[SEARCH]?.text = if (query.isEmpty()) "Search" else "Search: $query"
        chipViews[SORT]?.text = "Sort: " + SORTS[sortIdx]
    }

    // ------------------------------------------------------------------ page life
    override fun onShow() {
        if (!Store.hasLogin(act)) {
            act.open("pair")
            return
        }
        if (!loaded) loadLocal()
        refresh(false)
        if (genreKey == RECENT || genreKey == WATCHED) rebuildGrid()
    }

    override fun onKeysChanged() {
        loaded = false
        items = emptyList()
        built = 0L
        if (visibility == View.VISIBLE) onShow()
    }

    /** A film is about to play on top: let go of the poster pictures so the video has the memory. */
    override fun onActivityPause() {
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? ViewGroup)?.let { clearImgs(it) }
        grid.recycledViewPool.clear()
        Img.clear()
        System.gc()
    }

    private fun clearImgs(v: ViewGroup) {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is ImageView) c.setImageDrawable(null) else if (c is ViewGroup) clearImgs(c)
        }
    }

    override fun onActivityResume() {
        if (visibility != View.VISIBLE) return
        if (genreKey == RECENT || genreKey == WATCHED) rebuildGrid()
        else if (genreKey != GENRES && shownItems.isNotEmpty()) adapter.notifyDataSetChanged()
    }

    override fun focusFirst() {
        if (episodeView.visibility == View.VISIBLE) {
            episodeView.getChildAt(0)?.requestFocus()
        } else if (genreKey == GENRES) {
            genreScroll.post { firstTile()?.requestFocus() ?: chipViews[GENRES]?.requestFocus() }
        } else if (shownItems.isNotEmpty()) {
            grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: chipViews[genreKey]?.requestFocus() }
        } else {
            chipViews[GENRES]?.requestFocus()
        }
    }

    override fun onBack(): Boolean {
        if (popup.shown) {
            popup.hide()
            return true
        }
        if (episodeView.visibility == View.VISIBLE) {
            episodeView.visibility = View.GONE
            root.visibility = View.VISIBLE
            grid.post { focusFirst() }
            return true
        }
        if (genreKey != GENRES) {
            genreKey = GENRES
            markChips()
            rebuildGrid()
            focusFirst()
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
        markChips()
        rebuildGrid()
        openPendingSeries()
    }

    // ------------------------------------------------------------------ genres and grid
    private fun refreshPanel() {
        markChips()
    }

    private fun chipPicked(key: String) {
        when (key) {
            SEARCH -> askSearch()
            SORT -> {
                sortIdx = (sortIdx + 1) % SORTS.size
                if (genreKey == GENRES) genreKey = ALL
                markChips()
                rebuildGrid()
                chipViews[SORT]?.requestFocus()
            }
            GENRES -> {
                query = ""
                genreKey = GENRES
                markChips()
                rebuildGrid()
                focusFirst()
            }
            else -> {
                genreKey = key
                markChips()
                rebuildGrid()
                focusFirst()
            }
        }
    }

    private fun askSearch() {
        popup.search(if (series) "Search shows" else "Search movies", query) { q ->
            query = q
            genreKey = if (q.isEmpty()) GENRES else ALL
            markChips()
            rebuildGrid()
            chipViews[SEARCH]?.requestFocus()
        }
    }

    private fun pins(): List<String> = Store.str(act, "vod_pin_$kind").split("|").filter { it.isNotEmpty() }

    private fun togglePin(g: String) {
        val l = pins().toMutableList()
        if (g in l) l.remove(g) else l.add(0, g)
        Store.putStr(act, "vod_pin_$kind", l.joinToString("|"))
        buildGenres()
        genreBox.post { tileFor(g)?.requestFocus() }
    }

    private val tiles = HashMap<String, View>()
    private fun tileFor(g: String): View? = tiles[g]
    private fun firstTile(): View? {
        val l = ArrayList<View>()
        genreBox.addFocusables(l, View.FOCUS_DOWN)
        return l.firstOrNull { it.isShown }
    }

    /** The genre front screen: a poster collage per genre, big ones first, pinned ones before those. */
    private fun buildGenres() {
        genreBox.removeAllViews()
        tiles.clear()
        val byGenre = HashMap<String, MutableList<VodItem>>()
        for (it in items) byGenre.getOrPut(Vod.genreOf(it.cat)) { ArrayList() }.add(it)
        val pinned = pins()
        val genreOrder = byGenre.keys.sortedWith(compareBy<String>({ if (it in pinned) pinned.indexOf(it) else 1000 }, { -(byGenre[it]?.size ?: 0) }, { it.lowercase() }))
        if (genreOrder.isEmpty()) return
        buildForYou()
        val order = listOf(FORYOU) + genreOrder
        genreBox.addView(Skin.text(act, "Hold OK on a genre to pin it to the front.", 14f, Skin.DIM), Skin.gapParams(act, bottomDp = 8))
        var i = 0
        var rowNo = 0
        while (i < order.size) {
            val per = if (rowNo == 0) 3 else 4
            val h = if (rowNo == 0) 150 else 104
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until per) {
                if (i >= order.size) break
                val g = order[i++]
                if (g == FORYOU) {
                    val ft = forYouTile()
                    tiles[g] = ft
                    line.addView(ft, LinearLayout.LayoutParams(0, Skin.dp(act, h), 1f).apply { rightMargin = Skin.dp(act, 10) })
                    continue
                }
                val list = byGenre[g] ?: continue
                val top = list.filter { it.icon.startsWith("http") }.sortedByDescending { it.rating }.take(3)
                val t = genreTile(g, list.size, top, g in pinned)
                tiles[g] = t
                line.addView(t, LinearLayout.LayoutParams(0, Skin.dp(act, h), 1f).apply { rightMargin = Skin.dp(act, 10) })
            }
            genreBox.addView(line, Skin.gapParams(act, bottomDp = 10))
            rowNo++
        }
    }

    /**
     * "For You": titles you have not watched, picked from the kinds you watch most (newest watching counts most),
     * with better rated and newer ones first and no single genre taking over. With no history yet it simply
     * shows the best rated titles.
     */
    private fun buildForYou() {
        val byId = HashMap<String, VodItem>()
        for (x in items) byId[x.id] = x
        val seen = HashSet<String>()
        val weight = HashMap<String, Double>()
        var n = 0
        for (h in History.all(act)) {
            val id = if (series) (if (h.kind == "episode") h.seriesId else "") else (if (h.kind == "movie") h.id else "")
            if (id.isEmpty() || !seen.add(id)) continue
            val item = byId[id] ?: continue
            val g = Vod.genreOf(item.cat)
            weight[g] = (weight[g] ?: 0.0) + 1.0 / (1.0 + n * 0.08)
            n++
        }
        val learned = weight.isNotEmpty()
        val top = weight.values.maxOrNull() ?: 1.0
        val cand = ArrayList<Pair<Double, VodItem>>()
        for (x in items) {
            if (x.id in seen || !x.icon.startsWith("http")) continue
            val gw = if (learned) (weight[Vod.genreOf(x.cat)] ?: 0.0) / top else 0.0
            if (learned && gw <= 0.0) continue
            val r = x.rating.coerceIn(0.0, 10.0) / 10.0
            val fresh = if (x.year > 0) (x.year - 1980).coerceIn(0, 50) / 50.0 else 0.3
            cand.add(Pair(gw * 2.0 + r * 1.2 + fresh * 0.6, x))
        }
        cand.sortByDescending { it.first }
        val perGenre = HashMap<String, Int>()
        val out = ArrayList<VodItem>()
        for (pair in cand) {
            val g = Vod.genreOf(pair.second.cat)
            val used = perGenre[g] ?: 0
            if (learned && used >= 12) continue
            perGenre[g] = used + 1
            out.add(pair.second)
            if (out.size >= 48) break
        }
        forYou = out
        forYouLearned = learned
    }

    /** The first tile on the genre screen: its own purple-to-pink look, with posters from the suggestions. */
    private fun forYouTile(): FrameLayout {
        val c = act
        val count = forYou.size
        val t = FrameLayout(c).apply {
            background = Skin.card(c, 14)
            isFocusable = true
            isClickable = true
            setPadding(Skin.dp(c, 4), Skin.dp(c, 4), Skin.dp(c, 4), Skin.dp(c, 4))
            setOnClickListener {
                genreKey = FORYOU
                markChips()
                rebuildGrid()
                focusFirst()
            }
            setOnFocusChangeListener { _, f ->
                if (f) {
                    dTitle.text = "For You"
                    dMeta.text = "$count picks"
                    dPlot.text = if (forYouLearned) "Picked from the kinds of ${if (series) "shows" else "films"} you watch" else "Watch a few and these will get smarter"
                }
            }
        }
        val bg = View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF2A1B5E.toInt(), 0xFF7B2FBF.toInt(), Skin.MAGENTA))
        }
        t.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val strip = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in forYou.take(3)) {
            val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            strip.addView(iv, LinearLayout.LayoutParams(Skin.dp(c, 62), -1).apply { leftMargin = Skin.dp(c, 6) })
            Img.load(iv, p.icon, 170, 250)
        }
        t.addView(strip, FrameLayout.LayoutParams(-2, -1, Gravity.END).apply {
            topMargin = Skin.dp(c, 10); bottomMargin = Skin.dp(c, 10); rightMargin = Skin.dp(c, 10)
        })
        val shade = View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB007030F.toInt(), 0x00000000))
        }
        t.addView(shade, FrameLayout.LayoutParams(-1, -1))
        t.addView(Skin.text(c, "★ Picked for you", 13f, Color.WHITE, true).apply {
            setBackgroundColor(0x44FFFFFF)
            setPadding(Skin.dp(c, 8), Skin.dp(c, 2), Skin.dp(c, 8), Skin.dp(c, 2))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = Skin.dp(c, 8); leftMargin = Skin.dp(c, 10) })
        val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Skin.dp(c, 12), 0, Skin.dp(c, 10), Skin.dp(c, 8)) }
        txt.addView(Skin.oneLine(Skin.text(c, "FOR YOU", 24f, Color.WHITE, true)))
        txt.addView(Skin.oneLine(Skin.text(c, "Smart suggestions  -  %,d titles".format(count), 14f, 0xFFF0E6FF.toInt())))
        t.addView(txt, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return t
    }

    private fun genreTile(g: String, count: Int, top: List<VodItem>, pinned: Boolean): FrameLayout {
        val c = act
        val t = FrameLayout(c).apply {
            background = Skin.card(c, 14)
            isFocusable = true
            isClickable = true
            setPadding(Skin.dp(c, 4), Skin.dp(c, 4), Skin.dp(c, 4), Skin.dp(c, 4))
            setOnClickListener {
                genreKey = "g:$g"
                markChips()
                rebuildGrid()
                focusFirst()
            }
            setOnLongClickListener { togglePin(g); true }
            setOnFocusChangeListener { _, f -> if (f) { dTitle.text = g; dMeta.text = "$count titles"; dPlot.text = "" } }
        }
        val art = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Crest.color(g)) }
        for (p in top) {
            val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            art.addView(iv, LinearLayout.LayoutParams(0, -1, 1f))
            Img.load(iv, p.icon, 170, 250)
        }
        t.addView(art, FrameLayout.LayoutParams(-1, -1))
        val shade = View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF207030F.toInt(), 0x66000000))
        }
        t.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Skin.dp(c, 12), 0, Skin.dp(c, 10), Skin.dp(c, 8)) }
        txt.addView(Skin.oneLine(Skin.text(c, g.uppercase(), 24f, Color.WHITE, true)))
        txt.addView(Skin.oneLine(Skin.text(c, "%,d titles".format(count), 14f, 0xFFD5C9F2.toInt())))
        t.addView(txt, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        if (pinned) t.addView(Skin.text(c, "★", 18f, Skin.MAGENTA, true), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = Skin.dp(c, 6); rightMargin = Skin.dp(c, 10) })
        return t
    }

    private fun rebuildGrid() {
        if (genreKey == GENRES) {
            grid.visibility = View.GONE
            genreScroll.visibility = View.VISIBLE
            shownItems = emptyList()
            adapter.notifyDataSetChanged()
            buildGenres()
            showHead(null)
            dTitle.text = if (series) "Shows" else "Movies"
            dMeta.text = if (items.isEmpty()) "" else "Pick a genre"
            return
        }
        genreScroll.visibility = View.GONE
        grid.visibility = View.VISIBLE
        if (genreKey == RECENT) {
            shownItems = recentRows()
        } else if (genreKey == WATCHED) {
            shownItems = watchedRows()
        } else if (genreKey == FORYOU) {
            buildForYou()
            shownItems = forYou.map { PosterRow(it, null, Vod.displayName(it.name), it.icon) }
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
            dTitle.text = when (genreKey) {
                RECENT -> "Nothing to continue yet"
                WATCHED -> "Nothing watched yet"
                FORYOU -> "No suggestions yet"
                else -> if (items.isEmpty()) "" else "No matches"
            }
        }
    }

    // ---- clearing Continue watching / Recently watched (hold OK on a card)
    private fun hideName() = if (series) "cont_hide_shows" else "cont_hide_movies"

    private fun hiddenMap(): HashMap<String, Long> {
        val m = HashMap<String, Long>()
        for (line in Store.str(act, hideName()).split("\n")) {
            val p = line.split("|")
            if (p.size == 2) p[1].toLongOrNull()?.let { m[p[0]] = it }
        }
        return m
    }

    private fun saveHidden(m: Map<String, Long>) {
        Store.putStr(act, hideName(), m.entries.joinToString("\n") { it.key + "|" + it.value })
    }

    private fun rowKey(h: Hist): String = if (series) h.seriesId else h.id

    private fun isHidden(m: Map<String, Long>, h: Hist): Boolean {
        val t = m[rowKey(h)] ?: return false
        return h.ts <= t
    }

    private fun cardMenu(r: PosterRow) {
        val h = r.hist ?: return
        val recent = genreKey == RECENT
        val where = if (recent) "Continue watching" else "Recently watched"
        popup.choices(
            r.title,
            listOf(
                Pair("Remove from $where") {
                    if (recent) {
                        val m = hiddenMap(); m[rowKey(h)] = System.currentTimeMillis() / 1000; saveHidden(m)
                    } else {
                        for (x in History.all(act)) {
                            if (series) { if (x.seriesId == h.seriesId) History.remove(act, x.key) }
                            else if (x.key == h.key) History.remove(act, x.key)
                        }
                    }
                    popup.hide(); rebuildGrid(); focusFirst()
                },
                Pair("Clear all of $where") { popup.hide(); confirmClear(recent, where) },
                Pair("Cancel") { popup.hide() }
            )
        )
    }

    private fun confirmClear(recent: Boolean, where: String) {
        popup.choices(
            "Clear all of $where?",
            listOf(
                Pair("Yes, clear them all") {
                    if (recent) {
                        val m = hiddenMap(); val now = System.currentTimeMillis() / 1000
                        for (row in shownItems) row.hist?.let { m[rowKey(it)] = now }
                        saveHidden(m)
                    } else {
                        for (x in History.all(act)) {
                            if (series && x.kind == "episode") History.remove(act, x.key)
                            else if (!series && x.kind == "movie") History.remove(act, x.key)
                        }
                    }
                    popup.hide(); rebuildGrid(); focusFirst()
                },
                Pair("No, keep them") { popup.hide() }
            )
        )
    }

    private fun recentRows(): List<PosterRow> {
        val byId = HashMap<String, VodItem>()
        for (it in items) byId[it.id] = it
        val out = ArrayList<PosterRow>()
        val hid = hiddenMap()
        if (!series) {
            for (h in History.all(act)) {
                if (h.kind != "movie" || !h.resumable() || isHidden(hid, h)) continue
                val item = byId[h.id]
                out.add(PosterRow(item, h, h.title, item?.icon ?: ""))
            }
            return out
        }
        // shows: one card per series, from the newest episode you touched. Half-watched = continue it,
        // finished = offer the next episode.
        val seen = HashSet<String>()
        for (h in History.all(act)) {
            if (h.kind != "episode" || h.seriesId.isEmpty() || !seen.add(h.seriesId)) continue
            if (isHidden(hid, h)) continue
            val item = byId[h.seriesId]
            val name = item?.let { Vod.displayName(it.name) } ?: h.title
            if (h.resumable()) out.add(PosterRow(item, h, h.title, item?.icon ?: ""))
            else if (h.percent() >= 95) out.add(PosterRow(item, h, name, item?.icon ?: "", next = true))
        }
        return out
    }

    /** Everything you have watched lately, finished or not (one card per show). */
    private fun watchedRows(): List<PosterRow> {
        val byId = HashMap<String, VodItem>()
        for (it in items) byId[it.id] = it
        val out = ArrayList<PosterRow>()
        val seen = HashSet<String>()
        for (h in History.all(act)) {
            if (series) {
                if (h.kind != "episode" || h.seriesId.isEmpty() || !seen.add(h.seriesId)) continue
                val item = byId[h.seriesId]
                out.add(PosterRow(item, h, item?.let { Vod.displayName(it.name) } ?: h.title, item?.icon ?: ""))
            } else {
                if (h.kind != "movie") continue
                val item = byId[h.id]
                out.add(PosterRow(item, h, h.title, item?.icon ?: ""))
            }
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
            dMeta.text = if (r.next) "Next episode" else if (genreKey == WATCHED) "Watched  " + r.hist.percent() + "%" else "Continue  " + r.hist.percent() + "% watched"
            dPlot.text = if (r.next) "Up next after " + r.hist.sub else r.hist.sub
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
            if (r.next && h != null) {
                playNext(xt, h, r.item)
            } else if (genreKey == WATCHED && r.item != null) {
                showEpisodes(r.item)
            } else if (h != null) {
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

    /** The episode after the one you just finished. */
    private fun playNext(xt: Xtream, last: Hist, item: VodItem?) {
        status.text = "Finding the next episode..."
        Bg.run {
            try {
                val eps = xt.episodes(last.seriesId).sortedWith(compareBy({ it.season }, { it.num }))
                val i = eps.indexOfFirst { it.id == last.id }
                val nx = if (i >= 0) eps.getOrNull(i + 1) else null
                Bg.ui {
                    status.text = ""
                    if (nx == null) {
                        status.text = "That was the last episode we have for this show."
                    } else {
                        val name = item?.let { Vod.displayName(it.name) } ?: last.title.substringBefore("  S")
                        playEpisode(xt, nx.id, nx.ext, name + "  S" + nx.season + " E" + nx.num, last.seriesId, "S${nx.season} E${nx.num}  " + nx.title)
                    }
                }
            } catch (e: Exception) {
                val msg = xt.redact(e.message ?: "unknown error")
                Bg.ui { status.text = "Couldn't find the next episode: $msg" }
            }
        }
    }

    override fun onArg(arg: Any?) {
        if (arg is String && arg.startsWith("series:")) {
            pendingSeries = arg.removePrefix("series:")
            openPendingSeries()
        }
    }

    private fun openPendingSeries() {
        if (pendingSeries.isEmpty() || items.isEmpty()) return
        val it = items.firstOrNull { x -> x.id == pendingSeries }
        pendingSeries = ""
        if (it != null) showEpisodes(it)
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

        // where to land: the episode you were on, or the one after it if you finished it
        val sorted = eps.sortedWith(compareBy({ it.season }, { it.num }))
        val latest = sorted.mapNotNull { e -> History.find(act, "ep:" + e.id)?.let { e to it } }.maxByOrNull { it.second.ts }
        val target: Episode? = latest?.let { (e, h) ->
            if (h.percent() >= 95) sorted.getOrNull(sorted.indexOf(e) + 1) ?: e else e
        }

        fun fill(season: Int) {
            listBox.removeAllViews()
            var focus: View? = null
            for (e in eps.filter { it.season == season }) {
                val key = "ep:" + e.id
                val h = History.find(act, key)
                val label = "E" + e.num + "   " + e.title.ifBlank { "Episode ${e.num}" }
                val sub = when {
                    e === target && (h == null || h.percent() >= 95 || !h.resumable()) && latest != null -> "Up next"
                    h != null && h.percent() >= 95 -> "Watched"
                    h != null && h.resumable() -> "Continue  " + h.percent() + "%"
                    else -> e.plot
                }
                val title = Vod.displayName(s.name) + "  S" + season + " E" + e.num
                val row = Skin.choice(act, label, sub) { playEpisode(xt, e.id, e.ext, title, s.id, "S$season E${e.num}  " + e.title) }
                listBox.addView(row, Skin.gapParams(act, bottomDp = 6))
                if (focus == null || e === target) focus = row
            }
            focus?.requestFocus()
        }
        for (sn in seasons) {
            val b = Skin.button(act, "Season $sn") { fill(sn) }
            seasonRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 10) })
        }
        fill(target?.season ?: seasons.first())
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
                layoutParams = RecyclerView.LayoutParams(-1, Skin.dp(c, 232))
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
            val is4k = FOUR_K.containsMatchIn((r.item?.name ?: r.title) + " " + (r.item?.cat ?: ""))
            h.badge.text = if (r.next) "NEXT" else (if (is4k) "4K" else "") + (if (is4k && rating > 0) "  " else "") + (if (rating > 0) "★ $rating" else "")
            h.badge.visibility = if (r.next || rating > 0 || is4k) View.VISIBLE else View.GONE
            h.bar.pct = r.hist?.percent() ?: 0
            h.bar.visibility = if (r.hist != null) View.VISIBLE else View.GONE
            Img.load(h.img, r.icon, 230, 330)
            h.card.setOnClickListener { open(r) }
            h.card.setOnLongClickListener(
                if (r.hist != null && (genreKey == RECENT || genreKey == WATCHED)) View.OnLongClickListener { cardMenu(r); true } else null
            )
            h.card.setOnFocusChangeListener { _, focused -> if (focused) showHead(r) }
        }
    }
}
