package com.gimso2x.herdr.watch

import com.gimso2x.herdr.access.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PollLoopTest {
    /** Monotonic time is [elapsed]; the wall clock is frozen so any use of it would show. */
    private class FakeClock(var elapsed: Long = 0) : Clock {
        override fun nowMillis(): Long = 42L

        override fun elapsedMillis(): Long = elapsed
    }

    @Test
    fun pauseIsTheRestOfTheIntervalAfterTheStart() {
        val loop = PollLoop(FakeClock(), intervalMillis = 15_000)
        assertEquals(15_000L, loop.pauseAfter(startedAt = 100, endedAt = 100))
        assertEquals(9_000L, loop.pauseAfter(startedAt = 100, endedAt = 6_100))
        assertEquals(0L, loop.pauseAfter(startedAt = 100, endedAt = 15_100))
        assertEquals("an overlong poll leaves no pause, not a negative or a full one", 0L, loop.pauseAfter(100, 40_100))
        assertEquals("a backwards reading never stretches the interval", 15_000L, loop.pauseAfter(100, 50))
    }

    @Test
    fun pollsStartFifteenSecondsApartUnlessOnePollRunsLonger() {
        val clock = FakeClock()
        val sleeps = mutableListOf<Long>()
        val starts = mutableListOf<Long>()
        val durations = ArrayDeque(listOf(6_000L, 20_000L, 100L))
        val loop = PollLoop(
            clock = clock,
            intervalMillis = 15_000,
            sleeper = { ms ->
                sleeps += ms
                clock.elapsed += ms
                if (sleeps.size == 3) throw CancellationException("enough cycles")
            },
        )
        runBlocking {
            try {
                loop.run {
                    starts += clock.elapsed
                    clock.elapsed += durations.removeFirst() // the slow fixture: status + enrichment time
                }
            } catch (_: CancellationException) {
                // the sleeper ends the loop after three cycles
            }
        }
        // A 6 s poll pauses 9 s; a 20 s poll pauses 0 (not another 15 s); a short poll pauses the rest.
        assertEquals(listOf(9_000L, 0L, 14_900L), sleeps)
        assertEquals(listOf(0L, 15_000L, 35_000L), starts)
    }
}
