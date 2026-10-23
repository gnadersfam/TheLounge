package com.lounge.tv

import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Home: match-day banner, things to carry on with, and big buttons for every part of the app. */
class HomeScreen(act: MainActivity) : Screen(act) {

    private val col = LinearLayout(act)
    private val scroll = ScrollView(act)

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Skin.dp(act, 40), Skin.dp(act, 24), Skin.dp(act, 36), Skin.dp(act, 30))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
    }

    override fun onShow() {
        build()
        Updates.checkInBackground(act, false) { if (visibility == View.VISIBLE) build() }
    }

    override fun onActivityResume() {
        if (visibility == View.VISIBLE) build()
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

    private fun tile(icon: Int, label: String, sub: String, open: () -> Unit): LinearLayout {
        val t = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Skin.card(act, 18)
            isFocusable = true
            isClickable = true
            setPadding(Skin.dp(act, 10), Skin.dp(act, 16), Skin.dp(act, 10), Skin.dp(act, 14))
            setOnClickListener { open() }
        }
        val img = ImageView(act).apply { setImageResource(icon); setColorFilter(Skin.CYAN) }
        t.addView(img, LinearLayout.LayoutParams(Skin.dp(act, 44), Skin.dp(act, 44)))
        t.addView(Skin.text(act, label, 19f, Skin.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, Skin.dp(act, 8), 0, 0) })
        if (sub.isNotEmpty()) t.addView(Skin.oneLine(Skin.text(act, sub, 13f, Skin.MUTED)).apply { gravity = Gravity.CENTER })
        return t
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
        col.addView(Skin.text(act, "HOME", 40f, Skin.TEXT, true).apply { letterSpacing = 0.03f }, Skin.gapParams(act, bottomDp = 12))

        if (Updates.available(act)) {
            col.addView(banner("A newer version of The Lounge is ready.", Skin.MAGENTA, "Open Settings") { act.open("settings") }, Skin.gapParams(act, bottomDp = 12))
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
            col.addView(Skin.text(act, "TONIGHT AND NEXT", 20f, Skin.CYAN, true).apply { letterSpacing = 0.1f }, Skin.gapParams(act, bottomDp = 8))
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in next) {
                val chip = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Skin.card(act, 16)
                    isFocusable = true
                    isClickable = true
                    setPadding(Skin.dp(act, 14), Skin.dp(act, 8), Skin.dp(act, 14), Skin.dp(act, 8))
                    setOnClickListener { act.open("football", "match:${m.lk}|${m.fx.utc}|${m.fx.home}|${m.fx.away}") }
                }
                chip.addView(Crest.badge(act, m.fx.home, 30), LinearLayout.LayoutParams(Skin.dp(act, 30), Skin.dp(act, 30)))
                chip.addView(Crest.badge(act, m.fx.away, 30), LinearLayout.LayoutParams(Skin.dp(act, 30), Skin.dp(act, 30)).apply { leftMargin = -Skin.dp(act, 8) })
                val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Skin.dp(act, 10), 0, 0, 0) }
                txt.addView(Skin.oneLine(Skin.text(act, "${m.fx.home} v ${m.fx.away}", 16f, Skin.TEXT, true)))
                txt.addView(Skin.oneLine(Skin.text(act, Skin.dayTime(m.ko), 13f, Skin.MUTED)))
                chip.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Skin.dp(act, 12) })
            }
            col.addView(line, Skin.gapParams(act, bottomDp = 14))
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
            col.addView(Skin.text(act, "Carry on", 20f, Skin.CYAN, true), Skin.gapParams(act, bottomDp = 8))
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, go) in resume) {
                val card = Skin.choice(act, label) { go() }
                row.addView(card, LinearLayout.LayoutParams(Skin.dp(act, 260), -2).apply { rightMargin = Skin.dp(act, 10) })
            }
            val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            col.addView(hs, Skin.gapParams(act, bottomDp = 18))
        }

        // big buttons
        val live = Store.hasLogin(act)
        val rows = listOf(
            listOf(
                Triple(R.drawable.ic_tv, "Live TV", "Guide and channels") to { act.open("live") },
                Triple(R.drawable.ic_movies, "Movies", "English only") to { act.open("movies") },
                Triple(R.drawable.ic_shows, "Shows", "Series and episodes") to { act.open("shows") }),
            listOf(
                Triple(R.drawable.ic_football, "Football", "Match Day and predictions") to { act.open("football") },
                Triple(R.drawable.ic_music, "Music", "Spotify remote") to { act.open("music") },
                Triple(R.drawable.ic_settings, "Settings", "Pair, update, backup") to { act.open("settings") })
        )
        for (r in rows) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((t, go) in r) {
                line.addView(tile(t.first, t.second, if (!live && t.second in listOf("Live TV", "Movies", "Shows")) "Pair with phone first" else t.third) { go() },
                    LinearLayout.LayoutParams(0, Skin.dp(act, 112), 1f).apply { rightMargin = Skin.dp(act, 12) })
            }
            col.addView(line, Skin.gapParams(act, bottomDp = 12))
        }
    }

    private fun playHist(h: Hist) {
        val xt = Session.xt ?: Store.xtream(act) ?: return
        Session.xt = xt
        val url = if (h.kind == "episode") xt.episodeUrl(h.id, h.ext) else xt.movieUrl(h.id, h.ext)
        act.playVod(h.title, url, h.key, h.pos)
    }
}
