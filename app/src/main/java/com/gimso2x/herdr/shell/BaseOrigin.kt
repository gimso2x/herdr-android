package com.gimso2x.herdr.shell

import java.net.URI
import java.util.Locale

/**
 * Exact scheme + host + port comparison against the effective base URL (HerdrConfig.baseUrl,
 * which carries the debug fixture override). Pure; anything that does not parse cleanly, has no
 * host, or carries userinfo never matches.
 */
internal object BaseOrigin {
    fun matches(url: String?, baseUrl: String): Boolean {
        val candidate = originOf(url) ?: return false
        val base = originOf(baseUrl) ?: return false
        return candidate == base
    }

    private fun originOf(url: String?): Triple<String, String, Int>? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        return Triple(scheme, host, port)
    }
}
