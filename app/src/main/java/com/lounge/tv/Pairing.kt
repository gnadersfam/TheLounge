package com.lounge.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Pair with phone": while the pairing screen is open the Firestick runs a tiny web page that only your own
 * Wi-Fi can reach. You open it from your phone, paste your keys and logins, and they are saved on the Firestick.
 * Nothing is sent to the internet, and nothing is kept on the phone.
 */
class PairServer(private val ctx: Context) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    var port = 0
        private set
    val pin: String = (1000 + SecureRandom().nextInt(9000)).toString()

    /** Called on the main thread with the names of what was just saved. */
    var onSaved: ((List<String>) -> Unit)? = null

    /** Extra pages (used for the Spotify sign-in hand-back). Returns an HTML page, or null if not handled. */
    var route: ((path: String, query: Map<String, String>, body: Map<String, String>) -> String?)? = null

    private val main = Handler(Looper.getMainLooper())

    fun start(): Boolean {
        if (running) return true
        for (p in 8080..8095) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (e: IOException) {
                server = null
            }
        }
        val s = server ?: return false
        running = true
        Thread {
            while (running) {
                try {
                    val sock = s.accept()
                    Thread { handle(sock) }.start()
                } catch (e: IOException) {
                    break
                }
            }
        }.start()
        return true
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: IOException) {
        }
        server = null
    }

    fun address(): String {
        val ip = localIp() ?: "your-firestick"
        return "http://$ip:$port/?k=$pin"
    }

    // ------------------------------------------------------------------ http
    private fun readRequest(input: InputStream): Triple<String, Map<String, String>, ByteArray>? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 16384) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val first = lines.firstOrNull() ?: return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = (headers["content-length"] ?: "0").toIntOrNull() ?: 0
        if (len > 65536) return null
        val body = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = input.read(body, got, len - got)
            if (n < 0) break
            got += n
        }
        return Triple(first, headers, body)
    }

    private fun parseQuery(q: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (q.isEmpty()) return out
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            try {
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (e: IllegalArgumentException) {
                // ignore a broken piece
            }
        }
        return out
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 8000
            val req = readRequest(sock.getInputStream()) ?: return
            val parts = req.first.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val qi = target.indexOf('?')
            val path = if (qi < 0) target else target.substring(0, qi)
            val query = parseQuery(if (qi < 0) "" else target.substring(qi + 1))
            val body = parseQuery(String(req.third, Charsets.UTF_8))

            var status = "200 OK"
            val html: String
            val extra = route?.invoke(path, query, body)
            if (extra != null) {
                html = extra
            } else if (path == "/save" && method == "POST") {
                if (body["k"] != pin) {
                    status = "403 Forbidden"
                    html = page("Wrong PIN", "<p>That PIN is not right. Go back and scan the code on the TV again.</p>")
                } else {
                    val saved = save(body)
                    html = page(
                        "Saved",
                        if (saved.isEmpty()) "<p>Nothing was filled in, so nothing changed.</p>"
                        else "<p class=ok>Saved on your Firestick:</p><ul>" + saved.joinToString("") { "<li>$it</li>" } + "</ul><p>You can close this page and look at the TV.</p>"
                    )
                    if (saved.isNotEmpty()) main.post { onSaved?.invoke(saved) }
                }
            } else if (path == "/backup") {
                if (query["k"] != pin) {
                    status = "403 Forbidden"
                    html = page("Wrong PIN", "<p>Please scan the code on the TV again, so the PIN is included.</p>")
                } else {
                    html = backupPage()
                }
            } else if (path == "/restore" && method == "POST") {
                if (body["k"] != pin) {
                    status = "403 Forbidden"
                    html = page("Wrong PIN", "<p>That PIN is not right.</p>")
                } else {
                    val result = Backup.restore(ctx, body["data"].orEmpty())
                    html = page("Restore", "<p class=ok>" + esc(result) + "</p><p>Close and reopen The Lounge to see everything.</p>")
                    main.post { onSaved?.invoke(listOf("Settings restored")) }
                }
            } else if (path == "/" ) {
                if (query["k"] != pin) {
                    status = "403 Forbidden"
                    html = page("Wrong PIN", "<p>Please scan the code on the TV again, so the PIN is included.</p>")
                } else {
                    html = form()
                }
            } else {
                status = "404 Not Found"
                html = page("Not found", "<p>Nothing here.</p>")
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            // a phone that goes away half way is not a problem
        } finally {
            try {
                sock.close()
            } catch (e: IOException) {
            }
        }
    }

    // ------------------------------------------------------------------ saving
    private fun save(b: Map<String, String>): List<String> {
        val saved = ArrayList<String>()
        val server = b["server"].orEmpty().trim()
        val user = b["user"].orEmpty().trim()
        val pass = b["pass"].orEmpty().trim()
        if (server.isNotEmpty() || user.isNotEmpty() || pass.isNotEmpty()) {
            Store.saveLogin(
                ctx,
                server.ifEmpty { Store.server(ctx) },
                user.ifEmpty { Store.user(ctx) },
                pass.ifEmpty { Store.pass(ctx) }
            )
            saved.add("IPTV login")
        }
        val fdo = b["fdo_key"].orEmpty().trim()
        if (fdo.isNotEmpty()) {
            Store.putStr(ctx, "fdo_key", fdo)
            saved.add("football-data.org key")
        }
        val af = b["apifootball_key"].orEmpty().trim()
        if (af.isNotEmpty()) {
            Store.putStr(ctx, "apifootball_key", af)
            saved.add("API-Football key")
        }
        val sp = b["spotify_client_id"].orEmpty().trim()
        if (sp.isNotEmpty()) {
            Store.putStr(ctx, "spotify_client_id", sp)
            saved.add("Spotify app ID")
        }
        val tw = b["twitch_client_id"].orEmpty().trim()
        if (tw.isNotEmpty()) {
            Store.putStr(ctx, "twitch_client_id", tw)
            saved.add("Twitch Client ID")
        }
        return saved
    }

    // ------------------------------------------------------------------ pages
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(title: String, body: String): String =
        "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<title>The Lounge</title><style>" +
            "body{margin:0;padding:20px;background:#07030F;color:#F5EEFF;font:18px system-ui,sans-serif}" +
            "h1{color:#19F0FF;font-size:26px;margin:0 0 4px}h2{font-size:18px;color:#FF3DDB;margin:26px 0 6px}" +
            "p{color:#C9B8F0;line-height:1.4}.ok{color:#3DDC97}label{display:block;margin:14px 0 4px;color:#F5EEFF}" +
            "input{width:100%;box-sizing:border-box;padding:13px;border-radius:10px;border:1px solid #3A2468;background:#1A1030;color:#F5EEFF;font-size:18px}" +
            "button{margin-top:26px;width:100%;padding:15px;border:0;border-radius:12px;background:#19F0FF;color:#07030F;font-size:20px;font-weight:700}" +
            ".had{color:#3DDC97;font-size:14px}</style></head><body><h1>The Lounge</h1><p>$title</p>$body</body></html>"

    private fun backupPage(): String {
        val data = esc(Backup.export(ctx))
        val body = "<h2>Save a copy</h2><p>Press and hold inside the box, choose Select all, Copy, and paste it into a note on your phone. " +
            "It holds your category order, hidden categories, picks, watch-list and watching history. It does not hold any logins or keys.</p>" +
            "<textarea id=d readonly style='width:100%;height:160px;box-sizing:border-box;background:#1A1030;color:#F5EEFF;border:1px solid #3A2468;border-radius:10px;padding:10px'>$data</textarea>" +
            "<h2>Restore a copy</h2><form method=post action=/restore><input type=hidden name=k value='$pin'>" +
            "<p>Paste a saved copy here:</p><textarea name=data style='width:100%;height:120px;box-sizing:border-box;background:#1A1030;color:#F5EEFF;border:1px solid #3A2468;border-radius:10px;padding:10px'></textarea>" +
            "<button type=submit>Restore on the Firestick</button></form>"
        return page("Backup and restore", body)
    }

    private fun had(key: String, have: Boolean) = if (have) " <span class=had>(already saved)</span>" else ""

    private fun form(): String {
        val body = "<form method=post action=/save><input type=hidden name=k value='$pin'>" +
            "<h2>IPTV login</h2>" +
            "<label>Server (e.g. http://example.com:8080)${had("s", Store.server(ctx).isNotBlank())}</label><input name=server autocapitalize=none autocorrect=off>" +
            "<label>Username${had("u", Store.user(ctx).isNotBlank())}</label><input name=user autocapitalize=none autocorrect=off>" +
            "<label>Password${had("p", Store.pass(ctx).isNotBlank())}</label><input name=pass type=password autocapitalize=none autocorrect=off>" +
            "<h2>Football keys</h2>" +
            "<label>football-data.org key${had("f", Store.fdoKey(ctx).isNotBlank())}</label><input name=fdo_key autocapitalize=none autocorrect=off>" +
            "<label>API-Football key${had("a", Store.apiFootballKey(ctx).isNotBlank())}</label><input name=apifootball_key autocapitalize=none autocorrect=off>" +
            "<h2>Spotify</h2>" +
            "<label>Spotify app Client ID${had("c", Store.spotifyClientId(ctx).isNotBlank())}</label><input name=spotify_client_id autocapitalize=none autocorrect=off>" +
            "<h2>Twitch</h2>" +
            "<label>Twitch Client ID${had("t", Twitch.clientId(ctx).isNotBlank())}</label><input name=twitch_client_id autocapitalize=none autocorrect=off>" +
            "<p>Leave a box empty to keep what is already saved.</p>" +
            "<p><a style='color:#19F0FF' href='/backup?k=$pin'>Back up or restore my settings</a></p>" +
            "<button type=submit>Save on the Firestick</button></form>"
        return page("Type or paste into any box. This goes straight to your Firestick on your own Wi-Fi.", body)
    }

    companion object {
        /** The Firestick's address on your home network, e.g. 192.168.1.45. */
        fun localIp(): String? {
            try {
                val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
                var fallback: String? = null
                while (ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            if (a.isSiteLocalAddress) return a.hostAddress
                            if (fallback == null) fallback = a.hostAddress
                        }
                    }
                }
                return fallback
            } catch (e: Exception) {
                return null
            }
        }
    }
}
