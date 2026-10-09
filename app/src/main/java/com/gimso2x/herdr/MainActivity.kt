package com.gimso2x.herdr

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
import com.gimso2x.herdr.shell.LiveQaGuard
import com.gimso2x.herdr.shell.RefreshOutcome
import com.gimso2x.herdr.shell.RefreshScheduler
import com.gimso2x.herdr.shell.UrlDecision
import com.gimso2x.herdr.shell.UrlPolicy
import com.gimso2x.herdr.ui.HerdrTheme
import com.gimso2x.herdr.ui.SetupError
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
 * fresh by [RefreshScheduler] while resumed. No JavascriptInterface is exposed.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as HerdrApplication
    private val clock: Clock = AccessTestHooks.clock ?: Clock.SYSTEM
    private val policy = UrlPolicy(debugOrigin = HerdrConfig.baseUrl.takeIf { it != HerdrConfig.BASE_URL })
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

    private var startUrl: String = "${HerdrConfig.baseUrl}/"
    private var lastHerdrUrl: String = startUrl

    /** Saved WebView state waiting for a fresh session before it is restored (null on fresh starts). */
    private var pendingState: Bundle? = null
    private var recovering = false
    private var setupOpened = false

    /** A failure sent the owner to setup; reload the last page when setup comes back with a token. */
    private var reloadAfterSetup = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

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
        // A recreated activity gets the original launch intent again; its pane was already consumed.
        if (savedInstanceState == null) {
            paneUrl(intent)?.let { setStartUrl(it) }
            intent.removeExtra(EXTRA_PANE_ID)
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
                if (first is RefreshOutcome.Fresh) setupOpened = false
                val failure = (first as? RefreshOutcome.NeedsSetup) ?: scheduler.run()
                // Once per failure episode: backing out of setup must not bounce straight back into it.
                if (!setupOpened) openSetup(failure.error)
            }
        }
        startFlow()
    }

    override fun onStart() {
        super.onStart()
        WatcherController.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pane = paneUrl(intent)
        intent.removeExtra(EXTRA_PANE_ID)
        when {
            pane != null -> navigate(pane)
            reloadAfterSetup -> {
                reloadAfterSetup = false
                navigate(lastHerdrUrl)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Before the first load there is nothing of ours to save; keep what we were handed.
        val state = if (started.value) Bundle().also { webView.saveState(it) } else pendingState
        if (state != null) outState.putBundle(KEY_WEBVIEW, state)
    }

    override fun onDestroy() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
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

            override fun onPageFinished(view: WebView, url: String?) {
                keepSplash = false
            }
        }
        // Must precede every page load (including restoreState) so the live site is never unguarded.
        LiveQaGuard.installIfEnabled(this, view)
        return view
    }

    private fun setStartUrl(url: String) {
        startUrl = url
        lastHerdrUrl = url
    }

    private fun retry() {
        startUrl = lastHerdrUrl
        startFlow()
    }

    /** No token -> setup; else a fresh session, then restore the saved page or load [startUrl]. */
    private fun startFlow() {
        showRetry = false
        startJob?.cancel()
        startJob = lifecycleScope.launch {
            val hasToken = withContext(Dispatchers.IO) { app.tokenStore.get() != null }
            if (!hasToken) {
                startActivity(SetupActivity.intent(this@MainActivity))
                finish()
                return@launch
            }
            when (val outcome = RefreshOutcome.of(app.accessSession.ensureFresh())) {
                RefreshOutcome.Fresh -> {
                    loadInitialPage()
                    started.value = true
                    maybeRequestNotificationPermission()
                }
                RefreshOutcome.KeepPage -> {
                    keepSplash = false
                    showRetry = true
                }
                is RefreshOutcome.NeedsSetup -> {
                    startActivity(SetupActivity.intent(this@MainActivity, outcome.error))
                    finish()
                }
            }
        }
    }

    private fun loadInitialPage() {
        val saved = pendingState
        pendingState = null
        if (saved != null && restoreHistory(saved)) return
        webView.loadUrl(startUrl)
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
            return
        }
        lifecycleScope.launch {
            when (val outcome = RefreshOutcome.of(app.accessSession.ensureFresh())) {
                // On a transport failure the cookie may still be valid: try the page anyway.
                RefreshOutcome.Fresh, RefreshOutcome.KeepPage -> webView.loadUrl(url)
                is RefreshOutcome.NeedsSetup -> {
                    reloadAfterSetup = true
                    startActivity(SetupActivity.intent(this@MainActivity, outcome.error))
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
                    RefreshOutcome.Fresh -> webView.loadUrl(lastHerdrUrl)
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

    private fun openSetup(error: SetupError) {
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

    /** Asks once per install (persisted), after a first successful startup. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = AppPreferences(this)
        if (prefs.notificationPermissionAsked) return
        prefs.notificationPermissionAsked = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun paneUrl(intent: Intent?): String? {
        val pane = intent?.getStringExtra(EXTRA_PANE_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return DeepLink.paneUrl(HerdrConfig.baseUrl, pane)
    }

    companion object {
        /** Pane to open, set by status-watcher notifications. */
        const val EXTRA_PANE_ID = "pane_id"

        private const val KEY_WEBVIEW = "webview_state"
        private const val SPLASH_CAP_MILLIS = 8_000L
    }
}

private suspend fun realDelay(millis: Long) = delay(millis)
