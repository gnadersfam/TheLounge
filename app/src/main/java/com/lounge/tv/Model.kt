package com.lounge.tv

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** A played match (one row of the football-data.co.uk CSV). Stats that were not recorded are null. */
class Match(
    val date: String, val time: String, val home: String, val away: String,
    val hg: Int, val ag: Int, val ref: String,
    val hxg: Double?, val axg: Double?,
    val hs: Int?, val aws: Int?, val hst: Int?, val ast: Int?,
    val hf: Int?, val af: Int?, val hy: Int?, val ay: Int?, val hr: Int?, val ar: Int?,
    val season: String = ""
)

class Ratings(val mu: Double, val ha: Double, val att: Map<String, Double>, val dfn: Map<String, Double>, val n: Map<String, Int>)
class Prior(val att: Map<String, Double>, val dfn: Map<String, Double>, val hx: Double, val ax: Double)

class Pred(
    var home: Double, var draw: Double, var away: Double, var btts: Double, var over25: Double,
    val scoreH: Int, val scoreA: Int, val scoreP: Double, val lh: Double, val la: Double
) {
    var pModel: DoubleArray = doubleArrayOf(home, draw, away)
    var pMarket: DoubleArray? = null
    var mkt = false
}

class Agreement(val code: String, val label: String, val model: Int, val market: Int, val pm: DoubleArray, val mk: DoubleArray)

class TableRow(val team: String) {
    var P = 0; var W = 0; var D = 0; var L = 0; var GF = 0; var GA = 0
    var xGF = 0.0; var xGA = 0.0
    val GD get() = GF - GA
    val Pts get() = 3 * W + D
    val xGD get() = xGF - xGA
    var pos = 0
}

class TeamFactors(val avg: Map<String, Double>, val teams: Map<String, Map<String, Double>>)

class RefStat(
    val key: String, val name: String, val n: Int, val yc: Double, val rc: Double, val fouls: Double,
    val hyc: Double, val ayc: Double, val factor: Double, val matches: List<Match>
)
class RefStats(val league: Map<String, Double>, val refs: Map<String, RefStat>)

/** One player's line in one match (from ESPN). */
data class PlayerLine(
    val id: String, val name: String, val pos: String, val starter: Boolean, val subIn: Boolean, val played: Boolean,
    val fouls: Int, val fouled: Int, val shots: Int, val sot: Int, val yc: Int, val rc: Int
)
class SideLine(val team: String, val formation: String, val players: List<PlayerLine>)
class Event(val league: String, val date: String, val home: String, val away: String, val home_: SideLine?, val away_: SideLine?) {
    fun side(s: String): SideLine? = if (s == "home") home_ else away_
    val sidesCount get() = (if (home_ != null) 1 else 0) + (if (away_ != null) 1 else 0)
}

// stat order used everywhere for the player maths
const val S_FOULS = 0
const val S_FOULED = 1
const val S_SHOTS = 2
const val S_SOT = 3
const val S_YC = 4

class RoleTot { var apps = 0; val s = IntArray(5) }
class Agg(val id: String, var name: String, var team: String) {
    val posCounts = LinkedHashMap<String, Int>()
    val start = RoleTot()
    val sub = RoleTot()
    var rc = 0
    var pos = ""
    var group = ""
    fun role(r: String) = if (r == "start") start else sub
}
class Rates(val v: DoubleArray, var apps: Double)
class LastEvent(val date: String, val formation: String, val players: List<PlayerLine>)
class Aggregated(
    val players: Map<String, Agg>, val teamMatches: Map<String, Int>,
    val lastEvent: Map<String, LastEvent>, val teamHist: Map<String, List<List<PlayerLine>>>
)

class FormEntry(var s: Double, var b: Double, var p: PlayerLine)

class LineSide(
    val starters: List<PlayerLine>, val bench: List<PlayerLine>, val confirmed: Boolean,
    val form: Map<String, FormEntry>, val out: Set<String> = emptySet()
)
class Lineup(val home: LineSide, val away: LineSide) {
    fun side(s: String) = if (s == "home") home else away
}

class Ctx(
    val ratings: Ratings, val tf: TeamFactors, val players: Map<String, Agg>, val pri: Map<String, Rates>,
    val teamMatches: Map<String, Int>, val refFactor: Double = 1.0
)

class PRow(
    val id: String, val name: String, val team: String, val side: String, val pos: String, val group: String,
    val role: String, val plays: Double, val apps: Int,
    val booked: Double, val fouls: List<Double>, val fouled: List<Double>, val shots: List<Double>, val sot: List<Double>
) {
    var ycSeason = 0
    var flag = ""
}

object Model {
    const val MAXG = 10
    const val HALF_LIFE = 20.0
    const val SHRINK_TEAM = 20.0
    const val OLD_HALF_LIFE = 14.0
    const val OLD_SHRINK_TEAM = 5.0
    const val PRIOR_REGRESS = 0.75
    const val PRIOR_MU_WEIGHT = 90.0
    val PROMOTED_PL = doubleArrayOf(0.778, 1.203)
    val RELEGATED_ELC = doubleArrayOf(1.167, 0.856)
    val PROMOTED_L1 = doubleArrayOf(0.922, 1.036)
    const val OU_SLOPE = 0.6
    const val MARKET_WEIGHT = 0.8
    const val AGREE_GAP = 0.10

    fun poisPmf(k: Int, lam: Double): Double {
        var f = 1.0
        for (i in 2..k) f *= i
        return exp(-lam) * lam.pow(k) / f
    }

    fun pAtLeast(k: Int, lam: Double): Double {
        if (k <= 0) return 1.0
        var s = 0.0
        for (i in 0 until k) s += poisPmf(i, lam)
        return max(0.0, 1.0 - s)
    }

    fun clip(x: Double, lo: Double, hi: Double) = max(lo, min(hi, x))
    fun shrink(raw: Double, n: Double, k: Double) = (n * raw + k * 1.0) / (n + k)

    // ---- calibration (logit intercept, slope)
    private val CALIB: Map<String, DoubleArray> = mapOf(
        "shots|1|start" to doubleArrayOf(-0.070, 0.855), "shots|2|start" to doubleArrayOf(-0.125, 0.875),
        "shots|3|start" to doubleArrayOf(-0.191, 0.838), "sot|1|start" to doubleArrayOf(-0.198, 0.784),
        "sot|2|start" to doubleArrayOf(-0.438, 0.803), "shots|1|sub" to doubleArrayOf(0.169, 0.754),
        "shots|2|sub" to doubleArrayOf(-0.636, 0.437), "shots|3|sub" to doubleArrayOf(-0.908, 0.462),
        "sot|1|sub" to doubleArrayOf(-0.315, 0.655), "sot|2|sub" to doubleArrayOf(-1.849, 0.428),
        "yc|start" to doubleArrayOf(-1.016, 0.383), "yc|sub" to doubleArrayOf(-1.532, 0.417),
        "fouls|1" to doubleArrayOf(0.072, 0.753), "fouls|2" to doubleArrayOf(-0.317, 0.685),
        "fouls|3" to doubleArrayOf(-0.842, 0.620), "fouled|1" to doubleArrayOf(-0.068, 0.804),
        "fouled|2" to doubleArrayOf(-0.263, 0.723), "fouled|3" to doubleArrayOf(-0.797, 0.607)
    )

    fun recalibrate(p0: Double, key: String): Double {
        val pair = CALIB[key] ?: return p0
        val p = clip(p0, 1e-4, 1.0 - 1e-4)
        val z = pair[0] + pair[1] * ln(p / (1.0 - p))
        return 1.0 / (1.0 + exp(-z))
    }

    fun outcome(lh: Double, la: Double): Pred {
        val pm = Array(MAXG) { i -> DoubleArray(MAXG) { j -> poisPmf(i, lh) * poisPmf(j, la) } }
        var tot = 0.0
        for (r in pm) for (x in r) tot += x
        var hw = 0.0; var dr = 0.0; var aw = 0.0; var btts = 0.0; var o25 = 0.0
        var bi = 0; var bj = 0; var bp = -1.0
        for (i in 0 until MAXG) for (j in 0 until MAXG) {
            val p = pm[i][j] / tot
            if (i > j) hw += p else if (i == j) dr += p else aw += p
            if (i > 0 && j > 0) btts += p
            if (i + j >= 3) o25 += p
            if (p > bp) { bp = p; bi = i; bj = j }
        }
        return Pred(hw, dr, aw, btts, o25, bi, bj, bp, lh, la)
    }

    fun xg(m: Match): DoubleArray =
        if (m.hxg != null && m.axg != null) doubleArrayOf(m.hxg, m.axg) else doubleArrayOf(m.hg.toDouble(), m.ag.toDouble())

    private class Obs(val f: Double, val a: Double, val opp: String)

    fun buildRatings(matches: List<Match>, prior: Prior? = null): Ratings {
        val ms = matches.sortedWith(compareBy({ it.date }, { it.time }))
        val kTeam: Double; val half: Double; val muW: Double
        if (prior != null) { kTeam = SHRINK_TEAM; half = HALF_LIFE; muW = PRIOR_MU_WEIGHT }
        else { kTeam = OLD_SHRINK_TEAM; half = OLD_HALF_LIFE; muW = 0.0 }
        val nMs = ms.size
        var hx = 0.0; var ax = 0.0
        if (nMs > 0) {
            hx = ms.sumOf { xg(it)[0] } / nMs
            ax = ms.sumOf { xg(it)[1] } / nMs
        }
        if (prior != null && prior.hx != 0.0 && prior.ax != 0.0) {
            hx = (nMs * hx + muW * prior.hx) / (nMs + muW)
            ax = (nMs * ax + muW * prior.ax) / (nMs + muW)
        }
        val mu = (hx + ax) / 2.0
        if (mu <= 0) return Ratings(1.3, 1.1, emptyMap(), emptyMap(), emptyMap())
        val ha = if (ax > 0 && hx > 0) sqrt(hx / ax) else 1.1
        val obs = LinkedHashMap<String, MutableList<Obs>>()
        for (m in ms) {
            val g = xg(m)
            obs.getOrPut(m.home) { ArrayList() }.add(Obs(g[0] / ha, g[1] * ha, m.away))
            obs.getOrPut(m.away) { ArrayList() }.add(Obs(g[1] * ha, g[0] / ha, m.home))
        }
        val weights = HashMap<String, DoubleArray>()
        for ((t, lst) in obs) {
            val n = lst.size
            weights[t] = DoubleArray(n) { i -> 0.5.pow((n - 1 - i) / half) }
        }
        val pa = prior?.att ?: emptyMap()
        val pd = prior?.dfn ?: emptyMap()
        val teams = LinkedHashSet<String>()
        teams.addAll(obs.keys); teams.addAll(pa.keys); teams.addAll(pd.keys)
        val pAtt = HashMap<String, Double>(); val pDfn = HashMap<String, Double>()
        for (t in teams) { pAtt[t] = pa[t] ?: 1.0; pDfn[t] = pd[t] ?: 1.0 }
        var att: Map<String, Double> = HashMap(pAtt)
        var dfn: Map<String, Double> = HashMap(pDfn)
        repeat(25) {
            val na = HashMap<String, Double>(); val nd = HashMap<String, Double>()
            for (t in teams) {
                val lst = obs[t] ?: emptyList<Obs>()
                val w = weights[t] ?: DoubleArray(0)
                var sFor = 0.0; var sExp = 0.0; var sAg = 0.0; var sExpD = 0.0
                for (i in lst.indices) {
                    val o = lst[i]
                    sFor += w[i] * o.f
                    sExp += w[i] * mu * dfn.getValue(o.opp)
                    sAg += w[i] * o.a
                    sExpD += w[i] * mu * att.getValue(o.opp)
                }
                na[t] = (sFor + kTeam * mu * pAtt.getValue(t)) / (sExp + kTeam * mu)
                nd[t] = (sAg + kTeam * mu * pDfn.getValue(t)) / (sExpD + kTeam * mu)
            }
            att = na; dfn = nd
        }
        return Ratings(mu, ha, att, dfn, obs.mapValues { it.value.size })
    }

    fun makePrior(prevSame: List<Match>, prevTop: List<Match>, lk: String, teams: Collection<String>): Prior? {
        if (prevSame.size < 100) return null
        val rp = buildRatings(prevSame)
        val hx = prevSame.sumOf { xg(it)[0] } / prevSame.size
        val ax = prevSame.sumOf { xg(it)[1] } / prevSame.size
        val inTop = HashSet<String>()
        for (m in prevTop) { inTop.add(m.home); inTop.add(m.away) }
        val att = HashMap<String, Double>(); val dfn = HashMap<String, Double>()
        val all = LinkedHashSet<String>(); all.addAll(teams); all.addAll(rp.att.keys)
        for (t in all) {
            if (rp.att.containsKey(t)) {
                att[t] = 1.0 + PRIOR_REGRESS * (rp.att.getValue(t) - 1.0)
                dfn[t] = 1.0 + PRIOR_REGRESS * (rp.dfn.getValue(t) - 1.0)
            } else {
                val d = if (lk == "PL") PROMOTED_PL else if (inTop.contains(t)) RELEGATED_ELC else PROMOTED_L1
                att[t] = d[0]; dfn[t] = d[1]
            }
        }
        return Prior(att, dfn, hx, ax)
    }

    fun expectedGoals(r: Ratings, home: String, away: String): DoubleArray {
        val ah = r.att[home] ?: 1.0; val dh = r.dfn[home] ?: 1.0
        val aa = r.att[away] ?: 1.0; val da = r.dfn[away] ?: 1.0
        val lh = r.mu * r.ha * ah * da
        val la = r.mu / r.ha * aa * dh
        return doubleArrayOf(clip(lh, 0.15, 4.5), clip(la, 0.15, 4.5))
    }

    private fun logit(p0: Double): Double { val p = clip(p0, 1e-4, 1.0 - 1e-4); return ln(p / (1.0 - p)) }
    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))
    private fun oddsOk(o: DoubleArray?, n: Int) = o != null && o.size == n && o.all { it > 1.0 }

    fun predictMatch(r: Ratings, home: String, away: String, odds: DoubleArray? = null, o25odds: DoubleArray? = null): Pred {
        val g = expectedGoals(r, home, away)
        val pr = outcome(g[0], g[1])
        pr.btts = sigmoid(OU_SLOPE * logit(pr.btts))
        pr.over25 = sigmoid(OU_SLOPE * logit(pr.over25))
        pr.pModel = doubleArrayOf(pr.home, pr.draw, pr.away)
        pr.mkt = false
        pr.pMarket = null
        if (oddsOk(odds, 3)) {
            val inv = odds!!.map { 1.0 / it }
            val tot = inv.sum()
            val mk = DoubleArray(3) { inv[it] / tot }
            pr.pMarket = mk
            val v = DoubleArray(3) { max(pr.pModel[it], 1e-6).pow(1.0 - MARKET_WEIGHT) * mk[it].pow(MARKET_WEIGHT) }
            val s3 = v.sum()
            pr.home = v[0] / s3; pr.draw = v[1] / s3; pr.away = v[2] / s3
            pr.mkt = true
        }
        if (oddsOk(o25odds, 2)) {
            val a = 1.0 / o25odds!![0]; val b = 1.0 / o25odds[1]
            val pm = a / (a + b)
            pr.over25 = sigmoid((1.0 - MARKET_WEIGHT) * logit(pr.over25) + MARKET_WEIGHT * logit(pm))
            pr.mkt = true
        }
        return pr
    }

    fun agreement(pred: Pred): Agreement? {
        val pm = pred.pModel; val mk = pred.pMarket ?: return null
        val a = (0..2).maxByOrNull { pm[it] }!!
        val b = (0..2).maxByOrNull { mk[it] }!!
        if (a != b) return Agreement("disagree", "Disagree", a, b, pm, mk)
        val gap = pm[a] - mk[a]
        val (code, label) = when {
            gap >= AGREE_GAP -> "more" to "Model more sure"
            gap <= -AGREE_GAP -> "less" to "Model less sure"
            else -> "agree" to "Agree"
        }
        return Agreement(code, label, a, a, pm, mk)
    }

    fun leagueTable(matches: List<Match>): List<TableRow> {
        val t = LinkedHashMap<String, TableRow>()
        for (m in matches) {
            val g = xg(m)
            val sides = listOf(
                arrayOf<Any>(m.home, m.hg, m.ag, g[0], g[1]),
                arrayOf<Any>(m.away, m.ag, m.hg, g[1], g[0])
            )
            for (s in sides) {
                val e = t.getOrPut(s[0] as String) { TableRow(s[0] as String) }
                val gf = s[1] as Int; val ga = s[2] as Int
                e.P++; e.GF += gf; e.GA += ga; e.xGF += s[3] as Double; e.xGA += s[4] as Double
                if (gf > ga) e.W++ else if (gf == ga) e.D++ else e.L++
            }
        }
        val rows = t.values.sortedWith(compareBy({ -it.Pts }, { -it.GD }, { -it.GF }, { it.team }))
        rows.forEachIndexed { i, e -> e.pos = i + 1 }
        return rows
    }

    fun teamFactors(matches: List<Match>, k: Double = 4.0): TeamFactors {
        val keys = listOf("shots", "sot", "fouls", "yc")
        fun cols(m: Match, x: String): Pair<Int?, Int?> = when (x) {
            "shots" -> m.hs to m.aws; "sot" -> m.hst to m.ast; "fouls" -> m.hf to m.af; else -> m.hy to m.ay
        }
        val agg = LinkedHashMap<String, HashMap<String, DoubleArray>>()
        val tot = HashMap<String, DoubleArray>()
        for (x in keys) tot[x] = DoubleArray(2)
        for (m in matches) for (x in keys) {
            val (h, a) = cols(m, x)
            if (h == null || a == null) continue
            for ((team, f, ag) in listOf(Triple(m.home, h, a), Triple(m.away, a, h))) {
                val rec = agg.getOrPut(team) { HashMap() }.getOrPut(x) { DoubleArray(3) }
                rec[0] += f; rec[1] += ag; rec[2] += 1.0
                tot[x]!![0] += f; tot[x]!![1] += 1.0
            }
        }
        val avg = HashMap<String, Double>()
        for (x in keys) avg[x] = if (tot[x]!![1] > 0) tot[x]!![0] / tot[x]!![1] else 1.0
        val teams = HashMap<String, Map<String, Double>>()
        for ((team, recs) in agg) {
            val d = HashMap<String, Double>()
            for (x in keys) {
                val rec = recs[x]
                if (rec != null && rec[2] > 0 && avg.getValue(x) > 0) {
                    d[x + "_for"] = shrink((rec[0] / rec[2]) / avg.getValue(x), rec[2], k)
                    d[x + "_against"] = shrink((rec[1] / rec[2]) / avg.getValue(x), rec[2], k)
                } else { d[x + "_for"] = 1.0; d[x + "_against"] = 1.0 }
            }
            teams[team] = d
        }
        return TeamFactors(avg, teams)
    }

    fun refKey(name: String?): String {
        val n = (name ?: "").replace(Regex("[^A-Za-z' \\-]"), " ").trim().lowercase()
        val parts = n.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0]
        return parts[0].substring(0, 1) + " " + parts.last()
    }

    fun refereeStats(matches: List<Match>): RefStats {
        val by = LinkedHashMap<String, MutableList<Match>>()
        for (m in matches) if (m.ref.isNotEmpty()) by.getOrPut(refKey(m.ref)) { ArrayList() }.add(m)
        val allN = by.values.sumOf { it.size }
        var yc = 0.0; var rc = 0.0; var fl = 0.0
        for (v in by.values) for (m in v) {
            yc += (m.hy ?: 0) + (m.ay ?: 0); rc += (m.hr ?: 0) + (m.ar ?: 0); fl += (m.hf ?: 0) + (m.af ?: 0)
        }
        val league = mapOf("yc" to (if (allN > 0) yc / allN else 0.0), "rc" to (if (allN > 0) rc / allN else 0.0),
            "fouls" to (if (allN > 0) fl / allN else 0.0))
        val refs = LinkedHashMap<String, RefStat>()
        for ((key, v) in by) {
            val n = v.size
            val y = v.sumOf { (it.hy ?: 0) + (it.ay ?: 0) }.toDouble() / n
            val r = v.sumOf { (it.hr ?: 0) + (it.ar ?: 0) }.toDouble() / n
            val f = v.sumOf { (it.hf ?: 0) + (it.af ?: 0) }.toDouble() / n
            val hy = v.sumOf { it.hy ?: 0 }.toDouble() / n
            val ay = v.sumOf { it.ay ?: 0 }.toDouble() / n
            val names = LinkedHashMap<String, Int>()
            for (m in v) names[m.ref] = (names[m.ref] ?: 0) + 1
            val disp = names.maxByOrNull { it.value }!!.key
            val lgYc = league.getValue("yc")
            refs[key] = RefStat(key, disp, n, y, r, f, hy, ay,
                if (lgYc != 0.0) shrink(y / lgYc, n.toDouble(), 8.0) else 1.0,
                v.sortedByDescending { it.date })
        }
        return RefStats(league, refs)
    }

    // ---- positions
    fun posGroup(pos: String?): String {
        val p = (pos ?: "").uppercase()
        if (p == "G") return "G"
        if (p.startsWith("CD") || p in setOf("LB", "RB", "LWB", "RWB", "WB", "D")) return "D"
        if (p.startsWith("CF") || p in setOf("F", "ST", "SS", "LW", "RW", "FW")) return "F"
        if (p.startsWith("AM") || p.startsWith("CM") || p in setOf("DM", "SW", "LM", "RM", "M", "CAM", "CDM")) return "M"
        return ""
    }

    fun posSide(pos: String?): String {
        val p = (pos ?: "").uppercase()
        if (p.endsWith("-L") || p.startsWith("L")) return "L"
        if (p.endsWith("-R") || p.startsWith("R")) return "R"
        return "C"
    }

    fun posLine(pos: String?): String {
        val p = (pos ?: "").uppercase()
        if (p == "G") return "G"
        if (p in setOf("LB", "RB", "LWB", "RWB", "WB")) return "FB"
        if (p.startsWith("CD")) return "CB"
        if (p in setOf("LM", "RM", "LW", "RW") || p in setOf("AM-L", "AM-R")) return "W"
        if (p.startsWith("CF") || p in setOf("F", "ST", "SS", "FW")) return "S"
        if (p.startsWith("AM") || p.startsWith("CM") || p in setOf("DM", "SW", "M", "CAM", "CDM")) return "CM"
        return ""
    }

    const val FORM_MATCHES = 8
    const val FORM_HALF_LIFE = 1.5
    const val START_A = 0.08; const val START_B = 0.80
    const val NOTSTART_A = 0.07; const val NOTSTART_B = 0.90; const val NOTSTART_MAX = 0.60
    const val BENCH_A = 0.15; const val BENCH_B = 0.90; const val BENCH_S = 0.40
    const val SUB_LAM_SCALE = 0.6
    const val FOUL_SUB_FIX = true
    const val MIN_PLAY = 0.12
    const val PRI_K = 1.0

    fun aggregatePlayers(events: List<Event>): Aggregated {
        val players = LinkedHashMap<String, Agg>()
        val teamMatches = LinkedHashMap<String, Int>()
        val lastEvent = LinkedHashMap<String, LastEvent>()
        val hist = LinkedHashMap<String, MutableList<List<PlayerLine>>>()
        for (ev in events.sortedBy { it.date }) {
            for (side in listOf("home", "away")) {
                val sd = ev.side(side) ?: continue
                val team = sd.team
                teamMatches[team] = (teamMatches[team] ?: 0) + 1
                hist.getOrPut(team) { ArrayList() }.add(sd.players)
                lastEvent[team] = LastEvent(ev.date, sd.formation, sd.players)
                for (p in sd.players) {
                    if (!p.played) continue
                    val a = players.getOrPut(p.id) { Agg(p.id, p.name, team) }
                    a.team = team
                    val role = if (p.starter) "start" else "sub"
                    if (p.starter && p.pos.isNotEmpty()) a.posCounts[p.pos] = (a.posCounts[p.pos] ?: 0) + 1
                    val r = a.role(role)
                    r.apps++
                    r.s[S_FOULS] += p.fouls; r.s[S_FOULED] += p.fouled; r.s[S_SHOTS] += p.shots
                    r.s[S_SOT] += p.sot; r.s[S_YC] += p.yc
                    a.rc += p.rc
                }
            }
        }
        for (a in players.values) {
            a.pos = a.posCounts.maxByOrNull { it.value }?.key ?: ""
            a.group = posGroup(a.pos)
        }
        return Aggregated(players, teamMatches, lastEvent, hist.mapValues { it.value.takeLast(FORM_MATCHES) })
    }

    private fun d(f: Double, fd: Double, sh: Double, sot: Double, yc: Double) = doubleArrayOf(f, fd, sh, sot, yc)
    val DEFAULT_PRI: Map<String, DoubleArray> = mapOf(
        "ALL|start" to d(0.8496, 0.8484, 1.0129, 0.3368, 0.1471), "ALL|sub" to d(0.3221, 0.2917, 0.4169, 0.1422, 0.0636),
        "D|start" to d(0.8027, 0.6399, 0.5443, 0.1508, 0.1628), "D|sub" to d(0.2592, 0.1776, 0.1634, 0.0462, 0.0669),
        "F|start" to d(0.9957, 1.1328, 2.1166, 0.8407, 0.1103), "F|sub" to d(0.3745, 0.3206, 0.6383, 0.2341, 0.0644),
        "G|start" to d(0.0202, 0.1406, 0.0059, 0.0050, 0.0638), "G|sub" to d(0.0227, 0.0455, 0.0, 0.0, 0.0227),
        "M|start" to d(1.0432, 1.0553, 1.2061, 0.3757, 0.1690), "M|sub" to d(0.3355, 0.3244, 0.4158, 0.1383, 0.0660),
        "X|start" to d(0.8373, 1.4345, 2.0972, 0.7609, 0.1057), "X|sub" to d(0.2548, 0.2966, 0.4232, 0.1416, 0.0395)
    )

    fun groupPriors(players: Map<String, Agg>): Map<String, Rates> {
        val acc = LinkedHashMap<String, RoleTot>()
        for (a in players.values) for (role in listOf("start", "sub")) {
            val g = if (a.group.isEmpty()) "X" else a.group
            for (gg in LinkedHashSet(listOf(g, "ALL"))) {
                val e = acc.getOrPut("$gg|$role") { RoleTot() }
                val src = a.role(role)
                e.apps += src.apps
                for (i in 0 until 5) e.s[i] += src.s[i]
            }
        }
        val pri = LinkedHashMap<String, Rates>()
        val keys = LinkedHashSet<String>(); keys.addAll(acc.keys); keys.addAll(DEFAULT_PRI.keys)
        for (key in keys) {
            val e = acc[key] ?: RoleTot()
            val role = key.substringAfter('|')
            val df = DEFAULT_PRI[key] ?: DEFAULT_PRI["ALL|$role"]
            val v = DoubleArray(5)
            if (df != null && PRI_K > 0) {
                for (i in 0 until 5) v[i] = (e.s[i] + PRI_K * df[i]) / (e.apps + PRI_K)
            } else {
                for (i in 0 until 5) v[i] = if (e.apps > 0) e.s[i].toDouble() / e.apps else 0.0
            }
            pri[key] = Rates(v, e.apps.toDouble())
        }
        return pri
    }

    private fun priorFor(pri: Map<String, Rates>, group: String, role: String): Rates {
        val g = if (group.isEmpty()) "X" else group
        val x = pri["$g|$role"]
        if (x != null && x.apps >= 15) return x
        return pri["ALL|$role"] ?: Rates(DoubleArray(5), 0.0)
    }

    /** Shrunk per-appearance rates, for both roles (index 0 = start, 1 = sub). */
    fun playerRates(a: Agg?, pri: Map<String, Rates>, k: Double = 4.0): Array<Rates> {
        return Array(2) { ri ->
            val role = if (ri == 0) "start" else "sub"
            val p = priorFor(pri, a?.group ?: "", role)
            val rec = a?.role(role)
            val n = rec?.apps ?: 0
            val v = DoubleArray(5) { i -> ((rec?.s?.get(i) ?: 0) + k * p.v[i]) / (n + k) }
            Rates(v, n.toDouble())
        }
    }

    fun probOn(a: Agg?, teamN: Int): Double {
        val subs = a?.sub?.apps ?: 0
        return clip((subs + 0.6) / (teamN + 2.0), 0.05, 0.85)
    }

    fun teamForm(history: List<List<PlayerLine>>, half: Double = FORM_HALF_LIFE): Map<String, FormEntry> {
        val n = history.size
        if (n == 0) return emptyMap()
        val w = DoubleArray(n) { k -> 0.5.pow((n - 1 - k) / half) }
        val tot = w.sum()
        val out = LinkedHashMap<String, FormEntry>()
        for (k in 0 until n) for (p in history[k]) {
            val e = out.getOrPut(p.id) { FormEntry(0.0, 0.0, p) }
            e.p = p
            if (p.starter) e.s += w[k] / tot else if (p.subIn) e.b += w[k] / tot
        }
        return out
    }

    fun startChance(f: FormEntry) = clip(START_A + START_B * f.s, 0.01, 0.97)
    fun subChance(f: FormEntry) = clip(NOTSTART_A + NOTSTART_B * f.b, 0.02, NOTSTART_MAX)
    fun benchChance(f: FormEntry?, a: Agg?, teamN: Int): Double {
        if (f == null) return probOn(a, teamN)
        return clip(BENCH_A + BENCH_B * f.b + BENCH_S * f.s, 0.02, 0.8)
    }

    private fun opposite(side: String) = if (side == "L") "R" else if (side == "R") "L" else "C"

    fun facing(pos: String, opp: List<PlayerLine>): List<PlayerLine> {
        val line = posLine(pos); val side = posSide(pos)
        if (line == "" || line == "G") return emptyList()
        val o = opposite(side)
        fun pick(l: String, s: String?) = opp.filter { posLine(it.pos) == l && (s == null || posSide(it.pos) == s) }
        var c: List<PlayerLine> = emptyList()
        when (line) {
            "W" -> { c = pick("FB", o); if (c.isEmpty()) c = pick("W", o); if (c.isEmpty()) c = pick("CB", o) }
            "FB" -> { c = pick("W", o); if (c.isEmpty()) c = pick("S", null) }
            "CB" -> c = pick("S", null)
            "S" -> c = pick("CB", null)
            "CM" -> c = pick("CM", null)
        }
        return c
    }

    private fun rateRatio(rate: Double, avg: Double) = if (avg > 0) rate / avg else 1.0

    private fun foulsProbs(lam: Double, roleKey: String, stat: String = "fouls"): List<Double> {
        val raw = (1..3).map { pAtLeast(it, lam) }
        if (roleKey != "start") return raw
        val out = ArrayList<Double>(); var cap = 1.0
        for ((i, p) in raw.withIndex()) {
            val q = min(recalibrate(p, "$stat|${i + 1}"), cap)
            out.add(q); cap = q
        }
        return out
    }

    private fun countProbs(lam: Double, stat: String, ks: IntArray, roleKey: String): List<Double> {
        val out = ArrayList<Double>(); var cap = 1.0
        for (k in ks) {
            val q = min(recalibrate(pAtLeast(k, lam), "$stat|$k|$roleKey"), cap)
            out.add(q); cap = q
        }
        return out
    }

    private class Probs(val booked: List<Double>, val fouls: List<Double>, val fouled: List<Double>, val shots: List<Double>, val sot: List<Double>)

    private class Entry(val p: PlayerLine, val role: String, val ps: Double?)

    fun predictPlayers(home: String, away: String, lineup: Lineup, ctx: Ctx): List<PRow> {
        val r = ctx.ratings; val tf = ctx.tf
        val players = ctx.players; val pri = ctx.pri
        val refF = ctx.refFactor
        val rows = ArrayList<PRow>()
        val sides = listOf(Triple("home", home, away), Triple("away", away, home))
        val starterRates = HashMap<String, Pair<Rates, String>>()
        for ((side, _, _) in sides) for (p in lineup.side(side).starters) {
            val a = players[p.id]
            val rates = playerRates(a, pri)[0]
            val pos = if (p.pos.isNotEmpty()) p.pos else (a?.pos ?: "")
            starterRates[p.id] = rates to pos
        }
        for ((side, team, opp) in sides) {
            val oppSide = if (side == "home") "away" else "home"
            val oppStarters = lineup.side(oppSide).starters.map { it.copy(pos = starterRates.getValue(it.id).second) }
            val tOpp = tf.teams[opp] ?: emptyMap()
            val haScale = r.ha.pow(if (side == "home") 0.5 else -0.5)
            val fShots = (tOpp["shots_against"] ?: 1.0) * haScale
            val fSot = (tOpp["sot_against"] ?: 1.0) * haScale
            val fFouls = tOpp["fouls_against"] ?: 1.0
            val fFouled = tOpp["fouls_for"] ?: 1.0
            val fBook = clip(fFouls.pow(0.5), 0.7, 1.4) * refF
            val ls = lineup.side(side)
            val form = ls.form
            val confirmed = ls.confirmed
            val squad = ls.starters.associateBy { it.id }
            val entries = ArrayList<Entry>()
            if (confirmed || form.isEmpty()) {
                ls.starters.forEach { entries.add(Entry(it, "start", null)) }
                ls.bench.forEach { entries.add(Entry(it, "sub", null)) }
            } else {
                ls.starters.forEach { entries.add(Entry(it, "start", form[it.id]?.let { f -> startChance(f) } ?: 0.85)) }
                val banned = ls.out
                for ((pid, f) in form.entries.sortedByDescending { it.value.s }) {
                    if (squad.containsKey(pid) || banned.contains(pid)) continue
                    val a0 = players[pid]
                    if (a0 != null && a0.team != team) continue
                    val ps0 = startChance(f)
                    if (ps0 + (1.0 - ps0) * subChance(f) >= MIN_PLAY) entries.add(Entry(f.p, "sub", ps0))
                }
            }
            for (en in entries) {
                val p = en.p; val roleKey = en.role; val ps = en.ps
                val a = players[p.id]
                val ratesAll = playerRates(a, pri)
                val pos = if (p.pos.isNotEmpty() && p.pos.uppercase() != "SUB") p.pos else (a?.pos ?: "")
                val group = posGroup(pos).ifEmpty { a?.group ?: "" }

                fun probs(role: String, scale: Double, matchup: Boolean, fscale: Double?): Probs {
                    val rr = ratesAll[if (role == "start") 0 else 1].v
                    val fsc = if (fscale == null || !FOUL_SUB_FIX) scale else fscale
                    var mFouls = 1.0; var mFouled = 1.0
                    if (matchup) {
                        val opps = facing(pos, oppStarters)
                        if (opps.isNotEmpty()) {
                            val rFouled = ArrayList<Double>(); val rFouls = ArrayList<Double>()
                            for (o in opps) {
                                val (orates, opos) = starterRates.getValue(o.id)
                                val og = posGroup(opos).ifEmpty { "X" }
                                val avg = priorFor(pri, og, "start")
                                rFouled.add(rateRatio(orates.v[S_FOULED], avg.v[S_FOULED]))
                                rFouls.add(rateRatio(orates.v[S_FOULS], avg.v[S_FOULS]))
                            }
                            mFouls = clip((rFouled.sum() / rFouled.size).pow(0.5), 0.75, 1.45)
                            mFouled = clip((rFouls.sum() / rFouls.size).pow(0.5), 0.75, 1.45)
                        }
                    }
                    val lFouls = rr[S_FOULS] * fFouls * mFouls * fsc
                    val lFouled = rr[S_FOULED] * fFouled * mFouled * fsc
                    val lShots = rr[S_SHOTS] * fShots * scale
                    val lSot = rr[S_SOT] * fSot * scale
                    val lYc = rr[S_YC] * fBook * mFouls * scale
                    return Probs(
                        listOf(recalibrate(1.0 - exp(-lYc), "yc|$role")),
                        foulsProbs(lFouls, role), foulsProbs(lFouled, role, "fouled"),
                        countProbs(lShots, "shots", intArrayOf(1, 2, 3), role),
                        countProbs(lSot, "sot", intArrayOf(1, 2), role)
                    )
                }

                val pr: Probs; val plays: Double
                if (ps == null) {
                    var scale = 1.0
                    if (roleKey == "sub") scale = benchChance(form[p.id], a, ctx.teamMatches[team] ?: 0)
                    val lamScale = if (roleKey == "sub" && form[p.id] != null) scale * SUB_LAM_SCALE else scale
                    pr = probs(roleKey, lamScale, roleKey == "start", scale)
                    plays = if (roleKey == "start") 1.0 else scale
                } else {
                    val f = form[p.id]
                    val q = if (f != null) subChance(f) else 0.07
                    val inXi = squad.containsKey(p.id)
                    val pS = probs("start", 1.0, inXi, null)
                    val pB = probs("sub", q * SUB_LAM_SCALE, false, q)
                    fun mix(x: List<Double>, y: List<Double>) = x.indices.map { ps * x[it] + (1.0 - ps) * y[it] }
                    pr = Probs(mix(pS.booked, pB.booked), mix(pS.fouls, pB.fouls), mix(pS.fouled, pB.fouled),
                        mix(pS.shots, pB.shots), mix(pS.sot, pB.sot))
                    plays = ps + (1.0 - ps) * q
                }
                rows.add(PRow(p.id, p.name, team, side, pos, group, if (roleKey == "start") "Starter" else "Sub",
                    plays, (a?.let { it.start.apps + it.sub.apps } ?: 0),
                    pr.booked[0], pr.fouls, pr.fouled, pr.shots, pr.sot))
            }
        }
        return rows
    }
}
