package com.gimso2x.herdr.shell

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.widget.Toast
import com.gimso2x.herdr.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Routes WebView downloads through [DownloadPolicy]. The cookie is read and attached only inside
 * the in-app branch, so it can never reach DownloadManager for any other host; before enqueueing,
 * [DownloadPreflight] proves the origin answers directly (no redirect that would carry the cookie
 * elsewhere). A rejected cookie triggers [renewAccess] and one more probe.
 */
class HerdrDownloadListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val baseUrl: () -> String,
    private val openExternal: (String) -> Unit,
    /** Forces an Access cookie renewal; true when a fresh cookie is installed. */
    private val renewAccess: suspend () -> Boolean,
    private val preflight: DownloadPreflight = DownloadPreflight(),
) : DownloadListener {
    /** Names handed to DownloadManager by this process whose files may not exist on disk yet. */
    private val reserved = HashSet<String>()

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
            is DownloadDecision.Enqueue -> scope.launch { download(decision, userAgent) }
            is DownloadDecision.External -> openExternal(decision.url)
            DownloadDecision.Block -> Unit
        }
    }

    private suspend fun download(decision: DownloadDecision.Enqueue, userAgent: String?) {
        var renewed = false
        while (true) {
            val cookie = CookieManager.getInstance().getCookie(decision.url)
            when (withContext(Dispatchers.IO) { preflight.check(decision.url, cookie, userAgent) }) {
                PreflightResult.OK -> {
                    enqueue(decision, cookie, userAgent)
                    return
                }
                PreflightResult.ACCESS_REJECTED -> {
                    if (renewed || !renewAccess()) return failed()
                    renewed = true
                }
                PreflightResult.FAILED -> return failed()
            }
        }
    }

    private fun failed() {
        Toast.makeText(context, R.string.shell_download_failed, Toast.LENGTH_LONG).show()
    }

    private fun enqueue(decision: DownloadDecision.Enqueue, cookie: String?, userAgent: String?) {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val name = DownloadPolicy.uniqueFileName(decision.fileName) { it in reserved || File(directory, it).exists() }
        reserved += name
        val request = DownloadManager.Request(Uri.parse(decision.url))
            .setMimeType(decision.mimeType)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        cookie?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        try {
            context.getSystemService(DownloadManager::class.java).enqueue(request)
        } catch (e: RuntimeException) {
            reserved -= name
            Log.w(TAG, "download enqueue failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "HerdrDownload"
    }
}
