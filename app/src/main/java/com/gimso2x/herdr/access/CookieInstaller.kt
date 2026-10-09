package com.gimso2x.herdr.access

import android.webkit.CookieManager

/** Thin seam over the Android WebView cookie store so JVM tests need no framework. */
interface CookieSink {
    fun setCookie(url: String, value: String)

    fun flush()
}

class AndroidCookieSink : CookieSink {
    override fun setCookie(url: String, value: String) {
        CookieManager.getInstance().setCookie(url, value)
    }

    override fun flush() {
        CookieManager.getInstance().flush()
    }
}

class CookieInstaller(
    private val baseUrl: String,
    private val sink: CookieSink = AndroidCookieSink(),
) {
    fun install(setCookieHeader: String) {
        sink.setCookie(baseUrl, setCookieHeader)
        sink.flush()
    }
}
