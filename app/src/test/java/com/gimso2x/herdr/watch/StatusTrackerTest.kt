package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.watch.AgentStatus.BLOCKED
import com.gimso2x.herdr.watch.AgentStatus.DONE
import com.gimso2x.herdr.watch.AgentStatus.IDLE
import com.gimso2x.herdr.watch.AgentStatus.UNKNOWN
import com.gimso2x.herdr.watch.AgentStatus.WORKING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTrackerTest {
    private class FakeClock(var nowMillis: Long = 1_000_000L) : Clock {
        override fun nowMillis(): Long = nowMillis
    }

    private val clock = FakeClock()
    private val tracker = StatusTracker(clock)

    /** One poll at [atSeconds] after the start, with a single pane "p1" unless [samples] is given. */
    private fun poll(atSeconds: Long, status: AgentStatus, paneId: String = "p1"): List<Alert> =
        poll(atSeconds, listOf(PaneSample(paneId, status, "ws · $paneId")))

    private fun poll(atSeconds: Long, samples: List<PaneSample>): List<Alert> {
        clock.nowMillis = 1_000_000L + atSeconds * 1000
        return tracker.update(samples)
    }

    private fun kinds(alerts: List<Alert>) = alerts.map { it.kind }

    @Test
    fun seedEmitsNothing() {
        for ((i, status) in listOf(IDLE, DONE, WORKING, BLOCKED).withIndex()) {
            assertTrue(poll(0, status, "seed-$i").isEmpty())
        }
    }

    @Test
    fun idleWorkingBlockedEmitsNeedsInputOnce() {
        assertTrue(poll(0, IDLE).isEmpty())
        assertTrue(poll(15, WORKING).isEmpty())
        val alerts = poll(30, BLOCKED)
        assertEquals(listOf(AlertKind.NEEDS_INPUT), kinds(alerts))
        assertEquals("p1", alerts.single().paneId)
        assertEquals("ws · p1", alerts.single().title)
        assertEquals("입력이 필요합니다", alerts.single().body)
        assertTrue(poll(45, BLOCKED).isEmpty())
        assertTrue(poll(60, BLOCKED).isEmpty())
    }

    @Test
    fun blockedWorkingBlockedEmitsAgain() {
        poll(0, IDLE)
        poll(15, WORKING)
        assertEquals(listOf(AlertKind.NEEDS_INPUT), kinds(poll(30, BLOCKED)))
        assertTrue(poll(45, WORKING).isEmpty())
        assertEquals(listOf(AlertKind.NEEDS_INPUT), kinds(poll(60, BLOCKED)))
    }

    @Test
    fun idleDirectlyToBlockedIsAnEntryIntoBlocked() {
        poll(0, IDLE)
        assertEquals(listOf(AlertKind.NEEDS_INPUT), kinds(poll(15, BLOCKED)))
    }

    @Test
    fun firstSampleBlockedSeedsWithoutAlertAndStaysQuiet() {
        assertTrue(poll(0, BLOCKED).isEmpty())
        assertTrue(poll(15, BLOCKED).isEmpty())
    }

    @Test
    fun shortTurnDoesNotFinish() {
        poll(0, IDLE)
        poll(0, WORKING)
        assertTrue(poll(30, DONE).isEmpty())
    }

    @Test
    fun longTurnFinishesWhenGoingIdle() {
        poll(0, IDLE)
        assertTrue(poll(0, WORKING).isEmpty())
        val alerts = poll(61, IDLE)
        assertEquals(listOf(AlertKind.FINISHED), kinds(alerts))
        assertEquals("작업을 마쳤습니다", alerts.single().body)
        assertEquals("ws · p1", alerts.single().title)
        assertTrue(poll(76, IDLE).isEmpty())
    }

    @Test
    fun justUnderSixtySecondsDoesNotFinish() {
        poll(0, IDLE)
        poll(10, WORKING)
        clock.nowMillis = 1_000_000L + 10_000 + 59_999
        assertTrue(tracker.update(listOf(PaneSample("p1", DONE, "ws · p1"))).isEmpty())
    }

    @Test
    fun exactlySixtySecondsFinishes() {
        poll(0, IDLE)
        poll(0, WORKING)
        assertEquals(listOf(AlertKind.FINISHED), kinds(poll(60, DONE)))
    }

    @Test
    fun blockedThenDoneAfterLongTurnFinishes() {
        poll(0, IDLE)
        poll(0, WORKING)
        assertEquals(listOf(AlertKind.NEEDS_INPUT), kinds(poll(30, BLOCKED)))
        assertEquals(listOf(AlertKind.FINISHED), kinds(poll(90, DONE)))
    }

    @Test
    fun turnClockRestartsForTheNextTurn() {
        poll(0, IDLE)
        poll(0, WORKING)
        assertEquals(listOf(AlertKind.FINISHED), kinds(poll(100, IDLE)))
        poll(110, WORKING)
        assertTrue(poll(140, IDLE).isEmpty())
    }

    @Test
    fun firstSampleWorkingThenDoneIsUnknownStartAndSilent() {
        assertTrue(poll(0, WORKING).isEmpty())
        assertTrue(poll(600, DONE).isEmpty())
    }

    @Test
    fun unknownStartTurnStillAlertsWhenBlocked() {
        poll(0, WORKING)
        assertEquals(listOf(AlertKind.NEEDS_INPUT), kinds(poll(15, BLOCKED)))
        assertTrue(poll(600, DONE).isEmpty())
    }

    @Test
    fun blockedAndWorkingBetweenSamplesIsInvisible() {
        // Sampling limit: working -> blocked -> working inside one 15 s interval is never sampled.
        poll(0, IDLE)
        assertTrue(poll(0, WORKING).isEmpty())
        assertTrue(poll(15, WORKING).isEmpty())
        assertTrue(poll(30, WORKING).isEmpty())
    }

    @Test
    fun unknownIsIgnoredAndKeepsTheTurn() {
        poll(0, IDLE)
        poll(0, WORKING)
        assertTrue(poll(30, UNKNOWN).isEmpty())
        assertEquals(listOf(AlertKind.FINISHED), kinds(poll(61, DONE)))
    }

    @Test
    fun unknownDoesNotSeedAPane() {
        assertTrue(poll(0, UNKNOWN).isEmpty())
        assertTrue(poll(15, WORKING).isEmpty()) // this is the seed, so its start is unknown
        assertTrue(poll(200, DONE).isEmpty())
    }

    @Test
    fun vanishedPaneIsForgotten() {
        poll(0, IDLE)
        poll(0, WORKING)
        assertTrue(poll(30, emptyList()).isEmpty())
        // Back again: re-seeded as an unknown-start turn, so no FINISHED and no stale blocked state.
        assertTrue(poll(40, WORKING).isEmpty())
        assertTrue(poll(200, DONE).isEmpty())
    }

    @Test
    fun vanishedBlockedPaneSeedsQuietlyWhenItReturns() {
        poll(0, IDLE)
        poll(0, WORKING)
        assertEquals(listOf(AlertKind.NEEDS_INPUT), kinds(poll(15, BLOCKED)))
        poll(30, emptyList())
        assertTrue(poll(45, BLOCKED).isEmpty())
    }

    @Test
    fun panesAreTrackedIndependently() {
        poll(0, listOf(PaneSample("a", IDLE, "a"), PaneSample("b", IDLE, "b")))
        poll(0, listOf(PaneSample("a", WORKING, "a"), PaneSample("b", WORKING, "b")))
        val alerts = poll(70, listOf(PaneSample("a", DONE, "a"), PaneSample("b", BLOCKED, "b")))
        assertEquals(
            listOf("a" to AlertKind.FINISHED, "b" to AlertKind.NEEDS_INPUT),
            alerts.map { it.paneId to it.kind },
        )
    }

    @Test
    fun resetForgetsEverything() {
        poll(0, IDLE)
        poll(0, WORKING)
        tracker.reset()
        assertTrue(poll(100, DONE).isEmpty())
    }
}
