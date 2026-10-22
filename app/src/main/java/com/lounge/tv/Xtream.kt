package com.lounge.tv

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Prog(val start: Long, val stop: Long, val title: String)

/** One movie or show from the provider's list. [kind] is "movie" or "series". */
data class VodItem(
    val id: String,
    val name: String,
    val cat: String,
    val tag: String,
    val rating: Double,
    val year: Int,
    val added: Long,
    val ext: String,
    val kind: String,
    val icon: String = "",
    val plot: String = ""
)

data class Episode(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val plot: String = "")

/** Talks to an Xtream-style IPTV server (the same kind of login the PC app uses). */
class Xtream(private val base: String, private val user: String, private val pass: String) {

    companion object {
        const val API_UA = "Mozilla/5.0 (The Lounge)"
        const val STREAM_UA = "VLC/3.0.20 LibVLC/3.0.20"

        fun clean(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(s)) s = "http://$s"
            return s.split(Regex("""/(?:player_api|get|xmltv|panel_api)\.php"""))[0]
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(vararg extra: Pair<String, String>): String {
        val sb = StringBuilder("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}")
        for ((k, v) in extra) sb.append('&').append(k).append('=').append(enc(v))
        return sb.toString()
    }

    fun streamUrl(streamId: String, ext: String): String =
        "$base/live/${Uri.encode(user)}/${Uri.encode(pass)}/$streamId.$ext"

    fun movieUrl(id: String, ext: String): String =
        "$base/movie/${Uri.encode(user)}/${Uri.encode(pass)}/$id.${ext.ifBlank { "mp4" }}"

    fun episodeUrl(id: String, ext: String): String =
        "$base/series/${Uri.encode(user)}/${Uri.encode(pass)}/$id.${ext.ifBlank { "mp4" }}"

    /** Hide the login details if an error message happens to contain them. */
    fun redact(text: String): String {
        var t = text
        for (secret in listOf(pass, user)) if (secret.isNotEmpty()) t = t.replace(secret, "***")
        return t
    }

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", API_UA)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("The server answered HTTP $code")
            }
            return c
        } catch (e: IOException) {
            throw IOException(redact(e.message ?: "Connection problem"))
        }
    }

    private fun stream(c: HttpURLConnection): InputStream {
        val s = c.inputStream
        return if ((c.contentEncoding ?: "").equals("gzip", true)) java.util.zip.GZIPInputStream(s) else s
    }

    private fun getText(url: String, timeoutMs: Int): String {
        val c = open(url, timeoutMs)
        try {
            return stream(c).bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun getJson(url: String, timeoutMs: Int): Any? {
        val t = getText(url, timeoutMs)
        return try {
            Json.parse(t)
        } catch (e: Json.JsonError) {
            throw IOException("The server sent something that was not a list")
        }
    }

    /** Signs in. Returns the account details (user_info) or throws. */
    fun login(): Map<String, Any?> {
        val o = getJson(api(), 30000).obj()
        val ui = o["user_info"].obj()
        if (ui["auth"].str() != "1") throw IOException("Login was rejected. Check the server, username and password.")
        return ui
    }

    fun categories(action: String = "get_live_categories"): Map<String, String> {
        val out = HashMap<String, String>()
        val arr = try {
            getJson(api("action" to action), 60000).arr()
        } catch (e: IOException) {
            return out
        }
        for (o in arr) {
            val m = o.obj()
            out[m["category_id"].str()] = m["category_name"].str()
        }
        return out
    }

    private fun readString(r: JsonReader): String = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); "" }
    }

    /** Reads the full channel list as a stream (so it stays light on a Fire TV) and keeps only English channels. */
    fun englishChannels(cats: Map<String, String>): List<Chan> {
        val c = open(api("action" to "get_live_streams"), 120000)
        val out = ArrayList<Chan>()
        try {
            JsonReader(stream(c).bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    r.beginObject()
                    var id = ""
                    var name = ""
                    var cat = ""
                    var epg = ""
                    var icon = ""
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> id = readString(r)
                            "name" -> name = readString(r)
                            "category_id" -> cat = readString(r)
                            "epg_channel_id" -> epg = readString(r)
                            "stream_icon" -> icon = readString(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isEmpty()) continue
                    val catName = cats[cat] ?: ""
                    val tag = Filter.channelTag(name, catName)
                    if (Filter.isEnglish(tag)) out.add(Chan(id, name, catName, tag, epg.trim().lowercase(), icon))
                }
                r.endArray()
            }
        } catch (e: IllegalStateException) {
            throw IOException("The server did not send a channel list")
        } finally {
            c.disconnect()
        }
        return out
    }

    /** What is on now / next for one channel (small request, fetched only for the channel you look at). */
    fun shortEpg(streamId: String): List<Prog> {
        val o = try {
            getJson(api("action" to "get_short_epg", "stream_id" to streamId, "limit" to "4"), 15000).obj()
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Prog>()
        for (item in o["epg_listings"].arr()) {
            val p = item.obj()
            val start = p["start_timestamp"].str().toLongOrNull() ?: continue
            val stop = p["stop_timestamp"].str().toLongOrNull() ?: continue
            val raw = p["title"].str()
            val title = try {
                String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).ifBlank { raw }
            } catch (e: IllegalArgumentException) {
                raw
            }
            out.add(Prog(start, stop, title.trim()))
        }
        out.sortBy { it.start }
        return out
    }

    /** Streams the provider's full TV guide (xmltv). The caller closes the returned connection stream. */
    fun openGuide(): Pair<InputStream, () -> Unit> {
        val c = open("$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}", 240000)
        return Pair(c.inputStream, { c.disconnect() })
    }

    // ------------------------------------------------------------------ movies and shows
    private fun rating(v: String): Double {
        var r = v.toDoubleOrNull() ?: return 0.0
        if (r > 10) r /= 10.0
        return Math.round(r.coerceIn(0.0, 10.0) * 10.0) / 10.0
    }

    private val yearRe = Regex("""\b(19[3-9]\d|20[0-4]\d)\b""")

    private fun year(vararg texts: String): Int {
        for (t in texts) yearRe.find(t)?.let { return it.groupValues[1].toInt() }
        return 0
    }

    /** Streams one big list (movies or series) and keeps English-language titles only. */
    private fun readVod(action: String, series: Boolean, cats: Map<String, String>): List<VodItem> {
        val c = open(api("action" to action), 240000)
        val out = ArrayList<VodItem>()
        try {
            JsonReader(stream(c).bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    r.beginObject()
                    var id = ""
                    var name = ""
                    var cat = ""
                    var rate = ""
                    var added = ""
                    var ext = ""
                    var icon = ""
                    var yr = ""
                    var rel = ""
                    var plot = ""
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id", "series_id" -> id = readString(r)
                            "name" -> name = readString(r)
                            "category_id" -> cat = readString(r)
                            "rating" -> rate = readString(r)
                            "added", "last_modified" -> added = readString(r)
                            "container_extension" -> ext = readString(r)
                            "stream_icon", "cover" -> icon = readString(r)
                            "year" -> yr = readString(r)
                            "releaseDate", "release_date" -> rel = readString(r)
                            "plot" -> plot = readString(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isEmpty()) continue
                    val catName = cats[cat] ?: ""
                    val tag = Vod.tag(name, catName)
                    if (tag == "other") continue
                    out.add(
                        VodItem(
                            id, name, catName, tag, rating(rate), year(yr, rel, name),
                            added.toLongOrNull() ?: 0L, ext.ifBlank { if (series) "" else "mp4" },
                            if (series) "series" else "movie", icon, plot.take(300)
                        )
                    )
                }
                r.endArray()
            }
        } catch (e: IllegalStateException) {
            throw IOException("The server did not send a list of titles")
        } finally {
            c.disconnect()
        }
        return out
    }

    fun movies(): List<VodItem> = readVod("get_vod_streams", false, categories("get_vod_categories"))
    fun series(): List<VodItem> = readVod("get_series", true, categories("get_series_categories"))

    fun episodes(seriesId: String): List<Episode> {
        val data = getJson(api("action" to "get_series_info", "series_id" to seriesId), 60000).obj()
        val eps = data["episodes"]
        val flat = ArrayList<Pair<String, Map<String, Any?>>>()
        if (eps is Map<*, *>) {
            for ((season, lst) in eps) for (e in lst.arr()) flat.add(Pair(season.toString(), e.obj()))
        } else if (eps is List<*>) {
            for ((i, lst) in eps.withIndex()) for (e in lst.arr()) flat.add(Pair((i + 1).toString(), e.obj()))
        }
        val out = ArrayList<Episode>()
        for ((season, e) in flat) {
            val info = e["info"].obj()
            val s = e["season"].int(season.toIntOrNull() ?: 1)
            out.add(
                Episode(
                    e["id"].str(), s, e["episode_num"].int(), e["title"].str(),
                    e["container_extension"].str().ifBlank { "mp4" }, info["plot"].str().take(300)
                )
            )
        }
        out.sortWith(compareBy({ it.season }, { it.num }))
        return out
    }
}
