package com.gimso2x.herdr.access

import android.webkit.CookieManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Wipes every WebView cookie and returns once the wipe is done (a following setCookie is then safe). */
suspend fun clearAllCookies() {
    suspendCancellableCoroutine { continuation ->
        CookieManager.getInstance().removeAllCookies { continuation.resume(Unit) }
    }
    CookieManager.getInstance().flush()
}
