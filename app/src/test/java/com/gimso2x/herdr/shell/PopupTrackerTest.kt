package com.gimso2x.herdr.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupTrackerTest {
    /** Deterministic stand-in for the main looper: queued runnables only run when the test says so. */
    private class FakeTimers : PopupTracker.Timers {
        val delayed = mutableListOf<Runnable>()
        val immediate = mutableListOf<Runnable>()

        override fun postDelayed(task: Runnable, delayMillis: Long) {
            delayed += task
        }

        override fun post(task: Runnable) {
            immediate += task
        }

        override fun cancel(task: Runnable) {
            delayed.removeAll { it === task }
            immediate.removeAll { it === task }
        }

        fun pending() = delayed.size + immediate.size

        fun runPosted() {
            val batch = immediate.toList()
            immediate.clear()
            batch.forEach { it.run() }
        }
    }

    private val timers = FakeTimers()
    private val tracker = PopupTracker(timers, 10_000L)
    private var destroyed = 0

    @Test
    fun resolveDropsTheTimeoutAndDestroysOnceOnTheNextTurn() {
        val popup = tracker.open { destroyed++ }
        assertEquals(1, timers.delayed.size)

        popup.resolve()

        assertTrue(popup.resolved)
        assertEquals("timeout must not stay queued", 0, timers.delayed.size)
        assertEquals("never destroyed inside the callback", 0, destroyed)
        timers.runPosted()
        assertEquals(1, destroyed)
        assertEquals(0, timers.pending())
    }

    @Test
    fun timeoutDestroysAnUnresolvedPopupOnce() {
        val popup = tracker.open { destroyed++ }

        timers.delayed.single().run()

        assertEquals(1, destroyed)
        assertTrue(popup.resolved)
        popup.resolve()
        assertEquals(0, timers.pending())
    }

    @Test
    fun disposeDestroysEveryOutstandingPopupAndLeavesNoCallbacks() {
        val unresolved = tracker.open { destroyed++ }
        val resolvedNotYetRun = tracker.open { destroyed++ }
        resolvedNotYetRun.resolve()
        val leftover = (timers.delayed + timers.immediate).toList()

        tracker.dispose()

        assertEquals(2, destroyed)
        assertEquals("no queued runnable may keep the popup reachable", 0, timers.pending())
        assertTrue(unresolved.resolved)
        // Even if a queued copy ran anyway, nothing is destroyed twice.
        leftover.forEach { it.run() }
        assertEquals(2, destroyed)
    }

    @Test
    fun disposeIgnoresPopupsThatAlreadyFinished() {
        tracker.open { destroyed++ }.also {
            it.resolve()
            timers.runPosted()
        }
        assertEquals(1, destroyed)

        tracker.dispose()

        assertEquals(1, destroyed)
    }

    @Test
    fun resolvedFlagStopsFurtherRouting() {
        val popup = tracker.open { destroyed++ }
        assertFalse(popup.resolved)
        popup.resolve()
        popup.resolve()
        timers.runPosted()
        assertEquals(1, destroyed)
    }
}
