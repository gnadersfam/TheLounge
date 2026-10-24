package com.lounge.tv

/**
 * Puts the big football channels first in the UK | SPORTS list:
 * every "Sky Sports Main Event" (all versions), then TNT Sports 1, 2, 3, 4 and 5 (each with its versions).
 * Channels it does not find are simply left where they are, and everything else keeps its normal order.
 */
object Pins {
    private val SKY_MAIN = Regex("""\bSKY\s*SPORTS?\s*MAIN\s*EVENT\b""")
    private val TNT = Regex("""\b(?:TNT|BT)\s*SPORTS?\s*([1-5])\b""")

    // the category is called "ENGLISH UK | SPORTS" (or "UK | SPORTS" on some lists); "UK | SPORTS HEVC" and the like are left alone
    private fun applies(key: String): Boolean = key.uppercase().replace(" ", "").endsWith("UK|SPORTS")

    private fun clean(name: String): String = name.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()

    /** 0 = Sky Sports Main Event, 1..5 = TNT Sports 1..5, 99 = no pin. */
    private fun rank(name: String): Int {
        val n = clean(name)
        if (SKY_MAIN.containsMatchIn(n)) return 0
        val m = TNT.find(n) ?: return 99
        return m.groupValues[1].toInt()
    }

    fun apply(key: String, list: List<Chan>): List<Chan> {
        if (!applies(key)) return list
        val ranks = list.map { rank(it.name) }
        if (ranks.all { it == 99 }) return list
        // sortedBy is stable, so channels with the same rank keep their order
        return list.withIndex().sortedBy { ranks[it.index] }.map { it.value }
    }
}
