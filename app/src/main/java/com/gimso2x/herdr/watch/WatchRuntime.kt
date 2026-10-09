package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide state shared by [StatusWatcherService] and [WatchTestHooks]. */
internal object WatchRuntime {
    private val last = MutableStateFlow(PollResult.NONE)
    val lastPoll: StateFlow<PollResult> = last.asStateFlow()

    @Volatile
    var poller: WatchPoller? = null

    fun publish(result: PollResult) {
        last.value = result
    }
}

/** Seams for instrumented tests: a controllable clock and an on-demand poll. */
object WatchTestHooks {
    /** Time source of the running service's [StatusTracker]; replace to fake turn lengths. */
    @Volatile
    var clock: Clock = Clock.SYSTEM

    val lastPoll: StateFlow<PollResult> get() = WatchRuntime.lastPoll

    /** Runs one poll now and returns after it completes; [PollOutcome.NOT_RUNNING] when no service is up. */
    suspend fun pollNow(): PollResult =
        WatchRuntime.poller?.pollOnce() ?: PollResult(-1, PollOutcome.NOT_RUNNING)
}
