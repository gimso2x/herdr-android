package com.gimso2x.herdr.shell

/** What to do with the target of a popup / target=_blank / window.open. */
sealed interface PopupAction {
    /** Load in the main WebView (herdr origin only). */
    data class LoadInApp(val url: String) : PopupAction

    data class OpenExternal(val url: String) : PopupAction

    /** Nothing to do yet (initial about:blank, empty) - keep waiting for the real target. */
    data object Wait : PopupAction

    /** Drop it. */
    data object Ignore : PopupAction
}

/** Pure popup routing on top of [UrlPolicy]; a popup never gets a WebView of its own. */
object PopupPolicy {
    fun route(url: String?, policy: UrlPolicy): PopupAction {
        val target = url?.trim().orEmpty()
        if (target.isEmpty() || target == "about:blank") return PopupAction.Wait
        return when (val decision = policy.decide(target, isMainFrame = true)) {
            UrlDecision.InApp -> PopupAction.LoadInApp(target)
            is UrlDecision.External -> PopupAction.OpenExternal(decision.url)
            // Access redirects are handled by the main frame's own renewal; never from a popup.
            UrlDecision.RefreshSession, UrlDecision.Block -> PopupAction.Ignore
        }
    }
}
