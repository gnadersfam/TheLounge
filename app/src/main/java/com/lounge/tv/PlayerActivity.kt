package com.lounge.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Locale

/**
 * Full-screen player for live channels, movies and episodes.
 * Live: Up/Down change channel, OK shows what's on, Menu = audio / subtitles, Back = guide.
 * Movies and episodes: Left/Right skip, OK pause, Menu = audio / subtitles, Back = list. Your place is remembered.
 */
class PlayerActivity : Activity() {

    private lateinit var surface: SurfaceView
    private lateinit var spinner: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var line1: TextView
    private lateinit var line1Right: TextView
    private lateinit var line2: TextView
    private lateinit var bar: BarView
    private lateinit var line3: TextView
    private lateinit var hintLine: TextView
    private lateinit var menu: LinearLayout
    private lateinit var toast: TextView
    private lateinit var matchBox: TextView
    private lateinit var rootView: FrameLayout
    private var tw: TwitchWindow? = null

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var live = true
    private var ext = "ts"
    private var retries = 0
    private var triedAlt = false
    private var epgToken = 0
    private var lastPos = 0L
    private var tickCount = 0
    private var seekAccum = 0L
    private var waitSecs = 0
    private var pendingSeek = 0L
    private lateinit var slowMsg: TextView

    private val hidePanel = Runnable { panel.visibility = View.GONE; matchBox.visibility = View.GONE }
    private val hideToast = Runnable { toast.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            tickCount++
            val p = player
            if (p != null && p.playbackState == Player.STATE_BUFFERING) {
                waitSecs++
                if (waitSecs >= 20) {
                    slowMsg.text = "Still waiting for the provider. Press Back and pick it again if it never starts."
                    slowMsg.visibility = View.VISIBLE
                } else if (waitSecs >= 5) {
                    slowMsg.text = "Still starting... some providers are slow to begin a title"
                    slowMsg.visibility = View.VISIBLE
                }
            } else {
                waitSecs = 0
                slowMsg.visibility = View.GONE
            }
            if (!live && p != null) {
                if ((p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) && pendingSeek == 0L) lastPos = p.currentPosition
                if (panel.visibility == View.VISIBLE) updateVodInfo()
                if (tickCount % 10 == 0) saveProgress()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        Skin.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(Skin.dp(this, 48), Skin.dp(this, 48), Gravity.CENTER))
        slowMsg = Skin.text(this, "", 18f, Skin.TEXT, true).apply {
            background = Skin.card(this@PlayerActivity, 14, 0xE607030F.toInt(), Skin.LINE)
            setPadding(Skin.dp(this@PlayerActivity, 18), Skin.dp(this@PlayerActivity, 10), Skin.dp(this@PlayerActivity, 18), Skin.dp(this@PlayerActivity, 10))
            visibility = View.GONE
        }
        root.addView(slowMsg, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = Skin.dp(this@PlayerActivity, 100) })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(this@PlayerActivity, 18, 0xE607030F.toInt(), Skin.LINE)
            setPadding(Skin.dp(this@PlayerActivity, 24), Skin.dp(this@PlayerActivity, 16), Skin.dp(this@PlayerActivity, 24), Skin.dp(this@PlayerActivity, 14))
            visibility = View.GONE
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1 = Skin.oneLine(Skin.text(this, "", 26f, Skin.TEXT, true))
        line1Right = Skin.text(this, "", 16f, Skin.MUTED)
        head.addView(line1, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(line1Right)
        panel.addView(head)
        line2 = Skin.oneLine(Skin.text(this, "", 19f, Skin.TEXT)).apply { setPadding(0, Skin.dp(this@PlayerActivity, 6), 0, Skin.dp(this@PlayerActivity, 6)) }
        panel.addView(line2)
        bar = BarView(this)
        panel.addView(bar, LinearLayout.LayoutParams(-1, Skin.dp(this, 6)))
        line3 = Skin.oneLine(Skin.text(this, "", 17f, Skin.MUTED)).apply { setPadding(0, Skin.dp(this@PlayerActivity, 8), 0, 0) }
        panel.addView(line3)
        hintLine = Skin.oneLine(Skin.text(this, "", 13f, Skin.DIM)).apply { setPadding(0, Skin.dp(this@PlayerActivity, 6), 0, 0) }
        panel.addView(hintLine)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(Skin.dp(this@PlayerActivity, 36), 0, Skin.dp(this@PlayerActivity, 36), Skin.dp(this@PlayerActivity, 28))
        })

        toast = Skin.text(this, "", 22f, Skin.TEXT, true).apply {
            background = Skin.card(this@PlayerActivity, 14, 0xE607030F.toInt(), Skin.LINE)
            setPadding(Skin.dp(this@PlayerActivity, 18), Skin.dp(this@PlayerActivity, 10), Skin.dp(this@PlayerActivity, 18), Skin.dp(this@PlayerActivity, 10))
            visibility = View.GONE
        }
        root.addView(toast, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Skin.dp(this@PlayerActivity, 40) })

        matchBox = Skin.text(this, "", 15f, Skin.TEXT).apply {
            background = Skin.card(this@PlayerActivity, 14, 0xE607030F.toInt(), Skin.LINE)
            setPadding(Skin.dp(this@PlayerActivity, 16), Skin.dp(this@PlayerActivity, 12), Skin.dp(this@PlayerActivity, 16), Skin.dp(this@PlayerActivity, 12))
            visibility = View.GONE
        }
        root.addView(matchBox, FrameLayout.LayoutParams(Skin.dp(this, 330), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = Skin.dp(this@PlayerActivity, 30); rightMargin = Skin.dp(this@PlayerActivity, 30)
        })

        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(this@PlayerActivity, 18, 0xF007030F.toInt(), Skin.CYAN)
            setPadding(Skin.dp(this@PlayerActivity, 20), Skin.dp(this@PlayerActivity, 16), Skin.dp(this@PlayerActivity, 20), Skin.dp(this@PlayerActivity, 12))
            visibility = View.GONE
        }
        root.addView(menu, FrameLayout.LayoutParams(Skin.dp(this, 420), -2, Gravity.CENTER))
        setContentView(root)
        rootView = root
        live = Session.live
        ext = Store.ext(this)
    }

    override fun onStart() {
        super.onStart()
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(Xtream.STREAM_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        // keep a sensible amount ready (the default holds well over 100MB, too much for a Fire TV Stick)
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8000, 30000, 1500, 4000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(this)
            .setLoadControl(lc)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
        p.setVideoSurfaceView(surface)
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    retries = 0
                    if (pendingSeek > 0L) {
                        val t = pendingSeek
                        pendingSeek = 0L
                        player?.seekTo(t)
                        lastPos = t
                    }
                    if (live && ext != Store.ext(this@PlayerActivity)) Store.setExt(this@PlayerActivity, ext)
                }
                if (state == Player.STATE_ENDED && !live) {
                    markFinished()
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onStreamError()
            }
        })
        player = p
        Remote.player = this
        handler.postDelayed(tick, 1000)
        if (live) startChannel(true) else startVod()
    }

    override fun onStop() {
        super.onStop()
        if (Remote.player === this) Remote.player = null
        tw?.close()
        tw = null
        saveProgress()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    // ------------------------------------------------------------------ live
    private fun current(): Chan? = Session.channels.getOrNull(Session.index)

    private fun startChannel(resetRetries: Boolean) {
        val ch = current()
        val xt = Session.xt ?: Store.xtream(this)
        val p = player
        if (ch == null || xt == null || p == null) {
            finish()
            return
        }
        Session.xt = xt
        if (Session.curChanId != ch.id) {
            Session.prevChanId = Session.curChanId
            Session.curChanId = ch.id
        }
        if (resetRetries) {
            retries = 0
            triedAlt = false
        }
        showLiveInfo(ch)
        p.setMediaItem(MediaItem.fromUri(xt.streamUrl(ch.id, ext)))
        p.prepare()
        p.playWhenReady = true
        Store.putStr(this, "last_chan", ch.id)
        History.save(this, Hist("live:" + ch.id, "live", ch.name, ch.cat, ch.id, ext, 0, 0, System.currentTimeMillis() / 1000))
    }

    private fun showLiveInfo(ch: Chan) {
        panel.visibility = View.VISIBLE
        if (Session.matchPanel.isNotEmpty() && live) {
            matchBox.text = Session.matchPanel
            matchBox.visibility = View.VISIBLE
        } else matchBox.visibility = View.GONE
        line1.text = ch.name
        line1Right.text = ch.cat
        val num = Session.index + 1
        line1.text = "$num   ${ch.name}"
        line2.text = ""
        line3.text = ""
        bar.pct = 0
        hintLine.text = "Up / Down  change     Left  last channel     OK  info     Menu  audio and subtitles     Back  guide"
        handler.removeCallbacks(hidePanel)
        handler.postDelayed(hidePanel, 6000)
        val token = ++epgToken
        val now = System.currentTimeMillis() / 1000
        val local = if (ch.epg.isNotEmpty()) Session.epg.nowNext(ch.epg, now, 1) else null
        if (local != null && local.first != null) {
            fillNowNext(local.first, local.second.firstOrNull(), now)
            return
        }
        val xt = Session.xt ?: return
        Bg.run {
            val progs = try {
                xt.shortEpg(ch.id)
            } catch (e: Exception) {
                emptyList()
            }
            Bg.ui {
                if (token == epgToken && !isFinishing) {
                    val cur = progs.firstOrNull { it.start <= now && now < it.stop }?.let { EpgProg(it.start, it.stop, it.title) }
                    val nxt = progs.firstOrNull { it.start > now }?.let { EpgProg(it.start, it.stop, it.title) }
                    fillNowNext(cur, nxt, now)
                }
            }
        }
    }

    private fun fillNowNext(cur: EpgProg?, nxt: EpgProg?, now: Long) {
        if (cur != null) {
            line2.text = "Now  " + cur.title + "  (" + Skin.hhmm(cur.start) + " - " + Skin.hhmm(cur.stop) + ")"
            bar.pct = (((now - cur.start) * 100) / Math.max(1L, cur.stop - cur.start)).toInt()
        } else {
            line2.text = "Nothing listed right now"
        }
        line3.text = if (nxt != null) "Next  " + Skin.hhmm(nxt.start) + "  " + nxt.title else ""
    }

    // ---- used by the phone remote (always called on the main thread)
    fun remoteIsLive(): Boolean = live && player != null

    fun remoteChange(delta: Int) {
        if (live && player != null) change(delta)
    }

    fun remoteSeek(ms: Long) {
        val p = player ?: return
        if (live) return
        val d = p.duration
        val t = if (d == C.TIME_UNSET || d <= 0L) ms else ms.coerceIn(0L, Math.max(0L, d - 2000L))
        pendingSeek = 0L
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        p.seekTo(t)
        lastPos = t
        showVodInfo()
        // if the provider's file can't be skipped inside, the player may land at the start: leave a note in Help
        handler.postDelayed({
            val pp = player
            if (pp != null && t > 60000L && pp.currentPosition < 10000L) {
                Crash.log("Skip bar landed near the start", RuntimeException("asked " + t + " ms, got " + pp.currentPosition + " ms, length " + pp.duration + " ms"))
            }
        }, 5000)
    }

    fun remoteInfo(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        val p = player
        m["playing"] = p != null
        m["live"] = live
        if (p != null) {
            if (live) {
                val ch = current()
                m["title"] = ch?.name ?: ""
                m["num"] = Session.index + 1
                val now = System.currentTimeMillis() / 1000
                val on = if (ch != null && ch.epg.isNotEmpty()) Session.epg.nowNext(ch.epg, now, 1).first?.title else null
                m["sub"] = on ?: ""
            } else {
                m["title"] = Session.vodTitle
                m["pos"] = p.currentPosition
                m["dur"] = if (p.duration == C.TIME_UNSET) 0L else p.duration
            }
            m["paused"] = !p.playWhenReady
        }
        return m
    }

    /** Left on a live channel: jump back to the channel you were on before this one. */
    private fun lastChannel() {
        val id = Session.prevChanId
        if (id.isEmpty()) { flash("No previous channel yet"); return }
        val i = Session.channels.indexOfFirst { it.id == id }
        if (i < 0) { flash("The last channel isn't in this list"); return }
        Session.index = i
        handler.removeCallbacks(hidePanel)
        startChannel(true)
    }

    private fun change(delta: Int) {
        val n = Session.channels.size
        if (n == 0) return
        Session.index = (Session.index + delta + n) % n
        handler.removeCallbacks(hidePanel)
        startChannel(true)
    }

    // ------------------------------------------------------------------ movies and episodes
    private fun startVod() {
        val p = player
        if (p == null || Session.vodUrl.isEmpty()) {
            finish()
            return
        }
        lastPos = Session.vodResume
        // open at the start so a picture shows quickly, then jump to where you were (some providers are slow to open mid-file)
        pendingSeek = if (lastPos > 5000L) lastPos else 0L
        p.setMediaItem(MediaItem.fromUri(Session.vodUrl), if (pendingSeek > 0L) 0L else lastPos.coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = true
        showVodInfo()
    }

    private fun showVodInfo() {
        panel.visibility = View.VISIBLE
        line1.text = Session.vodTitle
        line3.text = ""
        hintLine.text = "Left / Right  skip     OK  pause     Menu  audio and subtitles     Back  list"
        updateVodInfo()
        handler.removeCallbacks(hidePanel)
        // always hide by itself a few seconds after it opens (it used to wait for the picture to start playing,
        // which was too late, so it never went away)
        handler.postDelayed(hidePanel, 6000)
    }

    private fun fmt(ms: Long): String {
        val s = Math.max(0L, ms / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, sec) else String.format(Locale.UK, "%d:%02d", m, sec)
    }

    private fun updateVodInfo() {
        val p = player ?: return
        val dur = if (p.duration == C.TIME_UNSET) 0L else p.duration
        val pos = p.currentPosition
        val vs = p.videoSize
        line1Right.text = if (vs.height > 0) {
            val tag = when {
                vs.width >= 3200 || vs.height >= 2000 -> "4K"
                vs.width >= 1800 || vs.height >= 1000 -> "1080p"
                vs.width >= 1200 || vs.height >= 700 -> "720p"
                else -> "SD"
            }
            "" + vs.width + " x " + vs.height + "   " + tag
        } else ""
        line2.text = (if (p.playWhenReady) "" else "Paused   ") + fmt(pos) + (if (dur > 0) "  /  " + fmt(dur) else "")
        bar.pct = if (dur > 0) ((pos * 100) / dur).toInt() else 0
    }

    private fun saveProgress() {
        if (live || pendingSeek > 0L) return
        val p = player
        val dur = if (p == null || p.duration == C.TIME_UNSET) 0L else p.duration
        val pos = if (p != null && p.currentPosition > 0) p.currentPosition else lastPos
        if (Session.vodKey.isEmpty()) return
        val old = History.find(this, Session.vodKey)
        History.save(
            this,
            Hist(
                Session.vodKey, if (Session.vodKey.startsWith("movie:")) "movie" else "episode", Session.vodTitle,
                old?.sub ?: "", old?.id ?: Session.vodKey.substringAfter(':'), old?.ext ?: "", pos, dur,
                System.currentTimeMillis() / 1000, old?.seriesId ?: ""
            )
        )
        Session.vodResume = pos
    }

    private fun markFinished() {
        val old = History.find(this, Session.vodKey) ?: return
        History.save(this, old.copy(pos = old.dur, ts = System.currentTimeMillis() / 1000))
    }

    private fun seek(deltaMs: Long) {
        val p = player ?: return
        val dur = if (p.duration == C.TIME_UNSET) Long.MAX_VALUE else p.duration
        val target = (p.currentPosition + deltaMs).coerceIn(0L, Math.max(0L, dur - 2000))
        p.seekTo(target)
        lastPos = target
        showVodInfo()
    }

    // ------------------------------------------------------------------ errors
    private val retryDelays = longArrayOf(2000, 4000, 8000, 15000, 30000)

    private fun onStreamError() {
        if (live && !triedAlt) {
            triedAlt = true
            ext = if (ext == "ts") "m3u8" else "ts"
            startChannel(false)
            return
        }
        if (retries < retryDelays.size) {
            val wait = retryDelays[retries]
            retries++
            panel.visibility = View.VISIBLE
            line2.text = "Reconnecting...  (try $retries of ${retryDelays.size})"
            handler.removeCallbacks(hidePanel)
            handler.postDelayed({
                if (player != null) {
                    if (live) startChannel(false) else {
                        pendingSeek = 0L
                        player?.setMediaItem(MediaItem.fromUri(Session.vodUrl), lastPos.coerceAtLeast(0L))
                        player?.prepare()
                        player?.playWhenReady = true
                    }
                }
            }, wait)
            return
        }
        panel.visibility = View.VISIBLE
        line2.text = if (live) "This channel won't play right now. Press Up / Down for another channel, or Back."
        else "This title won't play right now. Press Back and try again later."
        handler.removeCallbacks(hidePanel)
    }

    // ------------------------------------------------------------------ audio and subtitle menu
    private fun menuVisible() = menu.visibility == View.VISIBLE

    private fun closeMenu() {
        menu.visibility = View.GONE
        menu.removeAllViews()
    }

    private fun openMenu(heading: String, choices: List<Pair<String, () -> Unit>>) {
        menu.removeAllViews()
        menu.addView(Skin.oneLine(Skin.text(this, heading, 16f, Skin.CYAN, true)).apply { setPadding(0, 0, 0, Skin.dp(this@PlayerActivity, 10)) })
        var first: View? = null
        for ((label, action) in choices) {
            val c = Skin.choice(this, label) { action() }
            menu.addView(c, Skin.gapParams(this, bottomDp = 6))
            if (first == null) first = c
        }
        menu.visibility = View.VISIBLE
        first?.requestFocus()
    }

    private fun mainMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(Pair("Audio") { trackMenu(C.TRACK_TYPE_AUDIO, "Audio") })
        items.add(Pair("Subtitles") { trackMenu(C.TRACK_TYPE_TEXT, "Subtitles") })
        items.add(Pair("Twitch window") { twitchMenu() })
        if (live) items.add(Pair("Restart this channel") { closeMenu(); startChannel(true) })
        else items.add(Pair("Start over") { closeMenu(); player?.seekTo(0); lastPos = 0; showVodInfo() })
        items.add(Pair("Close") { closeMenu() })
        openMenu(if (live) "Live TV" else Session.vodTitle, items)
    }

    // ------------------------------------------------------------------ small Twitch window beside the picture
    private fun twitchMenu() {
        if (!Twitch.loggedIn(this)) {
            openMenu("Twitch window", listOf(
                Pair("Log in on the Twitch page first") { closeMenu() },
                Pair("Back") { mainMenu() }
            ))
            return
        }
        val w = tw
        if (w != null && w.active) {
            val items = ArrayList<Pair<String, () -> Unit>>()
            for ((i, s) in TwitchWindow.SIZES.withIndex()) {
                val mark = if (i == w.sizeIdx) "   (on)" else ""
                items.add(Pair("Size: " + s.label + mark) { w.setSize(i); closeMenu() })
            }
            items.add(Pair(if (w.hearTwitch) "Hear the film again" else "Hear Twitch instead") { w.swapSound(); closeMenu() })
            items.add(Pair("Change channel") { pickTwitch() })
            items.add(Pair("Hide Twitch window") { w.close(); tw = null; closeMenu() })
            items.add(Pair("Back") { mainMenu() })
            openMenu("Twitch window", items)
        } else {
            pickTwitch()
        }
    }

    private fun pickTwitch() {
        openMenu("Twitch window", listOf(Pair("Loading channels...") { }))
        Bg.run {
            val list: List<Twitch.Stream> = try {
                val f = Twitch.followed(this)
                if (f.isNotEmpty()) f else Twitch.top(this)
            } catch (e: Throwable) {
                emptyList()
            }
            Bg.ui {
                if (menuVisible()) {
                    val items = ArrayList<Pair<String, () -> Unit>>()
                    for (s in list.take(8)) {
                        val game = if (s.game.isNotEmpty()) "  -  " + s.game else ""
                        items.add(Pair(s.name + game) { openTwitchWindow(s); closeMenu() })
                    }
                    if (list.isEmpty()) items.add(Pair("No channels came back") { closeMenu() })
                    items.add(Pair("Back") { mainMenu() })
                    openMenu("Twitch window", items)
                }
            }
        }
    }

    private fun openTwitchWindow(s: Twitch.Stream) {
        var w = tw
        if (w == null) {
            w = TwitchWindow(this, rootView) { v -> player?.volume = v }
            tw = w
        }
        w.open(s.login, s.name)
    }

    private fun trackLabel(lang: String?, label: String?, channels: Int, n: Int): String {
        val l = if (!lang.isNullOrBlank() && lang != "und") Locale(lang).displayLanguage.ifBlank { lang } else ""
        val base = l.ifEmpty { label ?: "Track $n" }
        val ch = when {
            channels >= 6 -> " (5.1)"
            channels == 2 -> " (stereo)"
            else -> ""
        }
        return base + ch
    }

    private fun trackMenu(type: Int, heading: String) {
        val p = player ?: return
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (type == C.TRACK_TYPE_TEXT) {
            choices.add(Pair("Off") {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                closeMenu()
            })
        }
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val name = trackLabel(f.language, f.label, f.channelCount, n) + if (g.isTrackSelected(i)) "   (on)" else ""
                val group = g
                val idx = i
                choices.add(Pair(name) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, idx))
                        .build()
                    closeMenu()
                })
            }
        }
        if (n == 0) choices.add(Pair("Nothing to choose from yet") { closeMenu() })
        choices.add(Pair("Back") { mainMenu() })
        openMenu(heading, choices)
    }

    // ------------------------------------------------------------------ keys
    private fun flash(text: String) {
        toast.text = text
        toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 1500)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menuVisible()) {
            closeMenu()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (menuVisible()) closeMenu() else mainMenu()
            return true
        }
        if (menuVisible()) return super.onKeyDown(keyCode, event) // the menu's own buttons use the remote
        val rep = event?.repeatCount ?: 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (live) change(-1) else showVodInfo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (live) change(1) else panel.visibility = View.GONE
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (live) {
                    if (rep == 0) lastChannel()
                } else {
                    seek(if (rep > 6) -60000L else -10000L)
                    flash("-" + (if (rep > 6) "60" else "10") + " s")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!live) {
                    seek(if (rep > 6) 60000L else 10000L)
                    flash("+" + (if (rep > 6) "60" else "10") + " s")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (rep == 0) {
                    if (live) {
                        current()?.let { showLiveInfo(it) }
                    } else {
                        val p = player
                        if (p != null) p.playWhenReady = !p.playWhenReady
                        showVodInfo()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.playWhenReady = true; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.playWhenReady = false; if (!live) showVodInfo(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
