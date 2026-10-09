package com.gimso2x.herdr.shell

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

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
 * In-app means an exact scheme + host + port match with the herdr origin (or, in debug builds
 * only, the fixture origin passed as [debugOrigin]). Anything that does not parse cleanly, carries
 * userinfo, or has a look-alike host is never in-app.
 */
class UrlPolicy(debugOrigin: String? = null) {
    private val inAppOrigins: Set<Origin> = buildSet {
        add(HERDR_ORIGIN)
        debugOrigin?.let { originOf(it) }?.let { add(it) }
    }

    fun decide(url: String, isMainFrame: Boolean): UrlDecision {
        val uri = parse(url) ?: return UrlDecision.Block
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return UrlDecision.Block
        return when (scheme) {
            "http", "https" -> decideWeb(url, uri, scheme, isMainFrame)
            "blob" -> decideBlob(uri)
            "about" -> if (url == "about:blank") UrlDecision.InApp else UrlDecision.Block
            "intent", "tel", "mailto" -> if (isMainFrame) UrlDecision.External(url) else UrlDecision.Block
            // javascript:, file:, content:, data:, ftp:, custom schemes: never loaded or forwarded.
            else -> UrlDecision.Block
        }
    }

    /** True when [url] parses to an in-app (herdr or debug fixture) origin. */
    fun isInApp(url: String): Boolean = decide(url, isMainFrame = true) == UrlDecision.InApp

    private fun decideWeb(url: String, uri: URI, scheme: String, isMainFrame: Boolean): UrlDecision {
        val origin = originOf(uri, scheme) ?: return UrlDecision.Block
        if (uri.rawUserInfo != null) return if (isMainFrame) UrlDecision.External(url) else UrlDecision.Block
        if (origin in inAppOrigins) return UrlDecision.InApp
        if (!isMainFrame) return UrlDecision.Block
        if (origin == ACCESS_ORIGIN) return UrlDecision.RefreshSession
        return UrlDecision.External(url)
    }

    // blob:https://host/uuid is in-app only when the embedded origin is.
    private fun decideBlob(uri: URI): UrlDecision {
        val inner = uri.rawSchemeSpecificPart?.let { parse(it) } ?: return UrlDecision.Block
        val innerScheme = inner.scheme?.lowercase(Locale.ROOT) ?: return UrlDecision.Block
        if (innerScheme != "http" && innerScheme != "https") return UrlDecision.Block
        if (inner.rawUserInfo != null) return UrlDecision.Block
        val origin = originOf(inner, innerScheme) ?: return UrlDecision.Block
        return if (origin in inAppOrigins) UrlDecision.InApp else UrlDecision.Block
    }

    internal data class Origin(val scheme: String, val host: String, val port: Int)

    companion object {
        const val HERDR_HOST = "herdr.example.com"
        const val ACCESS_HOST = "your-team.cloudflareaccess.com"
        private val HERDR_ORIGIN = Origin("https", HERDR_HOST, 443)
        private val ACCESS_ORIGIN = Origin("https", ACCESS_HOST, 443)

        private fun parse(url: String): URI? = try {
            URI(url)
        } catch (_: Exception) {
            null
        }

        private fun originOf(url: String): Origin? {
            val uri = parse(url) ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            return originOf(uri, scheme)
        }

        /** Null for non-http(s), opaque or host-less URIs. */
        private fun originOf(uri: URI, scheme: String): Origin? {
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
            return Origin(scheme, host, port)
        }
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
