package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.Clock
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug builds only: seams for instrumented tests, a controllable clock and an on-demand poll.
 * Release builds get a stub (src/release) that exposes the real clock and nothing else.
 */
object WatchTestHooks {
    /** Time source of the running service's [StatusTracker]; replace to fake turn lengths. */
    @Volatile
    var clock: Clock = Clock.SYSTEM

    val lastPoll: StateFlow<PollResult> get() = WatchRuntime.lastPoll

    /**
     * Runs one poll now and returns after it completes, including the alert detail fetches it started
     * (so the posted notifications are final); [PollOutcome.NOT_RUNNING] when no service is up.
     */
    suspend fun pollNow(): PollResult {
        val poller = WatchRuntime.poller ?: return PollResult(-1, PollOutcome.NOT_RUNNING)
        return poller.pollOnce().also { poller.awaitDetails() }
    }
}
