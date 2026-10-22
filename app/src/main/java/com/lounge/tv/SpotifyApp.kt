package com.lounge.tv

import android.content.Context
import android.content.Intent

/** Keeps the Spotify sign-in in the app's private settings. */
class StoreTokens(private val c: Context) : SpTokens {
    override var clientId: String
        get() = Store.spotifyClientId(c)
        set(v) = Store.putStr(c, "spotify_client_id", v)
    override var access: String
        get() = Store.str(c, "sp_access")
        set(v) = Store.putStr(c, "sp_access", v)
    override var refresh: String
        get() = Store.str(c, "sp_refresh")
        set(v) = Store.putStr(c, "sp_refresh", v)
    override var expiresAt: Long
        get() = Store.long(c, "sp_expires", 0L)
        set(v) = Store.putLong(c, "sp_expires", v)
}

/** App-wide helpers: one shared API object, pausing music when a video starts, and opening the Spotify app. */
object Spotify {
    private var api: SpotifyApi? = null

    fun api(c: Context): SpotifyApi {
        val a = api ?: SpotifyApi(StoreTokens(c.applicationContext)).also { api = it }
        return a
    }

    fun connected(c: Context) = Store.spotifyClientId(c).isNotEmpty() && Store.str(c, "sp_refresh").isNotEmpty()

    /** When a video starts, pause the music (if Spotify is connected and the setting is on). */
    fun pauseForVideo(c: Context) {
        if (!connected(c) || !Store.bool(c, "sp_pause_video", true)) return
        val a = api(c)
        Bg.run {
            try {
                val st = a.state()
                if (st != null && st.isPlaying) a.pause()
            } catch (e: Exception) {
                // music keeps playing if Spotify cannot be reached; nothing to report
            }
        }
    }

    /** Opens the official Spotify app on this Fire TV (it must be running before it can be controlled). Returns false if it is not installed. */
    fun openApp(c: Context): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(SpotifyApi.APP_PACKAGE) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
        return true
    }
}
