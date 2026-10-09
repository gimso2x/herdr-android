package com.gimso2x.herdr.shell

/** What the first page load should do. */
data class StartupPlan(
    /** Try to restore the saved WebView history first (so Back still works). */
    val restoreHistory: Boolean,
    /** An explicit pane/start URL to load after a restore, or instead of it; null if none. */
    val explicitUrl: String?,
    /** Loaded when no history was restored and there is no [explicitUrl]. */
    val fallbackUrl: String,
)

/**
 * Pure first-load decision (no Android types). An explicit pane navigation always wins over saved
 * history: history is restored (when present) and then the pane URL is loaded on top of it.
 */
object StartupNavigation {
    /**
     * @param hasPendingState saved WebView history waiting to be restored
     * @param pendingStartUrl start URL saved across recreation (only saved when no history existed)
     * @param paneUrl pane URL delivered by a newer intent before the first load
     * @param rootUrl herdr root, used when nothing else applies
     */
    fun resolve(
        hasPendingState: Boolean,
        pendingStartUrl: String?,
        paneUrl: String?,
        rootUrl: String,
    ): StartupPlan = StartupPlan(
        restoreHistory = hasPendingState,
        explicitUrl = paneUrl ?: pendingStartUrl,
        fallbackUrl = rootUrl,
    )
}

enum class NotificationAction {
    /** Start the watcher right away. */
    START_WATCHER,

    /** Show the one-time POST_NOTIFICATIONS prompt; start the watcher in its result callback. */
    REQUEST_THEN_START,
}

/** Pure POST_NOTIFICATIONS gate: ask once per install, before the watcher, never block it. */
object NotificationGate {
    private const val TIRAMISU = 33

    fun decide(sdk: Int, granted: Boolean, asked: Boolean): NotificationAction =
        if (sdk >= TIRAMISU && !granted && !asked) NotificationAction.REQUEST_THEN_START
        else NotificationAction.START_WATCHER
}
