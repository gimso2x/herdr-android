package com.gimso2x.herdr.access

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessSessionTest {
    private class FakeClock(var nowMillis: Long) : Clock {
        override fun nowMillis(): Long = nowMillis
    }

    private class RecordingSink : CookieSink {
        val installed = mutableListOf<String>()
        var flushes = 0
        override fun setCookie(url: String, value: String) {
            installed += value
        }

        override fun flush() {
            flushes++
        }
    }

    private val now = 1_800_000_000L // epoch seconds
    private val clock = FakeClock(now * 1000)
    private val sink = RecordingSink()
    private val calls = AtomicInteger()

    /** Fetcher whose cookie always expires [ttlSeconds] after the (fake) time of the call. */
    private fun session(
        ttlSeconds: Long = 3600,
        store: TokenStore = MemoryTokenStore(ServiceToken("fixture-id", "fixture-secret")),
        result: (Long) -> BootstrapResult = {
            BootstrapResult.Success(TestJwt.setCookie(TestJwt.unsigned(it)), it)
        },
    ) = AccessSession(
        fetcher = { _ ->
            calls.incrementAndGet()
            result(clock.nowMillis / 1000 + ttlSeconds)
        },
        store = store,
        installer = CookieInstaller("https://herdr.example", sink),
        clock = clock,
    )

    @Test
    fun noCookieRefreshes() = runBlocking {
        val s = session()
        assertTrue(s.ensureFresh() is BootstrapResult.Success)
        assertEquals(1, calls.get())
        assertEquals(1, sink.installed.size)
        assertEquals(1, sink.flushes)
    }

    @Test
    fun expInOneHourDoesNotRefresh() = runBlocking {
        val s = session(ttlSeconds = 3600)
        s.ensureFresh()
        s.ensureFresh()
        assertEquals(1, calls.get())
    }

    @Test
    fun expIn100SecondsRefreshes() = runBlocking {
        val s = session(ttlSeconds = 100)
        s.ensureFresh()
        s.ensureFresh()
        assertEquals(2, calls.get())
    }

    @Test
    fun exactlyThreeHundredSecondsLeftRefreshesInclusive() = runBlocking {
        val s = session(ttlSeconds = 300)
        s.ensureFresh()
        s.ensureFresh()
        assertEquals(2, calls.get())
    }

    @Test
    fun threeHundredOneSecondsLeftDoesNotRefresh() = runBlocking {
        val s = session(ttlSeconds = 301)
        s.ensureFresh()
        s.ensureFresh()
        assertEquals(1, calls.get())
    }

    @Test
    fun refreshesOnceClockReachesNextRefreshAt() = runBlocking {
        val s = session(ttlSeconds = 3600)
        s.ensureFresh()
        val due = s.nextRefreshAt(clock.nowMillis)
        clock.nowMillis = due - 1
        s.ensureFresh()
        assertEquals(1, calls.get())
        clock.nowMillis = due
        s.ensureFresh()
        assertEquals(2, calls.get())
    }

    @Test
    fun forceRefreshesEvenWhenFresh() = runBlocking {
        val s = session(ttlSeconds = 3600)
        s.ensureFresh()
        s.ensureFresh(force = true)
        assertEquals(2, calls.get())
        assertEquals(2, sink.installed.size)
    }

    @Test
    fun nextRefreshAtIsExpMinusThreeHundredSeconds() = runBlocking {
        val s = session(ttlSeconds = 3600)
        val before = clock.nowMillis
        assertEquals("no cookie: due now", before, s.nextRefreshAt(before))
        s.ensureFresh()
        assertEquals(before + 3_300_000L, s.nextRefreshAt(before))
        // The distance is measured on the monotonic clock, so it is relative to the caller's `now`.
        assertEquals(before + 5_000 + 3_300_000L, s.nextRefreshAt(before + 5_000))
    }

    @Test
    fun concurrentEnsureFreshMakesOneRequest() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val s = AccessSession(
                fetcher = { _ ->
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    BootstrapResult.Success(TestJwt.setCookie(TestJwt.unsigned(now + 3600)), now + 3600)
                },
                store = MemoryTokenStore(ServiceToken("fixture-id", "fixture-secret")),
                installer = CookieInstaller("https://herdr.example", sink),
                clock = clock,
            )
            val first = async { s.ensureFresh() }
            val second = async { s.ensureFresh() }
            entered.await() // first holds the lock inside fetch; second is queued behind it
            release.complete(Unit)
            val a = first.await()
            val b = second.await()
            assertEquals(1, calls.get())
            assertTrue(a is BootstrapResult.Success)
            assertEquals(a, b)
            assertEquals(1, sink.installed.size)
        }
    }

    @Test
    fun concurrentForcedCallsShareOneRequest() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val s = AccessSession(
                fetcher = { _ ->
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    BootstrapResult.Success(TestJwt.setCookie(TestJwt.unsigned(now + 3600)), now + 3600)
                },
                store = MemoryTokenStore(ServiceToken("fixture-id", "fixture-secret")),
                installer = CookieInstaller("https://herdr.example", sink),
                clock = clock,
            )
            val first = async { s.ensureFresh(force = true) }
            val second = async { s.ensureFresh(force = true) }
            entered.await()
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun failedRefreshInstallsNothingAndKeepsNextRefreshAtDueNow() = runBlocking {
        val s = session(result = { BootstrapResult.InvalidToken })
        assertEquals(BootstrapResult.InvalidToken, s.ensureFresh())
        assertEquals(0, sink.installed.size)
        assertEquals(clock.nowMillis, s.nextRefreshAt(clock.nowMillis))
        s.ensureFresh()
        assertEquals("a failure does not suppress the next attempt", 2, calls.get())
    }

    @Test
    fun missingTokenIsInvalidTokenWithoutRequest() = runBlocking {
        val s = session(store = MemoryTokenStore())
        assertEquals(BootstrapResult.InvalidToken, s.ensureFresh())
        assertEquals(0, calls.get())
    }

    @Test
    fun invalidateDropsCookieSoNextEnsureFreshRefreshes() = runBlocking {
        val s = session(ttlSeconds = 3600)
        s.ensureFresh()
        assertEquals(1, calls.get())
        s.invalidate()
        assertEquals("no cookie again: due now", clock.nowMillis, s.nextRefreshAt(clock.nowMillis))
        s.ensureFresh()
        assertEquals(2, calls.get())
    }

    @Test
    fun invalidateDuringRefreshDoesNotCacheTheStaleOutcome() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val s = AccessSession(
                fetcher = { _ ->
                    if (calls.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    BootstrapResult.Success(TestJwt.setCookie(TestJwt.unsigned(now + 3600)), now + 3600)
                },
                store = MemoryTokenStore(ServiceToken("fixture-id", "fixture-secret")),
                installer = CookieInstaller("https://herdr.example", sink),
                clock = clock,
            )
            val first = async { s.ensureFresh() }
            entered.await()
            s.invalidate()
            release.complete(Unit)
            first.await()
            assertEquals(0, sink.installed.size)
            s.ensureFresh()
            assertEquals(2, calls.get())
            assertEquals(1, sink.installed.size)
        }
    }

    /** Wall clock and monotonic clock move independently, like a device whose wall clock is wrong. */
    private class SkewClock(var wall: Long, var elapsed: Long) : Clock {
        override fun nowMillis(): Long = wall
        override fun elapsedMillis(): Long = elapsed
    }

    private val serverNow = now * 1000

    private fun skewedSession(clock: SkewClock, withDate: Boolean = true) = AccessSession(
        fetcher = { _ ->
            calls.incrementAndGet()
            val exp = now + 3600 // one-hour cookie issued at server time
            BootstrapResult.Success(
                TestJwt.setCookie(TestJwt.unsigned(exp)),
                exp,
                if (withDate) serverNow else null,
            )
        },
        store = MemoryTokenStore(ServiceToken("fixture-id", "fixture-secret")),
        installer = CookieInstaller("https://herdr.example", sink),
        clock = clock,
    )

    @Test
    fun deviceTenMinutesBehindStillRenewsFiftyFiveMinutesAfterIssue() = runBlocking {
        val clock = SkewClock(wall = serverNow - 600_000, elapsed = 5_000)
        val s = skewedSession(clock)
        s.ensureFresh()
        val wall = clock.wall
        assertEquals("55 min from now, not 65", wall + 3_300_000L, s.nextRefreshAt(wall))
        clock.elapsed += 3_299_999L
        clock.wall += 3_299_999L
        s.ensureFresh()
        assertEquals(1, calls.get())
        clock.elapsed += 1L
        clock.wall += 1L
        s.ensureFresh()
        assertEquals(2, calls.get())
    }

    @Test
    fun deviceTenMinutesAheadDoesNotRenewEarly() = runBlocking {
        val clock = SkewClock(wall = serverNow + 600_000, elapsed = 5_000)
        val s = skewedSession(clock)
        s.ensureFresh()
        assertEquals(clock.wall + 3_300_000L, s.nextRefreshAt(clock.wall))
        s.ensureFresh()
        assertEquals("a cookie with 60 min of life is not due", 1, calls.get())
    }

    @Test
    fun wallClockJumpsDoNotMoveTheDeadline() = runBlocking {
        val clock = SkewClock(wall = serverNow, elapsed = 5_000)
        val s = skewedSession(clock)
        s.ensureFresh()
        clock.wall += 7_200_000L // user sets the clock two hours ahead
        s.ensureFresh()
        assertEquals(1, calls.get())
        clock.wall -= 14_400_000L // and then two hours behind
        clock.elapsed += 3_300_000L // while 55 real minutes pass
        s.ensureFresh()
        assertEquals(2, calls.get())
    }

    @Test
    fun withoutServerDateTheDeviceClockIsTheFallback() = runBlocking {
        val clock = SkewClock(wall = serverNow - 600_000, elapsed = 5_000)
        val s = skewedSession(clock, withDate = false)
        s.ensureFresh()
        assertEquals("device clock taken at face value", clock.wall + 3_900_000L, s.nextRefreshAt(clock.wall))
    }
}
