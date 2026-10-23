package com.lounge.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Watching one Twitch channel full screen: picture, a control bar (pause, mute, volume, quality, chat) and the chat beside it.
 * Any button on the remote brings the controls up. Back leaves.
 */
class TwitchPlayerActivity : Activity() {

    private lateinit var surface: SurfaceView
    private lateinit var spinner: ProgressBar
    private lateinit var controls: LinearLayout
    private lateinit var info: LinearLayout
    private lateinit var message: TextView
    private lateinit var chatPanel: LinearLayout
    private lateinit var chatList: LinearLayout
    private lateinit var menu: LinearLayout
    private lateinit var playBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var qualityBtn: Button
    private lateinit var chatBtn: Button
    private lateinit var volBox: LinearLayout
    private lateinit var volFill: View
    private lateinit var volText: TextView

    private var player: ExoPlayer? = null
    private var chat: TwitchChat? = null
    private val handler = Handler(Looper.getMainLooper())
    private var login = ""
    private var token = 0
    private var volume = 80
    private var muted = false
    private var quality = 720
    private var chatOn = true

    private val hideControls = Runnable { controls.visibility = View.GONE; info.visibility = View.GONE }

    private val QUALITIES = listOf(0 to "Auto (best)", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        Skin.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        login = intent.getStringExtra("login").orEmpty()
        val name = intent.getStringExtra("name").orEmpty().ifEmpty { login }
        val game = intent.getStringExtra("game").orEmpty()
        val viewers = intent.getIntExtra("viewers", 0)
        volume = Store.str(this, "twitch_vol", "80").toIntOrNull()?.coerceIn(0, 100) ?: 80
        quality = Store.str(this, "twitch_q", "720").toIntOrNull() ?: 720
        chatOn = Store.bool(this, "twitch_chat", true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }
        val video = FrameLayout(this)
        root.addView(video, LinearLayout.LayoutParams(0, -1, 1f))
        surface = SurfaceView(this)
        video.addView(surface, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(this).apply { visibility = View.VISIBLE }
        video.addView(spinner, FrameLayout.LayoutParams(Skin.dp(this, 48), Skin.dp(this, 48), Gravity.CENTER))
        message = Skin.text(this, "", 20f, Skin.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = Skin.card(this@TwitchPlayerActivity, 14, 0xE607030F.toInt(), Skin.LINE)
            setPadding(Skin.dp(this@TwitchPlayerActivity, 22), Skin.dp(this@TwitchPlayerActivity, 14), Skin.dp(this@TwitchPlayerActivity, 22), Skin.dp(this@TwitchPlayerActivity, 14))
            visibility = View.GONE
        }
        video.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // who is on, top left
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(this@TwitchPlayerActivity, 12, 0xB807030F.toInt(), Skin.LINE)
            setPadding(Skin.dp(this@TwitchPlayerActivity, 16), Skin.dp(this@TwitchPlayerActivity, 10), Skin.dp(this@TwitchPlayerActivity, 18), Skin.dp(this@TwitchPlayerActivity, 10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Skin.text(this, "LIVE", 12f, Color.WHITE, true).apply {
            background = Skin.card(this@TwitchPlayerActivity, 5, 0xFFE0245E.toInt(), 0xFFE0245E.toInt())
            setPadding(Skin.dp(this@TwitchPlayerActivity, 7), Skin.dp(this@TwitchPlayerActivity, 2), Skin.dp(this@TwitchPlayerActivity, 7), Skin.dp(this@TwitchPlayerActivity, 2))
        })
        head.addView(Skin.text(this, name, 20f, Skin.TEXT, true).apply { setPadding(Skin.dp(this@TwitchPlayerActivity, 10), 0, 0, 0) })
        info.addView(head)
        val sub = (if (game.isNotEmpty()) game else "Twitch") + (if (viewers > 0) "  -  " + viewers + " watching" else "")
        info.addView(Skin.oneLine(Skin.text(this, sub, 14f, Skin.MUTED)))
        video.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = Skin.dp(this@TwitchPlayerActivity, 24); leftMargin = Skin.dp(this@TwitchPlayerActivity, 28)
        })

        // control bar, bottom
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Skin.dp(this@TwitchPlayerActivity, 28), Skin.dp(this@TwitchPlayerActivity, 36), Skin.dp(this@TwitchPlayerActivity, 28), Skin.dp(this@TwitchPlayerActivity, 22))
            setBackgroundColor(0xB807030F.toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = Skin.button(this, "Pause") { togglePlay() }
        muteBtn = Skin.button(this, "Mute") { toggleMute() }
        qualityBtn = Skin.button(this, "") { qualityMenu() }
        chatBtn = Skin.button(this, "") { toggleChat() }
        volBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(this@TwitchPlayerActivity, 12)
            isFocusable = true
            isClickable = true
            setPadding(Skin.dp(this@TwitchPlayerActivity, 16), Skin.dp(this@TwitchPlayerActivity, 8), Skin.dp(this@TwitchPlayerActivity, 16), Skin.dp(this@TwitchPlayerActivity, 10))
        }
        volText = Skin.text(this, "", 13f, Skin.MUTED)
        volBox.addView(volText)
        val track = FrameLayout(this).apply { setBackgroundColor(Skin.LINE) }
        volFill = View(this).apply { setBackgroundColor(Skin.CYAN) }
        track.addView(volFill, FrameLayout.LayoutParams(0, -1))
        volBox.addView(track, LinearLayout.LayoutParams(Skin.dp(this, 240), Skin.dp(this, 8)).apply { topMargin = Skin.dp(this@TwitchPlayerActivity, 6) })
        val gap = Skin.dp(this, 12)
        row.addView(playBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap })
        row.addView(muteBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap })
        row.addView(volBox, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(qualityBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap })
        row.addView(chatBtn, LinearLayout.LayoutParams(-2, -2))
        controls.addView(row)
        controls.addView(Skin.text(this, "Left / Right on the volume bar changes it.  Your TV's own volume stays on the TV remote.  Back leaves.", 12f, Skin.DIM).apply {
            setPadding(0, Skin.dp(this@TwitchPlayerActivity, 8), 0, 0)
        })
        video.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(this@TwitchPlayerActivity, 18, 0xF007030F.toInt(), Skin.CYAN)
            setPadding(Skin.dp(this@TwitchPlayerActivity, 20), Skin.dp(this@TwitchPlayerActivity, 16), Skin.dp(this@TwitchPlayerActivity, 20), Skin.dp(this@TwitchPlayerActivity, 12))
            visibility = View.GONE
        }
        video.addView(menu, FrameLayout.LayoutParams(Skin.dp(this, 360), -2, Gravity.CENTER))

        // chat, right
        chatPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF120A24.toInt())
        }
        chatPanel.addView(Skin.text(this, "CHAT", 13f, Skin.DIM, true).apply {
            letterSpacing = 0.15f
            setPadding(Skin.dp(this@TwitchPlayerActivity, 16), Skin.dp(this@TwitchPlayerActivity, 14), 0, Skin.dp(this@TwitchPlayerActivity, 10))
        })
        val chatFrame = FrameLayout(this)
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatList.setPadding(Skin.dp(this, 14), 0, Skin.dp(this, 12), Skin.dp(this, 12))
        chatFrame.addView(chatList, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        chatPanel.addView(chatFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(chatPanel, LinearLayout.LayoutParams(Skin.dp(this, 320), -1))
        setContentView(root)

        updateButtons()
        showControls()
        playBtn.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2500, 15000, 1000, 2000)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .build()
        val p = ExoPlayer.Builder(this).setLoadControl(lc).build()
        p.setVideoSurfaceView(surface)
        applyQuality(p)
        p.volume = if (muted) 0f else volume / 100f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) message.visibility = View.GONE
                if (state == Player.STATE_ENDED) say("The stream has ended. Press Back.")
            }

            override fun onPlayerError(error: PlaybackException) {
                say("This channel stopped or is not live right now. Press Back.")
            }
        })
        player = p
        startStream()
        if (chatOn) startChat()
    }

    override fun onStop() {
        super.onStop()
        token++
        handler.removeCallbacksAndMessages(null)
        chat?.stop()
        chat = null
        player?.release()
        player = null
        finish() // leaving the screen ends the stream, so nothing keeps playing out of sight
    }

    private fun startStream() {
        val mine = ++token
        spinner.visibility = View.VISIBLE
        message.visibility = View.GONE
        Bg.run {
            try {
                val url = Twitch.streamUrl(login)
                Bg.ui {
                    if (mine == token) {
                        player?.setMediaItem(MediaItem.fromUri(url))
                        player?.prepare()
                        player?.playWhenReady = true
                    }
                }
            } catch (e: Throwable) {
                Bg.ui { if (mine == token) say(if ((e.message ?: "").contains("not live")) "That channel is not live right now. Press Back." else "Could not start the stream (" + (e.message ?: "unknown") + "). Press Back.") }
            }
        }
    }

    private fun say(text: String) {
        spinner.visibility = View.GONE
        message.text = text
        message.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ chat
    private fun startChat() {
        chat?.stop()
        val c = TwitchChat(login) { name, color, text -> Bg.ui { addChat(name, color, text) } }
        chat = c
        c.start()
    }

    private fun addChat(name: String, color: Int, text: String) {
        if (!chatOn) return
        val sb = SpannableStringBuilder()
        sb.append(name)
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ")
        sb.append(text.take(220))
        val tv = Skin.text(this, "", 14f, Skin.TEXT)
        tv.text = sb
        chatList.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Skin.dp(this@TwitchPlayerActivity, 6) })
        while (chatList.childCount > 40) chatList.removeViewAt(0)
    }

    private fun toggleChat() {
        chatOn = !chatOn
        Store.putBool(this, "twitch_chat", chatOn)
        chatPanel.visibility = if (chatOn) View.VISIBLE else View.GONE
        if (chatOn) startChat() else { chat?.stop(); chat = null; chatList.removeAllViews() }
        updateButtons()
        showControls()
    }

    // ------------------------------------------------------------------ controls
    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        updateButtons()
        showControls()
    }

    private fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else volume / 100f
        updateButtons()
        showControls()
    }

    private fun changeVolume(delta: Int) {
        volume = (volume + delta).coerceIn(0, 100)
        if (volume > 0) muted = false
        Store.putStr(this, "twitch_vol", volume.toString())
        player?.volume = if (muted) 0f else volume / 100f
        updateButtons()
        showControls()
    }

    private fun applyQuality(p: ExoPlayer) {
        val b = p.trackSelectionParameters.buildUpon()
        if (quality <= 0) b.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE) else b.setMaxVideoSize(quality * 16 / 9 + 2, quality)
        p.trackSelectionParameters = b.build()
    }

    private fun qualityLabel() = QUALITIES.firstOrNull { it.first == quality }?.second ?: "720p"

    private fun updateButtons() {
        playBtn.text = if (player?.playWhenReady == false) "Play" else "Pause"
        muteBtn.text = if (muted) "Sound on" else "Mute"
        qualityBtn.text = "Quality  " + qualityLabel()
        chatBtn.text = if (chatOn) "Chat on" else "Chat off"
        val shown = if (muted) 0 else volume
        volText.text = if (muted) "Twitch volume  muted" else "Twitch volume  $volume%"
        val w = Skin.dp(this, 240) * shown / 100
        volFill.layoutParams = FrameLayout.LayoutParams(w, -1)
        volFill.requestLayout()
        chatPanel.visibility = if (chatOn) View.VISIBLE else View.GONE
    }

    private fun showControls() {
        val wasHidden = controls.visibility != View.VISIBLE
        controls.visibility = View.VISIBLE
        info.visibility = View.VISIBLE
        if (wasHidden && !menuVisible()) playBtn.requestFocus()
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 7000)
    }

    private fun menuVisible() = menu.visibility == View.VISIBLE

    private fun closeMenu() {
        menu.visibility = View.GONE
        menu.removeAllViews()
        qualityBtn.requestFocus()
    }

    private fun qualityMenu() {
        menu.removeAllViews()
        menu.addView(Skin.oneLine(Skin.text(this, "Quality", 16f, Skin.CYAN, true)).apply { setPadding(0, 0, 0, Skin.dp(this@TwitchPlayerActivity, 10)) })
        var first: View? = null
        for ((q, label) in QUALITIES) {
            val on = if (q == quality) "   (on)" else ""
            val c = Skin.choice(this, label + on) {
                quality = q
                Store.putStr(this, "twitch_q", q.toString())
                player?.let { applyQuality(it) }
                closeMenu()
                updateButtons()
                showControls()
            }
            menu.addView(c, Skin.gapParams(this, bottomDp = 6))
            if (first == null) first = c
        }
        menu.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        first?.requestFocus()
    }

    // ------------------------------------------------------------------ keys
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
        if (menuVisible()) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.playWhenReady = true; updateButtons(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.playWhenReady = false; updateButtons(); return true }
        }
        val isKey = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MENU
        if (isKey && controls.visibility != View.VISIBLE) {
            showControls() // first press only wakes the controls
            return true
        }
        if (isKey) {
            handler.removeCallbacks(hideControls)
            handler.postDelayed(hideControls, 7000)
        }
        if (volBox.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { changeVolume(-5); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { changeVolume(5); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
