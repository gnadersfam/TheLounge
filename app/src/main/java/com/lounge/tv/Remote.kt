package com.lounge.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone remote. While The Lounge is open the Firestick runs a small web page on your own Wi-Fi. Your phone opens it
 * and the buttons act on the app (keys, skip bar, volume, channels, search). Every request must carry the PIN.
 */
object Remote {
    @Volatile var main: MainActivity? = null
    @Volatile var player: PlayerActivity? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private var app: Context? = null
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val inst = Instrumentation()
    private var scoreAt = 0L
    private var scoreJson = ""

    var port = 0
        private set

    fun pin(c: Context): String {
        var p = Store.str(c, "remote_pin")
        if (p.length != 4) {
            p = (1000 + SecureRandom().nextInt(9000)).toString()
            Store.putStr(c, "remote_pin", p)
        }
        return p
    }

    fun newPin(c: Context): String {
        Store.remove(c, "remote_pin")
        return pin(c)
    }

    /** The address to type on the phone, or "" if the remote could not start. */
    fun address(c: Context): String {
        if (port == 0) return ""
        val ip = PairServer.localIp() ?: return ""
        return "http://$ip:$port/r?k=${pin(c)}"
    }

    fun qr(text: String, size: Int): Bitmap? = try {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.MARGIN] = 2
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        bmp
    } catch (e: Exception) {
        null
    }

    fun start(c: Context) {
        app = c.applicationContext
        if (running) return
        var s: ServerSocket? = null
        for (p in 8765..8780) {
            try {
                s = ServerSocket(p)
                port = p
                break
            } catch (e: IOException) {
                s = null
            }
        }
        val sock = s ?: return
        server = sock
        running = true
        Thread {
            while (running) {
                try {
                    val conn = sock.accept()
                    pool.execute { handle(conn) }
                } catch (e: IOException) {
                    break
                }
            }
            running = false
        }.start()
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
        if (len > 16384) return null
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
            val c = app ?: return
            val req = readRequest(sock.getInputStream()) ?: return
            val parts = req.first.split(" ")
            if (parts.size < 2) return
            val target = parts[1]
            val qi = target.indexOf('?')
            val path = if (qi < 0) target else target.substring(0, qi)
            val query = parseQuery(if (qi < 0) "" else target.substring(qi + 1))
            val body = parseQuery(String(req.third, Charsets.UTF_8))
            val k = query["k"] ?: body["k"] ?: ""

            var status = "200 OK"
            var type = "application/json; charset=utf-8"
            var out = "{}"
            if (k != pin(c)) {
                status = "403 Forbidden"
                type = "text/html; charset=utf-8"
                out = "<p style='font:18px sans-serif'>Wrong PIN. Open the address shown on the TV (Settings, Phone remote).</p>"
            } else {
                when (path) {
                    "/r" -> {
                        type = "text/html; charset=utf-8"
                        out = RemotePage.HTML.replace("__PIN__", k)
                    }
                    "/s" -> out = state()
                    "/k" -> { key(body["key"].orEmpty()); out = reply(true, "") }
                    "/vol" -> { volume(body["d"].orEmpty()); out = reply(true, "") }
                    "/ch" -> out = channel(body["d"].orEmpty())
                    "/seek" -> { seek(body["ms"]?.toLongOrNull() ?: -1L); out = reply(true, "") }
                    "/open" -> { openPage(body["p"].orEmpty()); out = reply(true, "") }
                    "/find" -> out = find(query["q"].orEmpty())
                    "/play" -> out = play(body["t"].orEmpty(), body["id"].orEmpty())
                    "/scores" -> out = scores()
                    "/watch" -> out = watch(body["home"].orEmpty(), body["away"].orEmpty())
                    "/dm" -> out = discord("move", body)
                    "/dc" -> out = discord("click", body)
                    "/ds" -> out = discord("scroll", body)
                    "/dt" -> out = discord("type", body)
                    "/dopen" -> out = discord("action", body)
                    else -> {
                        status = "404 Not Found"
                        out = reply(false, "Not found")
                    }
                }
            }
            val bytes = out.toByteArray(Charsets.UTF_8)
            val o = sock.getOutputStream()
            o.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            o.write(bytes)
            o.flush()
        } catch (e: Exception) {
            // a phone that goes away half way is not a problem
        } finally {
            try {
                sock.close()
            } catch (e: IOException) {
            }
        }
    }

    private fun reply(ok: Boolean, msg: String): String = Json.write(linkedMapOf("ok" to ok, "msg" to msg))

    /** Runs something on the app's main thread and waits (briefly) for the answer. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> onMain(def: T, block: () -> T): T {
        val box = arrayOfNulls<Any?>(1)
        val latch = CountDownLatch(1)
        ui.post {
            try {
                box[0] = block()
            } catch (e: Throwable) {
                Crash.log("Phone remote", e)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(2, TimeUnit.SECONDS)) ((box[0] as T?) ?: def) else def
    }

    // ------------------------------------------------------------------ what is happening now
    private fun state(): String {
        val c = app ?: return "{}"
        val m = LinkedHashMap<String, Any?>()
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        m["vol"] = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        m["muted"] = if (Build.VERSION.SDK_INT >= 23) am.isStreamMute(AudioManager.STREAM_MUSIC) else false
        m["fixed"] = am.isVolumeFixed
        m["page"] = onMain("") { main?.currentName ?: "" }
        val info = onMain(emptyMap<String, Any?>()) { player?.remoteInfo() ?: emptyMap() }
        m.putAll(info)
        return Json.write(m)
    }

    // ------------------------------------------------------------------ keys and controls
    private fun key(name: String) {
        val code = when (name) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU
            "pp" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            else -> return
        }
        try {
            inst.sendKeyDownUpSync(code)
        } catch (e: Throwable) {
            fallbackKey(code)
        }
    }

    /** Used if the system refuses the first way: hand the key straight to the screen that is showing. */
    private fun fallbackKey(code: Int) {
        onMain(Unit) {
            val a: Activity? = player ?: main
            if (a != null) {
                val handled = a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                val dir = when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                    else -> 0
                }
                if (!handled && dir != 0) a.currentFocus?.focusSearch(dir)?.requestFocus()
            }
            Unit
        }
    }

    private fun volume(d: String) {
        val c = app ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        if (am.isVolumeFixed) {
            // a Fire TV Stick has "fixed" volume: the level belongs to the TV, so press the volume keys (the Stick passes them to the TV)
            val code = when (d) {
                "up" -> KeyEvent.KEYCODE_VOLUME_UP
                "down" -> KeyEvent.KEYCODE_VOLUME_DOWN
                "mute" -> KeyEvent.KEYCODE_VOLUME_MUTE
                else -> return
            }
            try {
                inst.sendKeyDownUpSync(code)
            } catch (e: Throwable) {
                Crash.log("Phone remote volume key", e)
            }
            return
        }
        when (d) {
            "up" -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> if (Build.VERSION.SDK_INT >= 23) {
                am.adjustStreamVolume(s, if (am.isStreamMute(s)) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    private fun channel(d: String): String {
        val ok = onMain(false) {
            val p = player
            if (p != null && p.remoteIsLive()) {
                p.remoteChange(if (d == "up") 1 else -1)
                true
            } else false
        }
        return if (ok) reply(true, "") else reply(false, "Start a live channel on the TV first")
    }

    private fun seek(ms: Long) {
        if (ms < 0L) return
        onMain(Unit) { player?.remoteSeek(ms); Unit }
    }

    private fun openPage(p: String) {
        if (p !in listOf("home", "live", "movies", "shows", "football")) return
        onMain(Unit) {
            player?.finish()
            main?.open(p)
            Unit
        }
    }

    // ------------------------------------------------------------------ search and play
    private fun rows(c: Context, file: String): List<List<Any?>> =
        Store.loadJson(c, file).obj()["rows"].arr().map { it.arr() }

    private fun find(q: String): String {
        val c = app ?: return "{}"
        val needle = q.trim().lowercase()
        val live = ArrayList<Map<String, Any?>>()
        val movies = ArrayList<Map<String, Any?>>()
        val series = ArrayList<Map<String, Any?>>()
        if (needle.length >= 2) {
            val now = System.currentTimeMillis() / 1000
            val epg = Session.epg
            for (ch in Store.loadChannels(c)?.second ?: emptyList()) {
                if (live.size >= 8) break
                if (!ch.name.lowercase().contains(needle)) continue
                val on = if (ch.epg.isNotEmpty() && epg.built > 0) epg.nowNext(ch.epg, now, 1).first?.title ?: "" else ""
                live.add(linkedMapOf("id" to ch.id, "name" to ch.name, "sub" to on))
            }
            for (a in rows(c, "vod_movies")) {
                if (movies.size >= 8) break
                if (a.size < 2 || !a[1].str().lowercase().contains(needle)) continue
                movies.add(linkedMapOf("id" to a[0].str(), "name" to Vod.displayName(a[1].str()), "sub" to ""))
            }
            for (a in rows(c, "vod_series")) {
                if (series.size >= 8) break
                if (a.size < 2 || !a[1].str().lowercase().contains(needle)) continue
                series.add(linkedMapOf("id" to a[0].str(), "name" to Vod.displayName(a[1].str()), "sub" to ""))
            }
        }
        return Json.write(linkedMapOf("live" to live, "movies" to movies, "series" to series))
    }

    private fun play(t: String, id: String): String {
        val c = app ?: return reply(false, "Not ready")
        val m = main ?: return reply(false, "Open The Lounge on the TV first")
        when (t) {
            "live" -> {
                val list = Store.loadChannels(c)?.second ?: emptyList()
                val i = list.indexOfFirst { it.id == id }
                if (i < 0) return reply(false, "Channel not found")
                onMain(Unit) { player?.finish(); m.playLive(list, i); Unit }
                return reply(true, "Starting on the TV")
            }
            "movie" -> {
                val row = rows(c, "vod_movies").firstOrNull { it.isNotEmpty() && it[0].str() == id } ?: return reply(false, "Film not found")
                val xt = Session.xt ?: Store.xtream(c) ?: return reply(false, "Pair your IPTV login first")
                Session.xt = xt
                val ext = if (row.size > 7) row[7].str().ifEmpty { "mp4" } else "mp4"
                val title = Vod.displayName(row[1].str())
                onMain(Unit) {
                    val key = "movie:$id"
                    val old = History.find(c, key)
                    val resume = if (old != null && old.resumable()) old.pos else 0L
                    History.save(c, Hist(key, "movie", title, old?.sub ?: "", id, ext, resume, old?.dur ?: 0L, System.currentTimeMillis() / 1000))
                    player?.finish()
                    m.playVod(title, xt.movieUrl(id, ext), key, resume)
                    Unit
                }
                return reply(true, "Starting on the TV")
            }
            "series" -> {
                onMain(Unit) { player?.finish(); m.open("shows", "series:$id"); Unit }
                return reply(true, "Opening the episodes on the TV")
            }
        }
        return reply(false, "Not sure what to play")
    }

    // ------------------------------------------------------------------ Discord window (the phone's Mouse mode)
    private fun discord(kind: String, b: Map<String, String>): String {
        val p = player ?: return reply(false, "Start a channel or film on the TV first, then open Discord")
        when (kind) {
            "move" -> {
                val dx = b["dx"]?.toIntOrNull() ?: 0
                val dy = b["dy"]?.toIntOrNull() ?: 0
                val on = onMain(false) { p.remoteDiscordPointer(dx, dy) }
                return if (on) reply(true, "") else reply(false, "Open Discord first (Open Discord button)")
            }
            "click" -> {
                val right = b["b"] == "r"
                val on = onMain(false) { p.remoteDiscordClick(right) }
                return if (on) reply(true, "") else reply(false, "Open Discord first (Open Discord button)")
            }
            "scroll" -> {
                val d = b["d"]?.toIntOrNull() ?: 0
                val on = onMain(false) { p.remoteDiscordScroll(d) }
                return if (on) reply(true, "") else reply(false, "Open Discord first (Open Discord button)")
            }
            "type" -> {
                val t = b["t"].orEmpty()
                val on = onMain(false) { p.remoteDiscordType(t) }
                return if (on) reply(true, "") else reply(false, "Open Discord first (Open Discord button)")
            }
            "action" -> {
                val a = b["a"].orEmpty()
                val msg = onMain("Discord did not answer") { p.remoteDiscord(a) }
                return reply(msg.isEmpty(), msg)
            }
        }
        return reply(false, "Not sure what to do")
    }

    // ------------------------------------------------------------------ football scores
    private fun scores(): String {
        val now = System.currentTimeMillis()
        if (scoreJson.isNotEmpty() && now - scoreAt < 30_000) return scoreJson
        val out = ArrayList<Map<String, Any?>>()
        for (lg in listOf("eng.1", "eng.2")) {
            try {
                val js = Net.json("https://site.api.espn.com/apis/site/v2/sports/soccer/$lg/scoreboard", timeoutMs = 8000)
                for (e in js.obj()["events"].arr()) {
                    val ev = e.obj()
                    val comp = ev["competitions"].arr().firstOrNull().obj()
                    var home = ""; var away = ""; var hn = ""; var an = ""; var hg = ""; var ag = ""
                    for (cc in comp["competitors"].arr()) {
                        val o = cc.obj()
                        val t = o["team"].obj()
                        val name = t["shortDisplayName"].str().ifEmpty { t["displayName"].str() }
                        val abbr = t["abbreviation"].str().ifEmpty { name }
                        if (o["homeAway"].str() == "home") { home = name; hn = abbr; hg = o["score"].str() }
                        else { away = name; an = abbr; ag = o["score"].str() }
                    }
                    if (home.isEmpty() || away.isEmpty()) continue
                    val st = ev["status"].obj()
                    val state = st["type"].obj()["state"].str()
                    out.add(linkedMapOf("home" to home, "away" to away, "hn" to hn, "an" to an, "hg" to hg, "ag" to ag,
                        "state" to state, "clock" to st["displayClock"].str(), "when" to ev["date"].str()))
                }
            } catch (e: Throwable) {
                // one league failing is fine
            }
        }
        out.sortBy { when (it["state"]) { "in" -> 0; "pre" -> 1; else -> 2 } }
        scoreJson = Json.write(linkedMapOf("list" to out.take(8)))
        scoreAt = now
        return scoreJson
    }

    /** Tap on a score: find a channel whose "on now" title has both team names, and play it. */
    private fun watch(home: String, away: String): String {
        val c = app ?: return reply(false, "Not ready")
        val m = main ?: return reply(false, "Open The Lounge on the TV first")
        if (!Store.hasLogin(c)) return reply(false, "Pair your IPTV login first")
        val chans = Store.loadChannels(c)?.second ?: return reply(false, "Open Live TV on the TV once first")
        val epg = if (Session.epg.built > 0) Session.epg else EpgData.fromJson(Store.loadJson(c, "live_epg"))
        if (epg.built == 0L) return reply(false, "Open Live TV on the TV once first")
        val now = System.currentTimeMillis() / 1000
        val h = home.lowercase()
        val a = away.lowercase()
        val hits = ArrayList<Chan>()
        for (ch in chans) {
            if (ch.epg.isEmpty()) continue
            val title = epg.nowNext(ch.epg, now, 1).first?.title?.lowercase() ?: continue
            if (title.contains(h) && title.contains(a)) hits.add(ch)
        }
        if (hits.isEmpty()) return reply(false, "No channel is showing that match right now")
        onMain(Unit) { player?.finish(); m.playLive(hits, 0); Unit }
        return reply(true, "Starting on the TV")
    }
}
