package com.lounge.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The Apps page: tiles for The Lounge's own tools and for other apps installed on the Fire TV.
 * OK opens a tile. Hold OK on a tile to move it or take it off. "Add tool" / "Add app" put things back or add new ones.
 */
class AppsScreen(act: MainActivity) : Screen(act) {

    private class Tool(val key: String, val label: String, val icon: Int, val color: Int)
    private class AppInfo(val pkg: String, val label: String)

    private val tools = listOf(
        Tool("live", "Live TV", R.drawable.ic_tv, 0xFF19F0FF.toInt()),
        Tool("movies", "Movies", R.drawable.ic_movies, 0xFFF2B84B.toInt()),
        Tool("shows", "Shows", R.drawable.ic_shows, 0xFFB05CFF.toInt()),
        Tool("football", "Football", R.drawable.ic_football, 0xFF3DDC97.toInt()),
        Tool("twitch", "Twitch", R.drawable.ic_twitch, 0xFF9A6FE0.toInt()),
        Tool("music", "Music", R.drawable.ic_music, 0xFFFF3DDB.toInt()),
        Tool("bluetooth", "Bluetooth", R.drawable.ic_bluetooth, 0xFF4F8FFF.toInt()),
        Tool("perf", "Performance", R.drawable.ic_perf, 0xFF8FA0B3.toInt()),
        Tool("settings", "Settings", R.drawable.ic_settings, 0xFFB4C0E0.toInt())
    )

    private companion object {
        const val PER_ROW = 6
        const val TOOLS_KEY = "apps_tools"
        const val APPS_KEY = "apps_pkgs"
        const val DEFAULT_TOOLS = "live|movies|shows|football|twitch|music|bluetooth"
    }

    private val col = LinearLayout(act)
    private val scroll = ScrollView(act)
    private val overlay = FrameLayout(act)
    private val picker = FrameLayout(act)
    private val popup = Popup(act, overlay, scroll)
    private var pickerReturn: View? = null
    private var message = ""

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Skin.dp(act, 40), Skin.dp(act, 26), Skin.dp(act, 34), Skin.dp(act, 30))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
        overlay.visibility = View.GONE
        addView(overlay, LayoutParams(-1, -1))
        picker.visibility = View.GONE
        addView(picker, LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------ saved choices
    private fun toolKeys(): MutableList<String> =
        Store.str(act, TOOLS_KEY, DEFAULT_TOOLS).split("|").filter { k -> k.isNotEmpty() && tools.any { it.key == k } }.toMutableList()

    private fun saveTools(l: List<String>) = Store.putStr(act, TOOLS_KEY, l.joinToString("|"))

    private fun appPkgs(): MutableList<String> =
        Store.str(act, APPS_KEY, "").split("|").filter { it.isNotEmpty() }.toMutableList()

    private fun saveApps(l: List<String>) = Store.putStr(act, APPS_KEY, l.joinToString("|"))

    // ------------------------------------------------------------------ page life
    override fun onShow() {
        message = ""
        build(null)
    }

    override fun onActivityResume() {
        // coming back from another app: its icon or name may have changed, or it may have been removed
        if (visibility == View.VISIBLE && !picker.isShown && !popup.shown) build(col.findFocus()?.tag as? String)
    }

    override fun focusFirst() {
        val l = ArrayList<View>()
        col.addFocusables(l, View.FOCUS_DOWN)
        (l.firstOrNull { it.isShown } ?: return).requestFocus()
    }

    override fun onBack(): Boolean {
        if (picker.visibility == View.VISIBLE) {
            hidePicker()
            return true
        }
        if (popup.shown) {
            popup.hide()
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ building the page
    private fun h2(s: String): TextView =
        Skin.text(act, s, 19f, Skin.CYAN, true).apply { setPadding(0, Skin.dp(act, 16), 0, Skin.dp(act, 8)) }

    private fun build(focusTag: String?) {
        col.removeAllViews()
        col.addView(Skin.text(act, "APPS", 40f, Skin.TEXT, true).apply { letterSpacing = 0.03f })

        // ---- tools
        col.addView(h2("MY TOOLS"))
        val tk = toolKeys()
        val toolTiles = ArrayList<View>()
        for (k in tk) {
            val t = tools.firstOrNull { it.key == k } ?: continue
            val v = tile(t.label, null, t.icon, t.color) { openTool(t.key) }
            v.tag = "t:$k"
            v.setOnLongClickListener { toolMenu(k); true }
            toolTiles.add(v)
        }
        toolTiles.add(addTile("Add tool") { addToolPicker() }.also { it.tag = "add:tool" })
        addRows(toolTiles)

        // ---- other apps
        col.addView(h2("MY APPS"))
        val pm = act.packageManager
        val appTiles = ArrayList<View>()
        for (pkg in appPkgs()) {
            var label = pkg.substringAfterLast('.')
            var icon: Drawable? = null
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                icon = pm.getApplicationIcon(pkg)
            } catch (e: Exception) {
                // not installed any more (or not visible): the tile stays so you can remove it
            }
            val v = tile(label, icon, R.drawable.ic_apps, Skin.MUTED) { launch(pkg) }
            v.tag = "a:$pkg"
            v.setOnLongClickListener { appMenu(pkg, label); true }
            appTiles.add(v)
        }
        appTiles.add(addTile("Add app") { addAppPicker() }.also { it.tag = "add:app" })
        addRows(appTiles)

        col.addView(Skin.text(act, "Press OK to open.  Hold OK on a tile to move it or remove it.", 14f, Skin.DIM).apply {
            setPadding(Skin.dp(act, 4), Skin.dp(act, 12), 0, Skin.dp(act, 4))
        })
        if (message.isNotEmpty()) {
            col.addView(Skin.text(act, message, 16f, Skin.AMBER).apply { setPadding(Skin.dp(act, 4), Skin.dp(act, 8), 0, 0) })
        }
        post {
            val want = if (focusTag != null) col.findViewWithTag<View>(focusTag) else null
            if (want != null) want.requestFocus() else focusFirst()
        }
    }

    /** Lay the tiles out in rows of PER_ROW. */
    private fun addRows(views: List<View>) {
        var i = 0
        while (i < views.size) {
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0 until PER_ROW) {
                val v: View = if (i < views.size) views[i] else View(act)
                i++
                line.addView(v, LinearLayout.LayoutParams(0, Skin.dp(act, 108), 1f).apply { rightMargin = Skin.dp(act, 10) })
            }
            col.addView(line, Skin.gapParams(act, bottomDp = 10))
        }
    }

    /** One tile: a picture over a name. [drawable] is an app's own icon; otherwise [iconRes] is tinted with [color]. */
    private fun tile(label: String, drawable: Drawable?, iconRes: Int, color: Int, onOpen: () -> Unit): FrameLayout {
        val c = act
        val t = FrameLayout(c).apply {
            background = Skin.card(c, 14)
            isFocusable = true
            isClickable = true
            setOnClickListener { onOpen() }
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Skin.dp(c, 6), Skin.dp(c, 10), Skin.dp(c, 6), Skin.dp(c, 6))
        }
        val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (drawable != null) iv.setImageDrawable(drawable) else {
            iv.setImageResource(iconRes)
            iv.setColorFilter(color)
        }
        box.addView(iv, LinearLayout.LayoutParams(Skin.dp(c, 52), Skin.dp(c, 52)))
        box.addView(Skin.oneLine(Skin.text(c, label, 15f, Skin.TEXT, true)).apply {
            gravity = Gravity.CENTER
            setPadding(0, Skin.dp(c, 8), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        t.addView(box, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        return t
    }

    private fun addTile(label: String, onOpen: () -> Unit): FrameLayout {
        val c = act
        val t = FrameLayout(c).apply {
            background = Skin.card(c, 14, Color.TRANSPARENT, Skin.DIM)
            isFocusable = true
            isClickable = true
            setOnClickListener { onOpen() }
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        box.addView(Skin.text(c, "+", 40f, Skin.MUTED, true).apply { gravity = Gravity.CENTER })
        box.addView(Skin.oneLine(Skin.text(c, label, 15f, Skin.MUTED, true)).apply { gravity = Gravity.CENTER })
        t.addView(box, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        return t
    }

    // ------------------------------------------------------------------ opening things
    private fun openTool(key: String) {
        act.open(key)
    }

    private fun launch(pkg: String) {
        val pm = act.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            message = "That app isn't installed any more. Hold OK on its tile to remove it."
            build("a:$pkg")
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(intent)
        } catch (e: Exception) {
            message = "Couldn't open that app."
            build("a:$pkg")
        }
    }

    // ------------------------------------------------------------------ hold OK menus
    private fun toolMenu(key: String) {
        val l = toolKeys()
        val i = l.indexOf(key)
        if (i < 0) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (i > 0) items.add(Pair("Move left") { popup.hide(); l.add(i - 1, l.removeAt(i)); saveTools(l); build("t:$key") })
        if (i < l.size - 1) items.add(Pair("Move right") { popup.hide(); l.add(i + 1, l.removeAt(i)); saveTools(l); build("t:$key") })
        items.add(Pair("Remove from this page") { popup.hide(); l.remove(key); saveTools(l); build("add:tool") })
        items.add(Pair("Cancel") { popup.hide() })
        popup.choices(tools.firstOrNull { it.key == key }?.label ?: "Tool", items)
    }

    private fun appMenu(pkg: String, label: String) {
        val l = appPkgs()
        val i = l.indexOf(pkg)
        if (i < 0) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (i > 0) items.add(Pair("Move left") { popup.hide(); l.add(i - 1, l.removeAt(i)); saveApps(l); build("a:$pkg") })
        if (i < l.size - 1) items.add(Pair("Move right") { popup.hide(); l.add(i + 1, l.removeAt(i)); saveApps(l); build("a:$pkg") })
        items.add(Pair("Remove from this page") { popup.hide(); l.remove(pkg); saveApps(l); build("add:app") })
        items.add(Pair("Cancel") { popup.hide() })
        popup.choices(label, items)
    }

    // ------------------------------------------------------------------ pickers (add a tool / add an app)
    private fun addToolPicker() {
        val shown = toolKeys()
        val rows = ArrayList<Pair<String, () -> Unit>>()
        for (t in tools) {
            if (t.key in shown) continue
            rows.add(Pair(t.label) {
                val l = toolKeys(); l.add(t.key); saveTools(l); build("t:${t.key}")
            })
        }
        showPicker("Add a tool", rows, "Every tool is already on the page.")
    }

    private fun installedApps(): List<AppInfo> {
        val pm = act.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val probe = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val found = try { pm.queryIntentActivities(probe, 0) } catch (e: Exception) { emptyList() }
            for (ri in found) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == act.packageName || !seen.add(pkg)) continue
                out.add(AppInfo(pkg, ri.loadLabel(pm).toString()))
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun addAppPicker() {
        val have = appPkgs()
        val rows = ArrayList<Pair<String, () -> Unit>>()
        for (a in installedApps()) {
            if (a.pkg in have) continue
            rows.add(Pair(a.label) {
                val l = appPkgs(); l.add(a.pkg); saveApps(l); build("a:${a.pkg}")
            })
        }
        showPicker("Add an app", rows, "No other apps were found on this Fire TV.")
    }

    private fun showPicker(heading: String, rows: List<Pair<String, () -> Unit>>, emptyNote: String) {
        pickerReturn = scroll.findFocus()
        picker.removeAllViews()
        picker.setBackgroundColor(0xDD07030F.toInt())
        picker.isClickable = true
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.card(act, 18)
            setPadding(Skin.dp(act, 20), Skin.dp(act, 16), Skin.dp(act, 20), Skin.dp(act, 14))
        }
        card.addView(Skin.oneLine(Skin.text(act, heading, 16f, Skin.CYAN, true)).apply { setPadding(0, 0, 0, Skin.dp(act, 10)) })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        for ((label, action) in rows) {
            val v = Skin.choice(act, label) { hidePicker(); action() }
            list.addView(v, Skin.gapParams(act, bottomDp = 6))
            if (first == null) first = v
        }
        if (rows.isEmpty()) list.addView(Skin.text(act, emptyNote, 16f, Skin.MUTED))
        val sc = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            addView(list, LinearLayout.LayoutParams(-1, -2))
        }
        card.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(Skin.text(act, "Back to cancel", 13f, Skin.DIM).apply { setPadding(0, Skin.dp(act, 8), 0, 0) })
        picker.addView(card, FrameLayout.LayoutParams(Skin.dp(act, 520), Skin.dp(act, 520), Gravity.CENTER))
        picker.visibility = View.VISIBLE
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        first?.requestFocus()
    }

    private fun hidePicker() {
        picker.visibility = View.GONE
        picker.removeAllViews()
        scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        pickerReturn?.requestFocus()
    }
}
