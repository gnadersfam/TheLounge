package com.lounge.tv

/** Yellow-card suspensions: who is banned for a coming match, and who is one booking away. (FA rules, league matches only.) */
class CardInfo(val id: String, var name: String, var team: String) {
    var yc = 0; var rc = 0
    class Ban(val n: Int, val ban: Int, val why: String)
    val bans = ArrayList<Ban>()
}
class CardAnalysis(val played: Map<String, Int>, val players: Map<String, CardInfo>)
class Flag(val code: String, val yc: Int, val text: String, val id: String, val name: String, val team: String)

object Discipline {
    private class Rule(val th: Int, val deadline: Int, val ban: Int)
    private val RULES = mapOf(
        "PL" to listOf(Rule(5, 19, 1), Rule(10, 32, 2), Rule(15, 38, 3)),
        "ELC" to listOf(Rule(5, 19, 1), Rule(10, 37, 2), Rule(15, 46, 3))
    )
    const val STRAIGHT_RED_BAN = 1
    const val SECOND_YELLOW_BAN = 1

    fun analyse(events: List<Event>, lk: String, upto: String? = null): CardAnalysis {
        val evs = events.filter { it.league == lk && (upto == null || it.date < upto) }.sortedBy { it.date }
        val played = LinkedHashMap<String, Int>()
        val pl = LinkedHashMap<String, CardInfo>()
        for (ev in evs) for (side in listOf("home", "away")) {
            val sd = ev.side(side) ?: continue
            val team = sd.team
            val n = (played[team] ?: 0) + 1
            played[team] = n
            for (p in sd.players) {
                if (!p.played) continue
                val e = pl.getOrPut(p.id) { CardInfo(p.id, p.name, team) }
                e.team = team; e.name = p.name
                if (p.yc != 0) {
                    val before = e.yc
                    e.yc += p.yc
                    for (r in RULES.getValue(lk)) if (before < r.th && r.th <= e.yc && n <= r.deadline)
                        e.bans.add(CardInfo.Ban(n, r.ban, "${r.th} yellow cards"))
                }
                if (p.rc != 0) {
                    e.rc += p.rc
                    if (p.yc != 0) e.bans.add(CardInfo.Ban(n, SECOND_YELLOW_BAN, "sent off (two yellows)"))
                    else e.bans.add(CardInfo.Ban(n, STRAIGHT_RED_BAN, "straight red card, at least 1 match"))
                }
            }
        }
        return CardAnalysis(played, pl)
    }

    fun status(info: CardInfo?, lk: String, team: String, nextIdx: Int): Flag? {
        if (info == null || info.team != team) return null
        for (b in info.bans) {
            if (b.n < nextIdx && nextIdx <= b.n + b.ban) {
                val left = b.n + b.ban - nextIdx + 1
                return Flag("out", info.yc, "Suspended (${b.why})" + (if (b.ban == 1) "" else ", $left match${if (left == 1) "" else "es"} left"),
                    info.id, info.name, team)
            }
        }
        for (r in RULES.getValue(lk)) {
            if (info.yc < r.th) {
                if (info.yc == r.th - 1 && nextIdx <= r.deadline)
                    return Flag("risk", info.yc, "${info.yc} yellow${if (info.yc == 1) "" else "s"}: one more means a ${r.ban}-match ban", info.id, info.name, team)
                break
            }
        }
        return null
    }

    fun teamReport(an: CardAnalysis, lk: String, team: String, ahead: Int = 1): List<Flag> {
        val nextIdx = (an.played[team] ?: 0) + maxOf(ahead, 1)
        val out = ArrayList<Flag>()
        for (info in an.players.values) status(info, lk, team, nextIdx)?.let { out.add(it) }
        out.sortWith(compareBy<Flag>({ it.code != "out" }, { -it.yc }, { it.name }))
        return out
    }
}
