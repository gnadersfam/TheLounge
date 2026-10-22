package com.lounge.tv

import java.util.Calendar

/** One live channel that is (or will be) showing a match. */
class TvHit(val chan: Chan, val region: String, val title: String, val start: Long, val off: Double)

/**
 * Finds the live channel for a match from the TV guide the Live TV tab already downloaded (no second download).
 * Ported from the PC app's TvIndex: a guide entry counts when it names both clubs, starts near kick-off, has a sensible
 * length and is not a highlights / replay / women's / other-sport show.
 */
class TvFinder(channels: List<Chan>, epg: EpgData, teams: TeamNames) {
    private class Entry(val s: Long, val e: Long, val epgId: String, val title: String)

    private val byEpg = HashMap<String, MutableList<Chan>>()
    private val byPair = HashMap<String, MutableList<Entry>>()

    init {
        for (c in channels) if (c.epg.isNotEmpty()) byEpg.getOrPut(c.epg.lowercase()) { ArrayList() }.add(c)
        for ((id, list) in epg.progs) {
            if (!byEpg.containsKey(id.lowercase())) continue
            for (p in list) {
                if (!maybeMatch(p.title) && !maybeMatch(p.desc)) continue
                val t = findTeams(teams, p.title) ?: findTeams(teams, p.desc) ?: continue
                byPair.getOrPut(pairKey(t.first, t.second)) { ArrayList() }.add(Entry(p.start, p.stop, id.lowercase(), p.title))
            }
        }
    }

    val hasEntries get() = byPair.isNotEmpty()

    fun find(home: String, away: String, kickoff: Long): List<TvHit> {
        val out = ArrayList<TvHit>(); val seen = HashSet<String>()
        for (p in byPair[pairKey(home, away)] ?: emptyList<Entry>()) {
            val delta = (p.s - kickoff) / 60.0
            val len = (p.e - p.s) / 60.0
            if (!(delta >= -START_BEFORE && delta <= START_AFTER && len >= MIN_LEN && len <= MAX_LEN)) continue
            if (NOT_LIVE.containsMatchIn(p.title)) continue
            for (c in byEpg[p.epgId] ?: emptyList<Chan>()) {
                if (!seen.add(c.id)) continue
                out.add(TvHit(c, regionOf(c.name), p.title, p.s, Math.abs(delta)))
            }
        }
        out.sortWith(compareBy<TvHit>({ it.region != "UK" }, { -qualityOf(it.chan.name) }, { it.off }, { it.chan.name }))
        return out
    }

    companion object {
        const val START_BEFORE = 90
        const val START_AFTER = 45
        const val MIN_LEN = 80
        const val MAX_LEN = 330
        private val IC = RegexOption.IGNORE_CASE
        private val NOT_LIVE = Regex(
            "highlights?|\\bHL\\b|\\bXXL\\b|review|replay|repeat|re-?run|rewind|classic|magazine|best of|reaction|analysis|" +
                "round-?up|preview|women|ladies|\\bwsl\\b|\\bu-?(?:18|19|21|23)\\b|youth|academy|reserves|" +
                "rugby|cricket|super league|\\bnrl\\b|hockey|netball|baseball|\\bnba\\b|\\bnfl\\b", IC)
        private val VS = Regex("\\s+(?:v|vs\\.?|versus)\\s+", IC)
        private val DASH = Regex("\\s+[-–—]\\s+")
        private val NOISE = Regex("[\\(\\[].*?[\\)\\]]")
        private val SPACES = Regex("\\s+")
        private val ALIASES = linkedMapOf(
            "spurs" to "Tottenham", "man utd" to "Man United", "man u" to "Man United",
            "forest" to "Nott'm Forest", "wolverhampton" to "Wolves", "wednesday" to "Sheffield Weds",
            "sheff utd" to "Sheffield United", "sheff wed" to "Sheffield Weds",
            "palace" to "Crystal Palace", "villa" to "Aston Villa", "newcastle utd" to "Newcastle"
        )
        private val ALIAS_RE = ALIASES.keys.associateWith { Regex("\\b" + Regex.escape(it) + "\\b") }

        fun pairKey(a: String, b: String) = if (a < b) "$a\u0001$b" else "$b\u0001$a"

        private fun maybeMatch(s: String) = s.length > 4 && (VS.containsMatchIn(s) || DASH.containsMatchIn(s))

        private fun strip(s: String) = SPACES.replace(NOISE.replace(s, " "), " ").trim()

        private fun club(teams: TeamNames, text: String): String? {
            val t = strip(text)
            val low = t.lowercase()
            for ((alias, canon) in ALIASES) if (ALIAS_RE.getValue(alias).containsMatchIn(low)) return canon
            return teams.canon(t)
        }

        fun findTeams(teams: TeamNames, text: String): Pair<String, String>? {
            if (text.isEmpty()) return null
            val title = strip(text)
            var parts = VS.split(title, 2)
            if (parts.size != 2) {
                val d = DASH.split(title)
                if (d.size < 2) return null
                parts = listOf(d[d.size - 2], d[d.size - 1])
            }
            val left = parts[0].split(Regex("[:|]")).last()
            val right = parts[1].split(Regex("[:|]")).first()
            val a = club(teams, left); val b = club(teams, right)
            return if (a != null && b != null && a != b) a to b else null
        }

        fun regionOf(name: String): String {
            val m = Regex("[\\[\\(\\{]([A-Za-z]{2,6})[\\]\\)\\}]\\s*$").find(name) ?: Regex("^\\s*([A-Za-z]{2,4})\\s*[|:]").find(name)
            val r = m?.groupValues?.get(1)?.uppercase() ?: ""
            if (r == "GB" || Regex("\\b(?:UK|GB)\\b").containsMatchIn(name)) return "UK"
            return r
        }

        fun qualityOf(name: String): Int {
            val n = name.uppercase()
            return when {
                "4K" in n || "UHD" in n -> 3
                "FHD" in n -> 3
                "HD" in n || "HEVC" in n -> 2
                "RAW" in n || " SD" in n -> 0
                else -> 1
            }
        }

        /** True for the Saturday 3pm slot, which UK broadcasters are not allowed to show. */
        fun isUkBlackout(kickoffSec: Long): Boolean {
            val c = Calendar.getInstance(); c.timeInMillis = kickoffSec * 1000
            val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            return c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY && mins >= 14 * 60 + 45 && mins <= 17 * 60 + 15
        }
    }
}
