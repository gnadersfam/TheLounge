package com.lounge.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** "Pair with phone": web address + QR code + PIN. Keys and logins are typed on the phone, saved on the Firestick. */
class PairScreen(act: MainActivity) : Screen(act) {
    private val server = PairServer(act)
    private val qr = ImageView(act)
    private val address = Skin.text(act, "", 17f, Skin.CYAN, true)
    private val pinText = Skin.text(act, "", 22f, Skin.MAGENTA, true)
    private val status = Skin.text(act, "", 16f, Skin.GREEN)
    private val doneBtn = Skin.button(act, "Done") { act.open("home") }
    private val retryBtn = Skin.button(act, "Try again") { restart() }

    init {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Skin.dp(act, 56), Skin.dp(act, 32), Skin.dp(act, 56), Skin.dp(act, 32))
        }
        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Skin.text(act, "Pair with phone", 28f, Skin.TEXT, true))
        left.addView(Skin.text(act, "Type your keys and logins on your phone, not with this remote.", 16f, Skin.MUTED).apply { setPadding(0, Skin.dp(act, 8), 0, Skin.dp(act, 22)) })
        left.addView(Skin.text(act, "1.  Put your phone on the same Wi-Fi as this Firestick.", 17f))
        left.addView(Skin.text(act, "2.  Point your phone camera at the square code.", 17f).apply { setPadding(0, Skin.dp(act, 8), 0, 0) })
        left.addView(Skin.text(act, "3.  Fill in what you need and press Save.", 17f).apply { setPadding(0, Skin.dp(act, 8), 0, Skin.dp(act, 22)) })
        left.addView(Skin.text(act, "Or type this address into your phone's browser:", 15f, Skin.MUTED))
        left.addView(address)
        left.addView(Skin.text(act, "PIN (already in the address and the code):", 15f, Skin.MUTED).apply { setPadding(0, Skin.dp(act, 14), 0, 0) })
        left.addView(pinText)
        left.addView(status.apply { setPadding(0, Skin.dp(act, 18), 0, Skin.dp(act, 10)) })
        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(doneBtn)
        btns.addView(retryBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Skin.dp(act, 16) })
        left.addView(btns)
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(qr, LinearLayout.LayoutParams(Skin.dp(act, 340), Skin.dp(act, 340)).apply { leftMargin = Skin.dp(act, 40) })
        addView(row, LayoutParams(-1, -1))
        server.onSaved = { saved ->
            status.text = "Saved: " + saved.joinToString(", ")
            Session.xt = null
            act.onKeysChanged()
        }
    }

    override fun onShow() {
        restart()
    }

    override fun onHide() {
        server.stop()
    }

    private fun restart() {
        server.stop()
        if (!server.start()) {
            status.setTextColor(Skin.AMBER)
            status.text = "Couldn't open the pairing page. Check the Firestick is on Wi-Fi, then choose Try again."
            qr.setImageBitmap(null)
            address.text = ""
            return
        }
        status.setTextColor(Skin.GREEN)
        status.text = ""
        val url = server.address()
        address.text = url
        pinText.text = server.pin
        qr.setImageBitmap(makeQr(url, 340))
    }

    private fun makeQr(text: String, size: Int): Bitmap? = try {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.MARGIN] = 2
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        bmp
    } catch (e: Exception) {
        null
    }

    override fun onBack(): Boolean {
        if (Store.hasLogin(act)) {
            act.open("home")
            return true
        }
        return false
    }

    override fun focusFirst() {
        doneBtn.requestFocus()
    }

    /** Lets other pages (Spotify) borrow the pairing page for the sign-in hand-back. */
    fun pairServer(): PairServer = server
}
