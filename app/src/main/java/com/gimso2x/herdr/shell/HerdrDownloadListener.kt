package com.gimso2x.herdr.shell

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil

/**
 * Routes WebView downloads through [DownloadPolicy]. The cookie is read and attached only inside
 * the in-app branch, so it can never reach DownloadManager for any other host.
 */
class HerdrDownloadListener(
    private val context: Context,
    private val baseUrl: () -> String,
    private val openExternal: (String) -> Unit,
) : DownloadListener {

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        when (
            val decision = DownloadPolicy.decide(
                url = url,
                contentDisposition = contentDisposition,
                mimeType = mimetype,
                baseUrl = baseUrl(),
                guessFileName = { u, cd, mime -> URLUtil.guessFileName(u, cd, mime) },
            )
        ) {
            is DownloadDecision.Enqueue -> enqueue(decision, userAgent)
            is DownloadDecision.External -> openExternal(decision.url)
            DownloadDecision.Block -> Unit
        }
    }

    private fun enqueue(decision: DownloadDecision.Enqueue, userAgent: String?) {
        val request = DownloadManager.Request(Uri.parse(decision.url))
            .setMimeType(decision.mimeType)
            .setTitle(decision.fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, decision.fileName)
        CookieManager.getInstance().getCookie(decision.url)
            ?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        try {
            context.getSystemService(DownloadManager::class.java).enqueue(request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "download enqueue failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "HerdrDownload"
    }
}
