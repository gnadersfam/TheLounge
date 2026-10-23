package com.lounge.tv

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * A small Twitch picture that sits in the corner while a film, show or channel plays.
 * Its sound is off to begin with; [swapSound] switches between hearing the film and hearing Twitch.
 */
class TwitchWindow(
    private val act: Activity,
    private val root: FrameLayout,
    private val setFilmVolume: (Float) -> Unit
) {
    class Size(val label: String, val w: Int, val h: Int)

    companion object {
        val SIZES = listOf(Size("Small", 320, 180), Size("Medium", 400, 225), Size("Large", 560, 315), Size("Half screen", 640, 360))
    }

    private var player: ExoPlayer? = null
    private var box: FrameLayout? = null
    private var soundTag: TextView? = null
    private var token = 0

    var channel: String = ""
        private set
    var sizeIdx: Int = Store.str(act, "twitch_win_size", "1").toIntOrNull()?.coerceIn(0, SIZES.size - 1) ?: 1
        private set
    var hearTwitch: Boolean = false
        private set

    val active: Boolean get() = box != null

    fun open(login: String, name: String) {
        close(false)
        channel = login
        hearTwitch = false
        setFilmVolume(1f)

        val b = FrameLayout(act)
        b.background = GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(Skin.dp(act, 3), Skin.CYAN)
            cornerRadius = Skin.dp(act, 12).toFloat()
        }
        val pad = Skin.dp(act, 3)
        val sv = SurfaceView(act)
        sv.setZOrderMediaOverlay(true)
        b.addView(sv, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })
        val tag = Skin.text(act, "LIVE  $name", 13f, Skin.TEXT, true).apply {
            background = Skin.card(act, 8, 0xCC07030F.toInt(), 0xCC07030F.toInt())
            setPadding(Skin.dp(act, 10), Skin.dp(act, 4), Skin.dp(act, 10), Skin.dp(act, 4))
        }
        b.addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(Skin.dp(act, 10), Skin.dp(act, 10), 0, 0) })
        val st = Skin.text(act, "Muted", 13f, Skin.AMBER, true).apply {
            background = Skin.card(act, 8, 0xCC07030F.toInt(), 0xCC07030F.toInt())
            setPadding(Skin.dp(act, 10), Skin.dp(act, 4), Skin.dp(act, 10), Skin.dp(act, 4))
        }
        b.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Skin.dp(act, 10), Skin.dp(act, 10)) })
        soundTag = st
        box = b
        root.addView(b, params())

        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2500, 15000, 1000, 2000)
            .setTargetBufferBytes(12 * 1024 * 1024)
            .build()
        val p = ExoPlayer.Builder(act).setLoadControl(lc).build()
        p.setVideoSurfaceView(sv)
        val tp = p.trackSelectionParameters.buildUpon()
        tp.setMaxVideoSize(854, 480) // a small picture does not need more, and it saves memory
        p.trackSelectionParameters = tp.build()
        p.volume = 0f
        player = p
        val mine = ++token
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
                Bg.ui { if (mine == token) soundTag?.text = "Not live" }
            }
        }
    }

    private fun params(): FrameLayout.LayoutParams {
        val s = SIZES[sizeIdx]
        return FrameLayout.LayoutParams(Skin.dp(act, s.w), Skin.dp(act, s.h), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Skin.dp(act, 32)
            bottomMargin = Skin.dp(act, 120)
        }
    }

    fun setSize(i: Int) {
        sizeIdx = i.coerceIn(0, SIZES.size - 1)
        Store.putStr(act, "twitch_win_size", sizeIdx.toString())
        box?.layoutParams = params()
        box?.requestLayout()
    }

    /** Switch between hearing the film and hearing Twitch. */
    fun swapSound() {
        val p = player ?: return
        hearTwitch = !hearTwitch
        val vol = (Store.str(act, "twitch_vol", "80").toIntOrNull() ?: 80).coerceIn(0, 100) / 100f
        p.volume = if (hearTwitch) vol else 0f
        setFilmVolume(if (hearTwitch) 0f else 1f)
        soundTag?.text = if (hearTwitch) "Sound on" else "Muted"
    }

    /** Take the window away. */
    fun close(giveSoundBack: Boolean = true) {
        token++
        player?.release()
        player = null
        box?.let { root.removeView(it) }
        box = null
        soundTag = null
        if (giveSoundBack) setFilmVolume(1f)
        hearTwitch = false
    }
}
