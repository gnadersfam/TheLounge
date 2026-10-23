package com.lounge.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** What the football pages need from the screen they are shown in. */
interface FootHost {
    val activity: MainActivity
    val eng: Engine
    val state: FootState
    val picks: Picks
    val io: Persist
    fun push(build: () -> View)
    fun rerender()
    fun flash(msg: String)
    fun popup(): Popup
    fun finder(): TvFinder?
    fun channels(): List<Chan>
    /** Rebuild the numbers from saved data (in the background), then run [done] on screen. */
    fun reload(done: () -> Unit)
}

/** The football pages: league, match, form, expected table, picks, watch-list and scorecards. */
class FootPages(private val h: FootHost) {
    private val c: Context get() = h.activity

    var sortIdx = 0
    var leagueTab = "table"
    var formLeague = "PL"
    var formN = 6
    var expLeague = "PL"
    var cardMode = "match"
    var cardLeague = "all"
    var cardSrc = "all"
    private val SORTS = listOf("Team", "Booked", "Fouls", "Fouled", "Shots", "On target")

    private fun nowSec() = System.currentTimeMillis() / 1000
    private fun leagueName(lk: String) = Leagues.ALL.getValue(lk).name
    private fun ko(fx: Fixture): Long = Utc.parse(fx.utc) ?: 0L

    private fun toggleBtn(label: String, on: Boolean, act: () -> Unit) =
        Skin.button(c, (if (on) "● " else "") + label, act)

    private fun picker(vararg items: Triple<String, Boolean, () -> Unit>): LinearLayout {
        val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Skin.dp(c, 6), 0, Skin.dp(c, 6)) }
        for ((label, on, act) in items) r.addView(toggleBtn(label, on, act), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(c, 10) })
        return r
    }

    // ------------------------------------------------------------------ match rows
    fun agreeColor(a: Agreement?) = when (a?.code) { "agree" -> Skin.GREEN; "disagree" -> Skin.RED; null -> Skin.DIM; else -> Skin.AMBER }

    fun fixtureRow(fv: FixtureView, showLeague: Boolean, channelCount: Int?): View {
        val p = fv.pred
        val a = Model.agreement(p)
        val cells = ArrayList<Cell>()
        cells.add(Cell(Skin.dayTime(ko(fv.fx)), 1.55f, color = Skin.CYAN))
        cells.add(Cell("${fv.fx.home} v ${fv.fx.away}", 2.7f, bold = true, crest = listOf(fv.fx.home, fv.fx.away)))
        if (showLeague) cells.add(Cell(fv.league, 0.5f, color = Skin.DIM))
        cells.add(Cell("${FootUi.pct(p.home)}  ${FootUi.pct(p.draw)}  ${FootUi.pct(p.away)}", 1.5f, Gravity.CENTER_HORIZONTAL))
        cells.add(Cell(a?.label ?: "", 1.25f, color = agreeColor(a)))
        if (channelCount != null) cells.add(Cell(if (channelCount > 0) "$channelCount channel${if (channelCount == 1) "" else "s"}" else "", 1.0f, Gravity.END, Skin.GREEN))
        return FootUi.row(c, cells) { h.push { matchPage(fv.league, fv.fx) } }
    }

    // ------------------------------------------------------------------ league page
    fun leaguePage(lk: String): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, leagueName(lk)))
        col.addView(picker(
            Triple("Table", leagueTab == "table") { leagueTab = "table"; h.rerender() },
            Triple("Upcoming matches", leagueTab == "up") { leagueTab = "up"; h.rerender() }))
        if (leagueTab == "table") {
            val t = h.eng.league.getValue(lk).table
            if (t.isEmpty()) { col.addView(FootUi.note(c, "No results yet for this season.")); return sv }
            col.addView(FootUi.row(c, listOf(Cell("#", 0.4f), Cell("Team", 2.3f), Cell("P", 0.5f, FootUi.RIGHT), Cell("W", 0.5f, FootUi.RIGHT),
                Cell("D", 0.5f, FootUi.RIGHT), Cell("L", 0.5f, FootUi.RIGHT), Cell("GF", 0.55f, FootUi.RIGHT), Cell("GA", 0.55f, FootUi.RIGHT),
                Cell("GD", 0.6f, FootUi.RIGHT), Cell("Pts", 0.6f, FootUi.RIGHT), Cell("xGF", 0.75f, FootUi.RIGHT), Cell("xGA", 0.75f, FootUi.RIGHT),
                Cell("xGD", 0.75f, FootUi.RIGHT)), header = true))
            for (r in t) col.addView(FootUi.row(c, listOf(Cell("${r.pos}", 0.4f, color = Skin.DIM), Cell(r.team, 2.3f, bold = true, crest = listOf(r.team)),
                Cell("${r.P}", 0.5f, FootUi.RIGHT), Cell("${r.W}", 0.5f, FootUi.RIGHT), Cell("${r.D}", 0.5f, FootUi.RIGHT), Cell("${r.L}", 0.5f, FootUi.RIGHT),
                Cell("${r.GF}", 0.55f, FootUi.RIGHT), Cell("${r.GA}", 0.55f, FootUi.RIGHT), Cell(FootUi.sign(r.GD.toDouble(), 0), 0.6f, FootUi.RIGHT),
                Cell("${r.Pts}", 0.6f, FootUi.RIGHT, Skin.CYAN, true), Cell("%.1f".format(r.xGF), 0.75f, FootUi.RIGHT, Skin.MUTED),
                Cell("%.1f".format(r.xGA), 0.75f, FootUi.RIGHT, Skin.MUTED), Cell(FootUi.sign(r.xGD), 0.75f, FootUi.RIGHT, Skin.MUTED))) {
                h.push { teamPage(lk, r.team) }
            })
            col.addView(FootUi.note(c, "Press OK on a team for its match log. xG columns are expected goals for, against and the difference."))
        } else {
            val fxs = h.eng.fixtures(lk)
            if (fxs.isEmpty()) col.addView(FootUi.note(c, "No upcoming matches saved. Press Refresh now (a football-data.org key shows the whole season)."))
            val limit = nowSec() - 3 * 3600
            for (fv in fxs.filter { ko(it.fx) >= limit }.take(40)) {
                col.addView(fixtureRow(fv, false, null))
                val o = fv.fx.odds
                if (o != null) col.addView(FootUi.note(c, "      Bookmaker odds ${FootUi.odds(o)}" + (fv.fx.o25?.let { "   Over/under 2.5: ${FootUi.odds(it)}" } ?: "")))
            }
            col.addView(FootUi.note(c, "Chances are home / draw / away. Press OK on a match for the full page. Odds are only known for the next few days."))
        }
        return sv
    }

    // ------------------------------------------------------------------ team log
    fun teamPage(lk: String, team: String): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, team))
        col.addView(FootUi.note(c, leagueName(lk) + " - every match this season, newest first"))
        col.addView(FootUi.row(c, listOf(Cell("Date", 1.1f), Cell("", 0.4f), Cell("Opponent", 2.2f), Cell("Score", 0.8f), Cell("xG", 0.7f, FootUi.RIGHT),
            Cell("xGA", 0.7f, FootUi.RIGHT), Cell("Fouls", 0.7f, FootUi.RIGHT), Cell("Yel", 0.6f, FootUi.RIGHT), Cell("Referee", 1.6f)), header = true))
        for (r in h.eng.teamView(lk, team)) col.addView(FootUi.row(c, listOf(Cell(r.date, 1.1f, color = Skin.MUTED), Cell(r.venue, 0.4f, color = Skin.DIM),
            Cell(r.opp, 2.2f, bold = true), Cell(r.score, 0.8f, color = Skin.CYAN, bold = true), Cell("%.1f".format(r.xg), 0.7f, FootUi.RIGHT),
            Cell("%.1f".format(r.xga), 0.7f, FootUi.RIGHT), Cell(r.fouls?.toString() ?: "-", 0.7f, FootUi.RIGHT), Cell(r.yc?.toString() ?: "-", 0.6f, FootUi.RIGHT),
            Cell(r.ref, 1.6f, color = Skin.MUTED))))
        return sv
    }

    // ------------------------------------------------------------------ match page
    private fun panelText(mv: MatchView, lk: String): String {
        val p = mv.pred
        val sb = StringBuilder()
        sb.append(mv.fx.home).append(" v ").append(mv.fx.away).append('\n')
        sb.append("Home ${FootUi.pct(p.home)}  Draw ${FootUi.pct(p.draw)}  Away ${FootUi.pct(p.away)}\n")
        sb.append("Likeliest ${p.scoreH}-${p.scoreA}   Both score ${FootUi.pct(p.btts)}   Over 2.5 ${FootUi.pct(p.over25)}")
        Model.tightText(p, mv.fx.home, mv.fx.away).let { if (it.isNotEmpty()) sb.append('\n').append(it) }
        if (mv.refName.isNotEmpty()) sb.append("\nReferee ${mv.refName}")
        val top = mv.players.sortedByDescending { it.booked }.take(3)
        if (top.isNotEmpty()) sb.append("\nMost likely booked: " + top.joinToString(", ") { it.name.substringAfterLast(' ') + " " + FootUi.pct(it.booked) })
        return sb.toString()
    }

    private fun channelListPage(title: String, list: List<Chan>, panel: String): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, title))
        if (list.isEmpty()) col.addView(FootUi.note(c, "Nothing found."))
        for ((i, ch) in list.withIndex()) col.addView(FootUi.row(c, listOf(Cell(ch.name, 3f, bold = true), Cell(ch.cat, 1.6f, color = Skin.MUTED))) {
            h.activity.playLive(list, i, panel)
        })
        return sv
    }

    private fun ukSports(): List<Chan> {
        val nameRe = Regex("sport|football|premier|efl|tnt|dazn|viaplay|main event|\\bbt\\b|eurosport|bbc|itv", RegexOption.IGNORE_CASE)
        val catRe = Regex("sport|football|soccer|premier|epl|efl|champions|dazn|sky|tnt|bt |match|league|live event", RegexOption.IGNORE_CASE)
        val seen = HashSet<String>(); val out = ArrayList<Chan>()
        for (ch in h.channels()) {
            if (TvFinder.regionOf(ch.name) != "UK") continue
            if (!nameRe.containsMatchIn(ch.name) && !catRe.containsMatchIn(ch.cat)) continue
            if (!seen.add(ch.name.lowercase().replace(Regex("[^a-z0-9]+"), ""))) continue
            out.add(ch)
        }
        return out.sortedWith(compareBy({ -TvFinder.qualityOf(it.name) }, { it.name })).take(60)
    }

    private fun watch(mv: MatchView, lk: String) {
        val fx = mv.fx
        if (!Store.hasLogin(c)) { h.flash("Pair your IPTV login first (Settings)"); return }
        val f = h.finder()
        val hits = f?.find(fx.home, fx.away, ko(fx)) ?: emptyList()
        val panel = panelText(mv, lk)
        if (hits.isNotEmpty()) {
            h.activity.playLive(hits.map { it.chan }, 0, panel)
            return
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("UK sports channels" to { h.popup().hide(); h.push { channelListPage("UK sports channels", ukSports(), panel) } })
        items.add("Search any channel" to {
            h.popup().hide()
            h.popup().search("Channel name", "") { q ->
                if (q.isNotEmpty()) h.push { channelListPage("Channels matching \"$q\"",
                    h.channels().filter { it.name.contains(q, true) }.sortedWith(compareBy({ TvFinder.regionOf(it.name) != "UK" }, { -TvFinder.qualityOf(it.name) }, { it.name })).take(100), panel) }
            }
        })
        val why = if (f == null || !f.hasEntries) "No listing for this match in the TV guide yet" else "No channel lists this match yet"
        h.popup().choices(why, items)
    }

    fun matchPage(lk: String, fx: Fixture): View {
        val mv = h.eng.matchView(lk, fx)
        val p = mv.pred
        val (sv, col) = FootUi.page(c)
        val titleRow = android.widget.LinearLayout(c).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val big = Skin.dp(c, 46)
        titleRow.addView(Crest.badge(c, fx.home, 46), android.widget.LinearLayout.LayoutParams(big, big).apply { rightMargin = Skin.dp(c, 12) })
        titleRow.addView(FootUi.h1(c, "${fx.home} v ${fx.away}"))
        titleRow.addView(Crest.badge(c, fx.away, 46), android.widget.LinearLayout.LayoutParams(big, big).apply { leftMargin = Skin.dp(c, 12) })
        col.addView(titleRow)
        col.addView(FootUi.note(c, "${leagueName(lk)}  -  ${Skin.dayTime(ko(fx))}  -  ${mv.lineupStatus}", Skin.CYAN))
        if (TvFinder.isUkBlackout(ko(fx))) col.addView(FootUi.note(c, "Saturday 3pm: UK channels are not allowed to show this one.", Skin.AMBER))

        col.addView(FootUi.h2(c, "Prediction"))
        col.addView(FootUi.row(c, listOf(Cell("${fx.home} win ${FootUi.pct(p.home)}", 1f, bold = true), Cell("Draw ${FootUi.pct(p.draw)}", 1f, Gravity.CENTER_HORIZONTAL, bold = true),
            Cell("${fx.away} win ${FootUi.pct(p.away)}", 1f, FootUi.RIGHT, bold = true)), size = 17f))
        col.addView(FootUi.row(c, listOf(Cell("Likeliest score ${p.scoreH}-${p.scoreA} (${FootUi.pct(p.scoreP)})", 1.2f), Cell("Both teams score ${FootUi.pct(p.btts)}", 1f),
            Cell("Over 2.5 goals ${FootUi.pct(p.over25)}", 1f), Cell("Expected goals %.1f - %.1f".format(p.lh, p.la), 1.2f, FootUi.RIGHT))))
        Model.tightText(p, fx.home, fx.away).let { if (it.isNotEmpty()) col.addView(FootUi.note(c, it, Skin.AMBER)) }
        val a = mv.agree
        if (fx.odds != null) {
            col.addView(FootUi.note(c, "Bookmaker odds (home / draw / away): ${FootUi.odds(fx.odds)}" + (fx.o25?.let { "     over / under 2.5: ${FootUi.odds(it)}" } ?: "")))
            if (a != null) {
                col.addView(FootUi.note(c, "Model alone ${a.pm.joinToString("  ") { FootUi.pct(it) }}    Bookmakers ${a.mk.joinToString("  ") { FootUi.pct(it) }}"))
                col.addView(FootUi.note(c, "${a.label}: a caution cue, not a tip. On past matches the odds beat the model.", agreeColor(a)))
            }
        } else col.addView(FootUi.note(c, "No bookmaker odds yet (only the next few days have them)."))

        col.addView(FootUi.h2(c, "Referee"))
        val ref = mv.ref
        col.addView(FootUi.note(c, if (mv.refName.isEmpty()) "Not announced yet." else if (ref == null) "${mv.refName} - no history in the data yet."
        else "${ref.name}: ${ref.n} matches, %.1f yellows and %.2f reds a match, %.0f fouls (league average %.1f yellows). Cards factor %.2f.".format(
            ref.yc, ref.rc, ref.fouls, h.eng.refs.league.getValue("yc"), ref.factor), Skin.TEXT))

        col.addView(FootUi.h2(c, "Recent form"))
        for ((side, team) in listOf("home" to fx.home, "away" to fx.away)) {
            val f = mv.form.getValue(side)
            col.addView(FootUi.row(c, listOf(Cell(team, 1.6f, bold = true)) + f.map { Cell(it.res, 0.35f, Gravity.CENTER_HORIZONTAL, FootUi.resColor(it.res), true) } +
                listOf(Cell(f.joinToString("  ") { "${it.venue} ${it.opp} ${it.score}" }, 5f, color = Skin.DIM)), size = 14f))
        }

        val flags = mv.susp.getValue("home").map { fx.home to it } + mv.susp.getValue("away").map { fx.away to it }
        if (flags.isNotEmpty()) {
            col.addView(FootUi.h2(c, "Suspension warnings"))
            for ((team, f) in flags) col.addView(FootUi.note(c, "$team - ${f.name}: ${f.text}", if (f.code == "out") Skin.RED else Skin.AMBER))
        }

        col.addView(FootUi.h2(c, "Actions"))
        val pick = h.picks.getPick(lk, fx)
        val started = ko(fx) <= nowSec()
        col.addView(FootUi.buttonRow(c,
            "Watch" to { watch(mv, lk) },
            "Refresh this match" to { refreshMatch(lk, fx) },
            (if (started) "Pick closed" else "My pick: " + (pick?.let { Picks.NAMES[it] } ?: "none")) to {
                if (started) h.flash("Picks close at kick-off") else h.popup().choices("Your pick (the model's chances are saved with it)", listOf(
                    "Home win" to { setPick(lk, fx, "H", p) }, "Draw" to { setPick(lk, fx, "D", p) }, "Away win" to { setPick(lk, fx, "A", p) },
                    "Clear my pick" to { h.popup().hide(); h.picks.clearPick(lk, fx); h.rerender() }))
            },
            "Head to head, form and xG" to { h.push { trendsPage(lk, fx) } }))

        col.addView(FootUi.h2(c, "Players"))
        if (mv.players.isEmpty()) {
            col.addView(FootUi.note(c, "No team sheets or earlier lineups to base player chances on yet."))
        } else {
            col.addView(picker(Triple("Sorted by: ${SORTS[sortIdx]}", true) { sortIdx = (sortIdx + 1) % SORTS.size; h.rerender() }))
            col.addView(FootUi.note(c, "Chances for each player. Press OK on a player to add or remove them from your watch-list."))
            col.addView(FootUi.row(c, listOf(Cell("Player", 2.5f), Cell("Role", 1.35f), Cell("Yel", 0.5f, FootUi.RIGHT), Cell("Booked", 0.85f, FootUi.RIGHT),
                Cell("Fouls 1+", 0.85f, FootUi.RIGHT), Cell("2+", 0.55f, FootUi.RIGHT), Cell("Fouled 1+", 0.95f, FootUi.RIGHT), Cell("Shots 1+", 0.85f, FootUi.RIGHT),
                Cell("2+", 0.55f, FootUi.RIGHT), Cell("On tgt 1+", 0.9f, FootUi.RIGHT)), header = true))
            val rows = when (sortIdx) {
                1 -> mv.players.sortedByDescending { it.booked }
                2 -> mv.players.sortedByDescending { it.fouls[0] }
                3 -> mv.players.sortedByDescending { it.fouled[0] }
                4 -> mv.players.sortedByDescending { it.shots[0] }
                5 -> mv.players.sortedByDescending { it.sot[0] }
                else -> mv.players.sortedWith(compareBy({ it.side != "home" }, { it.role != "Starter" }, { -it.plays }))
            }
            var lastTeam = ""
            for (r in rows) {
                if (sortIdx == 0 && r.team != lastTeam) { lastTeam = r.team; col.addView(FootUi.note(c, r.team, Skin.CYAN)) }
                val role = if (r.role == "Starter" && r.plays >= 0.999) "Starter" else "${r.role} (${FootUi.pct(r.plays)})"
                val watched = h.picks.inWatch(r.id)
                val nameColor = if (r.flag == "SUSPENDED") Skin.RED else if (r.flag.isNotEmpty()) Skin.AMBER else Skin.TEXT
                col.addView(FootUi.row(c, listOf(Cell((if (watched) "★ " else "") + r.name + (if (r.flag.isNotEmpty()) "  [${r.flag}]" else ""), 2.5f, color = nameColor, bold = true, faceId = r.id, faceName = r.name),
                    Cell(role, 1.35f, color = Skin.MUTED), Cell("${r.ycSeason}", 0.5f, FootUi.RIGHT, if (r.flag.isNotEmpty()) Skin.AMBER else Skin.DIM),
                    Cell(FootUi.pct(r.booked), 0.85f, FootUi.RIGHT, if (r.booked >= 0.2) Skin.AMBER else Skin.TEXT, true),
                    Cell(FootUi.pct(r.fouls[0]), 0.85f, FootUi.RIGHT), Cell(FootUi.pct(r.fouls[1]), 0.55f, FootUi.RIGHT, Skin.MUTED),
                    Cell(FootUi.pct(r.fouled[0]), 0.95f, FootUi.RIGHT), Cell(FootUi.pct(r.shots[0]), 0.85f, FootUi.RIGHT),
                    Cell(FootUi.pct(r.shots[1]), 0.55f, FootUi.RIGHT, Skin.MUTED), Cell(FootUi.pct(r.sot[0]), 0.9f, FootUi.RIGHT)), size = 13.5f) {
                    val on = h.picks.toggleWatch(r.id, r.name, r.team)
                    h.flash(if (on) "${r.name} added to your watch-list" else "${r.name} removed from your watch-list")
                    h.rerender()
                })
            }
            col.addView(FootUi.note(c, "Percentages are model estimates per appearance. Booked chances are modest at best (roughly 8-25%)."))
        }
        return sv
    }

    private fun setPick(lk: String, fx: Fixture, code: String, p: Pred) {
        h.popup().hide()
        h.picks.setPick(lk, fx, code, p, nowSec())
        h.flash("Pick saved: ${Picks.NAMES[code]}")
        h.rerender()
    }

    private fun refreshMatch(lk: String, fx: Fixture) {
        h.flash("Checking team sheets, referee and odds...")
        Bg.run {
            val got = Refresh.fetchMatch(lk, fx, h.state)
            val msg = Refresh.applyMatch(h.state, lk, fx, got)
            val warn = if (got.warnings.isNotEmpty()) " (" + got.warnings.first() + ")" else ""
            h.reload { h.flash("This match: $msg$warn"); h.rerender() }
        }
    }

    // ------------------------------------------------------------------ head to head, form and xG
    fun trendsPage(lk: String, fx: Fixture): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, "${fx.home} v ${fx.away}"))
        col.addView(FootUi.note(c, "Head to head, form and xG trends"))
        val hh = h.eng.h2h(lk, fx.home, fx.away)
        col.addView(FootUi.h2(c, "Head to head (this season and last)"))
        if (hh.rows.isEmpty()) col.addView(FootUi.note(c, "No earlier meetings in the data."))
        else {
            col.addView(FootUi.note(c, "${fx.home} ${hh.hw} wins   draws ${hh.d}   ${fx.away} ${hh.aw} wins   average %.1f goals".format(hh.avgGoals)))
            col.addView(FootUi.row(c, listOf(Cell("Date", 1f), Cell("Match", 3f), Cell("Score", 0.8f), Cell("xG", 1.2f), Cell("Yellows", 0.8f, FootUi.RIGHT)), header = true))
            for (r in hh.rows) col.addView(FootUi.row(c, listOf(Cell(r.date, 1f, color = Skin.MUTED), Cell("${r.home} v ${r.away}", 3f), Cell(r.score, 0.8f, color = Skin.CYAN, bold = true),
                Cell(r.xg, 1.2f, color = Skin.MUTED), Cell(r.yc, 0.8f, FootUi.RIGHT))))
        }
        col.addView(FootUi.h2(c, "Form table (last 6 matches)"))
        col.addView(formTable(lk, 6, setOf(fx.home, fx.away)))
        col.addView(FootUi.h2(c, "xG over the last 10 matches"))
        for (team in listOf(fx.home, fx.away)) {
            val t = h.eng.trend(lk, team)
            col.addView(FootUi.note(c, "$team: ${t.verdict}", Skin.TEXT))
            val chart = TrendChart(c).apply { title = team; xg = t.rows.map { it.xg }; xga = t.rows.map { it.xga }; avgF = t.avgF; avgA = t.avgA }
            col.addView(chart, LinearLayout.LayoutParams(-1, Skin.dp(c, 190)).apply { bottomMargin = Skin.dp(c, 8) })
        }
        col.addView(FootUi.note(c, "Dashed lines are the season averages. Solid cyan = chances created, magenta = chances conceded."))
        return sv
    }

    private fun formTable(lk: String, n: Int, mark: Set<String>): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(FootUi.row(c, listOf(Cell("#", 0.4f), Cell("Team", 2.2f), Cell("Last $n", 1.8f), Cell("P", 0.5f, FootUi.RIGHT), Cell("Pts", 0.6f, FootUi.RIGHT),
            Cell("GF", 0.55f, FootUi.RIGHT), Cell("GA", 0.55f, FootUi.RIGHT), Cell("xGF", 0.75f, FootUi.RIGHT), Cell("xGA", 0.75f, FootUi.RIGHT), Cell("xGD", 0.75f, FootUi.RIGHT)), header = true))
        for ((i, r) in h.eng.formTable(lk, n).withIndex()) {
            val hl = r.team in mark
            box.addView(FootUi.row(c, listOf(Cell("${i + 1}", 0.4f, color = Skin.DIM), Cell(r.team, 2.2f, color = if (hl) Skin.CYAN else Skin.TEXT, bold = true),
                Cell(r.seq.joinToString(" "), 1.8f, color = Skin.MUTED), Cell("${r.p}", 0.5f, FootUi.RIGHT), Cell("${r.pts}", 0.6f, FootUi.RIGHT, Skin.CYAN, true),
                Cell("${r.gf}", 0.55f, FootUi.RIGHT), Cell("${r.ga}", 0.55f, FootUi.RIGHT), Cell("%.1f".format(r.xgf), 0.75f, FootUi.RIGHT, Skin.MUTED),
                Cell("%.1f".format(r.xga), 0.75f, FootUi.RIGHT, Skin.MUTED), Cell(FootUi.sign(r.xgd), 0.75f, FootUi.RIGHT, if (r.xgd >= 0) Skin.GREEN else Skin.RED))) {
                h.push { teamPage(lk, r.team) }
            })
        }
        return box
    }

    // ------------------------------------------------------------------ form and expected pages
    fun formPage(): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, "Form"))
        col.addView(FootUi.note(c, "Every team ranked on its last few league results."))
        col.addView(picker(
            Triple("Premier League", formLeague == "PL") { formLeague = "PL"; h.rerender() },
            Triple("Championship", formLeague == "ELC") { formLeague = "ELC"; h.rerender() },
            Triple("Last $formN matches", true) { formN = when (formN) { 5 -> 6; 6 -> 8; 8 -> 10; else -> 5 }; h.rerender() }))
        col.addView(formTable(formLeague, formN, emptySet()))
        return sv
    }

    fun expectedPage(): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, "Expected table"))
        col.addView(FootUi.note(c, "The league table the xG says the teams deserve, beside the real one. Amber = more points than the chances earned; green = fewer, could climb."))
        col.addView(picker(
            Triple("Premier League", expLeague == "PL") { expLeague = "PL"; h.rerender() },
            Triple("Championship", expLeague == "ELC") { expLeague = "ELC"; h.rerender() }))
        col.addView(FootUi.row(c, listOf(Cell("#", 0.4f), Cell("Team", 2.3f), Cell("P", 0.5f, FootUi.RIGHT), Cell("Pts", 0.6f, FootUi.RIGHT), Cell("xPts", 0.8f, FootUi.RIGHT),
            Cell("Pts gap", 0.9f, FootUi.RIGHT), Cell("xG pos", 0.8f, FootUi.RIGHT), Cell("Places", 0.8f, FootUi.RIGHT)), header = true))
        for (r in h.eng.expectedTable(expLeague)) {
            val gapColor = if (r.ptsGap > 1.5) Skin.AMBER else if (r.ptsGap < -1.5) Skin.GREEN else Skin.TEXT
            col.addView(FootUi.row(c, listOf(Cell("${r.pos}", 0.4f, color = Skin.DIM), Cell(r.team, 2.3f, bold = true), Cell("${r.P}", 0.5f, FootUi.RIGHT),
                Cell("${r.Pts}", 0.6f, FootUi.RIGHT, Skin.CYAN, true), Cell("%.1f".format(r.xPts), 0.8f, FootUi.RIGHT, Skin.MUTED),
                Cell(FootUi.sign(r.ptsGap), 0.9f, FootUi.RIGHT, gapColor, true), Cell("${r.xpos}", 0.8f, FootUi.RIGHT, Skin.MUTED),
                Cell(FootUi.sign(r.posGap.toDouble(), 0), 0.8f, FootUi.RIGHT, gapColor))) { h.push { teamPage(expLeague, r.team) } })
        }
        col.addView(FootUi.note(c, "Places: + means the team sits that many places higher than the chances deserve. Matches without xG data count real goals."))
        return sv
    }

    // ------------------------------------------------------------------ picks and watch-list
    fun picksPage(): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, "My Picks"))
        val rows = h.picks.scoredRows(h.picks.loadPicks(), h.eng)
        val s = h.picks.summarise(rows)
        if (rows.isEmpty()) {
            col.addView(FootUi.note(c, "No picks yet. Open a match (Match Day or a league's upcoming list) and choose \"My pick\" before kick-off."))
            return sv
        }
        col.addView(FootUi.note(c, "Picks made: ${s.made}   Played: ${s.scored}" + if (s.scored > 0) "   You got ${s.you} right, the model ${s.model}, both ${s.both}." else ""))
        col.addView(FootUi.row(c, listOf(Cell("Date", 1f), Cell("Match", 3f), Cell("You", 0.8f), Cell("Model", 0.8f), Cell("Model chances H / D / A", 1.8f), Cell("Result", 0.9f)), header = true))
        for (r in rows) {
            val you = if (r.youOk == null) Skin.TEXT else if (r.youOk) Skin.GREEN else Skin.RED
            val mod = if (r.modelOk == null) Skin.TEXT else if (r.modelOk) Skin.GREEN else Skin.RED
            col.addView(FootUi.row(c, listOf(Cell(r.p.date, 1f, color = Skin.MUTED), Cell("${r.p.home} v ${r.p.away}", 3f, bold = true),
                Cell(Picks.NAMES.getValue(r.p.pick), 0.8f, color = you, bold = true), Cell(Picks.NAMES.getValue(r.model), 0.8f, color = mod),
                Cell(r.p.mp.joinToString("  ") { FootUi.pct(it) }, 1.8f, color = Skin.MUTED), Cell(r.res?.let { "${it[0]}-${it[1]}" } ?: "waiting", 0.9f, color = Skin.CYAN))))
        }
        if (s.differ.isNotEmpty()) col.addView(FootUi.note(c, "Where you and the model differed: you were right ${s.differ.count { it.youOk == true }} of ${s.differ.size} times."))
        return sv
    }

    fun watchPage(): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, "Watch-list"))
        val list = h.picks.loadWatch()
        if (list.isEmpty()) {
            col.addView(FootUi.note(c, "Nobody on your watch-list yet. Open a match, move to a player in the table and press OK."))
            return sv
        }
        col.addView(FootUi.note(c, "Each player's chances in their team's next three matches. Press OK on a player to take them off."))
        for (w in list) {
            col.addView(FootUi.row(c, listOf(Cell("★ ${w.name}", 2.3f, color = Skin.CYAN, bold = true), Cell(w.team, 2f, color = Skin.MUTED)), size = 16f) {
                h.picks.toggleWatch(w.id, w.name, w.team); h.flash("${w.name} removed"); h.rerender()
            })
            var shown = 0
            for (lk in Leagues.ORDER) {
                val next = (h.state.fixtures[lk] ?: emptyList<Fixture>()).filter { (it.home == w.team || it.away == w.team) && ko(it) > nowSec() - 3 * 3600 }.sortedBy { it.utc }.take(3)
                for (fx in next) {
                    val mv = try { h.eng.matchView(lk, fx) } catch (e: Exception) { null }
                    val r = mv?.players?.firstOrNull { it.id == w.id }
                    val opp = if (fx.home == w.team) "v ${fx.away}" else "at ${fx.home}"
                    shown++
                    if (r == null) {
                        col.addView(FootUi.row(c, listOf(Cell(Skin.dayDate(ko(fx)), 1f, color = Skin.DIM), Cell(opp, 2f), Cell("not in the expected squad", 3f, color = Skin.DIM)), focusable = false))
                    } else {
                        col.addView(FootUi.row(c, listOf(Cell(Skin.dayDate(ko(fx)), 1f, color = Skin.DIM), Cell(opp, 2f), Cell("Booked ${FootUi.pct(r.booked)}", 1f, color = Skin.AMBER),
                            Cell("Fouls ${FootUi.pct(r.fouls[0])}", 1f), Cell("Fouled ${FootUi.pct(r.fouled[0])}", 1f), Cell("Shots ${FootUi.pct(r.shots[0])}", 1f),
                            Cell("On target ${FootUi.pct(r.sot[0])}", 1.1f)), size = 13.5f, focusable = false))
                    }
                }
            }
            if (shown == 0) col.addView(FootUi.note(c, "      No upcoming matches saved for ${w.team}."))
        }
        return sv
    }

    // ------------------------------------------------------------------ scorecards
    fun scorecardPage(): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, "Scorecard"))
        col.addView(picker(
            Triple("Match predictions", cardMode == "match") { cardMode = "match"; h.rerender() },
            Triple("Player figures", cardMode == "player") { cardMode = "player"; h.rerender() }))
        col.addView(picker(
            Triple("All leagues", cardLeague == "all") { cardLeague = "all"; h.rerender() },
            Triple("Premier League", cardLeague == "PL") { cardLeague = "PL"; h.rerender() },
            Triple("Championship", cardLeague == "ELC") { cardLeague = "ELC"; h.rerender() },
            Triple(when (cardSrc) { "live" -> "Live only"; "replay" -> "Replayed only"; else -> "Live + replayed" }, true) {
                cardSrc = when (cardSrc) { "all" -> "live"; "live" -> "replay"; else -> "all" }; h.rerender()
            }))
        val lk = if (cardLeague == "all") null else cardLeague
        val src = if (cardSrc == "all") null else cardSrc
        if (cardMode == "match") {
            val recs = Scorecard.load(h.io)
            val s = Scorecard.summarise(Scorecard.scored(recs, lk, src))
            col.addView(FootUi.note(c, "Live = predicted before kick-off (the honest test). Replayed = rebuilt for matches already played, using only earlier results. Waiting for results: ${Scorecard.pendingCount(recs)}."))
            if (s.n == 0) { col.addView(FootUi.note(c, "Nothing to score yet. Press Refresh now.")); return sv }
            col.addView(FootUi.h2(c, "${s.n} matches scored"))
            col.addView(FootUi.row(c, listOf(Cell("Market", 3.2f), Cell("Matches", 0.9f, FootUi.RIGHT), Cell("Right", 0.8f, FootUi.RIGHT), Cell("Confidence", 1.0f, FootUi.RIGHT), Cell("Better than guessing", 1.5f, FootUi.RIGHT)), header = true))
            for (m in s.markets) col.addView(FootUi.row(c, listOf(Cell(m.label, 3.2f, bold = true), Cell("${m.n}", 0.9f, FootUi.RIGHT), Cell(FootUi.pct(m.hit), 0.8f, FootUi.RIGHT, Skin.CYAN, true),
                Cell(m.conf?.let { FootUi.pct(it) } ?: "-", 1.0f, FootUi.RIGHT, Skin.MUTED),
                Cell(m.skill?.let { FootUi.sign(it) + "%" } ?: "-", 1.5f, FootUi.RIGHT, if ((m.skill ?: 0.0) >= 0) Skin.GREEN else Skin.RED))))
            col.addView(FootUi.h2(c, "Is the confidence trustworthy?"))
            col.addView(FootUi.row(c, listOf(Cell("Result pick when confidence is...", 3.2f), Cell("Matches", 0.9f, FootUi.RIGHT), Cell("Said", 0.8f, FootUi.RIGHT), Cell("Happened", 1.0f, FootUi.RIGHT)), header = true))
            for (b in s.bands) col.addView(FootUi.row(c, listOf(Cell(b.label.removePrefix("Result pick when confidence is "), 3.2f), Cell("${b.n}", 0.9f, FootUi.RIGHT),
                Cell(FootUi.pct(b.conf), 0.8f, FootUi.RIGHT, Skin.MUTED), Cell(FootUi.pct(b.hit), 1.0f, FootUi.RIGHT, Skin.CYAN, true))))
            col.addView(FootUi.note(c, "\"Better than guessing\" compares with just using how often home / draw / away happened before; above 0 is better."))
        } else {
            val recs = Playercard.load(h.io)
            val s = Playercard.summarise(Playercard.scored(recs, lk, src))
            col.addView(FootUi.note(c, "Each player percentage marked against what the players really did, starters and substitutes separately. Waiting for results: ${Playercard.pendingCount(recs)}."))
            if (s.figures.isEmpty()) { col.addView(FootUi.note(c, "Nothing to score yet. Press Refresh now.")); return sv }
            col.addView(FootUi.row(c, listOf(Cell("Figure", 2.6f), Cell("Who", 1.2f), Cell("Players", 0.9f, FootUi.RIGHT), Cell("Said", 0.8f, FootUi.RIGHT), Cell("Happened", 1.0f, FootUi.RIGHT), Cell("Better than flat", 1.3f, FootUi.RIGHT)), header = true))
            for (f in s.figures) col.addView(FootUi.row(c, listOf(Cell(f.label, 2.6f, bold = true), Cell(f.role, 1.2f, color = Skin.MUTED), Cell("${f.n}", 0.9f, FootUi.RIGHT),
                Cell(FootUi.pct(f.pred), 0.8f, FootUi.RIGHT, Skin.MUTED), Cell(FootUi.pct(f.act), 1.0f, FootUi.RIGHT, Skin.CYAN, true),
                Cell(f.skill?.let { FootUi.sign(it) + "%" } ?: "-", 1.3f, FootUi.RIGHT, if ((f.skill ?: 0.0) >= 0) Skin.GREEN else Skin.RED)), size = 13.5f))
            col.addView(FootUi.h2(c, "By the chance the model gave"))
            col.addView(FootUi.row(c, listOf(Cell("Who", 1.4f), Cell("Chance given", 1.6f), Cell("Players", 0.9f, FootUi.RIGHT), Cell("Said", 0.8f, FootUi.RIGHT), Cell("Happened", 1.0f, FootUi.RIGHT)), header = true))
            for (b in s.bands) col.addView(FootUi.row(c, listOf(Cell(b.role, 1.4f, color = Skin.MUTED), Cell(b.band, 1.6f), Cell("${b.n}", 0.9f, FootUi.RIGHT),
                Cell(FootUi.pct(b.pred), 0.8f, FootUi.RIGHT, Skin.MUTED), Cell(FootUi.pct(b.act), 1.0f, FootUi.RIGHT, Skin.CYAN, true)), size = 13.5f))
            col.addView(FootUi.note(c, "A 30% chance should happen about 30% of the time. \"Better than flat\" compares with giving everyone the overall average."))
        }
        return sv
    }
}
