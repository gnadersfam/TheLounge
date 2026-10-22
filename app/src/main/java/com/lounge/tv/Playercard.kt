package com.lounge.tv

/** Player scorecard: how well do the player percentages (booked, fouls, fouled, shots, on target) really do? */
class PcRow(val id: String, val name: String, val team: String, val role: Int, val plays: Double, val v: DoubleArray)
class PcRec(
    val lk: String, val date: String, val home: String, val away: String, val src: String, val ver: Int, val conf: Boolean,
    val utc: String?, val made: String?, val rows: List<PcRow>, var res: Map<String, IntArray>?
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "lk" to lk, "date" to date, "home" to home, "away" to away, "src" to src, "ver" to ver, "conf" to conf, "utc" to utc, "made" to made,
        "rows" to rows.map { listOf(it.id, it.name, it.team, it.role, it.plays) + it.v.toList() },
        "res" to res?.mapValues { it.value.toList() }
    )

    companion object {
        fun fromJson(o: Any?): PcRec {
            val m = o.obj()
            val rows = m["rows"].arr().map { r ->
                val l = r.arr()
                PcRow(l[0].str(), l[1].str(), l[2].str(), l[3].int(), l[4].num(), DoubleArray(12) { l[5 + it].num() })
            }
            val rs = m["res"]
            val res = if (rs is Map<*, *>) rs.entries.associate { (k, v) -> k.toString() to v.arr().map { it.int() }.toIntArray() } else null
            return PcRec(m["lk"].str(), m["date"].str(), m["home"].str(), m["away"].str(), m["src"].str(), m["ver"].int(), m["conf"].bool(),
                m["utc"]?.str(), m["made"]?.str(), rows, res)
        }
    }
}
class PcFigure(val label: String, val role: String, val n: Int, val pred: Double, val act: Double, val skill: Double?)
class PcBand(val role: String, val band: String, val n: Int, val pred: Double, val act: Double)
class PcSummary(val figures: List<PcFigure>, val bands: List<PcBand>)

object Playercard {
    const val NAME = "f_playercard"
    const val PLAYER_VERSION = 2
    const val LIVE_DAYS = 7
    val FIELDS = listOf("booked", "f1", "f2", "f3", "d1", "d2", "d3", "s1", "s2", "s3", "t1", "t2")
    class Market(val label: String, val field: String, val stat: Int, val k: Int)
    val MARKETS = listOf(
        Market("Booked (yellow card)", "booked", 4, 1),
        Market("Fouls committed 1+", "f1", 0, 1), Market("Fouls committed 2+", "f2", 0, 2), Market("Fouls committed 3+", "f3", 0, 3),
        Market("Fouled (fouls won) 1+", "d1", 1, 1), Market("Fouled 2+", "d2", 1, 2), Market("Fouled 3+", "d3", 1, 3),
        Market("Shots 1+", "s1", 2, 1), Market("Shots 2+", "s2", 2, 2), Market("Shots 3+", "s3", 2, 3),
        Market("Shots on target 1+", "t1", 3, 1), Market("Shots on target 2+", "t2", 3, 2)
    )
    private val BANDS = listOf(Triple(0.0, 0.05, "under 5%"), Triple(0.05, 0.15, "5-15%"), Triple(0.15, 0.30, "15-30%"),
        Triple(0.30, 0.50, "30-50%"), Triple(0.50, 1.01, "50% or more"))

    fun load(io: Persist): MutableMap<String, PcRec> {
        val out = LinkedHashMap<String, PcRec>()
        for ((k, v) in io.load(NAME).obj()) out[k] = PcRec.fromJson(v)
        return out
    }

    fun save(io: Persist, recs: Map<String, PcRec>) = io.save(NAME, recs.mapValues { it.value.toJson() })

    private fun row(r: PRow): PcRow {
        val vals = listOf(r.booked) + r.fouls + r.fouled + r.shots + r.sot
        return PcRow(r.id, r.name, r.team, if (r.role == "Starter") 0 else 1, Scorecard.r4(r.plays), DoubleArray(12) { Scorecard.r4(vals[it]) })
    }

    private fun result(ev: Event): Map<String, IntArray> {
        val out = HashMap<String, IntArray>()
        for (s in listOf("home", "away")) ev.side(s)?.players?.forEach { if (it.played) out[it.id] = intArrayOf(it.fouls, it.fouled, it.shots, it.sot, it.yc) }
        return out
    }

    private fun make(lk: String, date: String, home: String, away: String, rows: List<PRow>, src: String, conf: Boolean, utc: String? = null, made: String? = null) =
        PcRec(lk, date, home, away, src, PLAYER_VERSION, conf, utc, made, rows.map { row(it) }, null)

    private fun replayLineup(ev: Event, form: Map<String, Map<String, FormEntry>>): Lineup {
        fun side(s: String): LineSide {
            val sd = ev.side(s)!!
            return LineSide(sd.players.filter { it.starter }, sd.players.filter { !it.starter }, true, form[sd.team] ?: emptyMap())
        }
        return Lineup(side("home"), side("away"))
    }

    private fun sameRows(a: List<PcRow>, b: List<PcRow>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.id != y.id || x.role != y.role || x.plays != y.plays || !x.v.contentEquals(y.v)) return false
        }
        return true
    }

    fun update(eng: Engine, recs: MutableMap<String, PcRec>, nowSec: Long): Boolean {
        var changed = false
        val stale = recs.filter { it.value.src == "replay" && it.value.ver != PLAYER_VERSION }.keys.toList()
        for (k in stale) { recs.remove(k); changed = true }
        val events = eng.eventsCur.filter { it.home.isNotEmpty() && it.away.isNotEmpty() && it.sidesCount == 2 }
        val byPair = HashMap<String, Event>()
        for (e in events) byPair["${e.league}|${e.home}|${e.away}"] = e

        val horizon = nowSec + LIVE_DAYS * 86400L
        for (lk in Leagues.ORDER) for (fx in eng.state.fixtures[lk] ?: emptyList<Fixture>()) {
            val ko = Utc.parse(fx.utc)
            if (ko == null || ko <= nowSec || ko > horizon) continue
            val key = Scorecard.recKey(lk, fx.utc.substring(0, 10), fx.home, fx.away)
            val old = recs[key]
            if (old != null && old.res != null) continue
            val mv = try { eng.matchView(lk, fx) } catch (e: Exception) { continue }
            if (mv.players.isEmpty()) continue
            val nw = make(lk, fx.utc.substring(0, 10), fx.home, fx.away, mv.players, "live", mv.lineupStatus == "Confirmed lineups", fx.utc, Utc.iso(nowSec))
            if (old == null || !sameRows(old.rows, nw.rows)) { recs[key] = nw; changed = true }
        }

        for ((_, rec) in recs) if (rec.res == null) {
            val ev = byPair["${rec.lk}|${rec.home}|${rec.away}"]
            if (ev != null) { rec.res = result(ev); changed = true }
        }

        val todo = java.util.TreeMap<String, MutableList<Event>>()
        for (e in events) if (!recs.containsKey(Scorecard.recKey(e.league, e.date, e.home, e.away))) todo.getOrPut(e.date) { ArrayList() }.add(e)
        if (todo.isNotEmpty()) {
            val prevAll = Leagues.ORDER.flatMap { eng.state.prev[it] ?: emptyList() }
            for ((d, list) in todo) {
                val before = events.filter { it.date < d }
                val ag = Model.aggregatePlayers(before)
                val pri = Model.groupPriors(ag.players)
                val form = ag.teamHist.mapValues { Model.teamForm(it.value) }
                val resultsBefore = Leagues.ORDER.associateWith { lk -> eng.league.getValue(lk).matches.filter { it.date < d } }
                val refs = Model.refereeStats(prevAll + resultsBefore.values.flatten())
                for (e in list) {
                    val lk = e.league
                    val ms = resultsBefore.getValue(lk)
                    val prior = eng.league.getValue(lk).prior
                    if (ms.size < Scorecard.MIN_PRIOR && prior == null) continue
                    if (e.home_!!.players.isEmpty() || e.away_!!.players.isEmpty()) continue
                    val ratings = Model.buildRatings(ms, prior)
                    val refName = eng.league.getValue(lk).matches.firstOrNull { it.date == d && it.home == e.home && it.away == e.away }?.ref ?: ""
                    val ref = if (refName.isNotEmpty()) refs.refs[Model.refKey(refName)] else null
                    val ctx = Ctx(ratings, Model.teamFactors(ms), ag.players, pri, ag.teamMatches, ref?.factor ?: 1.0)
                    val rows = Model.predictPlayers(e.home, e.away, replayLineup(e, form), ctx)
                    val rec = make(lk, d, e.home, e.away, rows, "replay", true)
                    rec.res = result(e)
                    recs[Scorecard.recKey(lk, d, e.home, e.away)] = rec
                    changed = true
                }
            }
        }
        return changed
    }

    fun scored(recs: Map<String, PcRec>, lk: String? = null, src: String? = null) =
        recs.values.filter { it.res != null && (lk == null || it.lk == lk) && (src == null || it.src == src) }

    fun pendingCount(recs: Map<String, PcRec>) = recs.values.count { it.res == null }

    fun summarise(rows: List<PcRec>): PcSummary {
        class Acc { var n = 0; var sp = 0.0; var sy = 0; var sb = 0.0 }
        val acc = HashMap<String, Acc>()
        val bands = HashMap<String, Acc>()
        for (rec in rows) {
            val res = rec.res!!
            for (row in rec.rows) {
                val line = res[row.id]
                for (m in MARKETS) {
                    val p = row.v[FIELDS.indexOf(m.field)]
                    val y = if (line != null && line[m.stat] >= m.k) 1 else 0
                    for (rl in listOf(row.role, 2)) {
                        val a = acc.getOrPut("${m.label}|$rl") { Acc() }
                        a.n++; a.sp += p; a.sy += y; a.sb += (p - y) * (p - y)
                    }
                    for ((i, b) in BANDS.withIndex()) if (p >= b.first && p < b.second) {
                        val a = bands.getOrPut("${row.role}|$i") { Acc() }
                        a.n++; a.sp += p; a.sy += y
                        break
                    }
                }
            }
        }
        val figs = ArrayList<PcFigure>()
        for (m in MARKETS) for ((rl, rname) in listOf(0 to "Starters", 1 to "Substitutes")) {
            val a = acc["${m.label}|$rl"] ?: continue
            if (a.n == 0) continue
            val q = a.sy.toDouble() / a.n
            val base = q * (1 - q)
            figs.add(PcFigure(m.label, rname, a.n, a.sp / a.n, q, if (base > 0) 100.0 * (1 - (a.sb / a.n) / base) else null))
        }
        val bs = ArrayList<PcBand>()
        for ((rl, rname) in listOf(0 to "Starters", 1 to "Substitutes")) for ((i, b) in BANDS.withIndex()) {
            val a = bands["$rl|$i"] ?: continue
            if (a.n > 0) bs.add(PcBand(rname, b.third, a.n, a.sp / a.n, a.sy.toDouble() / a.n))
        }
        return PcSummary(figs, bs)
    }
}
