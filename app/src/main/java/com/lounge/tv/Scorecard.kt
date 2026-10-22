package com.lounge.tv

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Prediction scorecard: remembers what the model predicted for each match, then scores it once the result is in. */
class ScRec(
    val lk: String, val date: String, val home: String, val away: String, val src: String, val utc: String?, val made: String?,
    val ver: Int, val mkt: Boolean, val pm: DoubleArray, val p: DoubleArray, val btts: Double, val o25: Double,
    val scoreH: Int, val scoreA: Int, val lh: Double, val la: Double, val base: DoubleArray, var res: IntArray?
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "lk" to lk, "date" to date, "home" to home, "away" to away, "src" to src, "utc" to utc, "made" to made, "ver" to ver,
        "mkt" to mkt, "pm" to pm.toList(), "p" to p.toList(), "btts" to btts, "o25" to o25, "score" to listOf(scoreH, scoreA),
        "lh" to lh, "la" to la, "base" to linkedMapOf("h" to base[0], "d" to base[1], "a" to base[2], "btts" to base[3], "o25" to base[4]),
        "res" to res?.toList()
    )

    companion object {
        fun fromJson(o: Any?): ScRec {
            val m = o.obj(); val b = m["base"].obj(); val sc = m["score"].arr()
            fun da(x: Any?) = x.arr().map { it.num() }.toDoubleArray()
            val r = m["res"]
            return ScRec(m["lk"].str(), m["date"].str(), m["home"].str(), m["away"].str(), m["src"].str(),
                m["utc"]?.str(), m["made"]?.str(), m["ver"].int(), m["mkt"].bool(), da(m["pm"]), da(m["p"]), m["btts"].num(), m["o25"].num(),
                sc.getOrNull(0).int(), sc.getOrNull(1).int(), m["lh"].num(), m["la"].num(),
                doubleArrayOf(b["h"].num(), b["d"].num(), b["a"].num(), b["btts"].num(), b["o25"].num()),
                if (r is List<*>) IntArray(r.size) { r[it].int() } else null)
        }
    }
}

class MarketScore(val label: String, val n: Int, val hit: Double, val conf: Double?, val skill: Double?)
class BandScore(val label: String, val n: Int, val hit: Double, val conf: Double)
class CardSummary(val n: Int, val markets: List<MarketScore>, val bands: List<BandScore>)

object Scorecard {
    const val MIN_PRIOR = 20
    const val MODEL_VERSION = 2
    const val NAME = "f_scorecard"
    private val FALLBACK = doubleArrayOf(0.45, 0.25, 0.30, 0.50, 0.50)

    fun seasonOf(date: String): String {
        val y = date.substring(0, 4).toInt(); val m = date.substring(5, 7).toInt()
        return (if (m >= 7) y else y - 1).toString()
    }

    fun recKey(lk: String, date: String, home: String, away: String) = "$lk|${seasonOf(date)}|$home|$away"

    fun baseRates(ms: List<Match>): DoubleArray {
        val n = ms.size
        if (n < 10) return FALLBACK.copyOf()
        val h = ms.count { it.hg > it.ag }.toDouble() / n
        val d = ms.count { it.hg == it.ag }.toDouble() / n
        return doubleArrayOf(h, d, 1.0 - h - d, ms.count { it.hg > 0 && it.ag > 0 }.toDouble() / n, ms.count { it.hg + it.ag >= 3 }.toDouble() / n)
    }

    fun r4(x: Double) = Math.round(x * 10000.0) / 10000.0
    fun r3(x: Double) = Math.round(x * 1000.0) / 1000.0

    fun make(lk: String, date: String, home: String, away: String, pred: Pred, base: DoubleArray, src: String, utc: String? = null, made: String? = null) =
        ScRec(lk, date, home, away, src, utc, made, MODEL_VERSION, pred.mkt,
            doubleArrayOf(r4(pred.pModel[0]), r4(pred.pModel[1]), r4(pred.pModel[2])),
            doubleArrayOf(r4(pred.home), r4(pred.draw), r4(pred.away)), r4(pred.btts), r4(pred.over25),
            pred.scoreH, pred.scoreA, r3(pred.lh), r3(pred.la), DoubleArray(5) { r4(base[it]) }, null)

    fun load(io: Persist): MutableMap<String, ScRec> {
        val out = LinkedHashMap<String, ScRec>()
        for ((k, v) in io.load(NAME).obj()) out[k] = ScRec.fromJson(v)
        return out
    }

    fun save(io: Persist, recs: Map<String, ScRec>) = io.save(NAME, recs.mapValues { it.value.toJson() })

    fun update(eng: Engine, recs: MutableMap<String, ScRec>, nowSec: Long): Boolean {
        var changed = false
        val stale = recs.filter { it.value.src == "replay" && it.value.ver != MODEL_VERSION }.keys.toList()
        for (k in stale) { recs.remove(k); changed = true }
        for (lk in Leagues.ORDER) {
            val lg = eng.league.getValue(lk)
            val ms = lg.matches
            val played = ms.associateBy { recKey(lk, it.date, it.home, it.away) }
            val base = baseRates(ms)
            val hasPrior = lg.prior != null
            if (ms.size >= MIN_PRIOR || hasPrior) {
                for (fx in eng.state.fixtures[lk] ?: emptyList<Fixture>()) {
                    val ko = Utc.parse(fx.utc)
                    if (ko == null || ko <= nowSec) continue
                    val key = recKey(lk, fx.utc.substring(0, 10), fx.home, fx.away)
                    val old = recs[key]
                    if (played.containsKey(key) || (old != null && old.res != null)) continue
                    val pred = Model.predictMatch(lg.ratings, fx.home, fx.away, fx.odds, fx.o25)
                    val nw = make(lk, fx.utc.substring(0, 10), fx.home, fx.away, pred, base, "live", fx.utc, Utc.iso(nowSec))
                    if (old == null || old.p.toList() != nw.p.toList() || old.btts != nw.btts || old.o25 != nw.o25 ||
                        old.scoreH != nw.scoreH || old.scoreA != nw.scoreA || old.mkt != nw.mkt) { recs[key] = nw; changed = true }
                }
            }
            for ((key, rec) in recs) if (rec.lk == lk && rec.res == null && played.containsKey(key)) {
                val m = played.getValue(key); rec.res = intArrayOf(m.hg, m.ag); changed = true
            }
            val todo = java.util.TreeMap<String, MutableList<Match>>()
            for (m in ms) if (!recs.containsKey(recKey(lk, m.date, m.home, m.away))) todo.getOrPut(m.date) { ArrayList() }.add(m)
            for ((d, list) in todo) {
                val prior = ms.filter { it.date < d }
                if (prior.size < MIN_PRIOR && !hasPrior) continue
                val r = Model.buildRatings(prior, lg.prior)
                val b = baseRates(prior)
                for (m in list) {
                    val pred = Model.predictMatch(r, m.home, m.away)
                    val rec = make(lk, m.date, m.home, m.away, pred, b, "replay")
                    rec.res = intArrayOf(m.hg, m.ag)
                    recs[recKey(lk, m.date, m.home, m.away)] = rec
                    changed = true
                }
            }
        }
        return changed
    }

    private fun ll(p0: Double): Double { val p = max(min(p0, 1.0 - 1e-4), 1e-4); return -ln(p) }
    fun outcomeIndex(r: IntArray) = if (r[0] > r[1]) 0 else if (r[0] == r[1]) 1 else 2
    fun pickIndex(rec: ScRec) = (0..2).maxByOrNull { rec.p[it] }!!

    fun scored(recs: Map<String, ScRec>, lk: String? = null, src: String? = null): List<ScRec> =
        recs.values.filter { it.res != null && (lk == null || it.lk == lk) && (src == null || it.src == src) }
            .sortedWith(compareByDescending<ScRec> { it.date }.thenByDescending { it.home })

    fun pendingCount(recs: Map<String, ScRec>) = recs.values.count { it.res == null }

    fun summarise(rows: List<ScRec>): CardSummary {
        val n = rows.size
        if (n == 0) return CardSummary(0, emptyList(), emptyList())
        fun skill(m: Double, b: Double) = if (b > 0) 100.0 * (b - m) / b else 0.0
        val markets = ArrayList<MarketScore>()
        var hit = 0.0; var llm = 0.0; var llb = 0.0; var conf = 0.0
        for (r in rows) {
            val o = outcomeIndex(r.res!!)
            if (pickIndex(r) == o) hit += 1
            llm += ll(r.p[o]); llb += ll(r.base[o]); conf += r.p.max()
        }
        markets.add(MarketScore("Match result (home / draw / away)", n, hit / n, conf / n, skill(llm, llb)))
        for ((label, which) in listOf("Both teams score" to 0, "Over 2.5 goals" to 1)) {
            hit = 0.0; llm = 0.0; llb = 0.0; conf = 0.0
            for (r in rows) {
                val g = r.res!!
                val y = if (which == 0) (g[0] > 0 && g[1] > 0) else (g[0] + g[1] >= 3)
                val p = if (which == 0) r.btts else r.o25
                if ((p >= 0.5) == y) hit += 1
                llm += ll(if (y) p else 1 - p)
                val bp = if (which == 0) r.base[3] else r.base[4]
                llb += ll(if (y) bp else 1 - bp)
                conf += max(p, 1 - p)
            }
            markets.add(MarketScore(label, n, hit / n, conf / n, skill(llm, llb)))
        }
        val exact = rows.count { it.res!![0] == it.scoreH && it.res!![1] == it.scoreA }
        markets.add(MarketScore("Exact score (the single likeliest)", n, exact.toDouble() / n, null, null))
        val bands = ArrayList<BandScore>()
        for ((lo, hi, name) in listOf(Triple(0.0, 0.40, "under 40%"), Triple(0.40, 0.50, "40-50%"), Triple(0.50, 0.60, "50-60%"), Triple(0.60, 1.01, "60% or more"))) {
            val b = rows.filter { it.p.max() >= lo && it.p.max() < hi }
            if (b.isNotEmpty()) bands.add(BandScore("Result pick when confidence is $name", b.size,
                b.count { pickIndex(it) == outcomeIndex(it.res!!) }.toDouble() / b.size, b.sumOf { it.p.max() } / b.size))
        }
        return CardSummary(n, markets, bands)
    }
}
