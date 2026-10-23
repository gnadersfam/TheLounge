package com.lounge.tv

import android.content.Context

/**
 * Real club badges. ESPN publishes one picture per club; the list (club name -> picture address) is downloaded
 * once a week, kept on the Firestick, and looked up by name. If a club is not found the badge stays as initials.
 */
object TeamLogos {
    private var map: Map<String, String> = emptyMap()
    private var names: TeamNames? = null
    private var started = false
    private const val WEEK = 7 * 86400L

    fun ensure(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        Bg.run {
            try {
                val o = Store.loadJson(app, "team_logos").obj()
                val m = HashMap<String, String>()
                for ((k, v) in o["m"].obj()) m[k] = v.str()
                if (m.isNotEmpty()) install(m)
                val now = System.currentTimeMillis() / 1000
                if (m.isEmpty() || now - o["at"].long() > WEEK) {
                    val fresh = download()
                    if (fresh.isNotEmpty()) {
                        Store.saveJson(app, "team_logos", mapOf("at" to now, "m" to fresh))
                        install(fresh)
                    }
                }
            } catch (e: Throwable) {
                Crash.log("Team badges", e)
            }
        }
    }

    @Synchronized
    private fun install(m: Map<String, String>) {
        map = m
        names = TeamNames(m.keys)
    }

    private fun download(): Map<String, String> {
        val out = HashMap<String, String>()
        for (lk in Leagues.ORDER) {
            try {
                val root = Net.json(Sources.ESPN + "/" + Leagues.ALL.getValue(lk).espn + "/teams").obj()
                val teams = root["sports"].arr().firstOrNull().obj()["leagues"].arr().firstOrNull().obj()["teams"].arr()
                for (t in teams) {
                    val tm = t.obj()["team"].obj()
                    val name = tm["displayName"].str()
                    val logo = tm["logos"].arr().firstOrNull().obj()["href"].str()
                    if (name.isNotEmpty() && logo.startsWith("http")) out[name] = logo
                }
            } catch (e: Throwable) {
                Crash.log("Team badges download $lk", e)
            }
        }
        return out
    }

    @Synchronized
    fun url(team: String): String {
        val m = map
        if (m.isEmpty()) return ""
        m[team]?.let { return it }
        val canon = names?.canon(team) ?: return ""
        return m[canon] ?: ""
    }

    fun faceUrl(playerId: String): String =
        if (playerId.isBlank() || !playerId.all { it.isDigit() }) "" else "https://a.espncdn.com/i/headshots/soccer/players/full/$playerId.png"
}
