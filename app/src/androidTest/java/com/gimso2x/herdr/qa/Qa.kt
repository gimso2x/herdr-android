package com.gimso2x.herdr.qa

import android.app.Instrumentation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.gimso2x.herdr.HerdrApplication
import com.gimso2x.herdr.HerdrConfig
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.access.Clock
import com.gimso2x.herdr.access.ServiceToken
import com.gimso2x.herdr.shell.AccessTestHooks
import com.gimso2x.herdr.shell.LiveQaGuard
import com.gimso2x.herdr.watch.WatchTestHooks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A manually advanced clock for the access/watch seams. */
class FakeClock(@Volatile var now: Long = System.currentTimeMillis()) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/**
 * Shared helpers of the instrumented QA suite. Every wait is a bounded condition loop ([until]),
 * a StateFlow first-match or a captured-sleeper release in the tests themselves; nothing sleeps
 * for a fixed time.
 */
object Qa {
    const val PKG = "com.gimso2x.herdr"
    const val LIVE = "https://herdr.example.com"
    const val ACCESS_HOST = "your-team.cloudflareaccess.com"
    private const val POLL_MS = 50L

    val instr: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val targetContext: Context get() = instr.targetContext
    val app: HerdrApplication get() = targetContext.applicationContext as HerdrApplication
    val device: UiDevice get() = UiDevice.getInstance(instr)

    /**
     * Re-evaluates [condition] until it holds; fails with [what] (plus [detail], evaluated only
     * on failure) after [timeoutMs].
     */
    fun until(timeoutMs: Long, what: String, detail: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (condition()) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                runCatching { captureFailure(what) }
                throw AssertionError("timed out after $timeoutMs ms waiting for: $what ${runCatching(detail).getOrDefault("")}")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    /** Name of the running test class, found on the stack (screenshot/log names). */
    private fun testName(): String =
        Thread.currentThread().stackTrace
            .firstOrNull { it.className.startsWith("com.gimso2x.herdr.qa.") && it.className.endsWith("Test") }
            ?.className?.substringAfterLast('.') ?: "unknown"

    fun logFact(message: String) {
        runCatching { shell("log -t QaFail ${message.replace(' ', '_')}") }
    }

    /** View/window focus of the app's WebView (document.hasFocus needs both). */
    fun focusFacts(): String = onMain {
        val webView = webViewUnsafe()
        "webViewFocused=${webView?.isFocused} webViewHasFocus=${webView?.hasFocus()} windowFocus=${webView?.hasWindowFocus()}"
    }

    /**
     * Failure evidence taken while the page is still up: a screenshot in /sdcard/Download/qa and a
     * few page/input-method facts in logcat tag QaFail (collected by ci/instrumented.sh).
     */
    private fun captureFailure(what: String) {
        val name = testName()
        screenshot("$name-fail.png")
        logFact("$name: $what url=${currentUrl()} activities=${liveActivityNames()} docFocus=${js("document.hasFocus()")} ${focusFacts()}")
        shell("dumpsys input_method").lineSequence()
            .filter { it.contains("mInputShown") || it.contains("mCurFocusedWindow") }
            .take(6)
            .forEach { logFact("$name: ${it.trim()}") }
    }

    /** Launch intents of every MainActivity created from now on (action, flags, extra keys). */
    val launchIntents = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun recordLaunchIntents() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback { activity, stage ->
                if (stage == Stage.PRE_ON_CREATE && activity is MainActivity) {
                    launchIntents += "action=${activity.intent.action} flags=0x${Integer.toHexString(activity.intent.flags)} extras=${activity.intent.extras?.keySet()}"
                }
            }
        }
    }

    // --- main thread -------------------------------------------------------------------------

    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instr.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    // --- shell -------------------------------------------------------------------------------

    /** Runs [command] as the shell user and returns its stdout once the command has ended. */
    fun shell(command: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun screenshot(name: String) {
        shell("mkdir -p /sdcard/Download/qa")
        shell("screencap -p /sdcard/Download/qa/$name")
    }

    // --- state -------------------------------------------------------------------------------

    fun syntheticToken(): ServiceToken = ServiceToken("qa-fixture-id", "qa-fixture-secret")

    fun setAlerts(enabled: Boolean) {
        val prefs = targetContext.getSharedPreferences("herdr_prefs", Context.MODE_PRIVATE)
        // commit(): host phases force-stop the app right after the test, apply() could be lost.
        check(
            prefs.edit()
                .putBoolean("alerts_enabled", enabled)
                .putBoolean("notification_permission_asked", true)
                .commit(),
        ) { "preferences not written" }
    }

    /** Hooks, overrides and persisted state back to a known baseline; [token] null clears it. */
    fun resetState(alerts: Boolean, token: ServiceToken?) {
        hooksReset()
        setAlerts(alerts)
        if (token == null) app.tokenStore.clear() else check(app.tokenStore.put(token)) { "token store unavailable" }
        onMain {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    fun hooksReset() {
        HerdrConfig.setDebugBaseUrl(null)
        AccessTestHooks.reset()
        LiveQaGuard.disable()
        WatchTestHooks.clock = Clock.SYSTEM
    }

    fun cookieFor(url: String): String? = onMain { CookieManager.getInstance().getCookie(url) }

    // --- activities and the page -------------------------------------------------------------

    private val STAGES = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)

    private fun mainUnsafe(): MainActivity? {
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        for (stage in STAGES) {
            registry.getActivitiesInStage(stage).filterIsInstance<MainActivity>().firstOrNull { !it.isFinishing }
                ?.let { return it }
        }
        return null
    }

    private fun findWebView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun webViewUnsafe(): WebView? = mainUnsafe()?.let { findWebView(it.window.decorView) }

    fun mainActivityExists(): Boolean = onMain { mainUnsafe() != null }

    /** Simple names of every activity of the app that is not destroyed. */
    fun liveActivityNames(): List<String> = onMain {
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        Stage.values().filter { it != Stage.DESTROYED && it != Stage.PRE_ON_CREATE }
            .flatMap { registry.getActivitiesInStage(it) }
            .filter { !it.isFinishing }
            .map { it.javaClass.simpleName }
    }

    fun currentUrl(): String? = onMain { webViewUnsafe()?.url }

    fun reload() = onMain { webViewUnsafe()!!.reload() }

    /**
     * Asks the window to show the keyboard for the focused page. Used after a real tap focused an
     * input: whether Chromium's own show request wins the race against a cold, overloaded Gboard
     * on a 2-core runner is emulator noise, while the layout reaction (F4 Back order, F5 resize)
     * is what the scenarios verify.
     */
    fun requestIme() = onMain {
        val activity = mainUnsafe()!!
        androidx.core.view.WindowCompat.getInsetsController(activity.window, webViewUnsafe()!!)
            .show(WindowInsetsCompat.Type.ime())
    }

    fun isImeVisible(): Boolean = onMain {
        val activity = mainUnsafe() ?: return@onMain false
        ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    /** Evaluates [script] (an expression) in the page; null when no page/undefined/error object. */
    fun js(expr: String): Any? {
        val wrapped = "(function(){try{return JSON.stringify($expr)}catch(e){return JSON.stringify({'__err':String(e)})}})()"
        val latch = CountDownLatch(1)
        var raw: String? = null
        val started = onMain {
            val webView = webViewUnsafe() ?: return@onMain false
            webView.evaluateJavascript(wrapped) {
                raw = it
                latch.countDown()
            }
            true
        }
        if (!started) return null
        check(latch.await(20, TimeUnit.SECONDS)) { "evaluateJavascript did not answer" }
        val outer = raw ?: return null
        if (outer == "null") return null
        val inner = JSONTokener(outer).nextValue() as? String ?: return null
        val value = JSONTokener(inner).nextValue()
        return if (value == JSONObject.NULL) null else value
    }

    fun awaitJs(what: String, timeoutMs: Long, expr: String) {
        until(timeoutMs, what) { js(expr) == true }
    }

    fun jsString(expr: String): String? = js(expr) as? String

    /** Two animation frames have been produced: the page is drawn and hit-testable. */
    fun awaitFrame() {
        awaitJs("page painted", 20_000, "performance.getEntriesByType('paint').length > 0")
        js("(function(){window.__qaFrame=0;requestAnimationFrame(function(){requestAnimationFrame(function(){window.__qaFrame=1})});return 1})()")
        awaitJs("page produced a frame", 20_000, "window.__qaFrame === 1")
    }

    /** A real finger tap on the first element matching [selector] (a genuine user gesture). */
    fun tap(selector: String) {
        awaitFrame()
        val quoted = JSONObject.quote(selector)
        val rect = js(
            "(function(){var e=document.querySelector($quoted);var b=e.getBoundingClientRect();" +
                "return [b.left+b.width/2,b.top+b.height/2,window.devicePixelRatio]})()",
        ) as? JSONArray ?: throw AssertionError("element not found: $selector")
        val origin = onMain {
            val location = IntArray(2)
            webViewUnsafe()!!.getLocationOnScreen(location)
            location
        }
        val x = origin[0] + (rect.getDouble(0) * rect.getDouble(2)).toInt()
        val y = origin[1] + (rect.getDouble(1) * rect.getDouble(2)).toInt()
        val hit = jsString("(function(){var e=document.elementFromPoint(${rect.getDouble(0)},${rect.getDouble(1)});return e?(e.tagName+'#'+e.id):'none'})()")
        logFact("tap $selector at $x,$y origin=${origin[0]},${origin[1]} dpr=${rect.getDouble(2)} hit=$hit")
        check(device.click(x, y)) { "tap on $selector was not injected" }
    }

    /** Waits for the app's WebView to show a document whose location matches the base host. */
    fun awaitHost(host: String, timeoutMs: Long = 30_000) {
        awaitJs("page on $host", timeoutMs, "location.host === ${JSONObject.quote(host)} && document.readyState === 'complete'")
    }
}

