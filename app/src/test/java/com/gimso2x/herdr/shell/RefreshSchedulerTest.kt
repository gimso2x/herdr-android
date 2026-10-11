package com.gimso2x.herdr.shell

import com.gimso2x.herdr.access.AccessSession
import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.access.CookieInstaller
import com.gimso2x.herdr.access.CookieSink
import com.gimso2x.herdr.access.MemoryTokenStore
import com.gimso2x.herdr.access.ServiceToken
import com.gimso2x.herdr.access.TestJwt
import com.gimso2x.herdr.ui.SetupError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshSchedulerTest {
    private class FakeClock(var now: Long) : Clock {
        override fun nowMillis(): Long = now
    }

    private val ok = BootstrapResult.Success("set-cookie-not-printed", 0)

    /** Sleeper that records each request and advances the fake clock instead of waiting. */
    private class Harness(start: Long) {
        val clock = FakeClock(start)
        val sleeps = mutableListOf<Long>()
        val sleeper: suspend (Long) -> Unit = { ms ->
            sleeps += ms
            clock.now += ms
        }
    }

    @Test
    fun requestedSleepIsNextRefreshAtMinusNow() {
        val h = Harness(start = 1_800_000_000_000L)
        val exp = h.clock.now + 3_600_000L // cookie expires in 1 h
        val scheduler = RefreshScheduler(
            clock = h.clock,
            sleeper = h.sleeper,
            nextRefreshAt = { exp - 300_000L },
            refresh = { ok },
        )
        val outcome = runBlocking { scheduler.step() }
        assertEquals(RefreshOutcome.Fresh, outcome)
        assertEquals(listOf(3_300_000L), h.sleeps)
    }

    @Test
    fun sleepIsFlooredWhenNothingIsDueLater() {
        val h = Harness(start = 5_000_000L)
        val scheduler = RefreshScheduler(
            clock = h.clock,
            sleeper = h.sleeper,
            nextRefreshAt = { now -> now }, // no cookie yet
            refresh = { BootstrapResult.Network },
        )
        runBlocking {
            scheduler.step()
            scheduler.step()
        }
        assertEquals(listOf(RefreshScheduler.MIN_SLEEP_MILLIS, RefreshScheduler.MIN_SLEEP_MILLIS), h.sleeps)
        assertTrue(RefreshScheduler.MIN_SLEEP_MILLIS > 0)
    }

    @Test
    fun pastDueTimeIsAlsoFloored() {
        val h = Harness(start = 5_000_000L)
        val scheduler = RefreshScheduler(h.clock, h.sleeper, { it - 10_000L }, { ok })
        runBlocking { scheduler.step() }
        assertEquals(listOf(RefreshScheduler.MIN_SLEEP_MILLIS), h.sleeps)
    }

    @Test
    fun failureOutcomesAreMapped() {
        assertEquals(RefreshOutcome.Fresh, RefreshOutcome.of(ok))
        assertEquals(RefreshOutcome.NeedsSetup(SetupError.REJECTED), RefreshOutcome.of(BootstrapResult.InvalidToken))
        assertEquals(
            RefreshOutcome.NeedsSetup(SetupError.NO_COOKIE),
            RefreshOutcome.of(BootstrapResult.StrictModeOrNoCookie),
        )
        assertEquals(RefreshOutcome.KeepPage, RefreshOutcome.of(BootstrapResult.Network))
        assertEquals(RefreshOutcome.KeepPage, RefreshOutcome.of(BootstrapResult.Unexpected(503)))
    }

    @Test
    fun networkKeepsLoopingAndSetupErrorEndsIt() {
        val h = Harness(start = 1_000_000L)
        val results = ArrayDeque(
            listOf(BootstrapResult.Network, ok, BootstrapResult.Unexpected(502), BootstrapResult.StrictModeOrNoCookie),
        )
        var refreshes = 0
        val exp = 1_000_000L + 3_600_000L
        val scheduler = RefreshScheduler(
            clock = h.clock,
            sleeper = h.sleeper,
            nextRefreshAt = { exp - 300_000L },
            refresh = {
                refreshes++
                results.removeFirst()
            },
        )
        val end = runBlocking { scheduler.run() }
        assertEquals(RefreshOutcome.NeedsSetup(SetupError.NO_COOKIE), end)
        assertEquals(4, refreshes)
        // First sleep is the full distance; once the due time has passed every sleep is the floor.
        assertEquals(3_300_000L, h.sleeps.first())
        assertTrue(h.sleeps.drop(1).all { it == RefreshScheduler.MIN_SLEEP_MILLIS })
    }

    @Test
    fun invalidTokenEndsTheLoopImmediately() {
        val h = Harness(start = 0L)
        val scheduler = RefreshScheduler(h.clock, h.sleeper, { it + 1_000L }, { BootstrapResult.InvalidToken })
        val end = runBlocking { scheduler.run() }
        assertEquals(SetupError.REJECTED, end.error)
        assertEquals(1, h.sleeps.size)
    }

    @Test
    fun accessRecoveryGivesUpOnSecondHitWithin30Seconds() {
        val clock = FakeClock(1_000L)
        val recovery = AccessRecovery(clock)
        assertFalse(recovery.onHit())
        clock.now += 29_999L
        assertTrue(recovery.onHit())
        // After giving up the window starts over.
        clock.now += 1L
        assertFalse(recovery.onHit())
    }

    @Test
    fun accessRecoveryForgivesHitsMoreThan30SecondsApart() {
        val clock = FakeClock(0L)
        val recovery = AccessRecovery(clock)
        assertFalse(recovery.onHit())
        clock.now += AccessRecovery.WINDOW_MILLIS
        assertFalse(recovery.onHit())
        clock.now += AccessRecovery.WINDOW_MILLIS + 1
        assertFalse(recovery.onHit())
    }

    @Test
    fun backOrderIsImeThenHistoryThenExit() {
        assertEquals(BackAction.HIDE_IME, BackPolicy.actionFor(imeVisible = true, canGoBack = true))
        assertEquals(BackAction.HIDE_IME, BackPolicy.actionFor(imeVisible = true, canGoBack = false))
        assertEquals(BackAction.GO_BACK, BackPolicy.actionFor(imeVisible = false, canGoBack = true))
        assertEquals(BackAction.EXIT, BackPolicy.actionFor(imeVisible = false, canGoBack = false))
    }

    private class SkewClock(var wall: Long, var elapsed: Long) : Clock {
        override fun nowMillis(): Long = wall
        override fun elapsedMillis(): Long = elapsed
    }

    private class NullSink : CookieSink {
        override fun setCookie(url: String, value: String) = Unit
        override fun flush() = Unit
    }

    /** A one-hour cookie issued at [serverNow] (server Date header) to a device whose clock is off by [skew]. */
    private fun renewalSleeps(skew: Long): List<Long> {
        val serverNow = 1_800_000_000_000L
        val exp = serverNow / 1000 + 3600
        val clock = SkewClock(wall = serverNow + skew, elapsed = 10_000L)
        var fetches = 0
        val session = AccessSession(
            fetcher = {
                fetches++
                BootstrapResult.Success(TestJwt.setCookie(TestJwt.unsigned(exp)), exp, serverNow)
            },
            store = MemoryTokenStore(ServiceToken("fixture-id", "fixture-secret")),
            installer = CookieInstaller("https://herdr.example", NullSink()),
            clock = clock,
        )
        runBlocking { session.ensureFresh() }
        val sleeps = mutableListOf<Long>()
        val scheduler = RefreshScheduler(
            clock = clock,
            sleeper = { ms ->
                sleeps += ms
                clock.wall += ms
                clock.elapsed += ms
            },
            nextRefreshAt = { now -> session.nextRefreshAt(now) },
            refresh = { session.ensureFresh() },
        )
        runBlocking { scheduler.step() }
        assertEquals("the sleep ended with a real renewal", 2, fetches)
        return sleeps
    }

    @Test
    fun renewalSleepIgnoresDeviceClockSkew() {
        val fiftyFiveMinutes = 3_300_000L
        assertEquals(listOf(fiftyFiveMinutes), renewalSleeps(skew = -600_000L)) // device 10 min behind
        assertEquals(listOf(fiftyFiveMinutes), renewalSleeps(skew = 600_000L)) // device 10 min ahead
        assertEquals(listOf(fiftyFiveMinutes), renewalSleeps(skew = 0L))
    }
}
