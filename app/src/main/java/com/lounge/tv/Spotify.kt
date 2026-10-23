package com.lounge.tv

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Where the Spotify sign-in is kept (private to this app on the Firestick). */
interface SpTokens {
    var clientId: String
    var access: String
    var refresh: String
    var expiresAt: Long
}

/** Plain web calls, so the Spotify logic can be tested without a network. Never throws for an HTTP error: returns the code. */
interface SpHttp {
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, String>
}

object NetHttp : SpHttp {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, String> =
        try {
            val b = Net.bytes(url, headers, 20000, method, body)
            200 to String(b, Charsets.UTF_8)
        } catch (e: Net.HttpError) {
            e.code to (e.message ?: "")
        }
}

class SpotifyError(val code: Int, msg: String) : IOException(msg) {
    val noDevice get() = code == 404 || message?.contains("NO_ACTIVE_DEVICE") == true
    val premium get() = code == 403
}

class SpTrack(val name: String, val artists: String, val album: String, val image: String, val uri: String, val durationMs: Long)
class SpDevice(val id: String, val name: String, val type: String, val active: Boolean, val volume: Int)
class SpPlaylist(val id: String, val name: String, val uri: String, val image: String, val owner: String)
class SpState(
    val isPlaying: Boolean, val progressMs: Long, val track: SpTrack?, val deviceName: String, val deviceId: String,
    val volume: Int, val shuffle: Boolean, val contextUri: String
)

/** The Spotify Web API calls The Lounge needs: it is a remote control for the official Spotify app on this Fire TV (Premium needed). */
class SpotifyApi(private val t: SpTokens, private val http: SpHttp = NetHttp, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {

    companion object {
        const val REDIRECT = "https://gnadersfam.github.io/TheLounge/spotify.html"
        const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private playlist-read-collaborative user-library-read"
        const val API = "https://api.spotify.com/v1"
        const val ACCOUNTS = "https://accounts.spotify.com"
        const val APP_PACKAGE = "com.spotify.tv.android"
        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun base64Url(data: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
                sb.append(B64[b0 shr 2])
                sb.append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
                if (i + 1 < data.size) sb.append(B64[((b1 and 15) shl 2) or (b2 shr 6)])
                if (i + 2 < data.size) sb.append(B64[b2 and 63])
                i += 3
            }
            return sb.toString()
        }

        fun newVerifier(): String {
            val b = ByteArray(48)
            SecureRandom().nextBytes(b)
            return base64Url(b)
        }

        fun newNonce(): String {
            val b = ByteArray(9)
            SecureRandom().nextBytes(b)
            return base64Url(b)
        }

        fun challenge(verifier: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun authUrl(clientId: String, challenge: String, state: String): String =
            "$ACCOUNTS/authorize?" + Net.form(linkedMapOf(
                "client_id" to clientId, "response_type" to "code", "redirect_uri" to REDIRECT, "scope" to SCOPES,
                "state" to state, "code_challenge_method" to "S256", "code_challenge" to challenge))

        // ---- reading answers
        private fun image(images: List<Any?>): String {
            // prefer a medium picture (about 300 px), else whatever is there
            var best = ""
            var bestW = Int.MAX_VALUE
            for (i in images) {
                val m = i.obj(); val w = m["width"].int(0)
                if (w in 150..700 && (best.isEmpty() || Math.abs(w - 300) < Math.abs(bestW - 300))) { best = m["url"].str(); bestW = w }
            }
            if (best.isEmpty()) best = images.firstOrNull().obj()["url"].str()
            return best
        }

        fun parseTrack(o: Any?): SpTrack? {
            val m = o.obj()
            if (m.isEmpty()) return null
            val album = m["album"].obj()
            return SpTrack(m["name"].str(), m["artists"].arr().joinToString(", ") { it.obj()["name"].str() }.ifEmpty { m["show"].obj()["name"].str() },
                album["name"].str(), image(album["images"].arr().ifEmpty { m["images"].arr() }), m["uri"].str(), m["duration_ms"].long())
        }

        fun parseState(o: Any?): SpState? {
            val m = o.obj()
            if (m.isEmpty()) return null
            val d = m["device"].obj()
            return SpState(m["is_playing"].bool(), m["progress_ms"].long(), parseTrack(m["item"]), d["name"].str(), d["id"].str(),
                d["volume_percent"].int(), m["shuffle_state"].bool(), m["context"].obj()["uri"].str())
        }

        fun parseDevices(o: Any?): List<SpDevice> = o.obj()["devices"].arr().map {
            val d = it.obj(); SpDevice(d["id"].str(), d["name"].str(), d["type"].str(), d["is_active"].bool(), d["volume_percent"].int())
        }

        fun parsePlaylists(o: Any?): List<SpPlaylist> = o.obj()["items"].arr().mapNotNull {
            val p = it.obj()
            if (p.isEmpty()) null else SpPlaylist(p["id"].str(), p["name"].str(), p["uri"].str(), image(p["images"].arr()), p["owner"].obj()["display_name"].str())
        }

        /** Songs in a playlist or in Liked Songs. Spotify calls each entry's song "item" (new) or "track" (older). */
        fun parseEntries(o: Any?): List<SpTrack> = o.obj()["items"].arr().mapNotNull {
            val e = it.obj()
            val t = if (e["item"].obj().isNotEmpty()) e["item"] else e["track"]
            parseTrack(t)
        }

        fun parseSearch(o: Any?): List<SpTrack> = o.obj()["tracks"].obj()["items"].arr().mapNotNull { parseTrack(it) }

        fun parseQueue(o: Any?): List<SpTrack> = o.obj()["queue"].arr().mapNotNull { parseTrack(it) }
    }

    val connected get() = t.refresh.isNotEmpty()

    // ---- signing in
    /** Swaps the one-time code from the phone sign-in for tokens. */
    fun exchange(code: String, verifier: String) {
        val (st, body) = http.request("POST", "$ACCOUNTS/api/token", mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            Net.form(linkedMapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT,
                "client_id" to t.clientId, "code_verifier" to verifier)).toByteArray())
        if (st != 200) throw SpotifyError(st, "Spotify did not accept the sign-in ($st)")
        store(Json.parseOrNull(body))
    }

    private fun store(o: Any?) {
        val m = o.obj()
        t.access = m["access_token"].str()
        if (m["refresh_token"].str().isNotEmpty()) t.refresh = m["refresh_token"].str()
        t.expiresAt = clock() + m["expires_in"].long(3600) - 60
    }

    private fun token(): String {
        if (t.access.isNotEmpty() && clock() < t.expiresAt) return t.access
        if (t.refresh.isEmpty()) throw SpotifyError(401, "Not signed in to Spotify")
        val (st, body) = http.request("POST", "$ACCOUNTS/api/token", mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            Net.form(linkedMapOf("grant_type" to "refresh_token", "refresh_token" to t.refresh, "client_id" to t.clientId)).toByteArray())
        if (st != 200) {
            if (st == 400 || st == 401) { t.access = ""; t.refresh = ""; t.expiresAt = 0 }
            throw SpotifyError(st, "Spotify sign-in needs renewing ($st)")
        }
        store(Json.parseOrNull(body))
        return t.access
    }

    fun signOut() { t.access = ""; t.refresh = ""; t.expiresAt = 0 }

    private fun call(method: String, path: String, json: Any? = null): Any? {
        val headers = HashMap<String, String>()
        headers["Authorization"] = "Bearer " + token()
        var body: ByteArray? = null
        if (method != "GET") {
            body = if (json == null) ByteArray(0) else Json.write(json).toByteArray()
            if (json != null) headers["Content-Type"] = "application/json"
        }
        val (st, text) = http.request(method, API + path, headers, body)
        if (st !in 200..299) throw SpotifyError(st, text)
        return if (text.isBlank()) null else Json.parseOrNull(text)
    }

    private fun q(vararg kv: Pair<String, String>) = "?" + Net.form(linkedMapOf(*kv))

    // ---- what is playing and where
    fun state(): SpState? = parseState(call("GET", "/me/player"))
    fun devices(): List<SpDevice> = parseDevices(call("GET", "/me/player/devices"))
    fun playlists(): List<SpPlaylist> = parsePlaylists(call("GET", "/me/playlists" + q("limit" to "50")))
    /** The songs inside a playlist (first 100). Tries the newer address, then the older one. */
    fun playlistTracks(id: String): List<SpTrack> {
        val l = q("limit" to "100")
        return try {
            parseEntries(call("GET", "/playlists/$id/items$l"))
        } catch (e: SpotifyError) {
            if (e.code != 404 && e.code != 400) throw e
            parseEntries(call("GET", "/playlists/$id/tracks$l"))
        }
    }

    fun likedTracks(): List<SpTrack> = parseEntries(call("GET", "/me/tracks" + q("limit" to "50")))

    /** Play a song inside its playlist, so the rest of the playlist carries on after it. */
    fun playTrackInContext(contextUri: String, trackUri: String, deviceId: String = "") {
        call("PUT", "/me/player/play" + (if (deviceId.isEmpty()) "" else q("device_id" to deviceId)),
            mapOf("context_uri" to contextUri, "offset" to mapOf("uri" to trackUri)))
    }

    /** Play a list of songs starting from the first one. */
    fun playUris(uris: List<String>, deviceId: String = "") {
        call("PUT", "/me/player/play" + (if (deviceId.isEmpty()) "" else q("device_id" to deviceId)), mapOf("uris" to uris.take(50)))
    }

    fun search(text: String): List<SpTrack> = parseSearch(call("GET", "/search" + q("q" to text, "type" to "track", "limit" to "10")))
    fun queue(): List<SpTrack> = parseQueue(call("GET", "/me/player/queue"))

    // ---- controls
    fun transfer(deviceId: String, play: Boolean = true) { call("PUT", "/me/player", mapOf("device_ids" to listOf(deviceId), "play" to play)) }
    fun resume(deviceId: String = "") { call("PUT", "/me/player/play" + (if (deviceId.isEmpty()) "" else q("device_id" to deviceId))) }
    fun pause() { call("PUT", "/me/player/pause") }
    fun next() { call("POST", "/me/player/next") }
    fun previous() { call("POST", "/me/player/previous") }
    fun volume(pct: Int, deviceId: String = "") {
        call("PUT", "/me/player/volume" + (if (deviceId.isEmpty()) q("volume_percent" to pct.coerceIn(0, 100).toString())
        else q("volume_percent" to pct.coerceIn(0, 100).toString(), "device_id" to deviceId)))
    }
    fun shuffle(on: Boolean) { call("PUT", "/me/player/shuffle" + q("state" to on.toString())) }
    fun playContext(uri: String, deviceId: String = "") {
        call("PUT", "/me/player/play" + (if (deviceId.isEmpty()) "" else q("device_id" to deviceId)), mapOf("context_uri" to uri))
    }
    fun playTrack(uri: String, deviceId: String = "") {
        call("PUT", "/me/player/play" + (if (deviceId.isEmpty()) "" else q("device_id" to deviceId)), mapOf("uris" to listOf(uri)))
    }
    fun addToQueue(uri: String) { call("POST", "/me/player/queue" + q("uri" to uri)) }
}
