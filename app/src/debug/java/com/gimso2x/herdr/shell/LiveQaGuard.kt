package com.gimso2x.herdr.shell

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Debug builds only. Live-site UI tests render the real herdr-web-ui; this guard makes that
 * strictly read-only (see assets/live_qa_guard.js). Instrumentation calls [enable] with the base
 * origin before launching MainActivity (the token file reading that decides `liveQa=true` is the
 * instrumentation's job); without it nothing is installed.
 */
object LiveQaGuard {
    const val ASSET = "live_qa_guard.js"

    @Volatile
    private var origin: String? = null

    /** [origin] is scheme://host[:port], e.g. `https://herdr.example.com`. */
    fun enable(origin: String) {
        require(origin.startsWith("https://") || origin.startsWith("http://")) { "origin must be http(s)" }
        require(origin.removePrefix("https://").removePrefix("http://").none { it == '/' }) {
            "origin must not contain a path"
        }
        this.origin = origin
    }

    fun disable() {
        origin = null
    }

    val isEnabled: Boolean get() = origin != null

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Installs the script for the enabled origin; call before the first page load. Fails closed:
     * when enabled but unsupported the activity must not render the live site unguarded.
     */
    fun installIfEnabled(context: Context, webView: WebView): Boolean {
        val origin = origin ?: return false
        check(isSupported()) { "DOCUMENT_START_SCRIPT unsupported; refusing to load the live site unguarded" }
        val script = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
        return true
    }
}
