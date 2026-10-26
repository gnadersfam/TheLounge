package com.lounge.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * A Discord window that sits in front of a film, show or channel, like the Twitch window.
 * It shows Discord's own website. The page is laid out at a fixed desktop size and shrunk to fit the window,
 * so Discord looks like it does on a computer. The phone remote's Mouse mode moves the pointer, clicks, scrolls and types.
 */
class DiscordWindow(
    private val act: Activity,
    private val root: FrameLayout,
    private val setFilmVolume: (Float) -> Unit
) {
    /** w and h are in dp; 0 means the whole screen. */
    class Size(val label: String, val w: Int, val h: Int)

    companion object {
        val SIZES = listOf(Size("Small", 400, 225), Size("Medium", 560, 315), Size("Large", 720, 405), Size("Full screen", 0, 0))
        const val FULL_IDX = 3
        val ALPHAS = intArrayOf(100, 75, 50, 25)
        const val APP_URL = "https://discord.com/app"
        const val LOGIN_URL = "https://discord.com/login"
        private const val VW = 1100f // the page is laid out this many CSS pixels wide
        private const val VH = 619f
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private var web: WebView? = null
    private var box: FrameLayout? = null
    private var pointer: View? = null
    private var soundTag: TextView? = null
    private var engaged = false
    private val dens = act.resources.displayMetrics.density
    private var ptX = VW / 2f
    private var ptY = VH / 2f
    private val hidePtr = Runnable { pointer?.visibility = View.GONE }

    var sizeIdx: Int = Store.str(act, "discord_win_size", "1").toIntOrNull()?.coerceIn(0, SIZES.size - 1) ?: 1
        private set
    var alphaIdx: Int = Store.str(act, "discord_win_alpha", "0").toIntOrNull()?.coerceIn(0, ALPHAS.size - 1) ?: 0
        private set
    var hearDiscord: Boolean = false
        private set

    // where the window sits: 0 = left / top edge, 1 = right / bottom edge
    private var fx = Store.str(act, "discord_win_fx", "0.97").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.97f
    private var fy = Store.str(act, "discord_win_fy", "0.06").toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.06f

    val active: Boolean get() = box != null && web != null

    private fun pad() = Skin.dp(act, 3)
    private fun screenW() = act.resources.displayMetrics.widthPixels
    private fun screenH() = act.resources.displayMetrics.heightPixels
    private fun boxW(): Int { val s = SIZES[sizeIdx]; return if (s.w == 0) screenW() else Skin.dp(act, s.w) }
    private fun boxH(): Int { val s = SIZES[sizeIdx]; return if (s.h == 0) screenH() else Skin.dp(act, s.h) }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(url: String) {
        val existing = web
        if (existing != null && box != null) {
            existing.loadUrl(url)
            return
        }
        close(false)
        val wv: WebView = try {
            WebView(act)
        } catch (e: Throwable) {
            Crash.log("Discord window", e)
            return
        }
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isVerticalScrollBarEnabled = false
        wv.setBackgroundColor(0xFF313338.toInt())
        val st = wv.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.mediaPlaybackRequiresUserGesture = false
        st.userAgentString = DESKTOP_UA
        st.useWideViewPort = true
        st.loadWithOverviewMode = false
        st.setSupportZoom(false)
        st.builtInZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                applyMute()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // the page ran out of memory: take the window away instead of crashing the app
                root.post { close() }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny() // watching needs no microphone or camera
            }
        }

        val b = FrameLayout(act)
        b.background = GradientDrawable().apply {
            setColor(0xFF1E1F22.toInt())
            setStroke(pad(), 0xFF5865F2.toInt())
            cornerRadius = Skin.dp(act, 12).toFloat()
        }
        b.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val p = pad()
        b.addView(wv, FrameLayout.LayoutParams((VW * dens).toInt(), (VH * dens).toInt(), Gravity.TOP or Gravity.START).apply { setMargins(p, p, p, p) })

        val tag = Skin.text(act, "DISCORD", 13f, Skin.TEXT, true).apply {
            background = Skin.card(act, 8, 0xCC07030F.toInt(), 0xCC07030F.toInt())
            setPadding(Skin.dp(act, 10), Skin.dp(act, 4), Skin.dp(act, 10), Skin.dp(act, 4))
        }
        b.addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(Skin.dp(act, 10), Skin.dp(act, 10), 0, 0) })
        val sd = Skin.text(act, "Film sound", 13f, Skin.AMBER, true).apply {
            background = Skin.card(act, 8, 0xCC07030F.toInt(), 0xCC07030F.toInt())
            setPadding(Skin.dp(act, 10), Skin.dp(act, 4), Skin.dp(act, 10), Skin.dp(act, 4))
        }
        b.addView(sd, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Skin.dp(act, 10), Skin.dp(act, 10)) })
        soundTag = sd

        val ptr = View(act)
        ptr.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Skin.CYAN)
            setStroke(Skin.dp(act, 2), 0xFFFFFFFF.toInt())
        }
        ptr.visibility = View.GONE
        b.addView(ptr, FrameLayout.LayoutParams(Skin.dp(act, 16), Skin.dp(act, 16)))
        pointer = ptr

        b.alpha = ALPHAS[alphaIdx] / 100f
        web = wv
        box = b
        hearDiscord = false
        ptX = VW / 2f
        ptY = VH / 2f
        root.addView(b, params())
        applyScale()
        applyMute()
        wv.loadUrl(url)
    }

    fun load(url: String) {
        web?.loadUrl(url)
    }

    private fun params(): FrameLayout.LayoutParams {
        val w = boxW()
        val h = boxH()
        return FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
            leftMargin = (fx * Math.max(0, screenW() - w)).toInt()
            topMargin = (fy * Math.max(0, screenH() - h)).toInt()
        }
    }

    private fun scale(): Float {
        val p2 = pad() * 2
        val sx = (boxW() - p2).toFloat() / (VW * dens)
        val sy = (boxH() - p2).toFloat() / (VH * dens)
        return Math.min(sx, sy)
    }

    private fun applyScale() {
        val w = web ?: return
        val s = scale()
        w.pivotX = 0f
        w.pivotY = 0f
        w.scaleX = s
        w.scaleY = s
        updatePointer()
    }

    fun setSize(i: Int) {
        sizeIdx = i.coerceIn(0, SIZES.size - 1)
        Store.putStr(act, "discord_win_size", sizeIdx.toString())
        box?.layoutParams = params()
        box?.requestLayout()
        applyScale()
    }

    fun setAlphaStep(i: Int) {
        alphaIdx = i.coerceIn(0, ALPHAS.size - 1)
        Store.putStr(act, "discord_win_alpha", alphaIdx.toString())
        box?.alpha = ALPHAS[alphaIdx] / 100f
    }

    /** Slide the window by this many pixels. */
    fun moveBy(dx: Int, dy: Int) {
        val rx = Math.max(1, screenW() - boxW())
        val ry = Math.max(1, screenH() - boxH())
        fx = (fx + dx.toFloat() / rx).coerceIn(0f, 1f)
        fy = (fy + dy.toFloat() / ry).coerceIn(0f, 1f)
        box?.layoutParams = params()
        box?.requestLayout()
    }

    fun savePos() {
        Store.putStr(act, "discord_win_fx", fx.toString())
        Store.putStr(act, "discord_win_fy", fy.toString())
    }

    /** Show a short instruction on the window while it is being moved (null puts the normal label back). */
    fun hint(text: String?) {
        soundTag?.text = text ?: if (hearDiscord) "Discord sound" else "Film sound"
    }

    // ------------------------------------------------------------------ sound
    /** Switch between hearing the film and hearing Discord. Muting Discord is best effort: it silences the video and audio on the page. */
    fun swapSound() {
        hearDiscord = !hearDiscord
        setFilmVolume(if (hearDiscord) 0f else 1f)
        applyMute()
        hint(null)
    }

    private fun applyMute() {
        val w = web ?: return
        val mute = !hearDiscord
        w.evaluateJavascript(
            "(function(m){window.__loungeMute=m;if(!window.__loungeT){window.__loungeT=setInterval(function(){" +
                "var l=document.querySelectorAll('video,audio');for(var i=0;i<l.length;i++){l[i].muted=window.__loungeMute;}},700);}})(" + mute + ")",
            null
        )
    }

    // ------------------------------------------------------------------ the pointer (driven by the phone remote's Mouse mode)
    /** The window takes the remote's arrow keys only while the phone's Mouse mode is in use; [release] gives them back to the film. */
    private fun engage() {
        val w = web ?: return
        if (engaged) return
        engaged = true
        box?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.requestFocus()
    }

    fun release() {
        val w = web ?: return
        engaged = false
        w.clearFocus()
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        box?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        pointer?.visibility = View.GONE
    }

    private fun updatePointer() {
        val ptr = pointer ?: return
        val s = scale()
        val half = Skin.dp(act, 8)
        ptr.translationX = pad() + ptX * dens * s - half
        ptr.translationY = pad() + ptY * dens * s - half
    }

    private fun showPointer() {
        val ptr = pointer ?: return
        ptr.visibility = View.VISIBLE
        ptr.bringToFront()
        ptr.removeCallbacks(hidePtr)
        ptr.postDelayed(hidePtr, 4000)
    }

    /** Move the pointer by this many page pixels. */
    fun pointerBy(dx: Float, dy: Float) {
        if (web == null) return
        engage()
        ptX = (ptX + dx).coerceIn(0f, VW - 1f)
        ptY = (ptY + dy).coerceIn(0f, VH - 1f)
        updatePointer()
        showPointer()
    }

    fun click(right: Boolean) {
        val w = web ?: return
        engage()
        showPointer()
        if (right) {
            w.evaluateJavascript(
                "(function(x,y){var e=document.elementFromPoint(x,y);if(e){e.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,clientX:x,clientY:y,button:2}));}})(" +
                    ptX.toInt() + "," + ptY.toInt() + ")",
                null
            )
            return
        }
        val t = SystemClock.uptimeMillis()
        val x = ptX * dens
        val y = ptY * dens
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        w.dispatchTouchEvent(down)
        w.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** amount: finger pixels dragged on the phone, positive = finger moved down (the page moves down with it). */
    fun scroll(amount: Float) {
        val w = web ?: return
        engage()
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = ptX * dens
            y = ptY * dens
            setAxisValue(MotionEvent.AXIS_VSCROLL, amount / 20f)
        })
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        w.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Type text into whatever box is selected on the Discord page. */
    fun type(text: String) {
        val w = web ?: return
        engage()
        w.evaluateJavascript(
            "(function(t){var e=document.activeElement;if(e){document.execCommand('insertText',false,t);}})(" + JSONObject.quote(text) + ")",
            null
        )
    }

    /** Press one key (Enter, Backspace) on the Discord page. */
    fun key(code: Int) {
        val w = web ?: return
        engage()
        val t = SystemClock.uptimeMillis()
        w.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        w.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
    }

    /** Take the window away. */
    fun close(giveSoundBack: Boolean = true) {
        pointer?.removeCallbacks(hidePtr)
        val w = web
        web = null
        engaged = false
        if (w != null) {
            try {
                w.stopLoading()
                w.loadUrl("about:blank")
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            } catch (e: Throwable) {
                Crash.log("Discord window close", e)
            }
        }
        box?.let { root.removeView(it) }
        box = null
        pointer = null
        soundTag = null
        if (giveSoundBack && hearDiscord) setFilmVolume(1f)
        hearDiscord = false
    }
}
