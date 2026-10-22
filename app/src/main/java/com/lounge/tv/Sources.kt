package com.lounge.tv

import java.io.IOException

/** Downloaders and readers for the free football data sources. The readers (parse...) take plain text so they can be tested. */
object Sources {
    const val FD_CO_UK = "https://www.football-data.co.uk"
    const val FDO = "https://api.football-data.org/v4"
    const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/soccer"

    class Lookahead(val div: String, val date: String, val time: String, val home: String, val away: String,
                    val ref: String, val odds: DoubleArray?, val o25: DoubleArray?)
    class Scoreboard(val id: String, val utc: String, val home: String, val away: String, val completed: Boolean)

    private fun dbl(v: String?): Double? = v?.trim()?.toDoubleOrNull()
    private fun int(v: String?): Int? = v?.trim()?.toIntOrNull()

    /** dd/mm/yyyy or dd/mm/yy -> yyyy-mm-dd */
    fun isoDate(s: String): String? {
        val p = s.split("/")
        if (p.size != 3) return null
        val d = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        var y = p[2].toIntOrNull() ?: return null
        if (p[2].length == 4) { /* as is */ }
        else if (p[2].length == 2) y += if (y >= 69) 1900 else 2000
        else return null
        if (m !in 1..12 || d !in 1..31) return null
        return "%04d-%02d-%02d".format(y, m, d)
    }

    fun parseResults(text: String, season: String): List<Match> {
        val out = ArrayList<Match>()
        for (r in Csv.parse(text)) {
            val date = isoDate((r["Date"] ?: "").trim())
            val fthg = int(r["FTHG"]); val ftag = int(r["FTAG"])
            if (date == null || fthg == null || ftag == null) continue
            out.add(Match(date, (r["Time"] ?: "").trim(), (r["HomeTeam"] ?: "").trim(), (r["AwayTeam"] ?: "").trim(), fthg, ftag,
                (r["Referee"] ?: "").trim(), dbl(r["HxG"]), dbl(r["AxG"]), int(r["HS"]), int(r["AS"]), int(r["HST"]), int(r["AST"]),
                int(r["HF"]), int(r["AF"]), int(r["HY"]), int(r["AY"]), int(r["HR"]), int(r["AR"]), season))
        }
        return out
    }

    private fun odds(row: Map<String, String>, vararg sets: List<String>): DoubleArray? {
        for (cols in sets) {
            val vals = cols.map { dbl(row[it]) }
            if (vals.all { it != null && it > 1.0 }) return DoubleArray(vals.size) { vals[it]!! }
        }
        return null
    }

    fun parseLookahead(text: String): List<Lookahead> {
        val out = ArrayList<Lookahead>()
        for (r in Csv.parse(text)) {
            val date = isoDate((r["Date"] ?: "").trim())
            if (date == null || (r["HomeTeam"] ?: "").isEmpty()) continue
            out.add(Lookahead(r["Div"] ?: "", date, (r["Time"] ?: "").trim(), r["HomeTeam"]!!.trim(), (r["AwayTeam"] ?: "").trim(),
                (r["Referee"] ?: "").trim(),
                odds(r, listOf("AvgH", "AvgD", "AvgA"), listOf("B365H", "B365D", "B365A")),
                odds(r, listOf("Avg>2.5", "Avg<2.5"), listOf("B365>2.5", "B365<2.5"))))
        }
        return out
    }

    fun parseFixtures(js: Any?): List<Fixture> {
        val out = ArrayList<Fixture>()
        for (m in js.obj()["matches"].arr()) {
            val o = m.obj()
            val refs = o["referees"].arr().map { it.obj() }.filter { it["type"].str() == "REFEREE" }.map { it["name"].str() }
            out.add(Fixture(o["utcDate"].str(), o["homeTeam"].obj()["name"].str(), o["awayTeam"].obj()["name"].str(),
                if (o["matchday"] == null) null else o["matchday"].int(), refs.firstOrNull() ?: "", null, null))
        }
        out.sortBy { it.utc }
        return out
    }

    fun parseScoreboard(js: Any?): List<Scoreboard> {
        val out = ArrayList<Scoreboard>()
        for (e in js.obj()["events"].arr()) {
            val ev = e.obj()
            val comp = ev["competitions"].arr().firstOrNull().obj()
            val sides = HashMap<String, String>()
            for (c in comp["competitors"].arr()) sides[c.obj()["homeAway"].str()] = c.obj()["team"].obj()["displayName"].str()
            if (!sides.containsKey("home") || !sides.containsKey("away")) continue
            val completed = ev["status"].obj()["type"].obj()["completed"].bool()
            out.add(Scoreboard(ev["id"].str(), ev["date"].str(), sides.getValue("home"), sides.getValue("away"), completed))
        }
        return out
    }

    private fun stat(stats: Map<String, Any?>, name: String): Int {
        val v = stats[name] ?: return 0
        val d = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null } ?: return 0
        return Math.rint(d).toInt()
    }

    fun parseSummary(js: Any?, eventId: String): Summary {
        val root = js.obj()
        val officials = ArrayList<String>()
        for (o in root["gameInfo"].obj()["officials"].arr()) {
            val ob = o.obj()
            val pos = ob["position"].obj()
            val p = (pos["name"].str().ifEmpty { pos["displayName"].str() }).lowercase()
            val name = ob["fullName"].str().ifEmpty { ob["displayName"].str() }
            if (name.isNotEmpty() && "referee" in p && "assistant" !in p && "fourth" !in p && "video" !in p) officials.add(name)
        }
        var home: SideLine? = null; var away: SideLine? = null
        for (t in root["rosters"].arr()) {
            val tm = t.obj()
            val side = tm["homeAway"].str()
            val players = ArrayList<PlayerLine>()
            for (p in tm["roster"].arr()) {
                val po = p.obj()
                val stats = HashMap<String, Any?>()
                for (s in po["stats"].arr()) stats[s.obj()["name"].str()] = s.obj()["value"]
                val ath = po["athlete"].obj()
                val posv = po["position"]
                val pos = if (posv is Map<*, *>) posv["abbreviation"].str() else posv.str()
                val starter = po["starter"].bool(); val subIn = po["subbedIn"].bool()
                val apps = stat(stats, "appearances")
                val played = starter || subIn || apps >= 1
                players.add(PlayerLine(
                    ath["id"].str(), ath["displayName"].str().ifEmpty { ath["fullName"].str().ifEmpty { "?" } }, pos, starter, subIn, played,
                    stat(stats, "foulsCommitted"), stat(stats, "foulsSuffered"), stat(stats, "totalShots"), stat(stats, "shotsOnTarget"),
                    stat(stats, "yellowCards"), stat(stats, "redCards")))
            }
            val sl = SideLine(tm["team"].obj()["displayName"].str(), tm["formation"].str(), players)
            if (side == "home") home = sl else if (side == "away") away = sl
        }
        return Summary(eventId, officials, home, away, "")
    }

    // ---- downloads (these touch the internet)
    class NetFail(msg: String) : IOException(msg)

    private fun <T> guard(f: () -> T): T = try { f() } catch (e: IOException) { throw NetFail(e.message ?: "connection problem") }

    fun fetchResults(lk: String, season: String): List<Match> = guard {
        parseResults(Net.text("$FD_CO_UK/mmz4281/$season/${Leagues.ALL.getValue(lk).csv}.csv"), season)
    }

    fun fetchLookahead(): List<Lookahead> = guard { parseLookahead(Net.text("$FD_CO_UK/fixtures.csv")) }

    fun fetchFixtures(lk: String, token: String): List<Fixture> = guard {
        parseFixtures(Net.json("$FDO/competitions/${Leagues.ALL.getValue(lk).fdo}/matches?status=SCHEDULED,TIMED", mapOf("X-Auth-Token" to token)))
    }

    fun espnScoreboard(lk: String, yyyymmdd: String): List<Scoreboard> = guard {
        parseScoreboard(Net.json("$ESPN/${Leagues.ALL.getValue(lk).espn}/scoreboard?dates=$yyyymmdd"))
    }

    fun espnSummary(lk: String, eventId: String): Summary = guard {
        parseSummary(Net.json("$ESPN/${Leagues.ALL.getValue(lk).espn}/summary?event=$eventId"), eventId)
    }
}
