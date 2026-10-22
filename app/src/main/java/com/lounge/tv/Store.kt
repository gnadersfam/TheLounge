package com.lounge.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shared state between the two screens. */
object Session {
    var xt: Xtream? = null
    var channels: List<Chan> = emptyList()
    var index: Int = 0
}

/** Saved login (private to this app on the Fire TV) and the saved channel list (no login details in it). */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("lounge", Context.MODE_PRIVATE)
    private fun cacheFile(c: Context) = File(c.filesDir, "channels.json")

    fun saveLogin(c: Context, server: String, user: String, pass: String) {
        prefs(c).edit().putString("server", server.trim()).putString("user", user.trim())
            .putString("pass", pass.trim()).apply()
        cacheFile(c).delete()
    }

    fun server(c: Context) = prefs(c).getString("server", "") ?: ""
    fun user(c: Context) = prefs(c).getString("user", "") ?: ""
    fun pass(c: Context) = prefs(c).getString("pass", "") ?: ""

    fun hasLogin(c: Context) = server(c).isNotBlank() && user(c).isNotBlank() && pass(c).isNotBlank()

    fun xtream(c: Context): Xtream? =
        if (hasLogin(c)) Xtream(Xtream.clean(server(c)), user(c).trim(), pass(c).trim()) else null

    fun ext(c: Context) = prefs(c).getString("ext", "ts") ?: "ts"
    fun setExt(c: Context, ext: String) = prefs(c).edit().putString("ext", ext).apply()

    fun loadChannels(c: Context): Pair<Long, List<Chan>>? {
        val f = cacheFile(c)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("rows")
            val list = ArrayList<Chan>(a.length())
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                list.add(Chan(r.getString(0), r.getString(1), r.getString(2), r.getString(3), r.getString(4)))
            }
            Pair(o.getLong("built"), list)
        } catch (e: Exception) {
            null
        }
    }

    fun saveChannels(c: Context, rows: List<Chan>) {
        val a = JSONArray()
        for (r in rows) a.put(JSONArray().put(r.id).put(r.name).put(r.cat).put(r.tag).put(r.epg))
        val o = JSONObject().put("built", System.currentTimeMillis() / 1000).put("rows", a)
        cacheFile(c).writeText(o.toString())
    }
}
