package com.gimso2x.herdr.access

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the Access cookie fresh. [ensureFresh] refreshes when there is no cookie, when forced, or
 * when the cookie has `<= 300 s` of life left (inclusive, so a scheduler waking at [nextRefreshAt]
 * always refreshes). Refreshes are single-flight: callers that arrive while a refresh runs share
 * its outcome. No stored token yields [BootstrapResult.InvalidToken] without a request.
 *
 * Remaining life is `exp - server time`, where server time is the response's `Date` header (the
 * device wall clock only when the response carried none). It is turned into a deadline on the
 * monotonic [Clock.elapsedMillis] at the moment the cookie arrives, so a wrong or edited device
 * wall clock can neither postpone renewal past the server-side expiry nor trigger it early.
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

    /** [Clock.elapsedMillis] reading at which [current] expires on the server. */
    @Volatile
    private var expiresAtElapsed = 0L

    @Volatile
    private var lastResult: BootstrapResult? = null

    suspend fun ensureFresh(force: Boolean = false): BootstrapResult {
        val seen = generation.get()
        return mutex.withLock {
            if (generation.get() != seen) lastResult?.let { return@withLock it }
            val cached = current
            if (!force && cached != null && !isDue()) return@withLock cached
            refresh()
        }
    }

    /**
     * Wall-clock millis, in the caller's [now] frame, at which the next refresh is due; [now] when
     * there is no cookie yet. The distance is measured on the monotonic clock.
     */
    fun nextRefreshAt(now: Long): Long {
        if (current == null) return now
        return now + (untilExpiry() - REFRESH_LEAD_MILLIS)
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

    private fun untilExpiry(): Long = expiresAtElapsed - clock.elapsedMillis()

    private fun isDue(): Boolean = untilExpiry() <= REFRESH_LEAD_MILLIS

    private suspend fun refresh(): BootstrapResult {
        val startedAt = generation.get()
        val token = store.get()
        val result = if (token == null) BootstrapResult.InvalidToken else fetcher.fetch(token)
        if (generation.get() != startedAt) return result // invalidated mid-flight: do not cache
        if (result is BootstrapResult.Success) {
            installer.install(result.setCookieHeader)
            val serverNow = result.serverTimeMillis ?: clock.nowMillis()
            expiresAtElapsed = clock.elapsedMillis() + (result.expEpochSeconds * 1000 - serverNow)
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
