package com.gimso2x.herdr.qa

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.shell.LiveQaGuard
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic probes (ci/instrumented.sh groups P0/P1, run only when QA_PHASES names them). They sample
 * the render-load COUNTS of a page (assets/render_counts.js: canvases, canvas contexts, decoded image
 * pixels, WebSocket bytes, resources by initiator) once a second for [SECONDS] seconds from launch and log
 * them to logcat tag RenderProbe. Never page text, titles, ids or screenshots. P0 samples a small fixture
 * page as the baseline, P1 the live page under the read-only LiveQaGuard (GET only, role forced to observe).
 */
private const val SECONDS = 8
private const val PROBE_TAG = "RenderProbe"

private fun sampleCounts(label: String) {
    val started = SystemClock.elapsedRealtime()
    for (second in 0..SECONDS) {
        val snap = runCatching { Qa.js("window.__qaRenderSnap ? window.__qaRenderSnap() : null") }.getOrNull()
        Log.i(PROBE_TAG, "$label t=${second}s ${snap ?: "no-page-yet"}")
        val wait = started + (second + 1) * 1000L - SystemClock.elapsedRealtime()
        if (wait > 0) SystemClock.sleep(wait)
    }
}

@RunWith(AndroidJUnit4::class)
class P0RenderProbeFixtureTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun fixtureCounts() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = { fx.html("probe", "<div id=app>fixture</div>") }
        fx.start()
        try {
            check(LiveQaGuard.isSupported()) { "DOCUMENT_START_SCRIPT unsupported by this WebView" }
            LiveQaGuard.enableCounting(fx.base)
            ActivityScenario.launch(MainActivity::class.java).use { sampleCounts("fixture") }
        } finally {
            fx.stop()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class P1RenderProbeLiveTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun liveCounts() {
        val token = LiveSupport.token()
        Qa.resetState(alerts = false, token = token, server = Qa.LIVE)
        check(LiveQaGuard.isSupported()) { "DOCUMENT_START_SCRIPT unsupported by this WebView" }
        LiveQaGuard.enableCounting(Qa.LIVE)
        ActivityScenario.launch(MainActivity::class.java).use { sampleCounts("live") }
    }
}
