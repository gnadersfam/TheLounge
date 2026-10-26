package com.lounge.tv

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Bluetooth shortcut: lists the headphones and controllers already paired with this Fire TV, shows which are
 * connected, and has a button that jumps straight to the Fire TV's own Bluetooth screen (where you pair, connect or
 * disconnect). Fire TV does not let an app pair or connect devices by itself.
 */
class BluetoothScreen(act: MainActivity) : Screen(act) {

    private val col = LinearLayout(act)
    private val scroll = ScrollView(act)
    private val connected = HashSet<String>()
    private val proxies = ArrayList<Pair<Int, BluetoothProfile>>()
    private var asked = 0
    private var answered = 0
    private var message = ""

    private companion object {
        const val PERM_CODE = 77
        const val PROFILE_INPUT_DEVICE = 4 // game controllers, keyboards, mice (the number is not in the public list)
    }

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Skin.dp(act, 40), Skin.dp(act, 26), Skin.dp(act, 34), Skin.dp(act, 30))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------ page life
    override fun onShow() {
        message = ""
        refresh()
    }

    override fun onHide() {
        closeProxies()
    }

    override fun onActivityResume() {
        // back from the Fire TV settings screen: the list may have changed
        if (visibility == View.VISIBLE) refresh()
    }

    override fun onBack(): Boolean {
        act.open("apps")
        return true
    }

    override fun focusFirst() {
        val l = ArrayList<View>()
        col.addFocusables(l, View.FOCUS_DOWN)
        (l.firstOrNull { it.isShown } ?: return).requestFocus()
    }

    // ------------------------------------------------------------------ asking Bluetooth what is connected
    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(act, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun closeProxies() {
        val a = BluetoothAdapter.getDefaultAdapter()
        for ((profile, proxy) in proxies) {
            try { a?.closeProfileProxy(profile, proxy) } catch (e: Throwable) { }
        }
        proxies.clear()
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val addrs = ArrayList<String>()
            try {
                for (d in proxy.connectedDevices) addrs.add(d.address)
            } catch (e: Throwable) {
                // no permission or the service went away: treat as "nothing connected through this one"
            }
            Bg.ui {
                connected.addAll(addrs)
                proxies.add(Pair(profile, proxy))
                answered++
                build()
            }
        }

        override fun onServiceDisconnected(profile: Int) {}
    }

    private fun refresh() {
        closeProxies()
        connected.clear()
        asked = 0
        answered = 0
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && adapter.isEnabled && hasPermission()) {
            for (p in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_INPUT_DEVICE)) {
                try {
                    if (adapter.getProfileProxy(act, listener, p)) asked++
                } catch (e: Throwable) {
                    // this Fire TV does not offer that kind of connection
                }
            }
        }
        build()
    }

    // ------------------------------------------------------------------ drawing
    private fun kindOf(c: BluetoothClass?): String = when (c?.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Headphones or speaker"
        BluetoothClass.Device.Major.PERIPHERAL -> "Controller, keyboard or mouse"
        else -> "Bluetooth device"
    }

    private fun build() {
        val hadFocus = col.hasFocus()
        col.removeAllViews()
        col.addView(Skin.text(act, "BLUETOOTH", 40f, Skin.TEXT, true).apply { letterSpacing = 0.03f })
        col.addView(Skin.text(act, "PAIRED DEVICES", 19f, Skin.CYAN, true).apply { setPadding(0, Skin.dp(act, 16), 0, Skin.dp(act, 8)) })

        val adapter = BluetoothAdapter.getDefaultAdapter()
        when {
            adapter == null -> col.addView(note("This Fire TV has no Bluetooth."))
            !hasPermission() -> {
                col.addView(note("Allow Bluetooth access so The Lounge can list your paired devices."))
                col.addView(Skin.button(act, "Allow Bluetooth access") {
                    ActivityCompat.requestPermissions(act, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), PERM_CODE)
                }, Skin.gapParams(act, -2, -2, 10))
            }
            !adapter.isEnabled -> col.addView(note("Bluetooth is switched off. Use the button below to open the Fire TV settings and turn it on."))
            else -> {
                val devices = try { adapter.bondedDevices?.toList() ?: emptyList() } catch (e: Throwable) { emptyList() }
                if (devices.isEmpty()) col.addView(note("No devices are paired yet."))
                val ready = asked > 0 && answered >= asked
                for (d in devices.sortedBy { x -> (try { d0(x.name) } catch (e: Throwable) { "" }).lowercase() }) {
                    val name = try { d0(d.name) } catch (e: Throwable) { "Bluetooth device" }
                    val status = when {
                        d.address in connected -> "Connected"
                        ready -> "Not connected"
                        else -> "Paired"
                    }
                    val kind = try { kindOf(d.bluetoothClass) } catch (e: Throwable) { "Bluetooth device" }
                    val row = Skin.choice(act, name, "$kind   -   $status") { openSettings() }
                    row.isSelected = d.address in connected
                    col.addView(row, Skin.gapParams(act, bottomDp = 8))
                }
            }
        }

        col.addView(Skin.text(act, "PAIR OR CHANGE A DEVICE", 19f, Skin.CYAN, true).apply { setPadding(0, Skin.dp(act, 18), 0, Skin.dp(act, 8)) })
        col.addView(Skin.button(act, "Open Fire TV Bluetooth settings") { openSettings() }, Skin.gapParams(act, -2, -2, 8))
        col.addView(note("This list shows what is paired. To pair a new device, or to connect or disconnect one, use the Fire TV screen that this button opens."))
        if (message.isNotEmpty()) col.addView(Skin.text(act, message, 16f, Skin.AMBER).apply { setPadding(Skin.dp(act, 4), Skin.dp(act, 10), 0, 0) })
        if (!hadFocus) post { focusFirst() }
    }

    private fun d0(s: String?): String = if (s.isNullOrBlank()) "Bluetooth device" else s

    private fun note(s: String) = Skin.text(act, s, 14f, Skin.MUTED).apply { setPadding(Skin.dp(act, 4), 0, 0, Skin.dp(act, 6)) }

    /** Open the Fire TV's own Bluetooth screen. Different Fire TVs name it differently, so try a few ways. */
    private fun openSettings() {
        val tries = listOf(
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.BluetoothActivity"),
            Intent().setClassName("com.amazon.tv.settings", "com.amazon.tv.settings.tv.BluetoothActivity"),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for ((i, intent) in tries.withIndex()) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(intent)
                if (i == tries.size - 1) {
                    message = "Opened the main settings. Look for Controllers and Bluetooth Devices."
                    build()
                }
                return
            } catch (e: Exception) {
                // try the next way
            }
        }
        message = "Couldn't open settings. On the Fire TV go to Settings, then Controllers and Bluetooth Devices."
        build()
    }
}
