package com.gimso2x.herdr.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide state shared by [StatusWatcherService] and the debug-only `WatchTestHooks`. */
internal object WatchRuntime {
    private val last = MutableStateFlow(PollResult.NONE)
    val lastPoll: StateFlow<PollResult> = last.asStateFlow()

    @Volatile
    var poller: WatchPoller? = null

    fun publish(result: PollResult) {
        last.value = result
    }
}
