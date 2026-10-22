package com.lounge.tv

import android.content.Context
import java.io.File

/** Shared state between screens (kept in memory only). */
object Session {
    var xt: Xtream? = null
    var channels: List<Chan> = emptyList()
    var index: Int = 0
    var epg: EpgData = EpgData.EMPTY

    /** What the player should open next when it is not a live channel: title + address + where to resume. */
    var vodTitle: String = ""
    var vodUrl: String = ""
    var vodKey: String = ""
    var vodResume: Long = 0L
    var live: Boolean = true

    /** Short match summary shown on top of the picture when a match is opened from the football pages. */
    var matchPanel: String = ""
}

/**
 * Everything saved on the Firestick. Logins and keys live ONLY here (private to this app), never in the code.
 * Small settings are in a private preferences file; bigger lists are JSON files in the app's private folder.
 */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("lounge", Context.MODE_PRIVATE)

    fun str(c: Context, key: String, def: String = ""): String = prefs(c).getString(key, def) ?: def
    fun putStr(c: Context, key: String, v: String) = prefs(c).edit().putString(key, v).apply()
    fun long(c: Context, key: String, def: Long = 0L): Long = prefs(c).getLong(key, def)
    fun putLong(c: Context, key: String, v: Long) = prefs(c).edit().putLong(key, v).apply()
    fun bool(c: Context, key: String, def: Boolean = false): Boolean = prefs(c).getBoolean(key, def)
    fun putBool(c: Context, key: String, v: Boolean) = prefs(c).edit().putBoolean(key, v).apply()
    fun remove(c: Context, key: String) = prefs(c).edit().remove(key).apply()

    // ---------------------------------------------------------------- logins and keys (set by the phone pairing page)
    fun server(c: Context) = str(c, "server")
    fun user(c: Context) = str(c, "user")
    fun pass(c: Context) = str(c, "pass")
    fun hasLogin(c: Context) = server(c).isNotBlank() && user(c).isNotBlank() && pass(c).isNotBlank()

    fun saveLogin(c: Context, server: String, user: String, pass: String) {
        prefs(c).edit().putString("server", server.trim()).putString("user", user.trim())
            .putString("pass", pass.trim()).apply()
        // a new login means the saved lists belong to someone else
        for (n in listOf("channels", "vod_movies", "vod_series", "live_epg")) deleteJson(c, n)
        Session.xt = null
    }

    fun xtream(c: Context): Xtream? =
        if (hasLogin(c)) Xtream(Xtream.clean(server(c)), user(c).trim(), pass(c).trim()) else null

    fun fdoKey(c: Context) = str(c, "fdo_key")
    fun apiFootballKey(c: Context) = str(c, "apifootball_key")
    fun spotifyClientId(c: Context) = str(c, "spotify_client_id")

    fun ext(c: Context) = str(c, "ext", "ts")
    fun setExt(c: Context, ext: String) = putStr(c, "ext", ext)

    // ---------------------------------------------------------------- JSON files
    private fun file(c: Context, name: String) = File(c.filesDir, "$name.json")

    fun loadJson(c: Context, name: String): Any? {
        val f = file(c, name)
        if (!f.exists()) return null
        return try {
            Json.parseOrNull(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun saveJson(c: Context, name: String, v: Any?) {
        try {
            val tmp = File(c.filesDir, "$name.json.tmp")
            tmp.writeText(Json.write(v))
            tmp.renameTo(file(c, name))
        } catch (e: Exception) {
            // saving is best effort; the app keeps working from memory
        }
    }

    fun deleteJson(c: Context, name: String) {
        file(c, name).delete()
    }

    fun ageSeconds(c: Context, name: String): Long {
        val f = file(c, name)
        return if (f.exists()) (System.currentTimeMillis() - f.lastModified()) / 1000 else Long.MAX_VALUE
    }

    // ---------------------------------------------------------------- live channel list cache
    fun loadChannels(c: Context): Pair<Long, List<Chan>>? {
        val o = loadJson(c, "channels").obj()
        val rows = o["rows"].arr()
        if (rows.isEmpty()) return null
        val list = ArrayList<Chan>(rows.size)
        for (r in rows) {
            val a = r.arr()
            if (a.size < 5) continue
            list.add(Chan(a[0].str(), a[1].str(), a[2].str(), a[3].str(), a[4].str(), a.getOrNull(5).str()))
        }
        return Pair(o["built"].long(), list)
    }

    fun saveChannels(c: Context, rows: List<Chan>) {
        val a = rows.map { listOf(it.id, it.name, it.cat, it.tag, it.epg, it.icon) }
        saveJson(c, "channels", mapOf("built" to System.currentTimeMillis() / 1000, "rows" to a))
    }
}
