package com.gimso2x.herdr.watch

import android.content.Context
import android.os.PowerManager

/** The slice of a partial wake lock the watcher needs; the seam that keeps [WatchWakeLock] JVM-testable. */
interface PartialWakeLock {
    val isHeld: Boolean

    fun acquire()

    fun release()
}

/**
 * Keeps the CPU running while the watcher is enabled, so the 15 s loop keeps firing on an exempted,
 * idle, screen-off device. [hold] and [drop] are idempotent; the owning service holds from the moment
 * watching starts and drops on every stop path (loop end and `onDestroy`).
 */
class WatchWakeLock(private val lock: PartialWakeLock) {
    fun hold() {
        if (!lock.isHeld) lock.acquire()
    }

    fun drop() {
        if (lock.isHeld) lock.release()
    }

    companion object {
        const val TAG = "herdr:watch"

        fun forContext(context: Context): WatchWakeLock {
            val power = context.getSystemService(PowerManager::class.java)
            val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply { setReferenceCounted(false) }
            return WatchWakeLock(
                object : PartialWakeLock {
                    override val isHeld: Boolean get() = wakeLock.isHeld

                    @Suppress("WakelockTimeout") // held for as long as watching is enabled, released in drop()
                    override fun acquire() = wakeLock.acquire()

                    override fun release() = wakeLock.release()
                },
            )
        }
    }
}
