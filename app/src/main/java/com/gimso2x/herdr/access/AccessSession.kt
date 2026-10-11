package com.gimso2x.herdr.access

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the Access cookie fresh. [ensureFresh] refreshes when there is no cookie, when forced, or
 * when `exp - now <= 300 s` (inclusive, so a scheduler waking at [nextRefreshAt] always refreshes).
 * Refreshes are single-flight: callers that arrive while a refresh runs share its outcome.
 * No stored token yields [BootstrapResult.InvalidToken] without a request.
 */
class AccessSession(
    private val fetcher: CookieFetcher,
    private val store: TokenStore,
    private val installer: CookieInstaller,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val mutex = Mutex()

    private val generation = AtomicLong(0)

    @Volatile
    private var current: BootstrapResult.Success? = null

    @Volatile
    private var lastResult: BootstrapResult? = null

    suspend fun ensureFresh(force: Boolean = false): BootstrapResult {
        val seen = generation.get()
        return mutex.withLock {
            if (generation.get() != seen) lastResult?.let { return@withLock it }
            val cached = current
            if (!force && cached != null && !isDue(cached)) return@withLock cached
            refresh()
        }
    }

    /** Epoch millis at which the next refresh is due; [now] when there is no cookie yet. */
    fun nextRefreshAt(now: Long): Long {
        val cached = current ?: return now
        return cached.expEpochSeconds * 1000 - REFRESH_LEAD_MILLIS
    }

    /**
     * Drops the cached cookie and the shared last result and bumps the generation, so the next
     * [ensureFresh] performs a real refresh (e.g. after the service token changed). A refresh that
     * is already in flight keeps running but its outcome is neither installed nor cached.
     */
    fun invalidate() {
        current = null
        lastResult = null
        generation.incrementAndGet()
    }

    private fun isDue(cookie: BootstrapResult.Success): Boolean =
        cookie.expEpochSeconds * 1000 - clock.nowMillis() <= REFRESH_LEAD_MILLIS

    private suspend fun refresh(): BootstrapResult {
        val startedAt = generation.get()
        val token = store.get()
        val result = if (token == null) BootstrapResult.InvalidToken else fetcher.fetch(token)
        if (generation.get() != startedAt) return result // invalidated mid-flight: do not cache
        if (result is BootstrapResult.Success) {
            installer.install(result.setCookieHeader)
            current = result
        }
        lastResult = result
        generation.incrementAndGet()
        return result
    }

    companion object {
        const val REFRESH_LEAD_MILLIS = 300_000L
    }
}
