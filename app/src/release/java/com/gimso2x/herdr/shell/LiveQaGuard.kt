package com.gimso2x.herdr.shell

import android.content.Context
import android.webkit.WebView

/** Release stub: the live-QA guard script and its enabling entry point exist only in debug builds. */
object LiveQaGuard {
    @Suppress("UNUSED_PARAMETER")
    fun installIfEnabled(context: Context, webView: WebView): Boolean = false
}
