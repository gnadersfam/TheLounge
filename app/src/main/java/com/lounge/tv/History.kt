package com.lounge.tv

import android.content.Context

/** One thing you watched: a live channel, a movie or an episode. [pos]/[dur] are milliseconds (0 for live). */
data class Hist(
    val key: String,
    val kind: String,        // "live", "movie" or "episode"
    val title: String,
    val sub: String,
    val id: String,
    val ext: String,
    val pos: Long,
    val dur: Long,
    val ts: Long,
    val seriesId: String = ""
) {
    /** Share of the film or episode already watched, 0-100. */
    fun percent(): Int = if (dur <= 0) 0 else ((pos * 100) / dur).toInt().coerceIn(0, 100)

    /** True when it is worth offering "continue": started, not finished. */
    fun resumable(): Boolean = kind != "live" && pos > 30_000 && percent() < 95
}

/** Continue watching / Recent list, saved on the Firestick. */
object History {
    private const val FILE = "history"
    private const val LIMIT = 80
    private var cache: List<Hist>? = null

    private fun load(c: Context): List<Hist> {
        cache?.let { return it }
        val out = ArrayList<Hist>()
        for (r in Store.loadJson(c, FILE).arr()) {
            val m = r.obj()
            if (m["key"].str().isEmpty()) continue
            out.add(
                Hist(
                    m["key"].str(), m["kind"].str(), m["title"].str(), m["sub"].str(), m["id"].str(),
                    m["ext"].str(), m["pos"].long(), m["dur"].long(), m["ts"].long(), m["series"].str()
                )
            )
        }
        cache = out
        return out
    }

    fun all(c: Context): List<Hist> = load(c).sortedByDescending { it.ts }

    fun find(c: Context, key: String): Hist? = load(c).firstOrNull { it.key == key }

    fun save(c: Context, h: Hist) {
        val list = ArrayList(load(c).filter { it.key != h.key })
        list.add(h)
        val trimmed = list.sortedByDescending { it.ts }.take(LIMIT)
        cache = trimmed
        Store.saveJson(
            c, FILE,
            trimmed.map {
                mapOf(
                    "key" to it.key, "kind" to it.kind, "title" to it.title, "sub" to it.sub, "id" to it.id,
                    "ext" to it.ext, "pos" to it.pos, "dur" to it.dur, "ts" to it.ts, "series" to it.seriesId
                )
            }
        )
    }

    fun remove(c: Context, key: String) {
        val list = load(c).filter { it.key != key }
        cache = list
        Store.saveJson(c, FILE, list.map {
            mapOf("key" to it.key, "kind" to it.kind, "title" to it.title, "sub" to it.sub, "id" to it.id,
                "ext" to it.ext, "pos" to it.pos, "dur" to it.dur, "ts" to it.ts, "series" to it.seriesId)
        })
    }

    fun clearCache() {
        cache = null
    }
}
