package com.lounge.tv

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Music: The Lounge is a remote control for the official Spotify app on this Fire TV (Premium needed).
 * The music itself plays in the Spotify app; this page picks playlists and songs, controls it, and shows what is playing.
 */
class MusicScreen(act: MainActivity) : Screen(act) {

    private val outer = LinearLayout(act)
    private val handler = Handler(Looper.getMainLooper())
    private var server: PairServer? = null
    private var verifier = ""
    private var nonce = ""

    // player view parts
    private val art = ImageView(act)
    private val tTitle = Skin.oneLine(Skin.text(act, "Nothing playing", 26f, Skin.TEXT, true))
    private val tArtist = Skin.oneLine(Skin.text(act, "", 18f, Skin.CYAN))
    private val tDevice = Skin.oneLine(Skin.text(act, "", 14f, Skin.MUTED))
    private val bar = BarView(act)
    private val msg = Skin.text(act, "", 15f, Skin.AMBER)
    private val listCol = LinearLayout(act)
    private val playBtn = Skin.button(act, "Play") { toggle() }

    private var tab = "playlists"
    private var last: SpState? = null
    private var showingPlayer = false
    private var lastImage = ""

    private val poll = object : Runnable {
        override fun run() {
            if (visibility == View.VISIBLE && showingPlayer) refreshState()
            handler.postDelayed(this, 5000)
        }
    }

    init {
        outer.orientation = LinearLayout.VERTICAL
        addView(outer, LayoutParams(-1, -1))
    }

    private val api get() = Spotify.api(act)

    // ------------------------------------------------------------------ page life
    override fun onShow() {
        build()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 500)
    }

    override fun onHide() {
        stopServer()
        handler.removeCallbacks(poll)
    }

    override fun onKeysChanged() {
        if (visibility == View.VISIBLE) build()
    }

    override fun onActivityResume() {
        if (visibility == View.VISIBLE && showingPlayer) refreshState()
    }

    override fun focusFirst() {
        val l = ArrayList<View>()
        outer.addFocusables(l, View.FOCUS_DOWN)
        l.firstOrNull { it.isShown }?.requestFocus()
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun clear(): LinearLayout {
        outer.removeAllViews()
        outer.setPadding(Skin.dp(act, 36), Skin.dp(act, 24), Skin.dp(act, 30), Skin.dp(act, 16))
        showingPlayer = false
        return outer
    }

    private fun build() {
        stopServer()
        when {
            Store.spotifyClientId(act).isEmpty() -> setupView()
            !Spotify.connected(act) -> connectView()
            else -> playerView()
        }
        post { focusFirst() }
    }

    // ------------------------------------------------------------------ set up and sign in
    private fun setupView() {
        val c = clear()
        c.addView(Skin.text(act, "Music", 28f, Skin.TEXT, true))
        c.addView(Skin.text(act, "Control the Spotify app on this Fire TV from here. You need Spotify Premium and a free Spotify developer app (a one-time job):", 16f, Skin.MUTED).apply { setPadding(0, Skin.dp(act, 8), 0, Skin.dp(act, 16)) })
        val steps = listOf(
            "1.  On your phone open  developer.spotify.com/dashboard  and sign in with your Premium account.",
            "2.  Press Create app. Any name. For Redirect URI type exactly:",
            "      " + SpotifyApi.REDIRECT,
            "3.  Tick \"Web API\", agree and save. Open the app's Settings, then User management, and add your own Spotify email.",
            "4.  Copy the Client ID. Here on the TV choose Pair with phone below, paste it in the Spotify box and press Save."
        )
        for (s in steps) c.addView(Skin.text(act, s, 16f, if (s.startsWith("      ")) Skin.CYAN else Skin.TEXT).apply { setPadding(0, 0, 0, Skin.dp(act, 8)) })
        c.addView(Skin.button(act, "Pair with phone") { act.open("pair") }, Skin.gapParams(act, -2, -2, 0).apply { topMargin = Skin.dp(act, 14) })
    }

    private fun connectView() {
        val c = clear()
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Skin.text(act, "Connect Spotify", 28f, Skin.TEXT, true))
        left.addView(Skin.text(act, "1.  Phone on the same Wi-Fi as this Firestick.", 17f).apply { setPadding(0, Skin.dp(act, 14), 0, 0) })
        left.addView(Skin.text(act, "2.  Scan the code, sign in to Spotify and press Agree.", 17f).apply { setPadding(0, Skin.dp(act, 8), 0, 0) })
        left.addView(Skin.text(act, "3.  The phone says it is connected, and this page changes by itself.", 17f).apply { setPadding(0, Skin.dp(act, 8), 0, Skin.dp(act, 14)) })
        val status = Skin.text(act, "", 16f, Skin.AMBER)
        left.addView(status)
        left.addView(Skin.button(act, "Try again") { build() }, Skin.gapParams(act, -2, -2, 0).apply { topMargin = Skin.dp(act, 10) })
        val qr = ImageView(act)
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(qr, LinearLayout.LayoutParams(Skin.dp(act, 330), Skin.dp(act, 330)).apply { leftMargin = Skin.dp(act, 30) })
        c.addView(row, LinearLayout.LayoutParams(-1, -1))

        val s = PairServer(act)
        if (!s.start()) { status.text = "Could not start the connection page. Check the Firestick is on Wi-Fi, then press Try again."; return }
        val ip = PairServer.localIp()
        if (ip == null) { status.text = "Could not find this Firestick's Wi-Fi address."; s.stop(); return }
        server = s
        verifier = SpotifyApi.newVerifier()
        nonce = SpotifyApi.newNonce()
        val state = "$ip:${s.port}.$nonce"
        val url = SpotifyApi.authUrl(Store.spotifyClientId(act), SpotifyApi.challenge(verifier), state)
        qr.setImageBitmap(Qr.make(url, 330))
        s.route = route@{ path, q, _ ->
            if (path != "/spotify") return@route null
            val code = q["code"].orEmpty()
            if (q["state"] != state || code.isEmpty()) {
                val why = q["error"].orEmpty()
                Bg.ui { status.text = if (why.isNotEmpty()) "Spotify said: $why" else "That sign-in did not match. Press Try again." }
                return@route reply("Not connected", if (why.isNotEmpty()) "Spotify said: $why. Go back to the TV and try again." else "That did not match the TV. Press Try again on the TV and scan the new code.")
            }
            Bg.run {
                try {
                    api.exchange(code, verifier)
                    Bg.ui { stopServer(); build() }
                } catch (e: Exception) {
                    Bg.ui { status.text = "Could not finish connecting: ${e.message}" }
                }
            }
            reply("Connected", "Spotify is connected to The Lounge. You can close this page and look at the TV.")
        }
    }

    private fun reply(title: String, body: String): String =
        "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>The Lounge</title>" +
            "<style>body{margin:0;padding:24px;background:#07030F;color:#F5EEFF;font:20px system-ui,sans-serif}h1{color:#19F0FF}p{color:#C9B8F0}</style></head>" +
            "<body><h1>$title</h1><p>$body</p></body></html>"

    // ------------------------------------------------------------------ player
    private fun playerView() {
        val c = clear()
        showingPlayer = true
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(art, LinearLayout.LayoutParams(Skin.dp(act, 130), Skin.dp(act, 130)))
        val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Skin.dp(act, 20), 0, 0, 0) }
        txt.addView(tTitle); txt.addView(tArtist); txt.addView(tDevice)
        txt.addView(bar, LinearLayout.LayoutParams(-1, Skin.dp(act, 6)).apply { topMargin = Skin.dp(act, 10) })
        top.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top, LinearLayout.LayoutParams(-1, -2))

        val ctl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Skin.dp(act, 12), 0, Skin.dp(act, 4)) }
        fun b(label: String, f: () -> Unit): View = Skin.button(act, label, f).also { ctl.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 8) }) }
        b("Previous") { command { api.previous() } }
        ctl.addView(playBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 8) })
        b("Next") { command { api.next() } }
        b("Vol -") { volume(-10) }
        b("Vol +") { volume(10) }
        b("Shuffle") { command { api.shuffle(!(last?.shuffle ?: false)) } }
        b("Devices") { devicesMenu() }
        b("Open Spotify") { if (!Spotify.openApp(act)) say("The Spotify app is not installed on this Fire TV.") }
        c.addView(ctl)
        c.addView(msg)

        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Skin.dp(act, 6), 0, Skin.dp(act, 6)) }
        for ((k, label) in listOf("playlists" to "Playlists", "search" to "Search", "queue" to "Queue")) {
            tabs.addView(Skin.button(act, (if (tab == k) "● " else "") + label) {
                if (k == "search") { tab = k; searchBox() } else { tab = k; loadTab() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 8) })
        }
        c.addView(tabs)
        listCol.orientation = LinearLayout.VERTICAL
        listCol.removeAllViews()
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(listCol, LinearLayout.LayoutParams(-1, -2)) }
        c.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        last?.let { show(it) }
        refreshState()
        loadTab()
    }

    private fun say(s: String) {
        msg.text = s
    }

    private fun explain(e: Exception) {
        val se = e as? SpotifyError
        when {
            se == null -> say("Spotify could not be reached (${e.message}).")
            se.noDevice -> say("Spotify is not running on this Fire TV yet. Press Open Spotify, wait a few seconds, then choose Devices.")
            se.premium -> say("Spotify says this needs Premium (the app owner's account must be Premium).")
            se.code == 401 -> { say("Spotify sign-in expired."); build() }
            se.code == 429 -> say("Spotify is busy. Try again in a moment.")
            else -> say("Spotify said no (${se.code}).")
        }
    }

    private fun command(f: () -> Unit) {
        Bg.run {
            try {
                f()
                Bg.ui { say("") }
                Thread.sleep(600)
                refreshState()
            } catch (e: Exception) {
                Bg.ui { explain(e) }
            }
        }
    }

    private fun refreshState() {
        Bg.run {
            try {
                val st = api.state()
                Bg.ui { last = st; if (st != null) { say(""); show(st) } else showNothing() }
            } catch (e: Exception) {
                Bg.ui { explain(e) }
            }
        }
    }

    private fun showNothing() {
        last = null
        tTitle.text = "Nothing playing"
        tArtist.text = "Choose Devices, or pick a playlist"
        tDevice.text = ""
        bar.pct = 0
        playBtn.text = "Play"
        art.setImageBitmap(null)
        lastImage = ""
    }

    private fun show(st: SpState) {
        val tr = st.track
        tTitle.text = tr?.name ?: "Nothing playing"
        tArtist.text = tr?.artists ?: ""
        tDevice.text = "on ${st.deviceName}   volume ${st.volume}%" + if (st.shuffle) "   shuffle on" else ""
        bar.pct = if (tr != null && tr.durationMs > 0) ((st.progressMs * 100) / tr.durationMs).toInt() else 0
        playBtn.text = if (st.isPlaying) "Pause" else "Play"
        val img = tr?.image ?: ""
        if (img.isNotEmpty() && img != lastImage) {
            lastImage = img
            Img.load(art, img, Skin.dp(act, 130), Skin.dp(act, 130))
        }
    }

    private fun toggle() {
        val st = last
        if (st == null) { devicesMenu(); return }
        command { if (st.isPlaying) api.pause() else api.resume() }
    }

    private fun volume(delta: Int) {
        val st = last
        if (st == null) { say("Nothing is playing yet."); return }
        command { api.volume(st.volume + delta, st.deviceId) }
    }

    /** The Spotify app on this Fire TV, as Spotify sees it. */
    private fun withDevice(f: (String) -> Unit) {
        Bg.run {
            try {
                val id = last?.deviceId?.takeIf { it.isNotEmpty() }
                val dev = if (id != null) id else {
                    val list = api.devices()
                    (list.firstOrNull { it.active } ?: list.firstOrNull { it.name.contains("Fire", true) || it.type.equals("TV", true) } ?: list.firstOrNull())?.id
                }
                if (dev == null) { Bg.ui { say("Spotify is not running on this Fire TV yet. Press Open Spotify, wait a few seconds, then try again.") }; return@run }
                f(dev)
                Bg.ui { say("") }
                Thread.sleep(800)
                refreshState()
            } catch (e: Exception) {
                Bg.ui { explain(e) }
            }
        }
    }

    private fun devicesMenu() {
        Bg.run {
            try {
                val list = api.devices()
                Bg.ui {
                    if (list.isEmpty()) {
                        say("No Spotify devices found. Press Open Spotify, wait a few seconds, then choose Devices again.")
                    } else {
                        tab = "devices"
                        listCol.removeAllViews()
                        listCol.addView(Skin.text(act, "Choose where the music plays", 18f, Skin.CYAN, true).apply { setPadding(0, 0, 0, Skin.dp(act, 8)) })
                        for (d in list) {
                            listCol.addView(Skin.choice(act, d.name + if (d.active) "   (playing here)" else "", "${d.type}   volume ${d.volume}%") {
                                command { api.transfer(d.id, true) }
                            }, Skin.gapParams(act, bottomDp = 6))
                        }
                        listCol.getChildAt(1)?.requestFocus()
                    }
                }
            } catch (e: Exception) {
                Bg.ui { explain(e) }
            }
        }
    }

    // ------------------------------------------------------------------ lists
    private fun searchBox() {
        popup.search("Search songs (up to 10 results)", "") { q ->
            if (q.isNotEmpty()) loadSearch(q) else loadTab()
        }
    }

    private val overlay = android.widget.FrameLayout(act)
    private val popup = Popup(act, overlay, outer)

    init {
        overlay.visibility = View.GONE
        addView(overlay, LayoutParams(-1, -1))
    }

    override fun onBack(): Boolean {
        if (popup.shown) {
            popup.hide()
            return true
        }
        return false
    }

    private fun loadTab() {
        when (tab) {
            "playlists" -> loadPlaylists()
            "queue" -> loadQueue()
            "search" -> {}
        }
    }

    private fun loadPlaylists() {
        listCol.removeAllViews()
        listCol.addView(Skin.text(act, "Loading your playlists...", 15f, Skin.MUTED))
        Bg.run {
            try {
                val list = api.playlists()
                Bg.ui {
                    if (tab != "playlists") return@ui
                    listCol.removeAllViews()
                    if (list.isEmpty()) listCol.addView(Skin.text(act, "No playlists found on this Spotify account.", 15f, Skin.MUTED))
                    for (p in list) listCol.addView(Skin.choice(act, p.name, p.owner) { withDevice { d -> api.playContext(p.uri, d) } }, Skin.gapParams(act, bottomDp = 6))
                }
            } catch (e: Exception) {
                Bg.ui { explain(e) }
            }
        }
    }

    private fun loadSearch(q: String) {
        listCol.removeAllViews()
        listCol.addView(Skin.text(act, "Searching...", 15f, Skin.MUTED))
        Bg.run {
            try {
                val list = api.search(q)
                Bg.ui {
                    if (tab != "search") return@ui
                    listCol.removeAllViews()
                    if (list.isEmpty()) listCol.addView(Skin.text(act, "Nothing found for \"$q\".", 15f, Skin.MUTED))
                    for (t in list) listCol.addView(Skin.choice(act, t.name, t.artists + "  -  " + t.album) {
                        withDevice { d -> api.playTrack(t.uri, d) }
                    }, Skin.gapParams(act, bottomDp = 6))
                    listCol.getChildAt(0)?.requestFocus()
                }
            } catch (e: Exception) {
                Bg.ui { explain(e) }
            }
        }
    }

    private fun loadQueue() {
        listCol.removeAllViews()
        listCol.addView(Skin.text(act, "Loading the queue...", 15f, Skin.MUTED))
        Bg.run {
            try {
                val list = api.queue()
                Bg.ui {
                    if (tab != "queue") return@ui
                    listCol.removeAllViews()
                    if (list.isEmpty()) listCol.addView(Skin.text(act, "The queue is empty.", 15f, Skin.MUTED))
                    for (t in list.take(30)) listCol.addView(Skin.choice(act, t.name, t.artists) {}, Skin.gapParams(act, bottomDp = 6))
                }
            } catch (e: Exception) {
                Bg.ui { explain(e) }
            }
        }
    }
}
