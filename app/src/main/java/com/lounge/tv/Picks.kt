package com.lounge.tv

/** Your own picks and your player watch-list, saved on the Firestick. */
class PickRec(val lk: String, val date: String, val utc: String, val home: String, val away: String, val pick: String, val mp: DoubleArray, val made: String) {
    fun toJson(): Map<String, Any?> = linkedMapOf("lk" to lk, "date" to date, "utc" to utc, "home" to home, "away" to away,
        "pick" to pick, "mp" to mp.toList(), "made" to made)
    companion object {
        fun fromJson(o: Any?): PickRec {
            val m = o.obj()
            return PickRec(m["lk"].str(), m["date"].str(), m["utc"].str(), m["home"].str(), m["away"].str(), m["pick"].str(),
                m["mp"].arr().map { it.num() }.toDoubleArray(), m["made"].str())
        }
    }
}
class PickRow(val key: String, val p: PickRec, val model: String, val res: IntArray?, val youOk: Boolean?, val modelOk: Boolean?)
class PickSummary(val made: Int, val scored: Int, val you: Int, val model: Int, val both: Int, val differ: List<PickRow>)
class WatchPlayer(val id: String, val name: String, val team: String)

class Picks(private val io: Persist) {
    companion object {
        const val PICKS = "f_picks"
        const val WATCH = "f_watchlist"
        val NAMES = mapOf("H" to "Home", "D" to "Draw", "A" to "Away")
        fun index(code: String) = "HDA".indexOf(code)
        fun pickKey(lk: String, fx: Fixture) = Scorecard.recKey(lk, fx.utc.substring(0, 10), fx.home, fx.away)
    }

    fun loadPicks(): MutableMap<String, PickRec> {
        val out = LinkedHashMap<String, PickRec>()
        for ((k, v) in io.load(PICKS).obj()) out[k] = PickRec.fromJson(v)
        return out
    }

    private fun savePicks(p: Map<String, PickRec>) = io.save(PICKS, p.mapValues { it.value.toJson() })

    fun getPick(lk: String, fx: Fixture): String? = loadPicks()[pickKey(lk, fx)]?.pick

    fun setPick(lk: String, fx: Fixture, code: String, pred: Pred, nowSec: Long) {
        val p = loadPicks()
        p[pickKey(lk, fx)] = PickRec(lk, fx.utc.substring(0, 10), fx.utc, fx.home, fx.away, code,
            doubleArrayOf(Scorecard.r4(pred.home), Scorecard.r4(pred.draw), Scorecard.r4(pred.away)), Utc.iso(nowSec))
        savePicks(p)
    }

    fun clearPick(lk: String, fx: Fixture) {
        val p = loadPicks()
        if (p.remove(pickKey(lk, fx)) != null) savePicks(p)
    }

    fun scoredRows(picks: Map<String, PickRec>, eng: Engine): List<PickRow> {
        val played = HashMap<String, Match>()
        for ((lk, lg) in eng.league) for (m in lg.matches) played[Scorecard.recKey(lk, m.date, m.home, m.away)] = m
        val rows = picks.map { (key, p) ->
            val m = played[key]
            val res = if (m != null) intArrayOf(m.hg, m.ag) else null
            val mi = (0..2).maxByOrNull { p.mp[it] }!!
            var you: Boolean? = null; var mod: Boolean? = null
            if (res != null) {
                val actual = Scorecard.outcomeIndex(res)
                you = index(p.pick) == actual; mod = mi == actual
            }
            PickRow(key, p, "HDA"[mi].toString(), res, you, mod)
        }
        return rows.sortedWith(compareByDescending<PickRow> { it.p.date }.thenByDescending { it.p.home })
    }

    fun summarise(rows: List<PickRow>): PickSummary {
        val done = rows.filter { it.res != null }
        return PickSummary(rows.size, done.size, done.count { it.youOk == true }, done.count { it.modelOk == true },
            done.count { it.youOk == true && it.modelOk == true }, done.filter { it.p.pick != it.model })
    }

    fun loadWatch(): List<WatchPlayer> = io.load(WATCH).arr().map { val o = it.obj(); WatchPlayer(o["id"].str(), o["name"].str(), o["team"].str()) }
    fun inWatch(id: String) = loadWatch().any { it.id == id }

    /** Adds the player, or removes them if already there. Returns true when now on the list. */
    fun toggleWatch(id: String, name: String, team: String): Boolean {
        val w = loadWatch()
        return if (w.any { it.id == id }) {
            io.save(WATCH, w.filter { it.id != id }.map { mapOf("id" to it.id, "name" to it.name, "team" to it.team) }); false
        } else {
            io.save(WATCH, (w + WatchPlayer(id, name, team)).map { mapOf("id" to it.id, "name" to it.name, "team" to it.team) }); true
        }
    }
}
