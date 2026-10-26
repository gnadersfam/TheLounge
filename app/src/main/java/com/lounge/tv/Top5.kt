package com.lounge.tv

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * Top 5 page (same as the PC app): for each kick-off time (e.g. Sat 12:30/12:45, Sat 15:00, Sun 14:00) the five players
 * most likely to be booked and the five most likely to commit a foul, with the chance in this match and the average
 * per game this season. Two players from the same match are fine.
 * The work of building each list is done in the background (one kick-off time at a time) and kept, so the page stays quick.
 */
class Top5(private val h: FootHost) {
    private val c: Context get() = h.activity

    private class Group(val first: Long, val items: MutableList<Pair<String, Fixture>>, val times: MutableList<String>) {
        var label = ""
    }
    private class Pick(val r: PRow, val fx: Fixture, val avg: Double?)
    private class Result(
        val matches: String, val booked: List<Pick>, val fouls: List<Pick>, val planned: Int, val confirmed: Int, val hadRows: Boolean
    )

    private var sel = 0
    private var groups: List<Group> = emptyList()
    private val cache = HashMap<String, Result>()
    private val busy = HashSet<String>()
    private var builtFor = ""

    private fun ko(fx: Fixture): Long = Utc.parse(fx.utc) ?: 0L

    private fun buildGroups() {
        val now = System.currentTimeMillis() / 1000
        val rows = ArrayList<Triple<Long, String, Fixture>>()
        for (lk in Leagues.ORDER) for (fx in (h.state.fixtures[lk] ?: emptyList<Fixture>())) {
            val t = ko(fx)
            if (t == 0L || t < now - 3 * 3600 || t > now + 7 * 86400) continue
            rows.add(Triple(t, lk, fx))
        }
        rows.sortBy { it.first }
        val out = ArrayList<Group>()
        for ((t, lk, fx) in rows) {
            val g = out.lastOrNull()
            if (g != null && Skin.dayDate(g.first) == Skin.dayDate(t) && t - g.first <= 30 * 60) {
                g.items.add(lk to fx); g.times.add(Skin.hhmm(t))
            } else out.add(Group(t, arrayListOf(lk to fx), arrayListOf(Skin.hhmm(t))))
        }
        for (g in out) g.label = Skin.dayDate(g.first) + " " + g.times.distinct().sorted().joinToString("/")
        groups = out
    }

    private fun perGame(id: String, stat: Int): Double? {
        val a = h.eng.players[id] ?: return null
        val g = a.start.apps + a.sub.apps
        if (g == 0) return null
        return (a.start.s[stat] + a.sub.s[stat]).toDouble() / g
    }

    private fun work(g: Group): Result {
        val all = ArrayList<Pair<PRow, Fixture>>()
        var planned = 0; var confirmed = 0
        for ((lk, fx) in g.items) {
            val mv = try { h.eng.matchView(lk, fx) } catch (e: Exception) { Crash.log("Top 5 page", e); continue }
            planned++
            if (mv.lineupStatus == "Confirmed lineups") confirmed++
            for (r in mv.players) {
                if (r.flag == "SUSPENDED") continue
                val a = h.eng.players[r.id]
                val games = if (a != null) a.start.apps + a.sub.apps else 0
                val starts = a?.start?.apps ?: 0
                if (games < MIN_GAMES || starts < MIN_STARTS) continue
                all.add(r to fx)
            }
        }
        val booked = all.sortedByDescending { it.first.booked }.take(5).map { Pick(it.first, it.second, perGame(it.first.id, S_YC)) }
        val fouls = all.sortedByDescending { it.first.fouls[0] }.take(5).map { Pick(it.first, it.second, perGame(it.first.id, S_FOULS)) }
        return Result(g.items.joinToString(",  ") { "${it.second.home} v ${it.second.away}" }, booked, fouls, planned, confirmed, all.isNotEmpty())
    }

    fun page(): View {
        val (sv, col) = FootUi.page(c)
        col.addView(FootUi.h1(c, "Top 5"))
        col.addView(FootUi.note(c, "Pick a kick-off time. Chance = this match; average = this season, per game. Two players from the same match are fine."))
        val sig = (h.state.fixtures.values.sumOf { it.size }).toString() + "|" + h.eng.players.size
        if (sig != builtFor) { cache.clear(); builtFor = sig; sel = 0 }
        buildGroups()
        if (groups.isEmpty()) {
            col.addView(FootUi.note(c, "No upcoming matches in the next 7 days. Press Refresh now to download fixtures.", Skin.TEXT))
            return sv
        }
        if (sel >= groups.size) sel = 0
        // kick-off buttons, six to a row
        var row: LinearLayout? = null
        for ((i, g) in groups.withIndex()) {
            if (i % 5 == 0) { row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Skin.dp(c, 4), 0, Skin.dp(c, 4)) }; col.addView(row) }
            row!!.addView(Skin.button(c, (if (i == sel) "● " else "") + g.label) { sel = i; h.rerender() },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(c, 10) })
        }
        val g = groups[sel]
        val res = cache[g.label]
        if (res == null) {
            col.addView(FootUi.note(c, "Working out the lists for ${g.label}...", Skin.TEXT))
            if (busy.add(g.label)) {
                val label = g.label
                Bg.run {
                    val r = try { work(g) } catch (e: Throwable) { Crash.log("Top 5 page", e); null }
                    Bg.ui {
                        busy.remove(label)
                        if (r != null) cache[label] = r
                        h.rerender()
                    }
                }
            }
            return sv
        }
        col.addView(FootUi.note(c, "Matches: ${res.matches}"))
        table(col, "Top 5 most likely to be booked", "Chance booked", "Bookings per game", res.booked) { it.r.booked }
        table(col, "Top 5 most likely to commit a foul", "Chance of a foul", "Fouls per game", res.fouls) { it.r.fouls[0] }
        val left = " Players with under $MIN_GAMES games, or no starts yet, this season are left out."
        col.addView(FootUi.note(c, when {
            !res.hadRows -> "No player data for these matches yet. Press Refresh now."
            res.confirmed == res.planned -> "Lineups confirmed for all ${res.planned} match${if (res.planned == 1) "" else "es"}.$left"
            else -> "Lineups confirmed for ${res.confirmed} of ${res.planned} match${if (res.planned == 1) "" else "es"}; the rest use predicted lineups, so these lists can change when team sheets come out.$left"
        }))
        return sv
    }

    private fun table(col: LinearLayout, title: String, chanceHead: String, avgHead: String, list: List<Pick>, chance: (Pick) -> Double) {
        col.addView(FootUi.h2(c, title))
        col.addView(FootUi.row(c, listOf(Cell("#", 0.4f), Cell("Player", 2.3f), Cell("Team", 1.8f), Cell("Match", 3.0f),
            Cell(chanceHead, 1.3f, FootUi.RIGHT), Cell(avgHead, 1.5f, FootUi.RIGHT)), header = true))
        for ((i, p) in list.withIndex()) {
            col.addView(FootUi.row(c, listOf(Cell("${i + 1}", 0.4f, color = Skin.MUTED), Cell(p.r.name, 2.3f, bold = true, faceId = p.r.id, faceName = p.r.name),
                Cell(p.r.team, 1.8f, crest = listOf(p.r.team)), Cell("${p.fx.home} v ${p.fx.away}", 3.0f, color = Skin.MUTED),
                Cell(FootUi.pct(chance(p)), 1.3f, FootUi.RIGHT, Skin.CYAN, true),
                Cell(p.avg?.let { "%.2f".format(it) } ?: "-", 1.5f, FootUi.RIGHT))))
        }
    }

    companion object {
        const val MIN_GAMES = 3     // players with fewer games than this this season are left out (too little to go on)
        const val MIN_STARTS = 1    // ... and so is anyone who has not started a match yet
    }
}
