package com.gimso2x.herdr.qa

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.webkit.WebStorage
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.shell.LiveQaGuard
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic phase (run by .github/workflows/terminal-diag.yml, never part of ci/instrumented.sh):
 * loads the LIVE herdr-web-ui in the app's WebView, selects a pane three ways (default start, the
 * notification deep link, a direct `view=terminal` URL) and logs layout/xterm/WebSocket METRICS to
 * logcat tag TermDiag: rectangles, counts, booleans, short error messages. Never pane text, titles or
 * ids.
 *
 * The page runs with [LiveQaGuard.enableDiagnostic]: the interact role is NOT rewritten to observe and
 * attach/resize frames are sent (the owner accepted this phone-equivalent session for diagnostics),
 * but input/keys/submit/pending-action/secret/take-over frames and every non-GET request are dropped
 * (assets/diag_probe.js).
 */
@RunWith(AndroidJUnit4::class)
class TerminalDiagTest {
    @After fun tearDown() = Qa.hooksReset()

    private val tag = "TermDiag"

    private fun asset(name: String): String =
        Qa.targetContext.assets.open(name).bufferedReader().use { it.readText() }

    private fun log(scenario: String, key: String, value: Any?) {
        val text = value.toString()
        text.chunked(3000).forEachIndexed { i, part -> Log.i(tag, "$scenario $key${if (i > 0) "#$i" else ""}=$part") }
    }

    private fun clearPageStorage() = Qa.onMain { WebStorage.getInstance().deleteAllData() }

    /** Polls the metrics (bounded) until a terminal grid with rows or a chat transcript shows; logs them. */
    private fun collect(scenario: String, metrics: String, timeoutMs: Long = 35_000) {
        try {
            Qa.awaitHost(Qa.liveHost, 60_000)
        } catch (e: AssertionError) {
            log(scenario, "host", "never reached: ${e.message?.take(200)}")
        }
        val started = SystemClock.elapsedRealtime()
        var lastTick = -1L
        var last: JSONObject? = null
        while (true) {
            val m = runCatching { Qa.js("\n$metrics\n") as? JSONObject }.getOrNull()
            if (m != null) last = m
            val elapsed = SystemClock.elapsedRealtime() - started
            val tick = elapsed / 5_000
            if (m != null && tick != lastTick) {
                lastTick = tick
                log(
                    scenario, "timeline",
                    "t=${elapsed / 1000}s xterms=${m.optInt("xtermCount")} anyRows=${m.optBoolean("anyXtermRows")} " +
                        "anyTall=${m.optBoolean("anyXtermTall")} transcriptChildren=${m.optJSONObject("chat")?.optInt("transcriptChildren")} " +
                        "ws=${m.optJSONArray("ws")?.let { a -> (0 until a.length()).joinToString("|") { a.getJSONObject(it).optJSONObject("recv").toString() } }}",
                )
            }
            val ready = m != null && (m.optBoolean("anyXtermRows") || (m.optJSONObject("chat")?.optInt("transcriptChildren") ?: 0) > 0)
            if (ready || elapsed >= timeoutMs) break
            SystemClock.sleep(1_000)
        }
        val m = last
        if (m == null) {
            log(scenario, "metrics", "unavailable")
            return
        }
        for (key in listOf("env", "units", "fixes", "chain", "appHeightVarComputed", "page", "layout", "xtermCount", "xterms", "anyXtermTall", "anyXtermRows", "chat", "ws", "diag")) {
            log(scenario, key, m.opt(key))
        }
        log(scenario, "verdict", "xterms=${m.optInt("xtermCount")} anyTall=${m.optBoolean("anyXtermTall")} anyRows=${m.optBoolean("anyXtermRows")}")
        a11y(scenario)
    }

    /**
     * What uiautomator (the release smoke's only view of the page) sees: accessibility nodes under the
     * WebView, as counts and `class bounds text-length desc-length` shapes. Never any text.
     */
    private fun a11y(scenario: String) {
        try {
            val out = java.io.ByteArrayOutputStream()
            Qa.device.dumpWindowHierarchy(out)
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(java.io.ByteArrayInputStream(out.toByteArray()))
            val all = doc.getElementsByTagName("node")
            var webView: org.w3c.dom.Element? = null
            for (i in 0 until all.length) {
                val e = all.item(i) as org.w3c.dom.Element
                if (e.getAttribute("class") == "android.webkit.WebView") { webView = e; break }
            }
            if (webView == null) { log(scenario, "a11y", "total=${all.length} webview=none"); return }
            val below = webView.getElementsByTagName("node")
            var described = 0
            val shapes = StringBuilder()
            for (i in 0 until below.length) {
                val e = below.item(i) as org.w3c.dom.Element
                val t = e.getAttribute("text").length
                val d = e.getAttribute("content-desc").length
                if (t > 0 || d > 0) described++
                if (i < 80) shapes.append("${e.getAttribute("class").substringAfterLast('.')} ${e.getAttribute("bounds")} t=$t d=$d; ")
            }
            log(scenario, "a11y", "total=${all.length} webviewDescendants=${below.length} described=$described")
            log(scenario, "a11yShape", shapes)
        } catch (e: Exception) {
            log(scenario, "a11y", "failed: ${e.javaClass.simpleName}")
        }
    }

    @Test
    fun diag() {
        val token = LiveSupport.token()
        val snapshot = JSONObject(LiveSupport.get("/api/session", token)).getJSONObject("snapshot")
        val panes = snapshot.getJSONArray("panes")
        val paneId = snapshot.optString("focused_pane_id").ifEmpty { panes.getJSONObject(0).getString("pane_id") }
        log("setup", "snapshot", "panes=${panes.length()} workspaces=${snapshot.getJSONArray("workspaces").length()}")
        val metrics = asset("diag_metrics.js")

        Qa.resetState(alerts = false, token = token, server = Qa.LIVE)
        check(LiveQaGuard.isSupported()) { "DOCUMENT_START_SCRIPT unsupported by this WebView" }
        LiveQaGuard.enableDiagnostic(Qa.LIVE)

        // 1. Plain start: whatever the page selects on its own (what the owner sees after opening the app).
        clearPageStorage()
        ActivityScenario.launch(MainActivity::class.java).use {
            collect("root", metrics)
        }

        // 2. The status-watcher notification deep link (`pane_id` extra -> ?machine=local&pane=..&view=chat).
        clearPageStorage()
        val intent = Intent(Qa.targetContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_PANE_ID, paneId)
        ActivityScenario.launch<MainActivity>(intent).use {
            collect("deeplink-chat", metrics)
        }

        // 3. A direct terminal URL loaded into the WebView after a normal start.
        clearPageStorage()
        ActivityScenario.launch(MainActivity::class.java).use {
            Qa.awaitHost(Qa.liveHost, 60_000)
            val encoded = java.net.URLEncoder.encode(paneId, "UTF-8").replace("+", "%20")
            Qa.js("window.__diagOld = 1")
            Qa.loadUrl("${Qa.LIVE}/?machine=local&pane=$encoded&view=terminal")
            Qa.awaitJs(
                "direct terminal URL committed", 60_000,
                "typeof window.__diagOld === 'undefined' && location.host === ${JSONObject.quote(Qa.liveHost)} && document.readyState === 'complete'",
            )
            collect("direct-terminal", metrics)
        }
    }
}
