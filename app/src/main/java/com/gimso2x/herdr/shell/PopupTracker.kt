package com.gimso2x.herdr.shell

/**
 * Owns the throwaway popup WebViews (as destroy callbacks) and their timeout [Runnable]s so none
 * outlives the activity: a resolved popup drops its timeout, [dispose] finishes everything left.
 * Main-thread only; [Timers] is the main looper in production and a fake in tests.
 */
class PopupTracker(private val timers: Timers, private val timeoutMillis: Long) {
    interface Timers {
        fun postDelayed(task: Runnable, delayMillis: Long)

        fun post(task: Runnable)

        /** Removes every queued copy of [task]. */
        fun cancel(task: Runnable)
    }

    private val open = LinkedHashSet<Popup>()

    inner class Popup internal constructor(private val destroy: () -> Unit) {
        private var finished = false
        private val finish = Runnable { finishNow() }

        /** True once the target was routed, timed out or disposed; further routing must be ignored. */
        var resolved = false
            private set

        internal fun schedule() = timers.postDelayed(finish, timeoutMillis)

        /** Target handled: drop the timeout and destroy on the next loop turn (never inside a WebView callback). */
        fun resolve() {
            if (resolved) return
            resolved = true
            timers.cancel(finish)
            timers.post(finish)
        }

        internal fun finishNow() {
            resolved = true
            timers.cancel(finish)
            if (finished) return
            finished = true
            open.remove(this)
            destroy()
        }
    }

    /** Starts tracking a popup; [destroy] runs exactly once, on resolve, timeout or [dispose]. */
    fun open(destroy: () -> Unit): Popup = Popup(destroy).also {
        open.add(it)
        it.schedule()
    }

    /** Activity is going away: cancel all pending callbacks and destroy every outstanding popup. */
    fun dispose() {
        for (popup in open.toList()) popup.finishNow()
    }
}
