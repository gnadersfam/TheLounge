package com.lounge.tv

import android.widget.FrameLayout

/** One page of the app (Home, Live TV, Football...). The main activity shows one at a time next to the icon rail. */
abstract class Screen(val act: MainActivity) : FrameLayout(act) {
    /** Called each time the page is opened. */
    open fun onShow() {}

    /** Called when another page replaces this one. */
    open fun onHide() {}

    /** Return true if Back was used up inside the page (closing a menu, going up a level...). */
    open fun onBack(): Boolean = false

    /** Put the remote's focus on the first sensible thing. */
    abstract fun focusFirst()

    /** The whole app went to the background (for example a video opened full screen). */
    open fun onActivityPause() {}

    /** The app came back to the front. */
    open fun onActivityResume() {}

    /** Logins or keys were changed on the phone. */
    open fun onKeysChanged() {}

    /** A few words about where the user is in this page (for the problem log). */
    open fun crumb(): String = ""

    /** Optional extra information when opened from elsewhere (for example a match to show). */
    open fun onArg(arg: Any?) {}
}
