package com.gimso2x.herdr.shell

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

    private fun originOf(url: String?): Origin? {
        if (url.isNullOrBlank()) return null
        val web = WebUrls.parse(url.trim()) ?: return null
        return if (web.hasUserInfo) null else web.origin
    }
}
