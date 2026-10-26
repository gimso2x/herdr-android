package com.gimso2x.herdr

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.shell.AccessRecovery
import com.gimso2x.herdr.shell.AccessTestHooks
import com.gimso2x.herdr.shell.BackAction
import com.gimso2x.herdr.shell.BackPolicy
import com.gimso2x.herdr.shell.DeepLink
import com.gimso2x.herdr.shell.HerdrChromeClient
import com.gimso2x.herdr.shell.HerdrDownloadListener
import com.gimso2x.herdr.shell.LiveQaGuard
import com.gimso2x.herdr.shell.RefreshOutcome
import com.gimso2x.herdr.shell.RefreshScheduler
import com.gimso2x.herdr.shell.NotificationAction
import com.gimso2x.herdr.shell.NotificationGate
import com.gimso2x.herdr.shell.StartupNavigation
import com.gimso2x.herdr.shell.UrlDecision
import com.gimso2x.herdr.shell.UrlPolicy
import com.gimso2x.herdr.ui.HerdrTheme
import com.gimso2x.herdr.ui.SetupError
import com.gimso2x.herdr.watch.PaneLink
import com.gimso2x.herdr.watch.WatcherController
import java.net.URISyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts herdr-web-ui in a WebView. Navigation is limited by [UrlPolicy]; the Access cookie is kept
 * fresh by [RefreshScheduler] while resumed. No native object is exposed to page scripts.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as HerdrApplication
    private val clock: Clock = AccessTestHooks.clock ?: Clock.SYSTEM
    private val policy = UrlPolicy { HerdrConfig.baseUrlOrNull }
    private val recovery = AccessRecovery(clock)
    private val scheduler = RefreshScheduler(
        clock = clock,
        sleeper = AccessTestHooks.sleeper ?: ::realDelay,
        nextRefreshAt = { now -> app.accessSession.nextRefreshAt(now) },
        refresh = { app.accessSession.ensureFresh() },
    )

    private lateinit var webView: WebView
    private var keepSplash = true
    private var showRetry by mutableStateOf(false)

    /** True once the first page load was issued; the resume loop waits for it. */
    private val started = MutableStateFlow(false)
    private var startJob: Job? = null

    private var startUrl: String? = null
    private var lastHerdrUrl: String? = null

    /** Saved WebView state waiting for a fresh session before it is restored (null on fresh starts). */
    private var pendingState: Bundle? = null

    /** Start URL saved across a recreation that happened before the first load (our own pane URL). */
    private var pendingStartUrl: String? = null

    /** Pane URL delivered (intent) before the first load; wins over restored history. */
    private var pendingPaneUrl: String? = null
    private var recovering = false

    /** Origin the WebView document was loaded from; a different configured origin forces a reset. */
    private var loadedOrigin: String? = null
    private var clearHistoryOnFinish = false
    private var setupOpened = false

    /** A failure sent the owner to setup; reload the last page when setup comes back with a token. */
    private var reloadAfterSetup = false

    /** In-app URL of the last failed main-frame load; the retry action loads exactly this page. */
    private var failedUrl: String? = null

    /** Set while a sign-out/server-change wipe is settling: errors of the page being torn down are not failures. */
    private var ignoreLoadErrors = false

    /** about:blank was loaded by a wipe; its history entry is dropped once it finished. */
    private var blankPending = false

    /** The token was removed: setup is opened without a rejection message. */
    private var signedOut = false

    // Asked once per install before the watcher starts; the watcher never depends on the answer.
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> WatcherController.start(this) }

    // File chooser / microphone: launchers are registered here (before STARTED); the chrome client
    // owns the WebView callbacks and always resolves them.
    private val filePickerSingle: androidx.activity.result.ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            chromeClient.onFilesPicked(listOfNotNull(uri))
        }
    private val filePickerMulti: androidx.activity.result.ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            chromeClient.onFilesPicked(uris)
        }
    private val audioPermission: androidx.activity.result.ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
            chromeClient.onAudioPermissionResult(granted)
        }

    private val chromeClient: HerdrChromeClient = HerdrChromeClient(
        host = object : HerdrChromeClient.Host {
            override fun launchFilePicker(mimeType: String, multiple: Boolean) {
                if (multiple) filePickerMulti.launch(mimeType) else filePickerSingle.launch(mimeType)
            }

            override fun hasAudioPermission(): Boolean =
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

            override fun launchAudioPermission() {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }

            override fun loadInApp(url: String) {
                navigate(url)
            }

            override fun openExternal(url: String) {
                launchExternal(url)
            }

            override fun popupContext(): android.content.Context = this@MainActivity
        },
        urlPolicy = policy,
        baseUrl = { HerdrConfig.baseUrl },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSplash }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            delay(SPLASH_CAP_MILLIS)
            keepSplash = false
        }

        webView = createWebView()
        pendingState = savedInstanceState?.getBundle(KEY_WEBVIEW)
        // Only ever a URL we built on the herdr base; anything else in the bundle is dropped.
        pendingStartUrl = savedInstanceState?.getString(KEY_PENDING_START_URL)?.takeIf { policy.isInApp(it) }
        pendingStartUrl?.let { setStartUrl(it) }
        // A recreated activity gets the original launch intent again; its pane was already consumed.
        if (savedInstanceState == null) {
            (paneUrl(intent) ?: openUrl(intent))?.let { navigate(it) }
            intent.removeExtra(EXTRA_PANE_ID)
            intent.removeExtra(EXTRA_OPEN_URL)
        }
        var handledSessionEnd = app.sessionEnd.value.serial
        lifecycleScope.launch {
            app.sessionEnd.collect { event ->
                if (event.serial == handledSessionEnd) return@collect
                handledSessionEnd = event.serial
                event.kind?.let(::onSessionEnded)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (BackPolicy.actionFor(isImeVisible(), webView.canGoBack())) {
                    BackAction.HIDE_IME ->
                        WindowCompat.getInsetsController(window, webView).hide(WindowInsetsCompat.Type.ime())
                    BackAction.GO_BACK -> webView.goBack()
                    BackAction.EXIT -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        setContent {
            HerdrTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val insets = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().then(insets))
                    if (showRetry) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .then(insets)
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(stringResource(R.string.shell_network_error), style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = ::retry) { Text(stringResource(R.string.shell_retry)) }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                started.first { it }
                // Every resume renews if due, independent of alerts; then sleep until the next due time.
                val first = RefreshOutcome.of(app.accessSession.ensureFresh())
                if (first is RefreshOutcome.Fresh) {
                    setupOpened = false
                    signedOut = false
                }
                resetIfOriginChanged()
                val failure = (first as? RefreshOutcome.NeedsSetup) ?: scheduler.run()
                // Once per failure episode: backing out of setup must not bounce straight back into it.
                if (!setupOpened) openSetup(if (signedOut) null else failure.error)
            }
        }
        startFlow()
        if (savedInstanceState == null) lifecycleScope.launch { openOnboardingIfPending() }
    }

    /** A stored token with the checklist not done yet (first run after an update): show it once. */
    private suspend fun openOnboardingIfPending() {
        if (AppPreferences(this).onboardingDone) return
        val hasToken = withContext(Dispatchers.IO) { app.tokenStore.get() != null && HerdrConfig.isConfigured }
        if (hasToken && !AppPreferences(this).onboardingDone) startActivity(OnboardingActivity.intent(this))
    }

    override fun onStart() {
        super.onStart()
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = AppPreferences(this)
        // While onboarding is pending it owns the permission request ("알림 허용" row).
        val asked = prefs.notificationPermissionAsked || !prefs.onboardingDone
        when (NotificationGate.decide(Build.VERSION.SDK_INT, granted, asked)) {
            NotificationAction.START_WATCHER -> WatcherController.start(this)
            NotificationAction.REQUEST_THEN_START -> {
                prefs.notificationPermissionAsked = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The requested pane (notification) or the target carried through setup/onboarding wins over lastUrl().
        val target = paneUrl(intent) ?: openUrl(intent)
        intent.removeExtra(EXTRA_PANE_ID)
        intent.removeExtra(EXTRA_OPEN_URL)
        when {
            target != null -> {
                reloadAfterSetup = false
                navigate(target)
            }
            reloadAfterSetup -> {
                reloadAfterSetup = false
                navigate(lastUrl())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) webView.requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (started.value) {
            outState.putBundle(KEY_WEBVIEW, Bundle().also { webView.saveState(it) })
        } else {
            // Before the first load there is no history of ours: keep what we were handed.
            pendingState?.let { outState.putBundle(KEY_WEBVIEW, it) }
            (pendingPaneUrl ?: pendingStartUrl)?.let { outState.putString(KEY_PENDING_START_URL, it) }
        }
    }

    override fun onDestroy() {
        chromeClient.dispose()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        // Without params Compose's AndroidView hands the view wrap_content ones; Chromium then forces a
        // zero layout height and vh/dvh/svh/lvh all compute to 0 (innerHeight stays right). herdr-web-ui
        // sizes its shell with `height: var(--app-height, 100dvh)`, so every pane collapsed to 0px.
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.setBackgroundColor(ContextCompat.getColor(this, R.color.shell_background))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                when (val decision = policy.decide(request.url.toString(), request.isForMainFrame)) {
                    UrlDecision.InApp -> false
                    UrlDecision.RefreshSession -> {
                        onAccessRedirect()
                        true
                    }
                    is UrlDecision.External -> {
                        launchExternal(decision.url)
                        true
                    }
                    UrlDecision.Block -> true
                }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null && url.startsWith("http") && policy.isInApp(url)) lastHerdrUrl = url
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onMainFrameFailed(request.url.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) onMainFrameFailed(request.url.toString())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (blankPending && url == "about:blank") {
                    blankPending = false
                    view.clearHistory()
                }
                if (clearHistoryOnFinish && url != null && url.startsWith("http") && policy.isInApp(url)) {
                    clearHistoryOnFinish = false
                    view.clearHistory()
                }
                keepSplash = false
                // The Compose host holds the initial focus; without view focus the page reports
                // document.hasFocus() == false (clipboard writes are refused, the keyboard may not show).
                view.requestFocus()
                com.gimso2x.herdr.watch.WatchTestHooks.pageFinished(url)
            }
        }
        view.webChromeClient = chromeClient
        view.setDownloadListener(
            HerdrDownloadListener(this, { HerdrConfig.baseUrl }, ::launchExternal, lifecycleScope, ::renewForDownload),
        )
        // Must precede every page load (including restoreState) so the live site is never unguarded.
        LiveQaGuard.installIfEnabled(this, view)
        return view
    }

    private fun setStartUrl(url: String) {
        startUrl = url
        lastHerdrUrl = url
    }

    private fun rootUrl(): String = "${HerdrConfig.baseUrl}/"

    private fun lastUrl(): String = lastHerdrUrl?.takeIf { policy.isInApp(it) } ?: rootUrl()

    /** Every page load of ours goes through here: it clears the failure state of the previous one. */
    private fun loadPage(url: String) {
        showRetry = false
        failedUrl = null
        ignoreLoadErrors = false
        webView.loadUrl(url)
    }

    /** A main-frame load of an in-app page failed (transport error or HTTP >= 400): offer the retry action. */
    private fun onMainFrameFailed(url: String) {
        if (ignoreLoadErrors || !url.startsWith("http") || !policy.isInApp(url)) return
        failedUrl = url
        keepSplash = false
        showRetry = true
    }

    /** Refreshes authentication first (startFlow), then loads the page that failed. */
    private fun retry() {
        startUrl = (failedUrl ?: lastHerdrUrl)?.takeIf { policy.isInApp(it) }
        startFlow(forceRefresh = true)
    }

    /** Token removed or server changed: nothing of the old session may stay in the WebView. */
    private fun onSessionEnded(kind: SessionEnd) {
        ignoreLoadErrors = true
        showRetry = false
        failedUrl = null
        pendingState = null
        pendingStartUrl = null
        pendingPaneUrl = null
        blankPending = true
        clearHistoryOnFinish = true
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.clearCache(true)
        if (kind == SessionEnd.SIGN_OUT) {
            signedOut = true
            startUrl = null
            lastHerdrUrl = null
            reloadAfterSetup = true
        }
    }

    /** Forced renewal for a download whose cookie was rejected; setup when the token itself is. */
    private suspend fun renewForDownload(): Boolean =
        when (val outcome = RefreshOutcome.of(app.accessSession.ensureFresh(force = true))) {
            RefreshOutcome.Fresh -> true
            RefreshOutcome.KeepPage -> false
            is RefreshOutcome.NeedsSetup -> {
                openSetup(outcome.error)
                false
            }
        }

    /**
     * The address was changed while this activity was alive: forget everything of the old origin
     * (document, history, pending URLs) and load the root of the new one.
     */
    private fun resetIfOriginChanged() {
        val loaded = loadedOrigin ?: return
        val now = HerdrConfig.baseUrlOrNull ?: return
        if (now == loaded) return
        loadedOrigin = now
        pendingState = null
        pendingStartUrl = null
        pendingPaneUrl = null
        startUrl = null
        lastHerdrUrl = null
        reloadAfterSetup = false
        clearHistoryOnFinish = true
        webView.clearHistory()
        loadPage(rootUrl())
    }

    /** No token or no server address -> setup; else a fresh session, then restore the saved page or load [startUrl]. */
    private fun startFlow(forceRefresh: Boolean = false) {
        showRetry = false
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            val (hasToken, configured) = withContext(Dispatchers.IO) {
                (app.tokenStore.get() != null) to HerdrConfig.isConfigured
            }
            if (!hasToken || !configured) {
                // An update from a build without the address field: the token is kept, only the address is asked.
                startActivity(SetupActivity.intent(this@MainActivity, addressOnly = hasToken, nextUrl = pendingTarget()))
                finish()
                return@launch
            }
            when (val outcome = RefreshOutcome.of(app.accessSession.ensureFresh(force = forceRefresh))) {
                RefreshOutcome.Fresh -> {
                    loadInitialPage()
                    started.value = true
                    // POST_NOTIFICATIONS is requested in onStart, independent of startup success.
                }
                RefreshOutcome.KeepPage -> {
                    keepSplash = false
                    showRetry = true
                }
                is RefreshOutcome.NeedsSetup -> {
                    startActivity(SetupActivity.intent(this@MainActivity, outcome.error, nextUrl = pendingTarget()))
                    finish()
                }
            }
        }
    }

    private fun loadInitialPage() {
        val saved = pendingState
        loadedOrigin = HerdrConfig.baseUrlOrNull
        val plan = StartupNavigation.resolve(
            hasPendingState = saved != null,
            pendingStartUrl = pendingStartUrl,
            paneUrl = pendingPaneUrl,
            rootUrl = startUrl ?: rootUrl(),
        )
        pendingState = null
        pendingStartUrl = null
        pendingPaneUrl = null
        val restored = plan.restoreHistory && saved != null && restoreHistory(saved)
        // An explicit pane wins: load it on top of restored history (Back keeps working).
        val explicit = plan.explicitUrl
        when {
            explicit != null -> loadPage(explicit)
            !restored -> loadPage(plan.fallbackUrl)
        }
    }

    /** Restores saved history; refuses state whose current page is not a herdr page (stale/foreign). */
    private fun restoreHistory(saved: Bundle): Boolean {
        val list = webView.restoreState(saved) ?: return false
        val current = list.currentItem?.url ?: return false
        return current.startsWith("http") && policy.isInApp(current)
    }

    /** Loads [url] in the existing WebView after making sure the session is fresh (warm paths). */
    private fun navigate(url: String) {
        if (!started.value) {
            setStartUrl(url)
            pendingPaneUrl = url
            return
        }
        lifecycleScope.launch {
            when (val outcome = RefreshOutcome.of(app.accessSession.ensureFresh())) {
                // On a transport failure the cookie may still be valid: try the page anyway.
                RefreshOutcome.Fresh, RefreshOutcome.KeepPage -> loadPage(url)
                is RefreshOutcome.NeedsSetup -> {
                    // Setup carries the requested page back, so it is opened afterwards, not the previous one.
                    reloadAfterSetup = true
                    startActivity(SetupActivity.intent(this@MainActivity, outcome.error, nextUrl = url))
                }
            }
        }
    }

    /** The main frame was bounced to Cloudflare Access: renew once and reload; a repeat means give up. */
    private fun onAccessRedirect() {
        if (recovering) return
        if (recovery.onHit()) {
            reloadAfterSetup = true
            startActivity(SetupActivity.intent(this, SetupError.REJECTED))
            return
        }
        recovering = true
        lifecycleScope.launch {
            try {
                when (val outcome = RefreshOutcome.of(app.accessSession.ensureFresh(force = true))) {
                    RefreshOutcome.Fresh -> loadPage(lastUrl())
                    RefreshOutcome.KeepPage -> showRetry = true
                    is RefreshOutcome.NeedsSetup -> {
                        reloadAfterSetup = true
                        startActivity(SetupActivity.intent(this@MainActivity, outcome.error))
                    }
                }
            } finally {
                recovering = false
            }
        }
    }

    private fun openSetup(error: SetupError?) {
        setupOpened = true
        reloadAfterSetup = true
        startActivity(SetupActivity.intent(this, error))
    }

    private fun launchExternal(url: String) {
        val intent = try {
            if (url.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    // Pages may only pick a target by action/data, never by component or selector.
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                    flags = flags and (
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        ).inv()
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } catch (_: URISyntaxException) {
            return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing can handle it: ignore.
        }
    }

    private fun isImeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** The page waiting for the first load (notification pane, restored start URL), to survive a setup detour. */
    private fun pendingTarget(): String? = pendingPaneUrl ?: pendingStartUrl ?: startUrl

    /** A target carried through setup/onboarding; only ever an in-app http(s) page of the current server. */
    private fun openUrl(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_OPEN_URL)?.takeIf { it.startsWith("http") && policy.isInApp(it) }

    private fun paneUrl(intent: Intent?): String? {
        val pane = intent?.getStringExtra(EXTRA_PANE_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DeepLink.paneUrl(HerdrConfig.baseUrl, pane, PaneLink.machineOf(intent?.dataString))
    }

    companion object {
        /** Pane to open, set by status-watcher notifications. */
        const val EXTRA_PANE_ID = "pane_id"

        /** In-app URL to open, carried through setup and onboarding back to MainActivity. */
        const val EXTRA_OPEN_URL = "open_url"

        private const val KEY_WEBVIEW = "webview_state"
        private const val KEY_PENDING_START_URL = "pending_start_url"
        private const val SPLASH_CAP_MILLIS = 8_000L
    }
}

private suspend fun realDelay(millis: Long) = delay(millis)
