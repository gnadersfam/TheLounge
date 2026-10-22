package com.lounge.tv

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Prog(val start: Long, val stop: Long, val title: String)

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

    private fun getText(url: String, timeoutMs: Int): String {
        val c = open(url, timeoutMs)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun login(): Boolean {
        val o = try {
            JSONObject(getText(api(), 30000))
        } catch (e: org.json.JSONException) {
            throw IOException("The server sent something that was not a channel list")
        }
        return o.optJSONObject("user_info")?.optString("auth") == "1"
    }

    fun categories(): Map<String, String> {
        val out = HashMap<String, String>()
        val arr = try {
            org.json.JSONArray(getText(api("action" to "get_live_categories"), 60000))
        } catch (e: org.json.JSONException) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out[o.optString("category_id")] = o.optString("category_name")
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
            JsonReader(c.inputStream.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    r.beginObject()
                    var id = ""
                    var name = ""
                    var cat = ""
                    var epg = ""
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> id = readString(r)
                            "name" -> name = readString(r)
                            "category_id" -> cat = readString(r)
                            "epg_channel_id" -> epg = readString(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isEmpty()) continue
                    val catName = cats[cat] ?: ""
                    val tag = Filter.channelTag(name, catName)
                    if (Filter.isEnglish(tag)) out.add(Chan(id, name, catName, tag, epg.trim().lowercase()))
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
            JSONObject(getText(api("action" to "get_short_epg", "stream_id" to streamId, "limit" to "4"), 15000))
        } catch (e: Exception) {
            return emptyList()
        }
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val start = p.optString("start_timestamp").toLongOrNull() ?: continue
            val stop = p.optString("stop_timestamp").toLongOrNull() ?: continue
            val raw = p.optString("title")
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
}
