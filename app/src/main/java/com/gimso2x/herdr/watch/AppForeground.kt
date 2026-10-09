package com.gimso2x.herdr.watch

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Whether an app activity is visible; alerts are suppressed then (adapted from ompgui). */
object AppForeground {
    private val foreground = AtomicBoolean(false)
    private var registered = false

    /** Main thread only. Safe to repeat; a late observer is replayed the current state. */
    @Synchronized
    fun init() {
        if (registered) return
        registered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground.set(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground.set(false)
            }
        })
    }

    fun isForeground(): Boolean = foreground.get()

    /** Test-only override. */
    fun setForegroundForTest(value: Boolean) {
        foreground.set(value)
    }
}
