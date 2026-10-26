package com.lounge.tv

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Downloads everything and saves it. Called by "Refresh now". The download calls can be swapped for tests. */
open class Downloads {
    open fun results(lk: String, season: String) = Sources.fetchResults(lk, season)
    open fun lookahead() = Sources.fetchLookahead()
    open fun fixtures(lk: String, token: String) = Sources.fetchFixtures(lk, token)
    open fun scoreboard(lk: String, d: String) = Sources.espnScoreboard(lk, d)
    open fun summary(lk: String, id: String) = Sources.espnSummary(lk, id)
}

object Refresh {
    const val UPCOMING_DAYS = 7

    fun fixtureKey(lk: String, fx: Fixture) = fx.key(lk)

    private fun canonSides(s: Summary, tn: TeamNames): Summary {
        fun fix(sl: SideLine?) = sl?.let { SideLine(tn.canon(it.team) ?: it.team, it.formation, it.players) }
        return Summary(s.event, s.officials, fix(s.homeSide), fix(s.awaySide), s.fetched)
    }

    private fun teamsOf(ms: List<Match>): Set<String> { val s = HashSet<String>(); ms.forEach { s.add(it.home); s.add(it.away) }; return s }

    class Got(var summary: Summary?, var odds: DoubleArray?, var o25: DoubleArray?, var ref: String, val warnings: MutableList<String>)

    /** Quick look at ONE upcoming match: team sheets, referee and odds. */
    fun fetchMatch(lk: String, fx: Fixture, state: FootState, dl: Downloads = Downloads()): Got {
        val out = Got(null, null, null, "", ArrayList())
        val tn = TeamNames(teamsOf(state.results[lk] ?: emptyList()))
        try {
            for (r in dl.lookahead()) if (r.home == fx.home && r.away == fx.away) { out.odds = r.odds; out.o25 = r.o25; out.ref = r.ref }
        } catch (e: Sources.NetFail) { out.warnings.add("Odds and referee: ${e.message}") }
        val day = fx.utc.substring(0, 10)
        try {
            val board = dl.scoreboard(lk, day.replace("-", ""))
            val ev = board.firstOrNull { tn.canon(it.home) == fx.home && tn.canon(it.away) == fx.away }
            if (ev != null) {
                val s = canonSides(dl.summary(lk, ev.id), tn)
                s.fetched = Utc.iso(System.currentTimeMillis() / 1000)
                out.summary = s
            } else out.warnings.add("ESPN has no listing for this match yet.")
        } catch (e: Sources.NetFail) { out.warnings.add("Lineups: ${e.message}") }
        return out
    }

    fun applyMatch(state: FootState, lk: String, fx: Fixture, got: Got): String {
        val bits = LinkedHashSet<String>()
        val stored = state.fixtures[lk]?.firstOrNull { it.same(fx) }
        if (stored != null) {
            if (got.odds != null) { stored.odds = got.odds; stored.o25 = got.o25; bits.add("odds") }
            if (got.ref.isNotEmpty() && stored.ref.isEmpty()) { stored.ref = got.ref; bits.add("referee") }
        }
        val s = got.summary
        if (s != null) {
            state.upcoming[fixtureKey(lk, fx)] = s
            val n = listOf("home", "away").count { sd -> (s.side(sd)?.players?.count { it.starter } ?: 0) == 11 }
            bits.add(if (n == 2) "lineups confirmed" else if (n == 1) "one lineup confirmed" else "lineups not out yet")
            if (s.officials.isNotEmpty() && stored != null && stored.ref.isEmpty()) { stored.ref = s.officials[0]; bits.add("referee") }
        }
        state.saveFixtures(); state.saveUpcoming()
        if (stored != null) logOdds(state, lk, listOf(stored), System.currentTimeMillis() / 1000)
        return if (bits.isEmpty()) "nothing new" else bits.joinToString(", ")
    }

    /** Keeps a small history of each match's bookmaker odds (one entry each time they change). Nothing uses it yet:
     *  it is saved so odds movement can be tested as a signal after some weeks. Never allowed to break a refresh. */
    @Suppress("UNCHECKED_CAST")
    fun logOdds(state: FootState, lk: String, fixtures: List<Fixture>, nowSec: Long) {
        try {
            val hist = LinkedHashMap<String, MutableList<Any?>>()
            for ((k, v) in state.io.load("f_odds_hist").obj()) hist[k] = v.arr().toMutableList()
            var changed = false
            for (f in fixtures) {
                val odds = f.odds ?: continue
                val list = hist.getOrPut(f.key(lk)) { ArrayList<Any?>() }
                val last = list.lastOrNull() as? Map<String, Any?>
                val o = odds.toList()
                val o25 = f.o25?.toList()
                if (last == null || last["odds"] != o || last["o25"] != o25) {
                    list.add(mapOf("t" to Utc.iso(nowSec), "odds" to o, "o25" to o25))
                    changed = true
                }
            }
            val cutoff = nowSec - 150L * 86400L
            val old = hist.keys.filter { (Utc.parse(it.split("|").getOrNull(1)) ?: Long.MAX_VALUE) < cutoff }
            for (k in old) { hist.remove(k); changed = true }
            if (changed) state.io.save("f_odds_hist", hist)
        } catch (e: Exception) {
        }
    }

    fun today(): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun dayNum(iso: String): Long? = Utc.parse(iso.take(10) + "T00:00:00Z")?.let { it / 86400 }

    private fun hasMatch(rows: List<Match>, home: String, away: String, day: String): Boolean {
        val d = dayNum(day) ?: return false
        return rows.any { it.home == home && it.away == away && (dayNum(it.date)?.let { x -> Math.abs(x - d) <= 1 } ?: false) }
    }

    /**
     * football-data.co.uk is often a day or two behind. Fill the gap with finished scores from ESPN (full time only,
     * no xG, shots or cards), same as the PC app. When the site catches up, its fuller rows replace these.
     * Never allowed to break a refresh.
     */
    private fun quickResults(lk: String, rows: List<Match>, season: String, dl: Downloads, now: Long): List<Match> {
        try {
            val today = Utc.day(now)
            val last = rows.maxOfOrNull { it.date }
            val floor = Utc.day(now - 14 * 86400L)
            val start = if (last != null && last > floor) last else floor
            val tn = TeamNames(teamsOf(rows))
            val added = ArrayList<Match>()
            var dn = dayNum(start) ?: return emptyList()
            val endN = dayNum(today) ?: return emptyList()
            while (dn <= endN) {
                val d = Utc.day(dn * 86400L)
                val board = try { dl.scoreboard(lk, d.replace("-", "")) } catch (e: Sources.NetFail) { emptyList() }
                for (ev in board) {
                    val hg = ev.hg; val ag = ev.ag
                    if (!ev.completed || hg == null || ag == null) continue
                    val h = tn.canon(ev.home) ?: continue
                    val a = tn.canon(ev.away) ?: continue
                    val day = if (ev.utc.length >= 10) ev.utc.substring(0, 10) else d
                    if (hasMatch(rows, h, a, day) || hasMatch(added, h, a, day)) continue
                    val time = if (ev.utc.length >= 16) ev.utc.substring(11, 16) else ""
                    added.add(Match(day, time, h, a, hg, ag, "", null, null, null, null, null, null, null, null, null, null, null, null, season))
                }
                dn++
            }
            return added
        } catch (e: Throwable) { return emptyList() }
    }

    /** Runs a full refresh. Returns warnings (plain sentences). */
    fun refresh(state: FootState, token: String, progress: (String, Double) -> Unit = { _, _ -> },
                dl: Downloads = Downloads(), now: Long = System.currentTimeMillis() / 1000, todayYmd: Triple<Int, Int, Int> = today()): List<String> {
        val warnings = ArrayList<String>()
        val season = Leagues.seasonCode(todayYmd.first, todayYmd.second)
        val prevSeason = Leagues.seasonCode(todayYmd.first, todayYmd.second, 1)
        val meta = state.meta
        @Suppress("UNCHECKED_CAST")
        val sources = (meta["sources"] as? MutableMap<String, Any?>) ?: HashMap<String, Any?>().also { meta["sources"] = it }
        val nowIso = Utc.iso(now)

        // 1. results
        @Suppress("UNCHECKED_CAST")
        val prevDone = (meta["prev_season"] as? MutableMap<String, Any?>) ?: HashMap<String, Any?>().also { meta["prev_season"] = it }
        for ((i, lk) in Leagues.ORDER.withIndex()) {
            progress("Downloading ${Leagues.ALL.getValue(lk).name} results", 0.02 + 0.04 * i)
            var gotCurrent = false
            try { state.results[lk] = dl.results(lk, season); gotCurrent = true }
            catch (e: Sources.NetFail) { warnings.add("Results for ${Leagues.ALL.getValue(lk).name}: ${e.message}") }
            // the results site is often a day or two behind, so add finished scores from ESPN (same as the PC app)
            val cur = state.results[lk] ?: emptyList()
            val quick = quickResults(lk, cur, season, dl, now)
            if (quick.isNotEmpty()) state.results[lk] = cur + quick
            if (!state.prev.containsKey(lk) || (gotCurrent && prevDone[lk] != prevSeason)) {
                try { state.prev[lk] = dl.results(lk, prevSeason); prevDone[lk] = prevSeason } catch (e: Sources.NetFail) { }
            }
        }
        state.saveResults(); state.savePrev()
        sources["results"] = nowIso

        val tns = Leagues.ORDER.associateWith { TeamNames(teamsOf(state.results[it] ?: emptyList())) }

        // 2. fixtures
        var laRows: List<Sources.Lookahead> = emptyList()
        try { laRows = dl.lookahead() } catch (e: Sources.NetFail) { warnings.add("Referee look-ahead: ${e.message}") }
        for ((i, lk) in Leagues.ORDER.withIndex()) {
            progress("Downloading ${Leagues.ALL.getValue(lk).name} fixtures", 0.12 + 0.04 * i)
            var fx: List<Fixture> = emptyList()
            if (token.isNotEmpty()) {
                try { fx = dl.fixtures(lk, token); sources["fixtures"] = nowIso }
                catch (e: Sources.NetFail) {
                    warnings.add("Fixtures for ${Leagues.ALL.getValue(lk).name}: ${e.message} (check your football-data.org key)")
                }
            } else {
                val div = if (lk == "PL") "E0" else "E1"
                fx = laRows.filter { it.div == div }.map {
                    Fixture("${it.date}T${it.time.ifEmpty { "15:00" }}:00Z", it.home, it.away, null, it.ref, null, null)
                }
                warnings.add("No football-data.org key set, so only the next few days of fixtures are shown.")
            }
            val tn = tns.getValue(lk)
            val out = ArrayList<Fixture>()
            for (f in fx) {
                val h = tn.canon(f.home); val a = tn.canon(f.away)
                if (h == null || a == null) continue
                out.add(Fixture(f.utc, h, a, f.matchday, f.ref, f.odds, f.o25))
            }
            for (f in out) for (r in laRows) if (r.home == f.home && r.away == f.away) {
                if (f.ref.isEmpty() && r.ref.isNotEmpty()) f.ref = r.ref
                if (r.odds != null) { f.odds = r.odds; f.o25 = r.o25 }
            }
            if (out.isNotEmpty() || token.isNotEmpty()) state.fixtures[lk] = out
        }
        state.saveFixtures()
        for (lk in Leagues.ORDER) logOdds(state, lk, state.fixtures[lk] ?: emptyList(), now)

        // 3. ESPN player lines for every played match
        @Suppress("UNCHECKED_CAST")
        val done = LinkedHashSet<String>((meta["espn_dates"] as? List<Any?>)?.map { it.str() } ?: emptyList())
        class Job(val lk: String, val d: String, val tag: String)
        val jobs = ArrayList<Job>()
        for (lk in Leagues.ORDER) {
            val dates = (state.results[lk] ?: emptyList()).map { it.date }.toSortedSet()
            for (d in dates) { val tag = "$lk|$d"; if (tag !in done) jobs.add(Job(lk, d, tag)) }
        }
        val knownEvents = HashSet(state.events.keys)
        progress("Reading player stats (${jobs.size} match days to check)", 0.2)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val boards = pool.invokeAll(jobs.map { j ->
                Callable<Pair<Job, Any>> {
                    try { j to (dl.scoreboard(j.lk, j.d.replace("-", "")) as Any) } catch (e: Sources.NetFail) { j to (e as Any) }
                }
            }).map { it.get() }
            class Item(val lk: String, val d: String, val ev: Sources.Scoreboard)
            val toFetch = ArrayList<Item>()
            for ((job, res) in boards) {
                if (res is Sources.NetFail) { warnings.add("Player stats ${job.lk} ${job.d}: ${res.message}"); continue }
                var ok = true
                @Suppress("UNCHECKED_CAST")
                for (ev in res as List<Sources.Scoreboard>) {
                    if (ev.completed) { if (ev.id !in knownEvents) toFetch.add(Item(job.lk, job.d, ev)) } else ok = false
                }
                if (ok) done.add(job.tag)
            }
            val sums = pool.invokeAll(toFetch.map { it ->
                Callable<Pair<Item, Any>> {
                    try { it to (dl.summary(it.lk, it.ev.id) as Any) } catch (e: Sources.NetFail) { it to (e as Any) }
                }
            }).map { it.get() }
            var fetched = 0
            for ((n, pr) in sums.withIndex()) {
                val (item, res) = pr
                progress("Reading player stats (${n + 1} of ${toFetch.size} matches)", 0.3 + 0.55 * (n + 1) / maxOf(toFetch.size, 1))
                if (res is Sources.NetFail) {
                    warnings.add("Player stats for ${item.ev.home} v ${item.ev.away}: ${res.message}")
                    done.remove("${item.lk}|${item.d}")
                    continue
                }
                val s = canonSides(res as Summary, tns.getValue(item.lk))
                state.events[item.ev.id] = Event(item.lk, item.d, s.homeSide?.team ?: "", s.awaySide?.team ?: "", s.homeSide, s.awaySide)
                fetched++
            }
            meta["new_matches"] = fetched
        } finally { pool.shutdown() }
        meta["espn_dates"] = done.toList().sorted()
        state.saveEvents()
        sources["players"] = nowIso

        // 4. upcoming matches: lineups and officials
        progress("Checking lineups for upcoming matches", 0.9)
        val todayS = Utc.day(now)
        val horizon = Utc.day(now + UPCOMING_DAYS * 86400L)
        val boardsCache = HashMap<String, List<Sources.Scoreboard>>()
        val newUpcoming = HashMap<String, Summary>()
        for (lk in Leagues.ORDER) for (fx in state.fixtures[lk] ?: emptyList<Fixture>()) {
            val day = fx.utc.substring(0, 10)
            if (day < todayS || day > horizon) continue
            val ck = "$lk|$day"
            if (!boardsCache.containsKey(ck)) {
                boardsCache[ck] = try { dl.scoreboard(lk, day.replace("-", "")) }
                catch (e: Sources.NetFail) { warnings.add("Lineups $lk $day: ${e.message}"); emptyList() }
            }
            val tn = tns.getValue(lk)
            val ev = boardsCache.getValue(ck).firstOrNull { tn.canon(it.home) == fx.home && tn.canon(it.away) == fx.away } ?: continue
            val s = try { canonSides(dl.summary(lk, ev.id), tn) }
            catch (e: Sources.NetFail) { warnings.add("Lineup for ${fx.home} v ${fx.away}: ${e.message}"); continue }
            s.fetched = nowIso
            newUpcoming[fixtureKey(lk, fx)] = s
            if (s.officials.isNotEmpty() && fx.ref.isEmpty()) fx.ref = s.officials[0]
        }
        state.upcoming = newUpcoming
        state.saveUpcoming(); state.saveFixtures()

        meta["last_updated"] = nowIso
        meta["warnings"] = warnings.toList()
        state.saveMeta()
        progress("Done", 1.0)
        return warnings
    }
}
