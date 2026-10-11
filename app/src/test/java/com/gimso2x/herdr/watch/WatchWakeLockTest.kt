package com.gimso2x.herdr.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchWakeLockTest {
    private class FakeLock : PartialWakeLock {
        var held = false
        var acquires = 0
        var releases = 0
        override val isHeld: Boolean get() = held

        override fun acquire() {
            acquires++
            held = true
        }

        override fun release() {
            releases++
            held = false
        }
    }

    @Test
    fun holdAcquiresOnceAndIsIdempotent() {
        val lock = FakeLock()
        val wake = WatchWakeLock(lock)
        wake.hold()
        wake.hold()
        assertTrue(lock.held)
        assertEquals(1, lock.acquires)
    }

    @Test
    fun dropReleasesWhatWasHeldAndNothingElse() {
        val lock = FakeLock()
        val wake = WatchWakeLock(lock)
        wake.drop() // stop path before the loop ever started
        assertEquals(0, lock.releases)
        wake.hold()
        wake.drop()
        wake.drop() // loop end and onDestroy both drop
        assertFalse(lock.held)
        assertEquals(1, lock.releases)
    }

    @Test
    fun canBeHeldAgainAfterAStop() {
        val lock = FakeLock()
        val wake = WatchWakeLock(lock)
        wake.hold()
        wake.drop()
        wake.hold()
        assertTrue(lock.held)
        assertEquals(2, lock.acquires)
    }

    @Test
    fun tagNamesTheWatcher() {
        assertEquals("herdr:watch", WatchWakeLock.TAG)
    }
}
