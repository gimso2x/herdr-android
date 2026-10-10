package com.gimso2x.herdr.access

import java.net.URI
import java.util.Locale

/**
 * Validation and normalisation of the server address the user types (pure, JVM-testable).
 *
 * Accepted: `https://host` or `https://host:port`, optionally with one trailing slash. Rejected: any
 * other scheme, a missing host, userinfo, a path, a query, a fragment, whitespace. The result is
 * `https://host[:port]` without a trailing slash and with a lower-case host (port 443 is dropped).
 * Debug builds may additionally allow `http(s)://127.0.0.1|localhost[:port]` for test fixtures.
 */
object ServerAddress {
    const val MAX_LENGTH = 253 + 16

    /** The normalised origin, or null when [input] is not an acceptable server address. */
    fun parse(input: String, allowLoopback: Boolean = false): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        if (text.any { it.isWhitespace() || it.isISOControl() || it == '\\' || it == '?' || it == '#' || it == '@' }) return null
        if (text.trimEnd('/').endsWith(":")) return null
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        val loopback = host == "127.0.0.1" || host == "localhost"
        if (scheme != "https" && !(allowLoopback && loopback && scheme == "http")) return null
        if (uri.port == 0 || uri.port > 65535) return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    /** `*.cloudflareaccess.com` (Cloudflare Access login/team hosts); the registrable domain is not matched. */
    fun isAccessHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h.length > ACCESS_SUFFIX.length && h.endsWith(ACCESS_SUFFIX)
    }

    private const val ACCESS_SUFFIX = ".cloudflareaccess.com"
}
