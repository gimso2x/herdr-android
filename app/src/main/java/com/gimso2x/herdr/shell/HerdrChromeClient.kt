package com.gimso2x.herdr.shell

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.graphics.Bitmap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * File chooser, microphone permission and popup routing for the herdr WebView. All decisions are
 * made by the pure policies; this class only adapts them to WebView callbacks and always resolves
 * every callback it is handed.
 */
class HerdrChromeClient(
    private val host: Host,
    private val urlPolicy: UrlPolicy,
    private val baseUrl: () -> String,
) : WebChromeClient() {

    /** Activity-side pieces (launchers, intents); results come back through the on* methods. */
    interface Host {
        fun launchFilePicker(mimeType: String, multiple: Boolean)

        fun hasAudioPermission(): Boolean

        fun launchAudioPermission()

        fun loadInApp(url: String)

        fun openExternal(url: String)

        /** A throwaway WebView needs a Context. */
        fun popupContext(): android.content.Context
    }

    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        resolveFiles(null) // a superseded chooser must still be answered
        pendingFiles = filePathCallback
        val mime = FileChooserPolicy.mimeType(fileChooserParams?.acceptTypes)
        val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        try {
            host.launchFilePicker(mime, multiple)
        } catch (_: ActivityNotFoundException) {
            resolveFiles(null)
        }
        return true
    }

    /** Picker finished; empty list = cancelled. */
    fun onFilesPicked(uris: List<Uri>) {
        resolveFiles(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun resolveFiles(value: Array<Uri>?) {
        val callback = pendingFiles ?: return
        pendingFiles = null
        callback.onReceiveValue(value)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        // A newer request replaces an unanswered one; the old one must still be answered.
        pendingPermission?.deny()
        pendingPermission = null
        when (val decision = decide(request, host.hasAudioPermission())) {
            is PermissionDecision.Grant -> request.grant(decision.resources.toTypedArray())
            PermissionDecision.RequestRuntime -> {
                pendingPermission = request
                host.launchAudioPermission()
            }
            PermissionDecision.Deny -> request.deny()
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        if (pendingPermission === request) pendingPermission = null
    }

    /** Runtime RECORD_AUDIO answer; the policy is consulted again, so a refusal can only deny. */
    fun onAudioPermissionResult(granted: Boolean) {
        val request = pendingPermission ?: return
        pendingPermission = null
        when (val decision = decide(request, granted)) {
            is PermissionDecision.Grant -> request.grant(decision.resources.toTypedArray())
            else -> request.deny()
        }
    }

    private fun decide(request: PermissionRequest, recordAudioGranted: Boolean): PermissionDecision =
        PermissionPolicy.decide(
            origin = request.origin?.toString(),
            resources = request.resources.toList(),
            baseUrl = baseUrl(),
            recordAudioGranted = recordAudioGranted,
        )

    /**
     * Popups, target=_blank and window.open: the target is read in a throwaway WebView that never
     * loads anything and is destroyed as soon as the target is routed (or after a timeout).
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        if (resultMsg == null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val throwaway = WebView(host.popupContext())
        var done = false
        val finish = Runnable {
            if (done) return@Runnable
            done = true
            throwaway.stopLoading()
            throwaway.webViewClient = WebViewClient()
            throwaway.destroy()
        }
        fun route(url: String?) {
            if (done) return
            when (val action = PopupPolicy.route(url, urlPolicy)) {
                PopupAction.Wait -> return
                is PopupAction.LoadInApp -> host.loadInApp(action.url)
                is PopupAction.OpenExternal -> host.openExternal(action.url)
                PopupAction.Ignore -> Unit
            }
            // Never destroy from inside the WebView's own callback.
            mainHandler.post(finish)
        }
        throwaway.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                route(request.url.toString())
                return true // the throwaway never loads the target itself
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.stopLoading()
                route(url)
            }
        }
        mainHandler.postDelayed(finish, POPUP_TIMEOUT_MILLIS)
        transport.webView = throwaway
        resultMsg.sendToTarget()
        return true
    }

    private companion object {
        const val POPUP_TIMEOUT_MILLIS = 10_000L
    }
}
