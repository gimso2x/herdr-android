package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Runs [poll] on a start-to-start schedule: each poll begins [intervalMillis] after the previous one
 * began, however long the previous one took (a slow poll shortens the following pause, down to none).
 * Time is read from [Clock.elapsedMillis], so wall-clock edits cannot stretch or collapse the interval.
 */
class PollLoop(
    private val clock: Clock = Clock.SYSTEM,
    private val intervalMillis: Long = StatusWatcherService.POLL_INTERVAL_MILLIS,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(poll: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            val startedAt = clock.elapsedMillis()
            poll()
            sleeper(pauseAfter(startedAt, clock.elapsedMillis()))
        }
    }

    /** Pause before the next poll for one that began at [startedAt] and ended at [endedAt]. */
    fun pauseAfter(startedAt: Long, endedAt: Long): Long =
        (startedAt + intervalMillis - endedAt).coerceIn(0L, intervalMillis)
}
