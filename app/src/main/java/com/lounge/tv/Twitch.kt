package com.lounge.tv

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Twitch: log in with a code (made for TVs), browse through Twitch's own API, find a stream's address, read chat.
 * Your Twitch Client ID is saved on the Firestick only (typed on the phone page), never in the code.
 */
object Twitch {
    const val SCOPE = "user:read:follows"
    private const val ID_URL = "https://id.twitch.tv/oauth2"
    private const val API = "https://api.twitch.tv/helix"

    // Twitch's own web player uses this public ID to ask for a stream's address. It is not a secret.
    private const val WEB_CLIENT = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    class Stream(val login: String, val name: String, val game: String, val title: String, val viewers: Int, val thumb: String)
    class Category(val id: String, val name: String, val art: String)
    class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)
    class AuthError(msg: String) : Exception(msg)

    // ------------------------------------------------------------------ saved login
    fun clientId(c: Context): String = Store.str(c, "twitch_client_id").trim()
    fun loggedIn(c: Context): Boolean = Store.str(c, "twitch_refresh").isNotEmpty() || Store.str(c, "twitch_access").isNotEmpty()

    fun logout(c: Context) {
        for (k in listOf("twitch_access", "twitch_refresh", "twitch_user", "twitch_login")) Store.remove(c, k)
    }

    private fun saveTokens(c: Context, o: Map<String, Any?>) {
        val a = o["access_token"].str()
        if (a.isEmpty()) throw AuthError("Twitch did not give a login. Try again.")
        Store.putStr(c, "twitch_access", a)
        val r = o["refresh_token"].str()
        if (r.isNotEmpty()) Store.putStr(c, "twitch_refresh", r)
    }

    // ------------------------------------------------------------------ log in with a code
    fun startLogin(c: Context): DeviceCode {
        val t = Net.postForm("$ID_URL/device", mapOf("client_id" to clientId(c), "scopes" to SCOPE))
        val o = Json.parse(t).obj()
        val code = o["device_code"].str()
        if (code.isEmpty()) throw AuthError("Twitch did not give a code. Check the Client ID.")
        return DeviceCode(
            code, o["user_code"].str(),
            o["verification_uri"].str().ifEmpty { "https://www.twitch.tv/activate" },
            o["expires_in"].int(900), o["interval"].int(5)
        )
    }

    /** True when the login finished, false while Twitch is still waiting for you. */
    fun pollLogin(c: Context, dc: DeviceCode): Boolean {
        try {
            val t = Net.postForm(
                "$ID_URL/token",
                mapOf(
                    "client_id" to clientId(c), "scopes" to SCOPE, "device_code" to dc.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                )
            )
            saveTokens(c, Json.parse(t).obj())
            return true
        } catch (e: Net.HttpError) {
            val m = e.message ?: ""
            if (m.contains("authorization_pending") || m.contains("slow_down")) return false
            if (m.contains("expired") || m.contains("invalid device code")) throw AuthError("The code ran out. Get a new one.")
            throw AuthError("Twitch said no: " + m.take(140))
        }
    }

    @Synchronized
    private fun refresh(c: Context, old: String): String {
        val now = Store.str(c, "twitch_access")
        if (now.isNotEmpty() && now != old) return now // someone else already refreshed
        val r = Store.str(c, "twitch_refresh")
        if (r.isEmpty()) {
            logout(c)
            throw AuthError("Please log in to Twitch again.")
        }
        try {
            val t = Net.postForm("$ID_URL/token", mapOf("client_id" to clientId(c), "grant_type" to "refresh_token", "refresh_token" to r))
            saveTokens(c, Json.parse(t).obj())
        } catch (e: Net.HttpError) {
            if (e.code == 400 || e.code == 401) {
                logout(c)
                throw AuthError("Please log in to Twitch again.")
            }
            throw e
        }
        return Store.str(c, "twitch_access")
    }

    // ------------------------------------------------------------------ browsing (needs the login)
    private fun get(c: Context, path: String, token: String): Map<String, Any?> =
        Net.json(API + path, mapOf("Authorization" to "Bearer $token", "Client-Id" to clientId(c)), 15000).obj()

    private fun api(c: Context, path: String): Map<String, Any?> {
        var token = Store.str(c, "twitch_access")
        if (token.isEmpty()) token = refresh(c, "")
        try {
            return get(c, path, token)
        } catch (e: Net.HttpError) {
            if (e.code != 401) throw e
            token = refresh(c, token)
            return get(c, path, token)
        }
    }

    private fun userId(c: Context): String {
        val saved = Store.str(c, "twitch_user")
        if (saved.isNotEmpty()) return saved
        val u = api(c, "/users")["data"].arr().firstOrNull().obj()
        Store.putStr(c, "twitch_user", u["id"].str())
        Store.putStr(c, "twitch_login", u["display_name"].str())
        return u["id"].str()
    }

    fun myName(c: Context): String = Store.str(c, "twitch_login")

    private fun pic(url: String, w: Int, h: Int): String = url.replace("{width}", w.toString()).replace("{height}", h.toString())

    private fun streams(js: Map<String, Any?>): List<Stream> {
        val out = ArrayList<Stream>()
        for (d in js["data"].arr()) {
            val o = d.obj()
            val login = o["user_login"].str()
            if (login.isEmpty()) continue
            out.add(Stream(login, o["user_name"].str().ifEmpty { login }, o["game_name"].str(), o["title"].str(), o["viewer_count"].int(), pic(o["thumbnail_url"].str(), 320, 180)))
        }
        return out
    }

    fun followed(c: Context): List<Stream> = streams(api(c, "/streams/followed?first=40&user_id=" + Net.enc(userId(c))))

    fun top(c: Context, gameId: String = ""): List<Stream> =
        streams(api(c, "/streams?first=40" + if (gameId.isEmpty()) "" else "&game_id=" + Net.enc(gameId)))

    fun search(c: Context, query: String): List<Stream> {
        val js = api(c, "/search/channels?first=30&live_only=true&query=" + Net.enc(query))
        val out = ArrayList<Stream>()
        for (d in js["data"].arr()) {
            val o = d.obj()
            val login = o["broadcaster_login"].str()
            if (login.isEmpty() || !o["is_live"].bool()) continue
            out.add(Stream(login, o["display_name"].str().ifEmpty { login }, o["game_name"].str(), o["title"].str(), 0, ""))
        }
        return out
    }

    fun categories(c: Context): List<Category> {
        val out = ArrayList<Category>()
        for (d in api(c, "/games/top?first=40")["data"].arr()) {
            val o = d.obj()
            out.add(Category(o["id"].str(), o["name"].str(), pic(o["box_art_url"].str(), 188, 250)))
        }
        return out
    }

    // ------------------------------------------------------------------ playing
    private val QUERY = "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) { " +
        "streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) { value signature __typename } " +
        "videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) { value signature __typename } }"

    /** The address of a live stream. This part is not official: Twitch could change it, and then it needs fixing. */
    fun streamUrl(login: String): String {
        val body = Json.write(
            linkedMapOf(
                "operationName" to "PlaybackAccessToken_Template",
                "query" to QUERY,
                "variables" to linkedMapOf("isLive" to true, "login" to login.lowercase(), "isVod" to false, "vodID" to "", "playerType" to "site")
            )
        )
        val raw = Net.bytes(
            "https://gql.twitch.tv/gql",
            mapOf("Client-ID" to WEB_CLIENT, "Content-Type" to "application/json"),
            15000, "POST", body.toByteArray(Charsets.UTF_8)
        )
        val tok = Json.parse(String(raw, Charsets.UTF_8)).obj()["data"].obj()["streamPlaybackAccessToken"].obj()
        val value = tok["value"].str()
        val sig = tok["signature"].str()
        if (value.isEmpty() || sig.isEmpty()) throw java.io.IOException("That channel is not live right now")
        return "https://usher.ttvnw.net/api/channel/hls/" + login.lowercase() + ".m3u8" +
            "?allow_source=true&allow_audio_only=true&fast_bread=true&player=twitchweb&playlist_include_framerate=true" +
            "&p=" + (Math.random() * 9999999).toInt() + "&sig=" + Net.enc(sig) + "&token=" + Net.enc(value)
    }
}

/** Reads a channel's chat (read only, no login needed). [onLine] gets the name, the name's colour and the message, on a background thread. */
class TwitchChat(private val channel: String, private val onLine: (String, Int, String) -> Unit) {
    @Volatile private var sock: SSLSocket? = null
    @Volatile private var stopped = false

    fun start() {
        Thread { loop() }.start()
    }

    fun stop() {
        stopped = true
        try { sock?.close() } catch (e: Exception) { }
    }

    private fun send(s: SSLSocket, line: String) {
        val o = s.getOutputStream()
        o.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        o.flush()
    }

    private fun loop() {
        while (!stopped) {
            try {
                val s = SSLSocketFactory.getDefault().createSocket("irc.chat.twitch.tv", 6697) as SSLSocket
                sock = s
                s.soTimeout = 330000
                send(s, "CAP REQ :twitch.tv/tags")
                send(s, "PASS SCHMOOPIIE")
                send(s, "NICK justinfan" + (10000 + (Math.random() * 80000).toInt()))
                send(s, "JOIN #" + channel.lowercase())
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (!stopped) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("PING")) send(s, "PONG :tmi.twitch.tv") else handle(line)
                }
            } catch (e: Exception) {
                // dropped connection: try again below
            }
            if (!stopped) try { Thread.sleep(3000) } catch (e: InterruptedException) { return }
        }
    }

    private fun handle(line: String) {
        var rest = line
        var tags = ""
        if (rest.startsWith("@")) {
            val i = rest.indexOf(' ')
            if (i < 0) return
            tags = rest.substring(1, i)
            rest = rest.substring(i + 1)
        }
        val p = rest.indexOf(" PRIVMSG ")
        if (p < 0) return
        val bang = rest.indexOf('!')
        if (bang < 1) return
        val nick = rest.substring(1, bang)
        val m = rest.indexOf(" :", p)
        if (m < 0) return
        val text = rest.substring(m + 2)
        var name = nick
        var color = 0
        for (t in tags.split(";")) {
            if (t.startsWith("display-name=") && t.length > 13) name = t.substring(13)
            if (t.startsWith("color=#") && t.length == 13) color = try { android.graphics.Color.parseColor(t.substring(6)) } catch (e: Exception) { 0 }
        }
        if (color == 0) color = PALETTE[Math.abs(nick.hashCode()) % PALETTE.size]
        onLine(name, color, text)
    }

    companion object {
        private val PALETTE = intArrayOf(0xFF22E6FF.toInt(), 0xFFFF2BD6.toInt(), 0xFF7BD66A.toInt(), 0xFFFFB02B.toInt(), 0xFFB9A2FF.toInt(), 0xFFFF5B8C.toInt())
    }
}
