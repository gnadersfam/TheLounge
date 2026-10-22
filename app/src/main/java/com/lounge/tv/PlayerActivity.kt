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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Full-screen player. Up / Down on the remote change channel, OK shows what's on, Back returns to the list.
 * If a stream drops it reconnects by itself, and if the server doesn't like .ts it tries .m3u8 (and remembers).
 */
class PlayerActivity : Activity() {

    private lateinit var surface: SurfaceView
    private lateinit var info: TextView
    private lateinit var spinner: ProgressBar
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ext = "ts"
    private var retries = 0
    private var triedAlt = false
    private var epgToken = 0
    private val hideInfo = Runnable { info.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spinner, FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72), Gravity.CENTER))
        info = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.parseColor(Ui.TEXT))
            setBackgroundColor(Color.parseColor("#CC0A0E1A"))
            setPadding(Ui.dp(this@PlayerActivity, 24), Ui.dp(this@PlayerActivity, 16), Ui.dp(this@PlayerActivity, 24), Ui.dp(this@PlayerActivity, 16))
            visibility = View.GONE
        }
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(Ui.dp(this, 48), 0, Ui.dp(this, 48), Ui.dp(this, 40))
        root.addView(info, lp)
        setContentView(root)
        ext = Store.ext(this)
    }

    override fun onStart() {
        super.onStart()
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(Xtream.STREAM_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    retries = 0
                    if (ext != Store.ext(this@PlayerActivity)) Store.setExt(this@PlayerActivity, ext)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onStreamError()
            }
        })
        player = p
        startChannel(resetRetries = true)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun current(): Chan? = Session.channels.getOrNull(Session.index)

    private fun startChannel(resetRetries: Boolean) {
        val ch = current()
        val xt = Session.xt
        val p = player
        if (ch == null || xt == null || p == null) {
            finish()
            return
        }
        if (resetRetries) {
            retries = 0
            triedAlt = false
        }
        showInfo(ch)
        p.setMediaItem(MediaItem.fromUri(xt.streamUrl(ch.id, ext)))
        p.prepare()
        p.playWhenReady = true
    }

    private fun onStreamError() {
        if (!triedAlt) {
            triedAlt = true
            ext = if (ext == "ts") "m3u8" else "ts"
            startChannel(resetRetries = false)
            return
        }
        if (retries < 4) {
            retries++
            info.text = "Reconnecting...  (${current()?.name ?: ""})"
            info.visibility = View.VISIBLE
            handler.postDelayed({ if (player != null) startChannel(resetRetries = false) }, 3000)
            return
        }
        info.text = "This channel won't play right now.  Press Up / Down for another channel, or Back."
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
    }

    private fun showInfo(ch: Chan) {
        info.text = ch.name
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 6000)
        val xt = Session.xt ?: return
        val token = ++epgToken
        Thread {
            val progs = try { xt.shortEpg(ch.id) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (token == epgToken && !isFinishing) {
                    info.text = ch.name + "\n" + Ui.nowNext(progs).first
                }
            }
        }.start()
    }

    private fun change(delta: Int) {
        val n = Session.channels.size
        if (n == 0) return
        Session.index = (Session.index + delta + n) % n
        handler.removeCallbacksAndMessages(null)
        startChannel(resetRetries = true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { change(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { change(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                current()?.let { showInfo(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
