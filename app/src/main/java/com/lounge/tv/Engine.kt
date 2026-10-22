package com.lounge.tv

import kotlin.math.exp

class LeagueData(
    val matches: List<Match>, val prior: Prior?, val ratings: Ratings, val tf: TeamFactors, val table: List<TableRow>
)
class FixtureView(val fx: Fixture, val pred: Pred, val league: String)
class FormRes(val date: String, val venue: String, val opp: String, val score: String, val res: String)
class MatchView(
    val fx: Fixture, val pred: Pred, val players: List<PRow>, val lineupStatus: String,
    val susp: Map<String, List<Flag>>, val agree: Agreement?, val form: Map<String, List<FormRes>>,
    val ref: RefStat?, val refName: String, val lineup: Lineup, val ratings: Map<String, DoubleArray>
)
class TeamLogRow(val date: String, val venue: String, val opp: String, val score: String, val xg: Double, val xga: Double,
                 val fouls: Int?, val yc: Int?, val ref: String)
class H2HRow(val date: String, val home: String, val away: String, val score: String, val xg: String, val yc: String)
class H2H(val rows: List<H2HRow>, val hw: Int, val aw: Int, val d: Int, val avgGoals: Double)
class FormTableRow(val team: String, val seq: List<String>, val p: Int, val pts: Int, val gf: Int, val ga: Int,
                   val xgf: Double, val xga: Double) { val xgd get() = xgf - xga }
class ExpRow(val team: String, val pos: Int, val P: Int, val Pts: Int, val GD: Int, val xGD: Double, val xPts: Double) {
    val ptsGap get() = Pts - xPts
    var xpos = 0
    val posGap get() = xpos - pos
}
class TrendRow(val opp: String, val venue: String, val score: String, val xg: Double, val xga: Double)
class Trend(val rows: List<TrendRow>, val avgF: Double?, val avgA: Double?, val verdict: String)

/** Turns saved data into things the screens can show. */
class Engine(val state: FootState, year: Int, month: Int) {
    val eventsCur: List<Event>
    val cards: Map<String, CardAnalysis>
    val players: Map<String, Agg>
    val teamMatches: Map<String, Int>
    val lastEvent: Map<String, LastEvent>
    val teamHist: Map<String, List<List<PlayerLine>>>
    val pri: Map<String, Rates>
    val league = LinkedHashMap<String, LeagueData>()
    val refs: RefStats

    init {
        val startYear = if (month >= 7) year else year - 1
        val cutoff = "%d-07-01".format(startYear)
        eventsCur = state.events.values.filter { it.date >= cutoff }
        cards = Leagues.ORDER.associateWith { Discipline.analyse(eventsCur, it) }
        val ag = Model.aggregatePlayers(eventsCur)
        players = ag.players; teamMatches = ag.teamMatches; lastEvent = ag.lastEvent; teamHist = ag.teamHist
        pri = Model.groupPriors(players)
        val all = ArrayList<Match>()
        for (lk in Leagues.ORDER) {
            val ms = state.results[lk] ?: emptyList()
            all.addAll(ms); all.addAll(state.prev[lk] ?: emptyList())
            val teams = HashSet<String>()
            ms.forEach { teams.add(it.home); teams.add(it.away) }
            (state.fixtures[lk] ?: emptyList<Fixture>()).forEach { teams.add(it.home); teams.add(it.away) }
            val prior = Model.makePrior(state.prev[lk] ?: emptyList(), state.prev["PL"] ?: emptyList(), lk, teams)
            league[lk] = LeagueData(ms, prior, Model.buildRatings(ms, prior), Model.teamFactors(ms), Model.leagueTable(ms))
        }
        refs = Model.refereeStats(all)
    }

    fun fixtures(lk: String): List<FixtureView> =
        (state.fixtures[lk] ?: emptyList<Fixture>()).map { FixtureView(it, Model.predictMatch(league.getValue(lk).ratings, it.home, it.away, it.odds, it.o25), lk) }

    private fun lineupSide(team: String, up: SideLine?): LineSide {
        val form = Model.teamForm(teamHist[team] ?: emptyList())
        if (up != null) {
            val st = up.players.filter { it.starter }
            if (st.size == 11) return LineSide(st, up.players.filter { !it.starter }, true, form)
        }
        val last = lastEvent[team] ?: return LineSide(emptyList(), emptyList(), false, form)
        return LineSide(last.players.filter { it.starter }, last.players.filter { !it.starter }, false, form)
    }

    fun ahead(lk: String, fx: Fixture, team: String): Int =
        1 + (state.fixtures[lk] ?: emptyList<Fixture>()).count { (it.home == team || it.away == team) && it.utc < fx.utc }

    fun suspensions(lk: String, fx: Fixture): Map<String, List<Flag>> =
        mapOf("home" to Discipline.teamReport(cards.getValue(lk), lk, fx.home, ahead(lk, fx, fx.home)),
            "away" to Discipline.teamReport(cards.getValue(lk), lk, fx.away, ahead(lk, fx, fx.away)))

    fun lineup(lk: String, fx: Fixture): Lineup {
        val up = state.upcoming[fx.key(lk)]
        val sus = suspensions(lk, fx)
        fun mk(side: String): LineSide {
            val team = if (side == "home") fx.home else fx.away
            val ln = lineupSide(team, up?.side(side))
            val out = sus.getValue(side).filter { it.code == "out" }.map { it.id }.toSet()
            return if (!ln.confirmed && out.isNotEmpty())
                LineSide(ln.starters.filter { it.id !in out }, ln.bench.filter { it.id !in out }, false, ln.form, out)
            else LineSide(ln.starters, ln.bench, ln.confirmed, ln.form, out)
        }
        return Lineup(mk("home"), mk("away"))
    }

    fun matchView(lk: String, fx: Fixture): MatchView {
        val lg = league.getValue(lk)
        val pred = Model.predictMatch(lg.ratings, fx.home, fx.away, fx.odds, fx.o25)
        val lineup = lineup(lk, fx)
        val refName = fx.ref
        val ref = if (refName.isNotEmpty()) refs.refs[Model.refKey(refName)] else null
        val ctx = Ctx(lg.ratings, lg.tf, players, pri, teamMatches, ref?.factor ?: 1.0)
        var rows: List<PRow> = emptyList()
        if (lineup.home.starters.isNotEmpty() && lineup.away.starters.isNotEmpty())
            rows = Model.predictPlayers(fx.home, fx.away, lineup, ctx)
        val sus = suspensions(lk, fx)
        val flag = HashMap<String, Flag>()
        sus.values.forEach { l -> l.forEach { flag[it.id] = it } }
        for (r in rows) {
            val a = players[r.id]
            r.ycSeason = if (a != null) a.start.s[S_YC] + a.sub.s[S_YC] else 0
            val f = flag[r.id]
            r.flag = if (f != null) (if (f.code == "out") "SUSPENDED" else "1 from ban") else ""
        }
        val status = if (lineup.home.confirmed && lineup.away.confirmed) "Confirmed lineups"
        else if (lineup.home.confirmed || lineup.away.confirmed) "Lineups partly confirmed" else "Predicted lineups"
        return MatchView(fx, pred, rows, status, sus, Model.agreement(pred),
            mapOf("home" to form(lk, fx.home), "away" to form(lk, fx.away)), ref, refName, lineup,
            listOf(fx.home, fx.away).associateWith { doubleArrayOf(lg.ratings.att[it] ?: 1.0, lg.ratings.dfn[it] ?: 1.0) })
    }

    fun form(lk: String, team: String, n: Int = 5): List<FormRes> {
        val ms = league.getValue(lk).matches.filter { it.home == team || it.away == team }.sortedWith(compareBy({ it.date }, { it.time }))
        return ms.takeLast(n).map { m ->
            val home = m.home == team
            val gf = if (home) m.hg else m.ag; val ag = if (home) m.ag else m.hg
            FormRes(m.date, if (home) "H" else "A", if (home) m.away else m.home, "$gf-$ag", if (gf > ag) "W" else if (gf == ag) "D" else "L")
        }
    }

    fun teamView(lk: String, team: String): List<TeamLogRow> {
        val ms = league.getValue(lk).matches.filter { it.home == team || it.away == team }.sortedByDescending { it.date }
        return ms.map { m ->
            val home = m.home == team
            val x = Model.xg(m)
            TeamLogRow(m.date, if (home) "H" else "A", if (home) m.away else m.home,
                if (home) "${m.hg}-${m.ag}" else "${m.ag}-${m.hg}", if (home) x[0] else x[1], if (home) x[1] else x[0],
                if (home) m.hf else m.af, if (home) m.hy else m.ay, m.ref)
        }
    }

    private fun allMatches(lk: String) = (state.prev[lk] ?: emptyList()) + league.getValue(lk).matches

    fun h2h(lk: String, a: String, b: String, n: Int = 6): H2H {
        val ms = allMatches(lk).filter { (it.home == a && it.away == b) || (it.home == b && it.away == a) }.sortedByDescending { it.date }.take(n)
        var aw = 0; var bw = 0; var d = 0; var goals = 0
        val rows = ArrayList<H2HRow>()
        for (m in ms) {
            val x = Model.xg(m)
            val ag = if (m.home == a) m.hg else m.ag; val bg = if (m.home == a) m.ag else m.hg
            if (ag > bg) aw++ else if (ag < bg) bw++ else d++
            goals += m.hg + m.ag
            val yc = if (m.hy != null && m.ay != null) (m.hy + m.ay).toString() else "-"
            rows.add(H2HRow(m.date, m.home, m.away, "${m.hg}-${m.ag}", "%.1f - %.1f".format(x[0], x[1]), yc))
        }
        return H2H(rows, aw, bw, d, if (ms.isNotEmpty()) goals / ms.size.toDouble() else 0.0)
    }

    fun formTable(lk: String, n: Int = 6): List<FormTableRow> {
        class G(val gf: Int, val ga: Int, val xf: Double, val xa: Double)
        val by = LinkedHashMap<String, MutableList<G>>()
        for (m in league.getValue(lk).matches.sortedWith(compareBy({ it.date }, { it.time }))) {
            val x = Model.xg(m)
            by.getOrPut(m.home) { ArrayList() }.add(G(m.hg, m.ag, x[0], x[1]))
            by.getOrPut(m.away) { ArrayList() }.add(G(m.ag, m.hg, x[1], x[0]))
        }
        val out = by.map { (team, ms) ->
            val last = ms.takeLast(n)
            val seq = last.map { if (it.gf > it.ga) "W" else if (it.gf == it.ga) "D" else "L" }
            FormTableRow(team, seq, last.size, seq.fold(0) { acc, r -> acc + (if (r == "W") 3 else if (r == "D") 1 else 0) },
                last.sumOf { it.gf }, last.sumOf { it.ga }, last.sumOf { it.xf }, last.sumOf { it.xa })
        }
        return out.sortedWith(compareBy({ -it.pts }, { -(it.gf - it.ga) }, { -it.xgd }, { it.team }))
    }

    private fun pois(lam0: Double): DoubleArray {
        val lam = maxOf(lam0, 0.0)
        var p = exp(-lam)
        val out = DoubleArray(13)
        for (k in 0..12) { out[k] = p; p = p * lam / (k + 1) }
        return out
    }

    private fun xpts(hx: Double, ax: Double): DoubleArray {
        val ph = pois(hx); val pa = pois(ax)
        var win = 0.0; var draw = 0.0; var lose = 0.0
        for (i in ph.indices) for (j in pa.indices) {
            val v = ph[i] * pa[j]
            if (i > j) win += v else if (i == j) draw += v else lose += v
        }
        var tot = win + draw + lose
        if (tot == 0.0) tot = 1.0
        return doubleArrayOf((3 * win + draw) / tot, (3 * lose + draw) / tot)
    }

    fun expectedTable(lk: String): List<ExpRow> {
        val xp = HashMap<String, Double>()
        for (m in league.getValue(lk).matches) {
            val x = Model.xg(m)
            val p = xpts(x[0], x[1])
            xp[m.home] = (xp[m.home] ?: 0.0) + p[0]
            xp[m.away] = (xp[m.away] ?: 0.0) + p[1]
        }
        val rows = league.getValue(lk).table.map { ExpRow(it.team, it.pos, it.P, it.Pts, it.GD, it.xGD, xp[it.team] ?: 0.0) }
        rows.sortedWith(compareBy({ -it.xPts }, { -it.xGD }, { it.team })).forEachIndexed { i, e -> e.xpos = i + 1 }
        return rows.sortedBy { it.pos }
    }

    fun trend(lk: String, team: String, n: Int = 10): Trend {
        val season = league.getValue(lk).matches.filter { it.home == team || it.away == team }.sortedWith(compareBy({ it.date }, { it.time }))
        val rows = season.takeLast(n).map { m ->
            val home = m.home == team; val x = Model.xg(m)
            TrendRow(if (home) m.away else m.home, if (home) "H" else "A", if (home) "${m.hg}-${m.ag}" else "${m.ag}-${m.hg}",
                if (home) x[0] else x[1], if (home) x[1] else x[0])
        }
        val allx = season.map { m -> val x = Model.xg(m); if (m.home == team) x else doubleArrayOf(x[1], x[0]) }
        val avgF = if (allx.isNotEmpty()) allx.sumOf { it[0] } / allx.size else null
        val avgA = if (allx.isNotEmpty()) allx.sumOf { it[1] } / allx.size else null
        var verdict = "Not enough matches yet"
        if (rows.size >= 4) {
            val k = rows.size / 2
            fun mean(v: List<Double>) = v.sum() / v.size
            val df = mean(rows.takeLast(k).map { it.xg }) - mean(rows.take(k).map { it.xg })
            val da = mean(rows.takeLast(k).map { it.xga }) - mean(rows.take(k).map { it.xga })
            val att = if (df > 0.2) "attack improving" else if (df < -0.2) "attack fading" else "attack steady"
            val dfn = if (da > 0.2) "defence leaking more" else if (da < -0.2) "defence tightening" else "defence steady"
            verdict = "$att, $dfn (recent half v earlier half)"
        }
        return Trend(rows, avgF, avgA, verdict)
    }
}
