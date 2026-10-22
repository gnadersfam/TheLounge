package com.lounge.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The shell: a thin icon rail down the left and one page at a time on the right.
 * Pages are made the first time they are opened and then kept, so coming back is instant.
 */
class MainActivity : Activity() {

    private class RailItem(val name: String, val icon: Int, val label: String)

    private val items = listOf(
        RailItem("home", R.drawable.ic_home, "Home"),
        RailItem("live", R.drawable.ic_tv, "Live TV"),
        RailItem("movies", R.drawable.ic_movies, "Movies"),
        RailItem("shows", R.drawable.ic_shows, "Shows"),
        RailItem("football", R.drawable.ic_football, "Football"),
        RailItem("music", R.drawable.ic_music, "Music"),
        RailItem("settings", R.drawable.ic_settings, "Settings")
    )

    private lateinit var content: FrameLayout
    private lateinit var rail: LinearLayout
    private val railViews = HashMap<String, FrameLayout>()
    private val screens = HashMap<String, Screen>()
    var currentName: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Skin.BG))
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rail = buildRail()
        root.addView(rail, LinearLayout.LayoutParams(Skin.dp(this, 64), -1))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)

        Updates.checkInBackground(this)
        open(if (Store.hasLogin(this)) "home" else "pair")
    }

    private fun buildRail(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Skin.RAIL)
            setPadding(0, Skin.dp(this@MainActivity, 24), 0, Skin.dp(this@MainActivity, 24))
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.logo) }
        col.addView(logo, LinearLayout.LayoutParams(Skin.dp(this, 40), Skin.dp(this, 40)).apply { bottomMargin = Skin.dp(this@MainActivity, 18) })
        for (it in items) {
            val cell = FrameLayout(this).apply {
                background = Skin.card(this@MainActivity, 14, Color.TRANSPARENT, Color.TRANSPARENT)
                isFocusable = true
                isClickable = true
                contentDescription = it.label
                setOnClickListener { _ -> open(it.name) }
            }
            val img = ImageView(this).apply {
                setImageResource(it.icon)
                setColorFilter(Skin.MUTED)
            }
            cell.addView(img, Skin.center(img, Skin.dp(this, 26), Skin.dp(this, 26)))
            railViews[it.name] = cell
            val lp = LinearLayout.LayoutParams(Skin.dp(this, 48), Skin.dp(this, 44)).apply { bottomMargin = Skin.dp(this@MainActivity, 14) }
            if (it.name == "settings") lp.topMargin = Skin.dp(this, 14)
            col.addView(cell, lp)
        }
        return col
    }

    private fun make(name: String): Screen = when (name) {
        "home" -> HomeScreen(this)
        "live" -> LiveScreen(this)
        "movies" -> VodScreen(this, false)
        "shows" -> VodScreen(this, true)
        "football" -> FootballScreen(this)
        "music" -> MusicScreen(this)
        "settings" -> SettingsScreen(this)
        else -> PairScreen(this)
    }

    /** Show a page. [arg] is handed to the page (for example a match to open). */
    fun open(name: String, arg: Any? = null) {
        if (name != currentName) {
            screens[currentName]?.let {
                it.onHide()
                it.visibility = View.GONE
            }
            val s = screens.getOrPut(name) {
                val made = make(name)
                content.addView(made, FrameLayout.LayoutParams(-1, -1))
                made
            }
            currentName = name
            s.visibility = View.VISIBLE
            for ((n, v) in railViews) {
                v.isSelected = n == name
                (v.getChildAt(0) as ImageView).setColorFilter(if (n == name) Skin.CYAN else Skin.MUTED)
            }
            s.onShow()
        }
        val s = screens[name] ?: return
        if (arg != null) s.onArg(arg)
        s.post { s.focusFirst() }
    }

    fun screen(name: String): Screen? = screens[name]

    // ------------------------------------------------------------------ playing
    fun playLive(list: List<Chan>, index: Int, panel: String = "") {
        Session.matchPanel = panel
        Session.channels = list
        Session.index = index
        Session.live = true
        Spotify.pauseForVideo(this)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    fun playVod(title: String, url: String, key: String, resumeMs: Long) {
        Session.matchPanel = ""
        Session.vodTitle = title
        Session.vodUrl = url
        Session.vodKey = key
        Session.vodResume = resumeMs
        Session.live = false
        Spotify.pauseForVideo(this)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ------------------------------------------------------------------ keys
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = screens[currentName]
        if (s != null && s.onBack()) return
        val inRail = rail.hasFocus()
        if (!inRail) {
            railViews[currentName]?.requestFocus()
            return
        }
        if (currentName != "home" && Store.hasLogin(this)) {
            open("home")
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            screens[currentName]?.let { if (it is MenuKeyHandler) return it.onMenuKey() }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        screens[currentName]?.onActivityPause()
    }

    override fun onResume() {
        super.onResume()
        screens[currentName]?.onActivityResume()
    }

    /** Called after the phone page saved something. */
    fun onKeysChanged() {
        for (s in screens.values) s.onKeysChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        Updates.shutdown()
    }
}

/** Pages that want the remote's menu (three lines) button. */
interface MenuKeyHandler {
    fun onMenuKey(): Boolean
}
