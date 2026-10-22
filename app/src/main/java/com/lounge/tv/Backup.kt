package com.lounge.tv

import android.content.Context

/** Copy of the things worth keeping if the app is ever reinstalled. Never includes logins or keys. */
object Backup {
    private val FILES = listOf("cat_cfg", "f_picks", "f_watchlist", "history")
    private val PREFS_STR = listOf("last_cat", "last_chan")
    private val PREFS_BOOL = listOf("preview", "sp_pause_video")

    fun export(c: Context): String {
        val files = LinkedHashMap<String, Any?>()
        for (f in FILES) Store.loadJson(c, f)?.let { files[f] = it }
        val prefs = LinkedHashMap<String, Any?>()
        for (k in PREFS_STR) Store.str(c, k).takeIf { it.isNotEmpty() }?.let { prefs[k] = it }
        for (k in PREFS_BOOL) prefs[k] = Store.bool(c, k, true)
        return Json.write(linkedMapOf("lounge_backup" to 1, "files" to files, "prefs" to prefs))
    }

    fun restore(c: Context, text: String): String {
        val root = Json.parseOrNull(text.trim()).obj()
        if (root["lounge_backup"] == null) return "That does not look like a Lounge backup, so nothing was changed."
        var n = 0
        for ((k, v) in root["files"].obj()) if (k in FILES) { Store.saveJson(c, k, v); n++ }
        for ((k, v) in root["prefs"].obj()) {
            if (k in PREFS_STR) { Store.putStr(c, k, v.str()); n++ }
            if (k in PREFS_BOOL) { Store.putBool(c, k, v.bool()); n++ }
        }
        return "Restored $n items."
    }
}
