package com.gimso2x.herdr

object HerdrConfig {
    const val BASE_URL: String = "https://herdr.example.com"

    @Volatile
    private var debugOverride: String? = null

    /** Effective base URL: [BASE_URL] unless a debug build installed an override. */
    val baseUrl: String
        get() = debugOverride ?: BASE_URL

    /** Debug builds only (instrumentation fixtures); ignored in release builds. Null/blank clears. */
    fun setDebugBaseUrl(url: String?) = applyOverride(url, BuildConfig.DEBUG)

    internal fun applyOverride(url: String?, debugBuild: Boolean) {
        if (!debugBuild) return
        val cleaned = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        require(cleaned == null || cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            "base url override must be http(s)"
        }
        debugOverride = cleaned
    }
}
