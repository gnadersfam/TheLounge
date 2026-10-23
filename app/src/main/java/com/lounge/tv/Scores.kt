package com.lounge.tv

import android.content.Context
import android.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live scores for the Home page, read from ESPN's free feed (the same one the phone remote uses).
 * Each match also carries its goals and cards, with the player's name and the colour of his club.
 */
object Scores {
    /** kind: 0 = goal, 1 = yellow card, 2 = red card. */
    class Ev(val kind: Int, val minute: String, val who: String, val team: String, val color: Int)

    class Match(
        val lg: String,
        val home: String, val away: String,
        val hAbbr: String, val aAbbr: String,
        val hLogo: String, val aLogo: String,
        val hg: String, val ag: String,
        val state: String, val status: String, val whenSec: Long,
        val evs: List<Ev>,
        val hNames: List<String>, val aNames: List<String>
    )

    @Volatile private var cache: List<Match> = emptyList()
    @Volatile private var cacheAt = 0L

    /** True once a download has worked at least once (so "no matches" can be told apart from "still loading"). */
    @Volatile var loaded = false
        private set

    fun cached(): List<Match> = cache

    private fun order(state: String) = when (state) { "in" -> 0; "pre" -> 1; else -> 2 }

    /** Download the scores now. Call this from a background thread. */
    fun load(): List<Match> {
        val out = ArrayList<Match>()
        var failed = 0
        for ((code, name) in listOf("eng.1" to "Premier League", "eng.2" to "Championship")) {
            try {
                val js = Net.json("https://site.api.espn.com/apis/site/v2/sports/soccer/$code/scoreboard", timeoutMs = 8000)
                for (e in js.obj()["events"].arr()) {
                    val m = parse(e.obj(), name)
                    if (m != null) out.add(m)
                }
            } catch (e: Throwable) {
                failed++
            }
        }
        if (failed >= 2) return cache
        val sorted = out.sortedWith(compareBy<Match>({ order(it.state) }, { if (it.state == "post") -it.whenSec else it.whenSec }))
        cache = sorted
        cacheAt = System.currentTimeMillis()
        loaded = true
        return sorted
    }

    private fun hex(s: String): Int {
        if (s.length != 6) return 0
        return try { Color.parseColor("#$s") } catch (e: Exception) { 0 }
    }

    private fun lum(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun lighten(c: Int): Int {
        val r = Color.red(c) + ((255 - Color.red(c)) * 0.6).toInt()
        val g = Color.green(c) + ((255 - Color.green(c)) * 0.6).toInt()
        val b = Color.blue(c) + ((255 - Color.blue(c)) * 0.6).toInt()
        return Color.rgb(r, g, b)
    }

    /** The club's colour, made lighter when it would be hard to read on the dark background. */
    private fun teamColor(t: Map<String, Any?>): Int {
        val c = hex(t["color"].str())
        val a = hex(t["alternateColor"].str())
        if (c == 0 && a == 0) return 0xFFCFC6EE.toInt()
        if (c != 0 && lum(c) >= 0.3) return c
        if (a != 0 && lum(a) >= 0.3) return a
        return lighten(if (c != 0) c else a)
    }

    /** "Bukayo Saka" -> "B. Saka". */
    private fun shortName(full: String): String {
        val parts = full.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.size < 2) return full.trim()
        return parts[0].take(1) + ". " + parts.drop(1).joinToString(" ")
    }

    private fun kickoffText(whenSec: Long): String {
        val now = System.currentTimeMillis() / 1000
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.UK).format(Date(whenSec * 1000)) == SimpleDateFormat("yyyyMMdd", Locale.UK).format(Date(now * 1000))
        return if (sameDay) "KICK OFF " + Skin.hhmm(whenSec)
        else SimpleDateFormat("EEE HH:mm", Locale.UK).format(Date(whenSec * 1000)).uppercase(Locale.UK)
    }

    private fun parse(ev: Map<String, Any?>, lg: String): Match? {
        val comp = ev["competitions"].arr().firstOrNull().obj()
        var home: Map<String, Any?> = emptyMap()
        var away: Map<String, Any?> = emptyMap()
        for (cc in comp["competitors"].arr()) {
            val o = cc.obj()
            if (o["homeAway"].str() == "home") home = o else away = o
        }
        val ht = home["team"].obj()
        val at = away["team"].obj()
        if (ht.isEmpty() || at.isEmpty()) return null
        val st = ev["status"].obj()
        val type = st["type"].obj()
        val state = type["state"].str()
        val whenSec = Utc.parse(ev["date"].str()) ?: 0L
        val tname = type["name"].str()
        val status = when {
            tname == "STATUS_HALFTIME" -> "HALF TIME"
            tname == "STATUS_POSTPONED" -> "POSTPONED"
            tname == "STATUS_CANCELED" || tname == "STATUS_CANCELLED" -> "CANCELLED"
            state == "in" -> "LIVE  " + st["displayClock"].str()
            state == "post" -> "FULL TIME"
            else -> kickoffText(whenSec)
        }
        val hName = ht["shortDisplayName"].str().ifEmpty { ht["displayName"].str() }
        val aName = at["shortDisplayName"].str().ifEmpty { at["displayName"].str() }
        if (hName.isEmpty() || aName.isEmpty()) return null
        val hAbbr = ht["abbreviation"].str().ifEmpty { hName.take(3).uppercase() }
        val aAbbr = at["abbreviation"].str().ifEmpty { aName.take(3).uppercase() }

        val abbrById = HashMap<String, String>()
        val colorById = HashMap<String, Int>()
        abbrById[ht["id"].str()] = hAbbr
        abbrById[at["id"].str()] = aAbbr
        colorById[ht["id"].str()] = teamColor(ht)
        colorById[at["id"].str()] = teamColor(at)

        val evs = ArrayList<Ev>()
        for (d in comp["details"].arr()) {
            val o = d.obj()
            val kind = when {
                o["scoringPlay"].bool() -> 0
                o["redCard"].bool() -> 2
                o["yellowCard"].bool() -> 1
                else -> -1
            }
            if (kind < 0) continue
            val tid = o["team"].obj()["id"].str()
            val player = o["athletesInvolved"].arr().firstOrNull().obj()
            var who = shortName(player["displayName"].str().ifEmpty { player["shortName"].str() })
            if (who.isEmpty()) who = "Unknown"
            if (kind == 0 && o["ownGoal"].bool()) who += " (og)"
            if (kind == 0 && o["penaltyKick"].bool()) who += " (pen)"
            evs.add(Ev(kind, o["clock"].obj()["displayValue"].str(), who, abbrById[tid] ?: "", colorById[tid] ?: 0xFFCFC6EE.toInt()))
        }

        return Match(
            lg, hName, aName, hAbbr, aAbbr,
            ht["logo"].str(), at["logo"].str(),
            home["score"].str(), away["score"].str(),
            state, status, whenSec, evs,
            listOf(hName, ht["displayName"].str(), ht["name"].str()).filter { it.length > 2 },
            listOf(aName, at["displayName"].str(), at["name"].str()).filter { it.length > 2 }
        )
    }

    /** Live channels that are showing this match right now (UK ones and the best quality first). */
    fun channelsFor(c: Context, m: Match): List<Chan> {
        val chans = Store.loadChannels(c)?.second ?: return emptyList()
        val epg = if (Session.epg.built > 0) Session.epg else EpgData.fromJson(Store.loadJson(c, "live_epg"))
        if (epg.built == 0L) return emptyList()
        val now = System.currentTimeMillis() / 1000
        val hs = m.hNames.map { it.lowercase() }
        val aws = m.aNames.map { it.lowercase() }
        val hits = ArrayList<Chan>()
        for (ch in chans) {
            if (ch.epg.isEmpty()) continue
            val title = epg.nowNext(ch.epg, now, 1).first?.title?.lowercase() ?: continue
            if (hs.any { title.contains(it) } && aws.any { title.contains(it) }) hits.add(ch)
        }
        return hits.sortedWith(compareBy<Chan>({ TvFinder.regionOf(it.name) != "UK" }, { -TvFinder.qualityOf(it.name) }))
    }
}
