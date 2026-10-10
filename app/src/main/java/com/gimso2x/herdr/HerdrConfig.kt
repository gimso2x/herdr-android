package com.gimso2x.herdr

/**
 * The server the app talks to. There is no built-in address: it is what the user entered in setup
 * (kept in the encrypted store and mirrored here), or, in debug builds only, a loopback fixture
 * override installed by instrumentation.
 */
object HerdrConfig {
    @Volatile
    private var debugOverride: String? = null

    @Volatile
    private var stored: String? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var loader: (() -> String?)? = null

    /** Application start: [load] reads the stored address on first use (it may touch the keystore). */
    fun install(load: () -> String?) {
        loader = load
        loaded = false
        stored = null
    }

    /** Effective base URL (`https://host[:port]`, no trailing slash), or null while none is configured. */
    val baseUrlOrNull: String?
        get() = debugOverride ?: storedAddress()

    /** [baseUrlOrNull], or "" when unconfigured; callers that need a real address check [isConfigured] first. */
    val baseUrl: String
        get() = baseUrlOrNull.orEmpty()

    val isConfigured: Boolean
        get() = baseUrlOrNull != null

    /** True while a debug fixture override is installed (it wins over the stored address). */
    val hasDebugOverride: Boolean
        get() = debugOverride != null

    /** Mirrors a successful write to (or removal from) the store; null forgets the address. */
    fun setServerAddress(address: String?) {
        stored = address
        loaded = true
    }

    private fun storedAddress(): String? {
        if (!loaded) {
            val load = loader ?: return null
            stored = load()
            loaded = true
        }
        return stored
    }

    /** Debug builds only (instrumentation fixtures); ignored in release builds. Null/blank clears. */
    fun setDebugBaseUrl(url: String?) = applyOverride(url, BuildConfig.DEBUG)

    internal fun applyOverride(url: String?, debugBuild: Boolean) {
        if (!debugBuild) return
        val cleaned = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        require(cleaned == null || LOOPBACK_ORIGIN.matches(cleaned)) {
            "base url override must be http(s)://127.0.0.1 or localhost, optionally with a port"
        }
        debugOverride = cleaned
    }

    private val LOOPBACK_ORIGIN = Regex("""https?://(127\.0\.0\.1|localhost)(:\d{1,5})?""")
}
