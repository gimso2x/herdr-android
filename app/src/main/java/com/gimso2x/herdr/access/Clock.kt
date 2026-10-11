package com.gimso2x.herdr.access

import android.os.SystemClock

/**
 * Injectable time source so refresh timing is testable without sleeping.
 *
 * [nowMillis] is the device wall clock (epoch milliseconds): it can be wrong or be edited by the
 * user. [elapsedMillis] is monotonic: it never jumps with wall-clock changes and keeps counting
 * while the device sleeps; only differences between two readings are meaningful. Clocks that only
 * model wall time (tests) get `elapsedMillis == nowMillis`.
 */
fun interface Clock {
    fun nowMillis(): Long

    fun elapsedMillis(): Long = nowMillis()

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun nowMillis(): Long = java.lang.System.currentTimeMillis()

            override fun elapsedMillis(): Long = try {
                SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                // Plain-JVM unit tests only: android.jar's SystemClock stub throws "not mocked".
                java.lang.System.nanoTime() / 1_000_000
            }
        }
    }
}
