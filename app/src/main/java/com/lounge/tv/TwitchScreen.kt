package com.lounge.tv

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Twitch page: log in with a code, then Following / Live now / Categories / Football, search, and open a channel. */
class TwitchScreen(act: MainActivity) : Screen(act) {

    private val root = LinearLayout(act)
    private val overlay = FrameLayout(act)
    private val popup = Popup(act, overlay, root)
    private val tabRow = LinearLayout(act)
    private val tabChips = LinkedHashMap<String, TextView>()
    private val sub = Skin.text(act, "", 13f, Skin.CYAN, true)
    private val scroll = ScrollView(act)
    private val grid = LinearLayout(act)
    private val accountBtn = Skin.button(act, "Account") { accountMenu() }
    private val searchBtn = Skin.button(act, "Search") { askSearch() }

    private var tab = "follow" // follow, live, cats, football, search, cat
    private var tabArg = ""
    private var tabLabel = ""
    private var loadToken = 0
    private var loginToken = 0

    private val TABS = listOf("follow" to "Following", "live" to "Live now", "cats" to "Categories", "football" to "Football")

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Skin.dp(act, 36), Skin.dp(act, 16), Skin.dp(act, 36), Skin.dp(act, 8))
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Skin.text(act, "TWITCH", 28f, Skin.TEXT, true).apply { letterSpacing = 0.03f }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(searchBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 12) })
        head.addView(accountBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 150) })
        root.addView(head, Skin.gapParams(act, bottomDp = 8))

        tabRow.orientation = LinearLayout.HORIZONTAL
        for ((key, label) in TABS) {
            val chip = Skin.text(act, label, 16f, Skin.TEXT, true).apply {
                background = Skin.card(act, 22)
                isFocusable = true
                isClickable = true
                setPadding(Skin.dp(act, 20), Skin.dp(act, 9), Skin.dp(act, 20), Skin.dp(act, 9))
                setOnClickListener { setTab(key, "", label) }
            }
            tabChips[key] = chip
            tabRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 10) })
        }
        root.addView(tabRow, Skin.gapParams(act, bottomDp = 8))
        root.addView(sub, Skin.gapParams(act, bottomDp = 8))

        grid.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addView(root, LayoutParams(-1, -1))
        overlay.visibility = View.GONE
        addView(overlay, LayoutParams(-1, -1))
    }

    override fun crumb(): String = "twitch tab=$tab"

    override fun onShow() {
        if (!Twitch.loggedIn(act)) showLogin() else load()
        updateAccount()
    }

    override fun onHide() {
        // free the pictures while another page is open
        loadToken++
        loginToken++
        grid.removeAllViews()
    }

    override fun onActivityPause() {
        loginToken++
    }

    override fun onActivityResume() {
        if (visibility == View.VISIBLE && grid.childCount == 0) onShow()
    }

    override fun onKeysChanged() {
        if (visibility == View.VISIBLE) onShow()
    }

    override fun onBack(): Boolean {
        if (popup.shown) {
            popup.hide()
            return true
        }
        if (tab == "cat" || tab == "search") {
            if (tab == "cat") setTab("cats", "", "Categories") else setTab("follow", "", "Following")
            return true
        }
        return false
    }

    override fun focusFirst() {
        val l = ArrayList<View>()
        grid.addFocusables(l, View.FOCUS_DOWN)
        val target = l.firstOrNull { it.isShown } ?: tabChips[if (tab == "cat" || tab == "search") "follow" else tab] ?: searchBtn
        target.requestFocus()
    }

    // ------------------------------------------------------------------ account and search
    private fun updateAccount() {
        accountBtn.text = if (Twitch.loggedIn(act)) {
            val n = Twitch.myName(act)
            if (n.isEmpty()) "Log out" else "Log out ($n)"
        } else "Not logged in"
    }

    private fun accountMenu() {
        if (!Twitch.loggedIn(act)) {
            showLogin()
            return
        }
        popup.choices("Twitch account", listOf(
            Pair("Log out of Twitch") {
                popup.hide()
                Twitch.logout(act)
                updateAccount()
                showLogin()
            },
            Pair("Cancel") { popup.hide() }
        ))
    }

    private fun askSearch() {
        if (!Twitch.loggedIn(act)) return
        popup.search("Search live channels", if (tab == "search") tabArg else "") { q ->
            if (q.isEmpty()) setTab("follow", "", "Following") else setTab("search", q, "Results for $q")
        }
    }

    // ------------------------------------------------------------------ tabs
    private fun setTab(t: String, arg: String, label: String) {
        tab = t
        tabArg = arg
        tabLabel = label
        load()
    }

    private fun markTabs() {
        for ((key, chip) in tabChips) {
            val on = key == tab
            chip.background = Skin.card(act, 22, if (on) Skin.MAGENTA else Skin.CARD, if (on) Skin.MAGENTA else Skin.LINE)
            chip.setTextColor(if (on) 0xFF07030F.toInt() else Skin.TEXT)
        }
        sub.text = when (tab) {
            "follow" -> "LIVE NOW FROM CHANNELS YOU FOLLOW"
            "live" -> "BUSIEST LIVE CHANNELS"
            "cats" -> "CATEGORIES"
            "football" -> "FOOTBALL CHANNELS LIVE NOW"
            "cat" -> tabLabel.uppercase() + "  -  LIVE NOW"
            else -> tabLabel.uppercase()
        }
    }

    // ------------------------------------------------------------------ messages and login
    private fun note(text: String, vararg buttons: Pair<String, () -> Unit>) {
        grid.removeAllViews()
        grid.addView(Skin.text(act, text, 17f, Skin.MUTED).apply { setPadding(0, Skin.dp(act, 10), 0, Skin.dp(act, 12)) }, Skin.gapParams(act, bottomDp = 4))
        for ((label, go) in buttons) {
            grid.addView(Skin.button(act, label, go), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Skin.dp(act, 10) })
        }
        if (visibility == View.VISIBLE) grid.post { focusFirst() }
    }

    private fun showLogin() {
        loadToken++
        loginToken++
        tabRow.visibility = View.GONE
        sub.text = ""
        updateAccount()
        if (Twitch.clientId(act).isEmpty()) {
            note(
                "Twitch needs your free Client ID first.\n\n" +
                    "1. On your phone, open the phone page (Settings shows its address).\n" +
                    "2. Paste your Twitch Client ID in the Twitch box and save.\n" +
                    "3. Come back here and press the button below.",
                Pair("I have added it") { onShow() }
            )
            return
        }
        note("Log in to Twitch to see who is live and the channels you follow.", Pair("Log in to Twitch") { beginLogin() })
    }

    private fun beginLogin() {
        val mine = ++loginToken
        note("Asking Twitch for a code...")
        Bg.run {
            try {
                val dc = Twitch.startLogin(act)
                Bg.ui { if (mine == loginToken) showCode(dc, mine) }
            } catch (e: Throwable) {
                Bg.ui { if (mine == loginToken) note("Could not start the login: " + (e.message ?: "unknown"), Pair("Try again") { beginLogin() }, Pair("Back") { showLogin() }) }
            }
        }
    }

    private fun showCode(dc: Twitch.DeviceCode, mine: Int) {
        grid.removeAllViews()
        grid.addView(Skin.text(act, "On your phone, open", 18f, Skin.MUTED), Skin.gapParams(act, bottomDp = 4))
        grid.addView(Skin.text(act, dc.url.removePrefix("https://").removePrefix("http://"), 30f, Skin.CYAN, true), Skin.gapParams(act, bottomDp = 12))
        grid.addView(Skin.text(act, "and type this code", 18f, Skin.MUTED), Skin.gapParams(act, bottomDp = 4))
        grid.addView(Skin.text(act, dc.userCode, 52f, Skin.MAGENTA, true).apply { letterSpacing = 0.15f }, Skin.gapParams(act, bottomDp = 12))
        val waiting = Skin.text(act, "Waiting for you...", 15f, Skin.DIM)
        grid.addView(waiting, Skin.gapParams(act, bottomDp = 14))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Skin.button(act, "Get a new code") { beginLogin() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Skin.dp(act, 12) })
        row.addView(Skin.button(act, "Cancel") { showLogin() })
        grid.addView(row)
        grid.post { focusFirst() }

        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        val interval = Math.max(3, dc.interval) * 1000L
        val poll = object : Runnable {
            override fun run() {
                if (mine != loginToken) return
                if (System.currentTimeMillis() > deadline) {
                    note("The code ran out.", Pair("Get a new code") { beginLogin() })
                    return
                }
                Bg.run {
                    try {
                        val done = Twitch.pollLogin(act, dc)
                        Bg.ui {
                            if (mine != loginToken) return@ui
                            if (done) {
                                Store.remove(act, "twitch_user")
                                Bg.run {
                                    try { Twitch.followed(act) } catch (e: Throwable) { }
                                    Bg.ui { if (mine == loginToken) onShow() }
                                }
                            } else Bg.uiDelayed(interval, this)
                        }
                    } catch (e: Throwable) {
                        Bg.ui { if (mine == loginToken) note((e.message ?: "The login did not work."), Pair("Try again") { beginLogin() }) }
                    }
                }
            }
        }
        Bg.uiDelayed(interval, poll)
    }

    // ------------------------------------------------------------------ browsing
    private fun load() {
        val mine = ++loadToken
        tabRow.visibility = View.VISIBLE
        markTabs()
        updateAccount()
        note("Loading...")
        Bg.run {
            try {
                if (tab == "cats") {
                    val cats = Twitch.categories(act)
                    Bg.ui { if (mine == loadToken) showCategories(cats) }
                } else {
                    val list: List<Twitch.Stream> = when (tab) {
                        "follow" -> Twitch.followed(act)
                        "live" -> Twitch.top(act)
                        "football" -> Twitch.search(act, "football")
                        "search" -> Twitch.search(act, tabArg)
                        "cat" -> Twitch.top(act, tabArg)
                        else -> emptyList()
                    }
                    Bg.ui { if (mine == loadToken) showStreams(list) }
                }
            } catch (e: Twitch.AuthError) {
                Bg.ui { if (mine == loadToken) showLogin() }
            } catch (e: Throwable) {
                Bg.ui { if (mine == loadToken) note("Could not reach Twitch: " + (e.message ?: "unknown"), Pair("Try again") { load() }) }
            }
        }
    }

    private fun showStreams(list: List<Twitch.Stream>) {
        if (list.isEmpty()) {
            note(
                when (tab) {
                    "follow" -> "None of the channels you follow are live right now. Follow channels on the Twitch website or app, then they show here."
                    else -> "Nothing live was found."
                }
            )
            return
        }
        grid.removeAllViews()
        val shown = list.take(24)
        var i = 0
        while (i < shown.size) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until 4) {
                val s = shown.getOrNull(i + j)
                if (s == null) {
                    line.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                line.addView(streamCard(s), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Skin.dp(act, 14) })
            }
            grid.addView(line, Skin.gapParams(act, bottomDp = 12))
            i += 4
        }
        if (visibility == View.VISIBLE) grid.post { focusFirst() }
    }

    private fun streamCard(s: Twitch.Stream): LinearLayout {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(act, 12)
            isFocusable = true
            isClickable = true
            setPadding(Skin.dp(act, 6), Skin.dp(act, 6), Skin.dp(act, 6), Skin.dp(act, 8))
            setOnClickListener { watch(s) }
        }
        val pic = FrameLayout(act).apply { setBackgroundColor(Skin.CARD_DIM) }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        pic.addView(iv, FrameLayout.LayoutParams(-1, -1))
        Img.load(iv, s.thumb, 320, 180)
        pic.addView(Skin.text(act, "LIVE", 11f, 0xFFFFFFFF.toInt(), true).apply {
            background = Skin.card(act, 5, 0xFFE0245E.toInt(), 0xFFE0245E.toInt())
            setPadding(Skin.dp(act, 6), Skin.dp(act, 2), Skin.dp(act, 6), Skin.dp(act, 2))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(Skin.dp(act, 6), Skin.dp(act, 6), 0, 0) })
        if (s.viewers > 0) {
            pic.addView(Skin.text(act, viewers(s.viewers), 12f, 0xFFFFFFFF.toInt(), true).apply {
                background = Skin.card(act, 5, 0xB0000000.toInt(), 0xB0000000.toInt())
                setPadding(Skin.dp(act, 6), Skin.dp(act, 2), Skin.dp(act, 6), Skin.dp(act, 2))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Skin.dp(act, 6), Skin.dp(act, 6)) })
        }
        c.addView(pic, LinearLayout.LayoutParams(-1, Skin.dp(act, 104)))
        c.addView(Skin.oneLine(Skin.text(act, s.name, 15f, Skin.TEXT, true)).apply { setPadding(Skin.dp(act, 4), Skin.dp(act, 6), 0, 0) })
        c.addView(Skin.oneLine(Skin.text(act, if (s.game.isNotEmpty()) s.game else s.title, 12f, Skin.MUTED)).apply { setPadding(Skin.dp(act, 4), 0, 0, 0) })
        return c
    }

    private fun viewers(n: Int): String = if (n >= 1000) String.format(java.util.Locale.UK, "%.1fK", n / 1000.0) else n.toString()

    private fun showCategories(list: List<Twitch.Category>) {
        if (list.isEmpty()) {
            note("No categories came back.")
            return
        }
        grid.removeAllViews()
        val shown = list.take(24)
        var i = 0
        while (i < shown.size) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until 6) {
                val cat = shown.getOrNull(i + j)
                if (cat == null) {
                    line.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                    continue
                }
                val c = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Skin.card(act, 12)
                    isFocusable = true
                    isClickable = true
                    setPadding(Skin.dp(act, 5), Skin.dp(act, 5), Skin.dp(act, 5), Skin.dp(act, 6))
                    setOnClickListener { setTab("cat", cat.id, cat.name) }
                }
                val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Skin.CARD_DIM) }
                Img.load(iv, cat.art, 188, 250)
                c.addView(iv, LinearLayout.LayoutParams(-1, Skin.dp(act, 150)))
                c.addView(Skin.oneLine(Skin.text(act, cat.name, 13f, Skin.TEXT, true)).apply { setPadding(Skin.dp(act, 3), Skin.dp(act, 5), 0, 0) })
                line.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Skin.dp(act, 10) })
            }
            grid.addView(line, Skin.gapParams(act, bottomDp = 10))
            i += 6
        }
        if (visibility == View.VISIBLE) grid.post { focusFirst() }
    }

    private fun watch(s: Twitch.Stream) {
        Spotify.pauseForVideo(act)
        act.startActivity(
            Intent(act, TwitchPlayerActivity::class.java)
                .putExtra("login", s.login).putExtra("name", s.name).putExtra("game", s.game).putExtra("viewers", s.viewers)
        )
    }
}
