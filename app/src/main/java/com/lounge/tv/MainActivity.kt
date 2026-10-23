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
import android.widget.TextView

/**
 * The shell: a labelled menu down the left, a clock top-right, and one page at a time in the middle.
 * Pages are made the first time they are opened and then kept, so coming back is instant.
 */
class MainActivity : Activity() {

    private class RailItem(val name: String, val icon: Int, val label: String, val group: String = "")

    private val items = listOf(
        RailItem("home", R.drawable.ic_home, "Home", "WATCH"),
        RailItem("live", R.drawable.ic_tv, "Live TV"),
        RailItem("movies", R.drawable.ic_movies, "Movies"),
        RailItem("shows", R.drawable.ic_shows, "Shows"),
        RailItem("football", R.drawable.ic_football, "Football", "PLAY"),
        RailItem("music", R.drawable.ic_music, "Music"),
        RailItem("settings", R.drawable.ic_settings, "Settings", "SYSTEM")
    )

    private lateinit var content: FrameLayout
    private lateinit var rail: LinearLayout
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var iptvNote: TextView
    private val railViews = HashMap<String, LinearLayout>()
    private val screens = HashMap<String, Screen>()
    var currentName: String = ""
        private set

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            Crash.crumb("page=$currentName " + (screens[currentName]?.crumb() ?: ""))
            Bg.uiDelayed(15_000, this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        Skin.load(this)
        TeamLogos.ensure(this)
        window.setBackgroundDrawable(ColorDrawable(Skin.BG))
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rail = buildRail()
        root.addView(rail, LinearLayout.LayoutParams(Skin.dp(this, 250), -1))
        val wrap = FrameLayout(this)
        content = FrameLayout(this)
        wrap.addView(content, FrameLayout.LayoutParams(-1, -1))
        // the clock sits on top of every page, top right
        val clock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            isFocusable = false
        }
        clockTime = Skin.text(this, "", 34f, Skin.TEXT, true).apply { gravity = Gravity.END }
        clockDate = Skin.text(this, "", 13f, Skin.DIM).apply { gravity = Gravity.END }
        clock.addView(clockTime)
        clock.addView(clockDate)
        wrap.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = Skin.dp(this@MainActivity, 18); rightMargin = Skin.dp(this@MainActivity, 36)
        })
        root.addView(wrap, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        updateClock()

        Updates.checkInBackground(this)
        open(if (Store.hasLogin(this)) "home" else "pair")
    }

    private fun updateClock() {
        val now = System.currentTimeMillis() / 1000
        clockTime.text = Skin.hhmm(now)
        clockDate.text = Skin.dayDate(now)
        val exp = Store.str(this, "acct_exp").toLongOrNull()
        if (exp != null && exp > 0) {
            val days = (exp - now) / 86400
            val ends = java.text.SimpleDateFormat("d MMM", java.util.Locale.UK).format(java.util.Date(exp * 1000))
            iptvNote.text = if (days >= 0) "IPTV ends $ends  -  $days days left" else "IPTV has ended"
        } else iptvNote.text = ""
    }

    override fun onStart() {
        super.onStart()
        Crash.markForeground(this)
        Bg.cancel(clockTick)
        Bg.uiDelayed(15_000, clockTick)
    }

    override fun onStop() {
        super.onStop()
        Crash.markLeft()
        Bg.cancel(clockTick)
    }

    private fun buildRail(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Skin.RAIL)
            setPadding(0, Skin.dp(this@MainActivity, 22), 0, 0)
        }
        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Skin.dp(this@MainActivity, 22), 0, 0, Skin.dp(this@MainActivity, 8))
        }
        logoRow.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(Skin.dp(this, 44), Skin.dp(this, 44)))
        logoRow.addView(Skin.text(this, "THE LOUNGE", 26f, Skin.TEXT, true).apply { setPadding(Skin.dp(this@MainActivity, 12), 0, 0, 0) })
        col.addView(logoRow)
        for (it in items) {
            if (it.group.isNotEmpty()) {
                col.addView(Skin.text(this, it.group, 12f, Skin.DIM, true).apply {
                    letterSpacing = 0.18f
                    setPadding(Skin.dp(this@MainActivity, 24), Skin.dp(this@MainActivity, 14), 0, Skin.dp(this@MainActivity, 4))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Skin.card(this@MainActivity, 12, Color.TRANSPARENT, Color.TRANSPARENT)
                isFocusable = true
                isClickable = true
                contentDescription = it.label
                setPadding(Skin.dp(this@MainActivity, 16), 0, Skin.dp(this@MainActivity, 12), 0)
                setOnClickListener { _ -> open(it.name) }
            }
            row.addView(ImageView(this).apply { setImageResource(it.icon); setColorFilter(Skin.MUTED) },
                LinearLayout.LayoutParams(Skin.dp(this, 26), Skin.dp(this, 26)))
            row.addView(Skin.text(this, it.label, 20f, Skin.MUTED, true).apply { setPadding(Skin.dp(this@MainActivity, 14), 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            railViews[it.name] = row
            col.addView(row, LinearLayout.LayoutParams(-1, Skin.dp(this, 48)).apply {
                leftMargin = Skin.dp(this@MainActivity, 10); rightMargin = Skin.dp(this@MainActivity, 10); topMargin = Skin.dp(this@MainActivity, 2)
            })
        }
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        iptvNote = Skin.text(this, "", 13f, Skin.DIM).apply { setPadding(Skin.dp(this@MainActivity, 22), Skin.dp(this@MainActivity, 14), Skin.dp(this@MainActivity, 12), Skin.dp(this@MainActivity, 16)) }
        col.addView(iptvNote)
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
            if (name == "football") {
                // the film and show lists are big; they reload quickly from the saved copy when you go back to them
                for (n in listOf("movies", "shows")) screens.remove(n)?.let { content.removeView(it) }
                Img.clear()
                System.gc()
            }
            s.visibility = View.VISIBLE
            for ((n, v) in railViews) {
                v.isSelected = n == name
                (v.getChildAt(0) as ImageView).setColorFilter(if (n == name) Skin.CYAN else Skin.MUTED)
                (v.getChildAt(1) as TextView).setTextColor(if (n == name) Skin.TEXT else Skin.MUTED)
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Crash.log("Memory is short (level $level)", RuntimeException(Crash.memLine() + "  at " + currentName))
            Img.clear()
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) (screens["live"] as? LiveScreen)?.trimMemory()
            System.gc()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Bg.cancel(clockTick)
        Updates.shutdown()
    }
}

/** Pages that want the remote's menu (three lines) button. */
interface MenuKeyHandler {
    fun onMenuKey(): Boolean
}
