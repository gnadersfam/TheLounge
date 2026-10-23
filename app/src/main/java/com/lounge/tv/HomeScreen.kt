package com.lounge.tv

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home: match-day banner, tonight's matches, things to carry on with, and live scores with goals and cards. */
class HomeScreen(act: MainActivity) : Screen(act) {

    private val col = LinearLayout(act)
    private val scroll = ScrollView(act)
    private val scoresBox = LinearLayout(act)
    private val scoreCards = ArrayList<View>()
    private var scoresSig = ""
    private val refresher = object : Runnable {
        override fun run() {
            if (visibility == View.VISIBLE) refreshScores()
            Bg.uiDelayed(30_000, this)
        }
    }

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Skin.dp(act, 36), Skin.dp(act, 16), Skin.dp(act, 36), Skin.dp(act, 14))
        scoresBox.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
    }

    override fun onShow() {
        build()
        startRefresh()
        Updates.checkInBackground(act, false) { if (visibility == View.VISIBLE) build() }
    }

    override fun onHide() {
        Bg.cancel(refresher)
    }

    override fun onActivityPause() {
        Bg.cancel(refresher)
    }

    override fun onActivityResume() {
        if (visibility == View.VISIBLE) {
            build()
            startRefresh()
        }
    }

    private fun startRefresh() {
        Bg.cancel(refresher)
        Bg.uiDelayed(30_000, refresher)
        refreshScores()
    }

    override fun onKeysChanged() {
        if (visibility == View.VISIBLE) build()
    }

    override fun focusFirst() {
        val l = ArrayList<View>()
        col.addFocusables(l, View.FOCUS_DOWN)
        (l.firstOrNull { it.isShown } ?: return).requestFocus()
    }

    private class Soon(val lk: String, val fx: Fixture, val minutes: Long)

    /** The nearest league match that kicks off within the next half hour (or started in the last 15 minutes). */
    private fun soonMatches(): List<Soon> {
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Soon>()
        val raw = Store.loadJson(act, "f_fixtures").obj()
        for ((lk, l) in raw) for (o in l.arr()) {
            val fx = FootJson.fixtureFrom(o)
            val ko = Utc.parse(fx.utc) ?: continue
            val mins = (ko - now) / 60
            if (mins in -15..30) out.add(Soon(lk, fx, mins))
        }
        return out.sortedBy { it.minutes }
    }

    private class Next(val lk: String, val fx: Fixture, val ko: Long)

    /** The next three matches that have not started yet (within two days). */
    private fun nextMatches(): List<Next> {
        return try {
            val now = System.currentTimeMillis() / 1000
            val out = ArrayList<Next>()
            val raw = Store.loadJson(act, "f_fixtures").obj()
            for ((lk, l) in raw) for (o in l.arr()) {
                val fx = FootJson.fixtureFrom(o)
                val ko = Utc.parse(fx.utc) ?: continue
                if (ko > now && ko - now < 2 * 86400) out.add(Next(lk, fx, ko))
            }
            out.sortedBy { it.ko }.take(3)
        } catch (e: Throwable) {
            Crash.log("Home tonight", e)
            emptyList()
        }
    }

    private fun banner(text: String, color: Int, button: String, onClick: () -> Unit): LinearLayout {
        val b = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Skin.card(act, 16, Skin.CARD, color)
            setPadding(Skin.dp(act, 20), Skin.dp(act, 12), Skin.dp(act, 14), Skin.dp(act, 12))
        }
        b.addView(Skin.text(act, text, 18f, Skin.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        b.addView(Skin.button(act, button, onClick))
        return b
    }

    private fun build() {
        col.removeAllViews()
        col.addView(Skin.text(act, "HOME", 28f, Skin.TEXT, true).apply { letterSpacing = 0.03f }, Skin.gapParams(act, bottomDp = 8))

        if (Updates.available(act)) {
            col.addView(banner("A newer version of The Lounge is ready.", Skin.MAGENTA, "Open Settings") { act.open("settings") }, Skin.gapParams(act, bottomDp = 8))
        }
        val soon = try { soonMatches() } catch (e: Throwable) { Crash.log("Home banner", e); emptyList() }
        if (soon.isNotEmpty()) {
            val first = soon.first()
            val when_ = if (first.minutes > 0) "kicks off in ${first.minutes} min" else if (first.minutes >= -1) "is kicking off now" else "started ${-first.minutes} min ago"
            val more = if (soon.size > 1) "  (+${soon.size - 1} more)" else ""
            col.addView(banner("${first.fx.home} v ${first.fx.away} $when_$more - watch?", Skin.CYAN, "Open match") {
                act.open("football", "match:${first.lk}|${first.fx.utc}|${first.fx.home}|${first.fx.away}")
            }, Skin.gapParams(act, bottomDp = 12))
        }
        val exp = Store.str(act, "acct_exp").toLongOrNull()
        if (exp != null && exp > 0) {
            val days = (exp - System.currentTimeMillis() / 1000) / 86400
            if (days in 0..14) col.addView(banner("Your IPTV subscription ends in $days day${if (days == 1L) "" else "s"}.", Skin.AMBER, "Settings") { act.open("settings") }, Skin.gapParams(act, bottomDp = 12))
            else if (days < 0) col.addView(banner("Your IPTV subscription has ended.", Skin.RED, "Settings") { act.open("settings") }, Skin.gapParams(act, bottomDp = 12))
        }

        // tonight: the next few league matches (from the football data saved on this Fire TV)
        val next = nextMatches()
        if (next.isNotEmpty()) {
            col.addView(label("TONIGHT AND NEXT"), Skin.gapParams(act, bottomDp = 6))
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in next) {
                val chip = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Skin.card(act, 14)
                    isFocusable = true
                    isClickable = true
                    setPadding(Skin.dp(act, 12), Skin.dp(act, 6), Skin.dp(act, 12), Skin.dp(act, 6))
                    setOnClickListener { act.open("football", "match:${m.lk}|${m.fx.utc}|${m.fx.home}|${m.fx.away}") }
                }
                chip.addView(Crest.badge(act, m.fx.home, 28), LinearLayout.LayoutParams(Skin.dp(act, 28), Skin.dp(act, 28)))
                chip.addView(Crest.badge(act, m.fx.away, 28), LinearLayout.LayoutParams(Skin.dp(act, 28), Skin.dp(act, 28)).apply { leftMargin = -Skin.dp(act, 8) })
                val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Skin.dp(act, 10), 0, 0, 0) }
                txt.addView(Skin.oneLine(Skin.text(act, "${m.fx.home} v ${m.fx.away}", 14f, Skin.TEXT, true)))
                txt.addView(Skin.oneLine(Skin.text(act, Skin.dayTime(m.ko), 11f, Skin.MUTED)))
                chip.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Skin.dp(act, 12) })
            }
            col.addView(line, Skin.gapParams(act, bottomDp = 8))
        }

        // carry on
        val resume = ArrayList<Pair<String, () -> Unit>>()
        val lastId = Store.str(act, "last_chan")
        if (lastId.isNotEmpty() && Store.hasLogin(act)) {
            val list = Store.loadChannels(act)?.second ?: emptyList()
            val idx = list.indexOfFirst { it.id == lastId }
            if (idx >= 0) resume.add("Live: " + list[idx].name to { act.playLive(list, idx) })
        }
        val seenSeries = HashSet<String>()
        val hideShows = History.hidden(act, true)
        val hideMovies = History.hidden(act, false)
        for (h in History.all(act)) {
            if (resume.size >= 9) break
            if (h.kind == "episode" && h.seriesId.isNotEmpty()) {
                if (!seenSeries.add(h.seriesId)) continue
                if ((hideShows[h.seriesId] ?: -1L) >= h.ts) continue
                if (h.resumable()) resume.add("${h.title}  ${h.percent()}%" to { playHist(h) })
                else if (h.percent() >= 95) resume.add("Next episode: " + h.title.substringBefore("  S") to { act.open("shows", "series:" + h.seriesId) })
            } else if (h.resumable()) {
                if (h.kind == "movie" && (hideMovies[h.id] ?: -1L) >= h.ts) continue
                resume.add("${h.title}  ${h.percent()}%" to { playHist(h) })
            }
        }
        if (resume.isNotEmpty()) {
            col.addView(label("Carry on"), Skin.gapParams(act, bottomDp = 6))
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((text, go) in resume) {
                row.addView(chip(text, go), LinearLayout.LayoutParams(Skin.dp(act, 240), -2).apply { rightMargin = Skin.dp(act, 10) })
            }
            val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            col.addView(hs, Skin.gapParams(act, bottomDp = 8))
        }

        // live scores, with goals and cards
        col.addView(label("LIVE SCORES"), Skin.gapParams(act, bottomDp = 6))
        (scoresBox.parent as? LinearLayout)?.removeView(scoresBox)
        col.addView(scoresBox, Skin.gapParams(act, bottomDp = 6))
        scoresSig = ""
        showScores(Scores.cached())
        col.addView(legend(), Skin.gapParams(act, bottomDp = 0))
    }

    private fun label(text: String): TextView =
        Skin.text(act, text, 13f, Skin.CYAN, true).apply { letterSpacing = 0.1f }

    private fun chip(text: String, go: () -> Unit): LinearLayout {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(act, 12)
            isFocusable = true
            isClickable = true
            setPadding(Skin.dp(act, 14), Skin.dp(act, 9), Skin.dp(act, 14), Skin.dp(act, 9))
            setOnClickListener { go() }
        }
        c.addView(Skin.oneLine(Skin.text(act, text, 14f, Skin.TEXT, true)))
        return c
    }

    // ------------------------------------------------------------------ live scores
    private fun refreshScores() {
        Bg.run {
            val list = Scores.load()
            Bg.ui { showScores(list) }
        }
    }

    private fun showScores(list: List<Scores.Match>) {
        val shown = list.take(6)
        val sig = Scores.loaded.toString() + shown.joinToString("|") { it.home + it.hg + it.ag + it.status + it.evs.size }
        if (sig == scoresSig && scoresBox.childCount > 0) return
        scoresSig = sig
        val focusedIdx = scoreCards.indexOfFirst { it.hasFocus() }
        scoresBox.removeAllViews()
        scoreCards.clear()
        if (shown.isEmpty()) {
            scoresBox.addView(Skin.text(act, if (Scores.loaded) "No matches on right now." else "Loading scores...", 14f, Skin.MUTED))
            return
        }
        var i = 0
        while (i < shown.size) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until 3) {
                val m = shown.getOrNull(i + j)
                if (m == null) {
                    line.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val card = scoreCard(m)
                scoreCards.add(card)
                line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Skin.dp(act, 10) })
            }
            scoresBox.addView(line, Skin.gapParams(act, bottomDp = 10))
            i += 3
        }
        if (focusedIdx >= 0) scoreCards.getOrNull(focusedIdx)?.requestFocus()
    }

    private fun logo(url: String, name: String, size: Int): View {
        if (url.isEmpty()) return Crest.badge(act, name, size)
        val f = FrameLayout(act)
        f.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF2F2F7.toInt()) }
        val iv = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = Skin.dp(act, 3)
            setPadding(p, p, p, p)
        }
        f.addView(iv, FrameLayout.LayoutParams(-1, -1))
        Img.load(iv, url, 96, 96)
        return f
    }

    private fun teamRow(url: String, name: String, score: String): LinearLayout {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(logo(url, name, 22), LinearLayout.LayoutParams(Skin.dp(act, 22), Skin.dp(act, 22)))
        r.addView(Skin.oneLine(Skin.text(act, name, 15f, Skin.TEXT, true)).apply { setPadding(Skin.dp(act, 8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(Skin.text(act, if (score.isEmpty()) "-" else score, 18f, Skin.TEXT, true))
        return r
    }

    private fun evIcon(kind: Int): View {
        val v = View(act)
        val w = if (kind == 0) 10 else 8
        val h = if (kind == 0) 10 else 12
        v.background = GradientDrawable().apply {
            when (kind) {
                0 -> { shape = GradientDrawable.OVAL; setColor(0xFFF5EEFF.toInt()) }
                1 -> { shape = GradientDrawable.RECTANGLE; cornerRadius = Skin.dp(act, 2).toFloat(); setColor(0xFFFFD62B.toInt()) }
                else -> { shape = GradientDrawable.RECTANGLE; cornerRadius = Skin.dp(act, 2).toFloat(); setColor(0xFFFF3B4E.toInt()) }
            }
        }
        v.layoutParams = LinearLayout.LayoutParams(Skin.dp(act, w), Skin.dp(act, h)).apply { rightMargin = Skin.dp(act, 7) }
        return v
    }

    private fun scoreCard(m: Scores.Match): LinearLayout {
        val live = m.state == "in"
        val stroke = if (live) Skin.MAGENTA else Skin.LINE
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(act, 14, Skin.CARD, stroke)
            isFocusable = true
            isClickable = true
            setPadding(Skin.dp(act, 13), Skin.dp(act, 9), Skin.dp(act, 13), Skin.dp(act, 9))
            setOnClickListener { openMatch(m) }
        }
        val statusColor = when (m.state) { "in" -> Skin.RED; "pre" -> Skin.CYAN; else -> Skin.DIM }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(Skin.oneLine(Skin.text(act, m.status, 11f, statusColor, true)), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Skin.oneLine(Skin.text(act, m.lg, 11f, Skin.DIM)))
        c.addView(head, Skin.gapParams(act, bottomDp = 6))
        val showScore = m.state != "pre"
        c.addView(teamRow(m.hLogo, m.home, if (showScore) m.hg else ""), Skin.gapParams(act, bottomDp = 4))
        c.addView(teamRow(m.aLogo, m.away, if (showScore) m.ag else ""), Skin.gapParams(act, bottomDp = 6))
        val evBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = Skin.dp(act, 52)
        }
        evBox.addView(View(act).apply { setBackgroundColor(Skin.CARD_FOCUS) }, LinearLayout.LayoutParams(-1, Skin.dp(act, 2)).apply { bottomMargin = Skin.dp(act, 6) })
        for (e in m.evs.takeLast(3)) {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(evIcon(e.kind))
            r.addView(Skin.text(act, e.minute, 12f, Skin.DIM).apply { minWidth = Skin.dp(act, 34) })
            r.addView(Skin.oneLine(Skin.text(act, e.who, 12f, e.color, true)))
            r.addView(Skin.oneLine(Skin.text(act, e.team, 12f, Skin.DIM)).apply { setPadding(Skin.dp(act, 7), 0, 0, 0) })
            evBox.addView(r, Skin.gapParams(act, bottomDp = 3))
        }
        c.addView(evBox)
        return c
    }

    private fun legend(): LinearLayout {
        val l = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        l.addView(Skin.text(act, "OK on a score opens that match.", 12f, Skin.DIM).apply { setPadding(0, 0, Skin.dp(act, 16), 0) })
        for ((k, t) in listOf(0 to "goal", 1 to "yellow", 2 to "red")) {
            l.addView(evIcon(k))
            l.addView(Skin.text(act, t, 12f, Skin.DIM).apply { setPadding(0, 0, Skin.dp(act, 14), 0) })
        }
        return l
    }

    private fun sameTeam(a: String, names: List<String>): Boolean {
        val n = TeamNames.norm(a)
        return names.any {
            val x = TeamNames.norm(it)
            x == n || (x.length > 3 && n.length > 3 && (n.contains(x) || x.contains(n)))
        }
    }

    private fun findFixture(m: Scores.Match): Pair<String, Fixture>? {
        return try {
            val raw = Store.loadJson(act, "f_fixtures").obj()
            for ((lk, l) in raw) for (o in l.arr()) {
                val fx = FootJson.fixtureFrom(o)
                val ko = Utc.parse(fx.utc) ?: continue
                if (Math.abs(ko - m.whenSec) > 86400) continue
                if (sameTeam(fx.home, m.hNames) && sameTeam(fx.away, m.aNames)) return lk to fx
            }
            null
        } catch (e: Throwable) {
            null
        }
    }

    /** Live match: go straight to the channel showing it. Otherwise open its match page. */
    private fun openMatch(m: Scores.Match) {
        if (m.state == "in" && Store.hasLogin(act)) {
            val hits = Scores.channelsFor(act, m)
            if (hits.isNotEmpty()) {
                act.playLive(hits, 0)
                return
            }
            Toast.makeText(act, "No channel is showing that match right now", Toast.LENGTH_SHORT).show()
        }
        val f = findFixture(m)
        if (f != null) act.open("football", "match:${f.first}|${f.second.utc}|${f.second.home}|${f.second.away}")
        else act.open("football")
    }

    private fun playHist(h: Hist) {
        val xt = Session.xt ?: Store.xtream(act) ?: return
        Session.xt = xt
        val url = if (h.kind == "episode") xt.episodeUrl(h.id, h.ext) else xt.movieUrl(h.id, h.ext)
        act.playVod(h.title, url, h.key, h.pos)
    }
}
