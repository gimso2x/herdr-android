package com.gimso2x.herdr.shell

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class Origin(val scheme: String, val host: String, val port: Int)

/** A parsed http(s) URL reduced to what the policies compare: its origin and whether it carries userinfo. */
internal data class WebUrl(val origin: Origin, val hasUserInfo: Boolean)

/**
 * Pure http(s) URL parsing for the navigation, download and permission policies. It uses
 * okhttp3.HttpUrl, which (like Chromium) accepts characters such as `[ ] | ^ { }` in the path and
 * query; java.net.URI rejects them and so made same-origin links look foreign. Anything ambiguous
 * (whitespace/control characters, a backslash or percent escape in the authority, a missing `//`)
 * is refused rather than interpreted.
 */
internal object WebUrls {
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    /** Lower-cased scheme of [url], or null when it has none. */
    fun schemeOf(url: String): String? = SCHEME.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** Null for non-http(s) or unparseable input. */
    fun parse(url: String): WebUrl? {
        if (url.any { it <= ' ' || it == '\u007f' }) return null
        val scheme = schemeOf(url) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(scheme.length + 1)
        if (!rest.startsWith("//")) return null
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.any { it == '\\' || it == '%' }) return null
        val http = url.toHttpUrlOrNull() ?: return null
        val host = http.host.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() } ?: return null
        val hasUserInfo = authority.contains('@') || http.username.isNotEmpty() || http.password.isNotEmpty()
        return WebUrl(Origin(http.scheme, host, http.port), hasUserInfo)
    }
}
