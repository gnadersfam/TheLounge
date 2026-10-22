package com.lounge.tv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Background work and "back to the screen" helpers. */
object Bg {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun run(work: () -> Unit) {
        pool.execute {
            try {
                work()
            } catch (e: Throwable) {
                // each caller reports its own problems; this just keeps one failure from stopping the pool
            }
        }
    }

    fun ui(work: () -> Unit) {
        main.post(work)
    }

    fun uiDelayed(ms: Long, work: Runnable) {
        main.postDelayed(work, ms)
    }

    fun cancel(work: Runnable) {
        main.removeCallbacks(work)
    }
}
