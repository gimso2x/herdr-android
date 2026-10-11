package com.gimso2x.herdr.watch

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether an app activity is visible; process lifecycle owns alert suppression. */
object AppForeground {
    private val foreground = MutableStateFlow(false)
    val state: StateFlow<Boolean> = foreground.asStateFlow()
    private var registered = false

    /** Main thread only. Safe to repeat; a late observer is replayed the current state. */
    @Synchronized
    fun init() {
        if (registered) return
        registered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { foreground.value = false }
        })
    }

    fun isForeground(): Boolean = foreground.value
}
