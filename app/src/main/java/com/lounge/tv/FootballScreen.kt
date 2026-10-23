package com.lounge.tv

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/**
 * Football: Match Day Hub, league pages, match pages (predictions, referee, players), form, expected table,
 * My Picks, watch-list and scorecards. The numbers come from the same maths as the PC app (see Model.kt).
 * Data is only updated when you press "Refresh now".
 */
class FootballScreen(act: MainActivity) : Screen(act), FootHost, MenuKeyHandler {

    override val activity: MainActivity get() = act
    override val io: Persist = AndroidPersist(act)
    override val state: FootState = FootState(io)
    override val picks: Picks = Picks(io)
    private var engine: Engine? = null
    override val eng: Engine get() = engine ?: throw IllegalStateException("football numbers not ready")
    private val pages = FootPages(this)

    private class Nav(val key: String, val label: String)
    private val navItems = listOf(
        Nav("hub", "Match Day"), Nav("PL", "Premier League"), Nav("ELC", "Championship"), Nav("form", "Form"),
        Nav("exp", "Expected table"), Nav("picks", "My Picks"), Nav("watch", "Watch-list"), Nav("card", "Scorecard"),
        Nav("refresh", "Refresh now")
    )
    private val navViews = HashMap<String, View>()
    private val navCol = LinearLayout(act)
    private val body = FrameLayout(act)
    private val overlay = FrameLayout(act)
    private val root = LinearLayout(act)
    private val popupObj = Popup(act, overlay, root)
    private val flashView: TextView = Skin.text(act, "", 16f, Skin.TEXT, true)
    private val updatedView: TextView = Skin.text(act, "", 12f, Skin.DIM)

    private var section = "hub"
    private val stack = ArrayList<() -> View>()
    private var loaded = false
    private var loading = false
    private var refreshing = false
    private var hubToday = false

    private var finderObj: TvFinder? = null
    private var finderBuiltFor = 0L
    private var finderBusy = false
    private var chanCache: List<Chan>? = null
    private var pendingArg: String? = null

    private val hideFlash = Runnable { flashView.visibility = View.GONE }

    init {
        root.orientation = LinearLayout.HORIZONTAL
        navCol.orientation = LinearLayout.VERTICAL
        navCol.setPadding(Skin.dp(act, 12), Skin.dp(act, 16), Skin.dp(act, 8), Skin.dp(act, 12))
        navCol.setBackgroundColor(Skin.PANEL)
        navCol.addView(Skin.text(act, "Football", 22f, Skin.TEXT, true).apply { setPadding(Skin.dp(act, 8), 0, 0, Skin.dp(act, 10)) })
        for (n in navItems) {
            val v = Skin.choice(act, n.label) { pick(n.key) }
            navViews[n.key] = v
            navCol.addView(v, Skin.gapParams(act, bottomDp = 6))
        }
        navCol.addView(updatedView.apply { setPadding(Skin.dp(act, 8), Skin.dp(act, 8), 0, 0) })
        root.addView(navCol, LinearLayout.LayoutParams(Skin.dp(act, 220), -1))
        root.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        addView(root, LayoutParams(-1, -1))
        flashView.visibility = View.GONE
        flashView.background = Skin.card(act, 14, 0xF007030F.toInt(), Skin.CYAN)
        flashView.setPadding(Skin.dp(act, 20), Skin.dp(act, 10), Skin.dp(act, 20), Skin.dp(act, 10))
        addView(flashView, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Skin.dp(act, 30) })
        overlay.visibility = View.GONE
        addView(overlay, LayoutParams(-1, -1))
    }

    override fun crumb(): String = "section=$section depth=${stack.size} refreshing=$refreshing"

    // ------------------------------------------------------------------ FootHost
    override fun push(build: () -> View) {
        stack.add(build)
        showTop()
    }

    override fun rerender() = showTop()

    override fun flash(msg: String) {
        flashView.text = msg
        flashView.visibility = View.VISIBLE
        Bg.cancel(hideFlash)
        Bg.uiDelayed(3500, hideFlash)
    }

    override fun popup(): Popup = popupObj

    override fun finder(): TvFinder? {
        ensureFinder()
        return finderObj
    }

    override fun channels(): List<Chan> {
        chanCache?.let { return it }
        val l = Store.loadChannels(act)?.second ?: emptyList()
        chanCache = l
        return l
    }

    override fun reload(done: () -> Unit) {
        Bg.run {
            try {
                state.load()
                engine = newEngine()
            } catch (e: Throwable) {
                Crash.log("Football reload", e)
            }
            Bg.ui(done)
        }
    }

    private fun newEngine(): Engine {
        val c = Calendar.getInstance()
        return Engine(state, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    private var finderGaveUp = false

    /**
     * The match-to-channel lookup uses the TV guide the Live TV page already saved (no second download).
     * The saved guide is very big, so it is read and matched on a background thread, never on the screen thread
     * (reading it on the screen thread froze the app and the Fire TV then closed it).
     */
    private fun ensureFinder() {
        if (finderBusy || engine == null || !Store.hasLogin(act)) return
        if (finderObj != null && Session.epg.built > 0 && finderBuiltFor == Session.epg.built) return
        if (finderGaveUp && Session.epg.built == 0L) return
        finderBusy = true
        val teams = HashSet<String>()
        for (lk in Leagues.ORDER) (state.results[lk] ?: emptyList()).forEach { teams.add(it.home); teams.add(it.away) }
        val cached = chanCache
        Bg.run {
            var epgUsed: EpgData? = null
            var chansUsed: List<Chan>? = null
            val f = try {
                val epg = if (Session.epg.built > 0) Session.epg else EpgData.fromJson(Store.loadJson(act, "live_epg"))
                if (epg.built == 0L) null
                else {
                    val chans = cached ?: (Store.loadChannels(act)?.second ?: emptyList())
                    epgUsed = epg
                    chansUsed = chans
                    TvFinder(chans, epg, TeamNames(teams))
                }
            } catch (e: Throwable) {
                Crash.log("Football channel finder", e)
                null
            }
            Bg.ui {
                finderBusy = false
                finderObj = f
                finderBuiltFor = epgUsed?.built ?: 0L
                finderGaveUp = f == null
                if (chanCache == null && chansUsed != null) chanCache = chansUsed
                if (epgUsed != null && Session.epg.built == 0L) Session.epg = epgUsed!!
                if (f != null && section == "hub" && stack.isEmpty() && visibility == View.VISIBLE && !refreshing) showTop()
            }
        }
    }

    // ------------------------------------------------------------------ page life
    override fun onShow() {
        if (!loaded && !loading) loadAll()
        updateUpdated()
    }

    override fun onKeysChanged() {
        chanCache = null
        finderObj = null
        finderBuiltFor = 0L
        finderGaveUp = false
    }

    override fun onArg(arg: Any?) {
        if (arg is String && arg.startsWith("match:")) {
            pendingArg = arg
            if (loaded) openPending()
        }
    }

    private fun openPending() {
        val a = pendingArg ?: return
        pendingArg = null
        val p = a.removePrefix("match:").split("|")
        if (p.size < 4) return
        val lk = p[0]
        val fx = (state.fixtures[lk] ?: emptyList<Fixture>()).firstOrNull { it.utc == p[1] && it.home == p[2] && it.away == p[3] } ?: return
        stack.clear()
        section = "hub"
        markNav()
        push { pages.matchPage(lk, fx) }
    }

    private fun loadAll() {
        loading = true
        showMessage("Loading football data...")
        Bg.run {
            try {
                state.load()
                engine = newEngine()
            } catch (e: Throwable) {
                Crash.log("Football load", e)
                engine = null
            }
            Bg.ui {
                loading = false
                loaded = engine != null
                updateUpdated()
                if (!loaded) showMessage("The football data could not be read. Press Refresh now.")
                else {
                    showTop()
                    if (pendingArg != null) openPending()
                    if (visibility == View.VISIBLE) focusFirst()
                }
            }
        }
    }

    private fun updateUpdated() {
        val u = state.lastUpdated()
        updatedView.text = if (u.isEmpty()) "Never updated" else {
            val t = Utc.parse(u)
            "Last updated " + (if (t != null) Skin.dayTime(t) else u)
        }
    }

    private fun markNav() {
        for ((k, v) in navViews) v.isSelected = k == section
    }

    private fun pick(key: String) {
        if (key == "refresh") { startRefresh(); return }
        if (refreshing) { flash("Refreshing football data - one moment"); return }
        section = key
        stack.clear()
        markNav()
        if (loaded) showTop()
    }

    private fun showMessage(msg: String) {
        body.removeAllViews()
        val (sv, col) = FootUi.page(act)
        col.addView(FootUi.note(act, msg, Skin.TEXT))
        body.addView(sv, LayoutParams(-1, -1))
    }

    private fun sectionView(): View {
        val e = engine
        if (e == null) return noData()
        return when (section) {
            "hub" -> if (!state.hasData) noData() else hubPage()
            "PL", "ELC" -> if (!state.hasData) noData() else pages.leaguePage(section)
            "form" -> if (!state.hasData) noData() else pages.formPage()
            "exp" -> if (!state.hasData) noData() else pages.expectedPage()
            "picks" -> pages.picksPage()
            "watch" -> if (!state.hasData) noData() else pages.watchPage()
            "card" -> pages.scorecardPage()
            else -> noData()
        }
    }

    private fun noData(): View {
        val (sv, col) = FootUi.page(act)
        col.addView(FootUi.h1(act, "Football"))
        col.addView(FootUi.note(act, "No football data yet. Press Refresh now (the first one takes a few minutes).", Skin.TEXT))
        if (Store.fdoKey(act).isEmpty())
            col.addView(FootUi.note(act, "Tip: add your free football-data.org key by pairing with your phone (Settings > Pair with phone) to see the whole season's fixtures.", Skin.AMBER))
        col.addView(FootUi.buttonRow(act, "Refresh now" to { startRefresh() }))
        return sv
    }

    private fun showTop() {
        Crash.crumb("football " + crumb())
        // while the numbers are being refreshed the data is changing underneath, so no page is built from it
        if (!loaded || refreshing) return
        popupObj.hide()
        val v = try {
            if (stack.isEmpty()) sectionView() else stack.last()()
        } catch (e: Throwable) {
            Crash.log("Football page", e)
            val (sv, col) = FootUi.page(act)
            col.addView(FootUi.note(act, "Something went wrong showing this page (${e.javaClass.simpleName}: ${e.message}). Press Back and try Refresh now.", Skin.RED))
            sv
        }
        body.removeAllViews()
        body.addView(v, LayoutParams(-1, -1))
        v.post { if (!refreshing) focusContent() }
    }

    private fun focusContent() {
        val f = body.getChildAt(0)?.let { firstFocusable(it) }
        if (f != null) f.requestFocus() else navViews[section]?.requestFocus()
    }

    private fun firstFocusable(v: View): View? {
        val l = ArrayList<View>()
        v.addFocusables(l, View.FOCUS_DOWN)
        return l.firstOrNull { it.isShown }
    }

    override fun focusFirst() {
        if (!loaded) { navViews[section]?.requestFocus(); return }
        focusContent()
    }

    override fun onBack(): Boolean {
        if (popupObj.shown) { popupObj.hide(); return true }
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.size - 1)
            showTop()
            return true
        }
        val inNav = navCol.hasFocus()
        if (!inNav) { navViews[section]?.requestFocus(); return true }
        return false
    }

    override fun onMenuKey(): Boolean {
        popupObj.choices("Football", listOf(
            "Match Day" to { popupObj.hide(); pick("hub") },
            "Refresh now" to { popupObj.hide(); startRefresh() },
            "Scorecard" to { popupObj.hide(); pick("card") }))
        return true
    }

    // ------------------------------------------------------------------ Match Day Hub
    private fun hubPage(): View {
        val (sv, col) = FootUi.page(act)
        col.addView(FootUi.h1(act, "Match Day Hub"))
        col.addView(FootUi.buttonRow(act,
            ((if (!hubToday) "● " else "") + "Next matches") to { hubToday = false; showTop() },
            ((if (hubToday) "● " else "") + "Today") to { hubToday = true; showTop() }))
        if (Store.fdoKey(act).isEmpty())
            col.addView(FootUi.note(act, "No football-data.org key set, so only the next few days of fixtures are shown (Settings > Pair with phone).", Skin.AMBER))
        val now = System.currentTimeMillis() / 1000
        val all = ArrayList<FixtureView>()
        for (lk in Leagues.ORDER) all.addAll(eng.fixtures(lk))
        fun koOf(f: FixtureView) = Utc.parse(f.fx.utc) ?: 0L
        val today = Calendar.getInstance()
        fun isToday(t: Long): Boolean {
            val c = Calendar.getInstance(); c.timeInMillis = t * 1000
            return c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        }
        val shown = all.filter { val t = koOf(it); if (hubToday) isToday(t) else (t >= now - 3 * 3600 && t <= now + 7 * 86400L) }.sortedBy { koOf(it) }
        val f = finder()
        val loginOk = Store.hasLogin(act)
        if (shown.isEmpty()) col.addView(FootUi.note(act, if (hubToday) "No matches today." else "No matches in the next 7 days. Press Refresh now.", Skin.TEXT))
        else {
            col.addView(FootUi.row(act, listOf(Cell("Kick-off", 1.55f), Cell("Match", 2.7f), Cell("", 0.5f), Cell("Home  Draw  Away", 1.5f, Gravity.CENTER_HORIZONTAL),
                Cell("Model v odds", 1.25f), Cell(if (loginOk) "TV" else "", 1.0f, Gravity.END)), header = true))
            for (fv in shown) col.addView(pages.fixtureRow(fv, true, if (f != null && f.hasEntries) f.find(fv.fx.home, fv.fx.away, koOf(fv)).size else null))
        }
        col.addView(FootUi.note(act, "Press OK on a match for the full page and a Watch button. Model v odds is a caution cue, not a tip."))
        if (loginOk && (f == null || !f.hasEntries))
            col.addView(FootUi.note(act, "Channels are found from the Live TV guide. Open Live TV once so it loads the guide, then come back here.", Skin.DIM))
        return sv
    }

    // ------------------------------------------------------------------ refresh
    private fun startRefresh() {
        if (refreshing) { flash("Already refreshing"); return }
        refreshing = true
        stack.clear()
        val (sv, col) = FootUi.page(act)
        col.addView(FootUi.h1(act, "Refreshing football data"))
        val msg = FootUi.note(act, "Starting...", Skin.TEXT)
        val bar = BarView(act)
        col.addView(msg)
        col.addView(bar, LinearLayout.LayoutParams(-1, Skin.dp(act, 8)))
        col.addView(FootUi.note(act, "Nothing else is needed. You can keep watching or browse other pages; this carries on."))
        body.removeAllViews()
        body.addView(sv, LayoutParams(-1, -1))
        val token = Store.fdoKey(act)
        Bg.run {
            var warnings: List<String> = emptyList()
            var failure: String? = null
            try {
                if (!loaded) state.load()
                warnings = Refresh.refresh(state, token, { m, f -> Bg.ui { msg.text = m; bar.pct = (f * 100).toInt() } })
                Bg.ui { msg.text = "Working out the numbers..."; bar.pct = 96 }
                engine = newEngine()
                val now = System.currentTimeMillis() / 1000
                try {
                    val sc = Scorecard.load(io)
                    if (Scorecard.update(engine!!, sc, now)) Scorecard.save(io, sc)
                    val pc = Playercard.load(io)
                    if (Playercard.update(engine!!, pc, now)) Playercard.save(io, pc)
                } catch (e: Throwable) {
                    warnings = warnings + "Scorecards: ${e.message}"
                }
            } catch (e: Throwable) {
                Crash.log("Football refresh", e)
                failure = e.message ?: e.javaClass.simpleName
            }
            Bg.ui {
                refreshing = false
                loaded = engine != null
                finderObj = null; finderBuiltFor = 0L
                updateUpdated()
                if (failure != null) {
                    showMessage("The refresh stopped: $failure")
                } else {
                    showTop()
                    val extra = warnings.filter { !it.startsWith("No football-data.org key") }
                    flash(if (extra.isEmpty()) "Football data updated" else "Updated, with ${extra.size} warning${if (extra.size == 1) "" else "s"}: ${extra.first()}")
                }
            }
        }
    }
}
