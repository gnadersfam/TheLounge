package com.lounge.tv

import java.util.GregorianCalendar
import java.util.TimeZone

/** Where football data is saved. The Android app uses Store (files on the Firestick); tests use memory. */
interface Persist {
    fun load(name: String): Any?
    fun save(name: String, v: Any?)
}

class MemPersist : Persist {
    val m = HashMap<String, Any?>()
    override fun load(name: String): Any? = m[name]?.let { Json.parseOrNull(Json.write(it)) }
    override fun save(name: String, v: Any?) { m[name] = Json.parseOrNull(Json.write(v)) }
}

class Fixture(
    val utc: String, val home: String, val away: String, val matchday: Int?,
    var ref: String, var odds: DoubleArray?, var o25: DoubleArray?
) {
    fun same(o: Fixture) = utc == o.utc && home == o.home && away == o.away
    fun key(lk: String) = "$lk|$utc|$home|$away"
}

/** What ESPN says about one match: team sheets (and, once played, each player's numbers) and officials. */
class Summary(val event: String, val officials: List<String>, val homeSide: SideLine?, val awaySide: SideLine?, var fetched: String) {
    fun side(s: String) = if (s == "home") homeSide else awaySide
}

object Utc {
    /** "2026-10-24T14:00:00Z" -> seconds since 1970, or null. */
    fun parse(s: String?): Long? {
        if (s == null || s.length < 10) return null
        return try {
            val y = s.substring(0, 4).toInt(); val mo = s.substring(5, 7).toInt(); val d = s.substring(8, 10).toInt()
            var h = 0; var mi = 0; var sec = 0
            if (s.length >= 16) { h = s.substring(11, 13).toInt(); mi = s.substring(14, 16).toInt() }
            if (s.length >= 19 && s[16] == ':') sec = s.substring(17, 19).toInt()
            val c = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(y, mo - 1, d, h, mi, sec)
            var t = c.timeInMillis / 1000
            // an explicit offset such as +01:00
            val tail = if (s.length > 19) s.substring(19) else ""
            val m = Regex("([+-])(\\d\\d):?(\\d\\d)$").find(tail)
            if (m != null) {
                val off = m.groupValues[2].toInt() * 3600 + m.groupValues[3].toInt() * 60
                t -= if (m.groupValues[1] == "+") off else -off
            }
            t
        } catch (e: Exception) { null }
    }

    fun iso(t: Long): String {
        val c = GregorianCalendar(TimeZone.getTimeZone("UTC")); c.timeInMillis = t * 1000
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(c.get(1), c.get(2) + 1, c.get(5), c.get(11), c.get(12), c.get(13))
    }

    fun day(t: Long): String = iso(t).substring(0, 10)
}

object Csv {
    /** Reads comma-separated text with a header row into one map per line. */
    fun parse(text0: String): List<Map<String, String>> {
        val text = text0.removePrefix("﻿")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cell = StringBuilder(); var q = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (q) {
                if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else q = false }
                else cell.append(ch)
            } else when (ch) {
                '"' -> q = true
                ',' -> { row.add(cell.toString()); cell.setLength(0) }
                '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList() }
                '\r' -> {}
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        if (rows.isEmpty()) return emptyList()
        val head = rows[0]
        val out = ArrayList<Map<String, String>>()
        for (r in rows.drop(1)) {
            if (r.size == 1 && r[0].isEmpty()) continue
            val m = HashMap<String, String>()
            for (k in head.indices) if (k < r.size) m[head[k]] = r[k]
            out.add(m)
        }
        return out
    }
}

object FootJson {
    private fun dn(x: Double?): Any? = x
    private fun ni(x: Int?): Any? = x

    fun matchTo(m: Match): Map<String, Any?> = linkedMapOf(
        "date" to m.date, "time" to m.time, "home" to m.home, "away" to m.away, "hg" to m.hg, "ag" to m.ag, "ref" to m.ref,
        "hxg" to dn(m.hxg), "axg" to dn(m.axg), "hs" to ni(m.hs), "as" to ni(m.aws), "hst" to ni(m.hst), "ast" to ni(m.ast),
        "hf" to ni(m.hf), "af" to ni(m.af), "hy" to ni(m.hy), "ay" to ni(m.ay), "hr" to ni(m.hr), "ar" to ni(m.ar), "season" to m.season
    )

    private fun oi(o: Any?): Int? = if (o == null) null else o.int()
    private fun od(o: Any?): Double? = if (o == null) null else o.num()

    fun matchFrom(o: Any?): Match {
        val m = o.obj()
        return Match(m["date"].str(), m["time"].str(), m["home"].str(), m["away"].str(), m["hg"].int(), m["ag"].int(), m["ref"].str(),
            od(m["hxg"]), od(m["axg"]), oi(m["hs"]), oi(m["as"]), oi(m["hst"]), oi(m["ast"]), oi(m["hf"]), oi(m["af"]),
            oi(m["hy"]), oi(m["ay"]), oi(m["hr"]), oi(m["ar"]), m["season"].str())
    }

    fun playerTo(p: PlayerLine): Map<String, Any?> = linkedMapOf(
        "id" to p.id, "name" to p.name, "pos" to p.pos, "starter" to p.starter, "sub_in" to p.subIn, "played" to p.played,
        "fouls" to p.fouls, "fouled" to p.fouled, "shots" to p.shots, "sot" to p.sot, "yc" to p.yc, "rc" to p.rc
    )

    fun playerFrom(o: Any?): PlayerLine {
        val p = o.obj()
        return PlayerLine(p["id"].str(), p["name"].str(), p["pos"].str(), p["starter"].bool(), p["sub_in"].bool(), p["played"].bool(),
            p["fouls"].int(), p["fouled"].int(), p["shots"].int(), p["sot"].int(), p["yc"].int(), p["rc"].int())
    }

    fun sideTo(s: SideLine): Map<String, Any?> =
        linkedMapOf("team" to s.team, "formation" to s.formation, "players" to s.players.map { playerTo(it) })

    fun sideFrom(o: Any?): SideLine? {
        if (o == null) return null
        val s = o.obj()
        return SideLine(s["team"].str(), s["formation"].str(), s["players"].arr().map { playerFrom(it) })
    }

    fun eventTo(e: Event): Map<String, Any?> {
        val sides = LinkedHashMap<String, Any?>()
        e.home_?.let { sides["home"] = sideTo(it) }
        e.away_?.let { sides["away"] = sideTo(it) }
        return linkedMapOf("league" to e.league, "date" to e.date, "home" to e.home, "away" to e.away, "sides" to sides)
    }

    fun eventFrom(o: Any?): Event {
        val e = o.obj(); val sd = e["sides"].obj()
        return Event(e["league"].str(), e["date"].str(), e["home"].str(), e["away"].str(), sideFrom(sd["home"]), sideFrom(sd["away"]))
    }

    private fun oddsTo(a: DoubleArray?): Any? = a?.toList()
    private fun oddsFrom(o: Any?): DoubleArray? {
        val l = (o as? List<*>) ?: return null
        if (l.isEmpty()) return null
        return DoubleArray(l.size) { l[it].num() }
    }

    fun fixtureTo(f: Fixture): Map<String, Any?> = linkedMapOf(
        "utc" to f.utc, "home" to f.home, "away" to f.away, "matchday" to ni(f.matchday), "ref" to f.ref,
        "odds" to oddsTo(f.odds), "o25odds" to oddsTo(f.o25)
    )

    fun fixtureFrom(o: Any?): Fixture {
        val f = o.obj()
        return Fixture(f["utc"].str(), f["home"].str(), f["away"].str(), oi(f["matchday"]), f["ref"].str(), oddsFrom(f["odds"]), oddsFrom(f["o25odds"]))
    }

    fun summaryTo(s: Summary): Map<String, Any?> {
        val sides = LinkedHashMap<String, Any?>()
        s.homeSide?.let { sides["home"] = sideTo(it) }
        s.awaySide?.let { sides["away"] = sideTo(it) }
        return linkedMapOf("event" to s.event, "officials" to s.officials, "sides" to sides, "fetched" to s.fetched)
    }

    fun summaryFrom(o: Any?): Summary {
        val s = o.obj(); val sd = s["sides"].obj()
        return Summary(s["event"].str(), s["officials"].arr().map { it.str() }, sideFrom(sd["home"]), sideFrom(sd["away"]), s["fetched"].str())
    }
}

/** Everything the football pages know, saved as small files on the Firestick. */
class FootState(val io: Persist) {
    var results: MutableMap<String, List<Match>> = HashMap()
    var prev: MutableMap<String, List<Match>> = HashMap()
    var fixtures: MutableMap<String, MutableList<Fixture>> = HashMap()
    var events: LinkedHashMap<String, Event> = LinkedHashMap()
    var upcoming: MutableMap<String, Summary> = HashMap()
    var meta: MutableMap<String, Any?> = HashMap()

    fun load(): FootState {
        results = loadMatches("f_results"); prev = loadMatches("f_prev")
        fixtures = HashMap()
        for ((lk, l) in io.load("f_fixtures").obj()) fixtures[lk] = l.arr().map { FootJson.fixtureFrom(it) }.toMutableList()
        events = LinkedHashMap()
        for ((id, e) in io.load("f_events").obj()) events[id] = FootJson.eventFrom(e)
        upcoming = HashMap()
        for ((k, s) in io.load("f_upcoming").obj()) upcoming[k] = FootJson.summaryFrom(s)
        meta = HashMap(io.load("f_meta").obj())
        return this
    }

    private fun loadMatches(name: String): MutableMap<String, List<Match>> {
        val out = HashMap<String, List<Match>>()
        for ((lk, l) in io.load(name).obj()) out[lk] = l.arr().map { FootJson.matchFrom(it) }
        return out
    }

    fun saveResults() = io.save("f_results", results.mapValues { e -> e.value.map { FootJson.matchTo(it) } })
    fun savePrev() = io.save("f_prev", prev.mapValues { e -> e.value.map { FootJson.matchTo(it) } })
    fun saveFixtures() = io.save("f_fixtures", fixtures.mapValues { e -> e.value.map { FootJson.fixtureTo(it) } })
    fun saveEvents() = io.save("f_events", events.mapValues { FootJson.eventTo(it.value) })
    fun saveUpcoming() = io.save("f_upcoming", upcoming.mapValues { FootJson.summaryTo(it.value) })
    fun saveMeta() = io.save("f_meta", meta)

    val hasData get() = (results.values.sumOf { it.size }) > 0
    fun lastUpdated(): String = meta["last_updated"].str()
}

object Leagues {
    val ORDER = listOf("PL", "ELC")
    class L(val name: String, val csv: String, val fdo: String, val espn: String)
    val ALL = mapOf(
        "PL" to L("Premier League", "E0", "PL", "eng.1"),
        "ELC" to L("Championship", "E1", "ELC", "eng.2")
    )

    /** football-data.co.uk season code such as "2627"; back = 1 gives last season. */
    fun seasonCode(year: Int, month: Int, back: Int = 0): String {
        var start = if (month >= 7) year else year - 1
        start -= back
        return "%02d%02d".format(start % 100, (start + 1) % 100)
    }
}
