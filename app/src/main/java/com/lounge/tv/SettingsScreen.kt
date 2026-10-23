package com.lounge.tv

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings: pair with phone, IPTV account, Live TV options, Spotify, updates and where to find the backup. */
class SettingsScreen(act: MainActivity) : Screen(act) {

    private val col = LinearLayout(act)
    private val scroll = ScrollView(act)
    private var updateNote = ""

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Skin.dp(act, 40), Skin.dp(act, 26), Skin.dp(act, 34), Skin.dp(act, 30))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
    }

    override fun onShow() = build()

    override fun onKeysChanged() {
        if (visibility == View.VISIBLE) build()
    }

    override fun focusFirst() {
        val l = ArrayList<View>()
        col.addFocusables(l, View.FOCUS_DOWN)
        (l.firstOrNull { it.isShown } ?: return).requestFocus()
    }

    private fun h2(s: String): TextView = Skin.text(act, s, 19f, Skin.CYAN, true).apply { setPadding(0, Skin.dp(act, 16), 0, Skin.dp(act, 6)) }
    private fun note(s: String, color: Int = Skin.MUTED): TextView = Skin.text(act, s, 14f, color).apply { setPadding(Skin.dp(act, 4), 0, 0, Skin.dp(act, 4)) }
    private fun item(label: String, sub: String = "", go: () -> Unit) = col.addView(Skin.choice(act, label, sub, go), Skin.gapParams(act, bottomDp = 6))

    private fun date(sec: Long) = SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(sec * 1000))

    private fun build() {
        col.removeAllViews()
        col.addView(Skin.text(act, "SETTINGS", 40f, Skin.TEXT, true).apply { letterSpacing = 0.03f })

        col.addView(h2("Skin"))
        val skinRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, label) in Skin.SKINS) {
            val b = Skin.choice(act, label, if (id == Skin.current) "In use" else "") {
                if (id != Skin.current) {
                    Store.putStr(act, "skin", id)
                    Skin.apply(id)
                    act.recreate()
                }
            }
            b.isSelected = id == Skin.current
            skinRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Skin.dp(act, 10) })
        }
        col.addView(skinRow, Skin.gapParams(act, bottomDp = 6))

        col.addView(h2("Keys and logins"))
        item("Pair with phone", "Type your IPTV login, football keys and Spotify ID on your phone") { act.open("pair") }
        val hasLogin = Store.hasLogin(act)
        col.addView(note("IPTV login: " + (if (hasLogin) "saved" else "not set") + "     football-data.org key: " + (if (Store.fdoKey(act).isNotEmpty()) "saved" else "not set") +
            "     Spotify app ID: " + (if (Store.spotifyClientId(act).isNotEmpty()) "saved" else "not set")))

        col.addView(h2("IPTV subscription"))
        if (!hasLogin) col.addView(note("Not signed in yet."))
        else {
            val exp = Store.str(act, "acct_exp").toLongOrNull()
            val status = Store.str(act, "acct_status")
            val conn = Store.str(act, "acct_conn")
            if (exp != null && exp > 0) {
                val days = (exp - System.currentTimeMillis() / 1000) / 86400
                col.addView(note("Ends ${date(exp)}  (" + (if (days >= 0) "$days day${if (days == 1L) "" else "s"} left" else "ended ${-days} days ago") + ")",
                    if (days in 0..14 || days < 0) Skin.AMBER else Skin.TEXT))
            } else col.addView(note("End date: not given by your provider, or not checked yet. Open Live TV once."))
            if (status.isNotEmpty()) col.addView(note("Status: $status" + if (conn.isNotEmpty()) "     Connections allowed: $conn" else ""))
        }

        col.addView(h2("Live TV"))
        val preview = Store.bool(act, "preview", true)
        item("Picture preview in the guide: " + if (preview) "on" else "off",
            "Turn off if your provider only allows one connection at a time") { Store.putBool(act, "preview", !preview); build() }
        val psound = Store.bool(act, "preview_sound", true)
        item("Sound on the guide preview: " + if (psound) "on" else "off", "You hear the channel while you browse the guide") { Store.putBool(act, "preview_sound", !psound); build() }
        item("Reset category order", "Puts the Live TV categories back in the standard order and shows hidden ones") {
            val live = act.screen("live") as? LiveScreen
            if (live != null) live.resetOrder() else Store.deleteJson(act, "cat_cfg")
            updateNote = "Category order reset."
            build()
        }

        col.addView(h2("Spotify"))
        val connected = Spotify.connected(act)
        col.addView(note("Spotify: " + if (connected) "connected" else if (Store.spotifyClientId(act).isEmpty()) "needs the app ID (see the Music page)" else "not connected yet (see the Music page)"))
        val pause = Store.bool(act, "sp_pause_video", true)
        item("Pause music when a video starts: " + if (pause) "on" else "off") { Store.putBool(act, "sp_pause_video", !pause); build() }
        if (connected) item("Disconnect Spotify", "Signs out of Spotify on this Fire TV") {
            Spotify.api(act).signOut()
            updateNote = "Spotify disconnected."
            build()
        }

        col.addView(h2("Updates"))
        val cur = Updates.current(act)
        val latest = Store.long(act, "upd_latest", 0L).toInt()
        col.addView(note("Installed version: $cur" + if (latest > 0) "     newest on GitHub: $latest" else ""))
        if (Updates.available(act)) item("Install the newest version", "Downloads the app and opens the installer") {
            Updates.install(act) { updateNote = it; build() }
        } else item("Check for updates now") {
            updateNote = "Checking..."
            build()
            Updates.checkInBackground(act, true) { updateNote = if (Updates.available(act)) "A newer version is ready." else "You have the newest version."; build() }
        }

        col.addView(h2("Backup"))
        item("Back up or restore my settings", "Opens the phone page: save a copy of your category order, picks and watch-list, or put one back") {
            act.open("pair")
        }
        col.addView(note("On the phone page press \"Back up or restore my settings\". Logins and keys are never included."))

        col.addView(h2("Help"))
        val errs = Crash.count()
        col.addView(note(if (errs == 0) "No problems recorded." else "$errs small problem${if (errs == 1) "" else "s"} recorded and skipped (the app kept going).",
            if (errs == 0) Skin.GREEN else Skin.AMBER))
        if (errs > 0) {
            col.addView(Skin.text(act, Crash.recent(2200), 10f, Skin.DIM).apply { setPadding(Skin.dp(act, 4), 0, 0, Skin.dp(act, 6)) })
            item("Clear the problem list") { Crash.clear(); build() }
        }

        if (updateNote.isNotEmpty()) col.addView(Skin.text(act, updateNote, 16f, Skin.GREEN).apply { setPadding(Skin.dp(act, 4), Skin.dp(act, 14), 0, 0) })
        post { focusFirst() }
    }
}
