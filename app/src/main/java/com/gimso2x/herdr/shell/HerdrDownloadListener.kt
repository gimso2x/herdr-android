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
import kotlinx.coroutines.MainScope
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
    private val baseUrl: () -> String,
    private val openExternal: (String) -> Unit,
    private val scope: CoroutineScope = MainScope(),
    /** Forces an Access cookie renewal; true when a fresh cookie is installed. */
    private val renewAccess: suspend () -> Boolean = { false },
    private val preflight: DownloadPreflight = DownloadPreflight(),
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
        val manager = context.getSystemService(DownloadManager::class.java)
        // On disk, or already requested by this app (DownloadManager keeps the title we set: the final name).
        val requested = requestedNames(manager)
        val name = DownloadPolicy.uniqueFileName(decision.fileName) { it in requested || File(directory, it).exists() }
        val request = DownloadManager.Request(Uri.parse(decision.url))
            .setMimeType(decision.mimeType)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        cookie?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        try {
            manager.enqueue(request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "download enqueue failed: ${e.javaClass.simpleName}")
        }
    }

    /** Titles (= destination names) of this app's downloads, including those whose file is still being created. */
    private fun requestedNames(manager: DownloadManager): Set<String> = try {
        manager.query(DownloadManager.Query()).use { cursor ->
            val column = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            buildSet { while (cursor.moveToNext()) cursor.getString(column)?.let { add(it) } }
        }
    } catch (e: RuntimeException) {
        emptySet()
    }

    private companion object {
        const val TAG = "HerdrDownload"
    }
}
