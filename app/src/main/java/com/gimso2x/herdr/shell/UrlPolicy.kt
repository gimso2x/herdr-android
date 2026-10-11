package com.gimso2x.herdr.shell

import com.gimso2x.herdr.access.ServerAddress
import java.net.URLEncoder

/** What the WebView should do with a navigation. */
sealed interface UrlDecision {
    /** Let the WebView load it. */
    data object InApp : UrlDecision

    /** Main frame reached Cloudflare Access: cancel, renew the cookie, reload the last herdr page. */
    data object RefreshSession : UrlDecision

    /** Hand to another app ([url] is the unmodified navigation URL). */
    data class External(val url: String) : UrlDecision

    /** Cancel silently. */
    data object Block : UrlDecision
}

/**
 * Pure navigation policy (no Android types, so it is JVM-testable).
 *
 * In-app means an exact scheme + host + port match with the configured server origin ([baseUrl] is
 * read on every decision, so a changed address takes effect at once; nothing is in-app while none is
 * configured). Anything that does not parse cleanly, carries userinfo, or has a look-alike host is
 * never in-app. A main-frame navigation to any `*.cloudflareaccess.com` host is a login redirect
 * (the session expired): [UrlDecision.RefreshSession].
 */
class UrlPolicy(private val baseUrl: () -> String?) {
    constructor(baseUrl: String?) : this({ baseUrl })

    private fun baseOrigin(): Origin? = baseUrl()?.let { WebUrls.parse(it)?.origin }

    fun decide(url: String, isMainFrame: Boolean): UrlDecision {
        if (url.any { it <= ' ' || it == '\u007f' }) return UrlDecision.Block
        val scheme = WebUrls.schemeOf(url) ?: return UrlDecision.Block
        return when (scheme) {
            "http", "https" -> decideWeb(url, isMainFrame)
            "blob" -> decideBlob(url)
            "about" -> if (url == "about:blank") UrlDecision.InApp else UrlDecision.Block
            "intent", "tel", "mailto" -> if (isMainFrame) UrlDecision.External(url) else UrlDecision.Block
            // javascript:, file:, content:, data:, ftp:, custom schemes: never loaded or forwarded.
            else -> UrlDecision.Block
        }
    }

    /** True when [url] parses to an in-app (herdr or debug fixture) origin. */
    fun isInApp(url: String): Boolean = decide(url, isMainFrame = true) == UrlDecision.InApp

    private fun decideWeb(url: String, isMainFrame: Boolean): UrlDecision {
        val web = WebUrls.parse(url) ?: return UrlDecision.Block
        if (web.hasUserInfo) return if (isMainFrame) UrlDecision.External(url) else UrlDecision.Block
        if (web.origin == baseOrigin()) return UrlDecision.InApp
        if (!isMainFrame) return UrlDecision.Block
        if (isAccess(web.origin)) return UrlDecision.RefreshSession
        return UrlDecision.External(url)
    }

    // blob:https://host/uuid is in-app only when the embedded origin is.
    private fun decideBlob(url: String): UrlDecision {
        val web = WebUrls.parse(url.substring("blob:".length)) ?: return UrlDecision.Block
        if (web.hasUserInfo) return UrlDecision.Block
        return if (web.origin == baseOrigin()) UrlDecision.InApp else UrlDecision.Block
    }

    private companion object {
        fun isAccess(origin: Origin): Boolean =
            origin.scheme == "https" && origin.port == 443 && ServerAddress.isAccessHost(origin.host)
    }
}

/** Builds the URL that opens a pane's chat (same shape as herdr-web-ui's notificationclick). */
object DeepLink {
    fun paneUrl(baseUrl: String, paneId: String): String =
        "${baseUrl.trimEnd('/')}/?machine=local&pane=${encodeComponent(paneId)}&view=chat"

    // Mirror encodeURIComponent: URLEncoder writes a space as '+'.
    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
