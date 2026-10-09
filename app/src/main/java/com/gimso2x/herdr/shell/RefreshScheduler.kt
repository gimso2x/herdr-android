package com.gimso2x.herdr.shell

import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.ui.SetupError
import com.gimso2x.herdr.ui.SetupLogic
import kotlinx.coroutines.delay

/** What a refresh result means for the open page. */
sealed interface RefreshOutcome {
    /** Cookie is fresh. */
    data object Fresh : RefreshOutcome

    /** Transport/server trouble: keep the page, try again on the next cycle. */
    data object KeepPage : RefreshOutcome

    /** The token no longer works: open setup with [error]. */
    data class NeedsSetup(val error: SetupError) : RefreshOutcome

    companion object {
        fun of(result: BootstrapResult): RefreshOutcome = when (result) {
            is BootstrapResult.Success -> Fresh
            BootstrapResult.InvalidToken,
            BootstrapResult.StrictModeOrNoCookie,
            -> NeedsSetup(SetupLogic.errorFor(result)!!)
            BootstrapResult.Network,
            is BootstrapResult.Unexpected,
            -> KeepPage
        }
    }
}

/**
 * The in-activity renewal loop: sleep until the cookie is due ([nextRefreshAt] - now), then
 * refresh. [clock] and [sleeper] are seams so tests never really wait.
 *
 * A requested sleep is never shorter than [MIN_SLEEP_MILLIS]: with no cookie yet, a cookie that
 * is already due, or a failing server, `nextRefreshAt - now` is <= 0 and would spin.
 */
class RefreshScheduler(
    private val clock: Clock,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val nextRefreshAt: (now: Long) -> Long,
    private val refresh: suspend () -> BootstrapResult,
) {
    /** One cycle: sleep, then refresh. */
    suspend fun step(): RefreshOutcome {
        val now = clock.nowMillis()
        sleeper((nextRefreshAt(now) - now).coerceAtLeast(MIN_SLEEP_MILLIS))
        return RefreshOutcome.of(refresh())
    }

    /** Loops until a refresh needs setup (returned) or the caller cancels. */
    suspend fun run(): RefreshOutcome.NeedsSetup {
        while (true) {
            val outcome = step()
            if (outcome is RefreshOutcome.NeedsSetup) return outcome
        }
    }

    companion object {
        const val MIN_SLEEP_MILLIS = 15_000L
    }
}

/** Second Access redirect within [WINDOW_MILLIS] of the previous one means renewal does not help. */
class AccessRecovery(private val clock: Clock) {
    private var lastHitAt: Long? = null

    /** Records a hit; true when it is a repeat and the caller must give up (open setup). */
    fun onHit(): Boolean {
        val now = clock.nowMillis()
        val last = lastHitAt
        if (last != null && now - last < WINDOW_MILLIS) {
            lastHitAt = null
            return true
        }
        lastHitAt = now
        return false
    }

    companion object {
        const val WINDOW_MILLIS = 30_000L
    }
}

enum class BackAction { HIDE_IME, GO_BACK, EXIT }

/** Back order: keyboard first, then page history, then leave. */
object BackPolicy {
    fun actionFor(imeVisible: Boolean, canGoBack: Boolean): BackAction = when {
        imeVisible -> BackAction.HIDE_IME
        canGoBack -> BackAction.GO_BACK
        else -> BackAction.EXIT
    }
}
