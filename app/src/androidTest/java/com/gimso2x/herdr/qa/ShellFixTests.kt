package com.gimso2x.herdr.qa

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.gimso2x.herdr.HerdrConfig
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.SettingsActivity
import com.gimso2x.herdr.access.ServiceToken
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RETRY = "다시 시도"

private fun mainIntent(pane: String? = null): Intent =
    Intent(Qa.targetContext, MainActivity::class.java).apply {
        if (pane != null) putExtra(MainActivity.EXTRA_PANE_ID, pane)
    }

private fun awaitActivity(simpleName: String) {
    Qa.until(60_000, "$simpleName live", { "live=${Qa.liveActivityNames()}" }) {
        Qa.liveActivityNames().contains(simpleName)
    }
}

/** Types the address and the credentials into the full setup screen and presses connect. */
private fun fillSetupAndConnect(address: String, token: ServiceToken) {
    val fields = Qa.device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 60_000)
    assertTrue("setup credential fields not shown", fields != null && fields.size >= 3)
    fields[0].text = address
    fields[1].text = token.clientId
    fields[2].text = token.clientSecret
    val connect = Qa.device.wait(Until.findObject(By.text("연결").enabled(true)), 30_000)
    assertNotNull("connect button never enabled", connect)
    connect.click()
}

private fun resetAll() {
    Qa.app.tokenStore.clearServer()
    HerdrConfig.setServerAddress(null)
    Qa.hooksReset()
}

private val REPLACEMENT = ServiceToken("qa-new-id", "qa-new-secret")

/** S1: a failed main-frame load shows the Korean retry overlay; retry refreshes auth and reloads the same URL. */
@RunWith(AndroidJUnit4::class)
class S1MainFrameRetryTest {
    @After fun tearDown() = resetAll()

    private fun rootPaths(fx: Fixture) =
        fx.log.map { it.path.orEmpty() }.filter { it.substringBefore('?') == "/" }

    private fun retryFlow(failure: () -> MockResponse) {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        val down = AtomicBoolean(true)
        fx.routes["/"] = { if (down.get()) failure() else fx.html("ok", "<div id=m>ok</div>") }
        fx.start()
        try {
            ActivityScenario.launch<MainActivity>(mainIntent(pane = "p1")).use {
                assertNotNull("retry overlay not shown", Qa.device.wait(Until.findObject(By.text(RETRY)), 60_000))
                down.set(false)
                val health = fx.healthRequests()
                Qa.device.findObject(By.text(RETRY)).click()
                Qa.awaitJs("page after retry", 60_000, "document.getElementById('m') !== null")
                Qa.until(30_000, "overlay gone") { !Qa.device.hasObject(By.text(RETRY)) }
                assertTrue("retry must refresh authentication first", fx.healthRequests() > health)
                val paths = rootPaths(fx)
                assertTrue("same URL requested again: $paths", paths.size >= 2 && paths.all { it.contains("pane=p1") })
                assertTrue(Qa.currentUrl().orEmpty().contains("pane=p1"))
            }
        } finally {
            fx.stop()
        }
    }

    @Test
    fun httpErrorShowsRetryAndRetryReloadsTheSameUrl() =
        retryFlow { MockResponse().setResponseCode(503).setBody("down") }

    @Test
    fun transportFailureShowsRetryAndRetryReloadsTheSameUrl() =
        retryFlow { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }

    @Test
    fun subresourceFailuresDoNotReplaceThePage() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = {
            fx.html(
                "sub",
                "<div id=m>ok</div><img id=i src=\"/missing.png\">" +
                    "<iframe id=f src=\"/boom\" onload=\"window.__f=1\"></iframe>",
            )
        }
        fx.routes["/boom"] = { MockResponse().setResponseCode(500).setBody("boom") }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs(
                    "subresources settled",
                    60_000,
                    "document.readyState === 'complete' && document.getElementById('i').complete && window.__f === 1",
                )
                Qa.device.waitForIdle(3_000)
                assertFalse("retry overlay must not cover a page whose subresources failed", Qa.device.hasObject(By.text(RETRY)))
                assertEquals(true, Qa.js("document.getElementById('m') !== null"))
            }
        } finally {
            fx.stop()
        }
    }
}

/** S2: the requested pane survives a detour through setup (warm, cold) and the onboarding checklist. */
@RunWith(AndroidJUnit4::class)
class S2PendingPaneSetupTest {
    @After fun tearDown() = resetAll()

    private fun paneFixture(): Fixture {
        val fx = Fixture()
        fx.routes["/"] = { fx.html("pane", "<div id=m>pane</div>") }
        return fx.start()
    }

    private fun awaitPane(pane: String) {
        Qa.until(60_000, "pane $pane opened", { "url=${Qa.currentUrl()} live=${Qa.liveActivityNames()}" }) {
            Qa.currentUrl().orEmpty().contains("pane=$pane")
        }
    }

    @Test
    fun warmAppOpensTheRequestedPaneAfterSetup() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = paneFixture()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("root page", 60_000, "document.getElementById('m') !== null")
                // The service token was replaced server-side: the stored one is rejected from now on.
                fx.expectedToken = REPLACEMENT
                Qa.app.accessSession.invalidate()
                Qa.targetContext.startActivity(mainIntent(pane = "pane-warm").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                awaitActivity("SetupActivity")
                fillSetupAndConnect(fx.base, REPLACEMENT)
                awaitPane("pane-warm")
            }
        } finally {
            fx.stop()
        }
    }

    @Test
    fun coldStartOpensTheRequestedPaneAfterSetup() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = paneFixture()
        fx.expectedToken = REPLACEMENT
        try {
            ActivityScenario.launch<MainActivity>(mainIntent(pane = "pane-cold")).use {
                awaitActivity("SetupActivity")
                fillSetupAndConnect(fx.base, REPLACEMENT)
                awaitPane("pane-cold")
            }
        } finally {
            fx.stop()
        }
    }

    @Test
    fun firstRunCarriesThePaneThroughOnboarding() {
        Qa.resetState(alerts = false, token = null)
        Qa.setOnboarding(done = false, asked = true)
        val fx = paneFixture()
        try {
            ActivityScenario.launch<MainActivity>(mainIntent(pane = "pane-first")).use {
                awaitActivity("SetupActivity")
                fillSetupAndConnect(fx.base, Qa.syntheticToken())
                awaitActivity("OnboardingActivity")
                val start = Qa.device.wait(Until.findObject(By.text("시작하기")), 30_000)
                assertNotNull("onboarding start button not shown", start)
                start.click()
                awaitPane("pane-first")
            }
        } finally {
            fx.stop()
        }
    }
}

/** S3: when server-change recovery cannot reuse the token, setup asks for credentials, not just the address. */
@RunWith(AndroidJUnit4::class)
class S3ServerChangeRecoveryTest {
    @After fun tearDown() = resetAll()

    private fun pageFixture(): Fixture {
        val fx = Fixture()
        fx.routes["/"] = { fx.html("addr", "<div id=m>addr</div>") }
        return fx.start(override = false)
    }

    private fun changeServerThenExpectFullSetup(b: Fixture, replacement: ServiceToken) {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val field = Qa.device.wait(Until.findObject(By.clazz("android.widget.EditText")), 30_000)
            assertNotNull(field)
            field.text = b.base
            val change = Qa.device.wait(Until.findObject(By.text("주소 바꾸기").enabled(true)), 30_000)
            assertNotNull("change button never enabled", change)
            change.click()
            awaitActivity("SetupActivity")
            val fields = Qa.device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 30_000)
            assertTrue("credential fields must be offered, got ${fields?.size}", fields != null && fields.size >= 3)
            assertFalse(Qa.device.hasObject(By.textContains("저장된 서비스 토큰은 그대로")))
            fillSetupAndConnect(b.base, replacement)
            Qa.until(60_000, "credentials stored", { "server=${Qa.app.tokenStore.getServer()}" }) {
                Qa.app.tokenStore.getServer() == b.base && Qa.app.tokenStore.get() == replacement
            }
        }
    }

    @Test
    fun missingTokenOffersCredentialEntry() {
        val a = pageFixture()
        val b = pageFixture()
        try {
            Qa.resetState(alerts = false, token = null, server = a.base)
            changeServerThenExpectFullSetup(b, Qa.syntheticToken())
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun tokenRejectedByTheNewServerOffersCredentialEntry() {
        val a = pageFixture()
        val b = pageFixture()
        b.expectedToken = REPLACEMENT
        try {
            Qa.resetState(alerts = false, token = Qa.syntheticToken(), server = a.base)
            changeServerThenExpectFullSetup(b, REPLACEMENT)
        } finally {
            a.stop()
            b.stop()
        }
    }
}

private data class DlRow(val id: Long, val status: Int, val title: String?, val uri: String?)

private fun dlRows(manager: DownloadManager, uriSuffix: String): List<DlRow> =
    manager.query(DownloadManager.Query()).use {
        val out = ArrayList<DlRow>()
        while (it.moveToNext()) {
            val row = DlRow(
                id = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                title = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),
                uri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)),
            )
            if (row.uri?.endsWith(uriSuffix) == true) out += row
        }
        out
    }

private fun awaitSuccessfulCount(manager: DownloadManager, uriSuffix: String, count: Int): List<DlRow> {
    var ok: List<DlRow> = emptyList()
    Qa.until(
        60_000,
        "$count successful downloads of $uriSuffix",
        { dlRows(manager, uriSuffix).joinToString { "[status=${it.status} title=${it.title}]" } },
    ) {
        val rows = dlRows(manager, uriSuffix)
        if (rows.any { it.status == DownloadManager.STATUS_FAILED }) throw AssertionError("a download failed: $rows")
        ok = rows.filter { it.status == DownloadManager.STATUS_SUCCESSFUL }
        ok.size >= count
    }
    return ok
}

/** S4a: repeated downloads, and different paths with one basename, never collide in Downloads. */
@RunWith(AndroidJUnit4::class)
class S4DownloadNamesTest {
    @After fun tearDown() = resetAll()

    @Test
    fun repeatedDownloadsGetDistinctNames() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val served = AtomicInteger()
        val fx = Fixture()
        fun file(path: String): (okhttp3.mockwebserver.RecordedRequest) -> MockResponse = {
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setHeader("Content-Disposition", "attachment; filename=\"s4-output.txt\"")
                .setBody("payload:$path:${served.incrementAndGet()}")
        }
        fx.routes["/"] = {
            fx.html(
                "s4",
                "<a id=a href=\"/f.txt\" style=\"display:block;height:80px\">a</a>" +
                    "<a id=b href=\"/other/f.txt\" style=\"display:block;height:80px\">b</a>",
            )
        }
        fx.routes["/f.txt"] = file("/f.txt")
        fx.routes["/other/f.txt"] = file("/other/f.txt")
        fx.start()
        Qa.shell("rm -f /sdcard/Download/s4-output*.txt")
        val manager = Qa.targetContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        DownloadAssertions.removeAll(manager)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 60_000, "document.getElementById('b') !== null && document.readyState === 'complete'")
                Qa.tap("#a")
                awaitSuccessfulCount(manager, "/f.txt", 1)
                Qa.tap("#a")
                val both = awaitSuccessfulCount(manager, "/f.txt", 2)
                Qa.tap("#b")
                val other = awaitSuccessfulCount(manager, "/other/f.txt", 1)
                val titles = (both + other).map { it.title }.toSet()
                assertEquals(setOf("s4-output.txt", "s4-output (1).txt", "s4-output (2).txt"), titles)
                val bodies = (both + other).map { String(DownloadAssertions.read(manager, it.id)) }.toSet()
                assertEquals("every download keeps its own content", 3, bodies.size)
            }
        } finally {
            DownloadAssertions.removeAll(manager)
            fx.stop()
        }
    }
}

/** S4b: a download URL that redirects at request time is refused (the cookie never follows) and Access is renewed. */
@RunWith(AndroidJUnit4::class)
class S4DownloadRedirectTest {
    @After fun tearDown() = resetAll()

    private class Setup(val fx: Fixture, val preflights: AtomicInteger, val plain: AtomicInteger)

    private fun fixture(redirectUntilPreflight: Int): Setup {
        val preflights = AtomicInteger()
        val plain = AtomicInteger()
        val fx = Fixture()
        fx.routes["/"] = { fx.html("s4r", "<a id=d href=\"/d.bin\" style=\"display:block;height:80px\">d</a>") }
        fx.routes["/evil"] = { MockResponse().setBody("evil") }
        fx.routes["/d.bin"] = { request ->
            // The app's own probe is the only request that carries a Range header.
            val probe = request.getHeader("Range") != null
            if (!probe) plain.incrementAndGet()
            if (probe && preflights.incrementAndGet() <= redirectUntilPreflight) {
                MockResponse().setResponseCode(302).setHeader("Location", "${fx.base}/evil")
            } else {
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setHeader("Content-Disposition", "attachment; filename=\"s4-d.bin\"")
                    .setBody("binary")
            }
        }
        return Setup(fx.start(), preflights, plain)
    }

    @Test
    fun rejectedCookieRenewsAccessAndThenDownloads() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val s = fixture(redirectUntilPreflight = 1)
        val manager = Qa.targetContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        DownloadAssertions.removeAll(manager)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 60_000, "document.getElementById('d') !== null && document.readyState === 'complete'")
                val health = s.fx.healthRequests()
                Qa.tap("#d")
                awaitSuccessfulCount(manager, "/d.bin", 1)
                assertTrue("Access must be renewed after the rejection", s.fx.healthRequests() > health)
                assertEquals(0, s.fx.count("/evil"))
            }
        } finally {
            DownloadAssertions.removeAll(manager)
            s.fx.stop()
        }
    }

    @Test
    fun persistentRedirectIsRefusedAndNothingIsEnqueued() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val s = fixture(redirectUntilPreflight = Int.MAX_VALUE)
        val manager = Qa.targetContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        DownloadAssertions.removeAll(manager)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 60_000, "document.getElementById('d') !== null && document.readyState === 'complete'")
                val health = s.fx.healthRequests()
                Qa.tap("#d")
                // The probe, a forced renewal, the probe again; then the user is told.
                Qa.until(60_000, "probe, renewal, probe", { "probes=${s.preflights.get()} health=${s.fx.healthRequests() - health}" }) {
                    s.preflights.get() >= 2 && s.fx.healthRequests() > health
                }
                Qa.device.wait(Until.findObject(By.textContains("다운로드할 수 없습니다")), 15_000)
                assertEquals("DownloadManager must never request the URL", 1, s.plain.get())
                assertEquals(2, s.preflights.get())
                assertEquals(0, s.fx.count("/evil"))
                assertTrue(dlRows(manager, "/d.bin").isEmpty())
            }
        } finally {
            s.fx.stop()
        }
    }
}

/** S5: removing the token drops the running page, its history and web storage; setup follows without a rejection message. */
@RunWith(AndroidJUnit4::class)
class S5SignOutWipeTest {
    @After fun tearDown() = resetAll()

    @Test
    fun clearingTheTokenWipesTheRunningSession() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        val script = "if(!localStorage.getItem('k')){localStorage.setItem('k','v');document.title='fresh'}else{document.title='kept'}"
        fx.routes["/"] = { fx.html("fresh", "<div id=m>root</div><a id=n href=\"/second\" style=\"display:block;height:80px\">n</a>", script) }
        fx.routes["/second"] = { fx.html("second", "<div id=s>second</div>", script) }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("root page", 60_000, "document.title === 'fresh' && document.getElementById('n') !== null")
                Qa.tap("#n")
                Qa.awaitJs("second page", 60_000, "document.getElementById('s') !== null")
                assertEquals(true, Qa.canGoBack())
                ActivityScenario.launch(SettingsActivity::class.java).use {
                    val clear = Qa.device.wait(Until.findObject(By.text("토큰 지우기").enabled(true)), 30_000)
                    assertNotNull("clear button not shown", clear)
                    clear.click()
                    Qa.until(30_000, "token removed") { Qa.app.tokenStore.get() == null }
                    Qa.until(30_000, "page dropped", { "url=${Qa.currentUrl()} back=${Qa.canGoBack()}" }) {
                        Qa.currentUrl() == "about:blank" && Qa.canGoBack() == false
                    }
                }
                awaitActivity("SetupActivity")
                assertNotNull(Qa.device.wait(Until.findObject(By.clazz("android.widget.EditText")), 30_000))
                assertFalse("sign-out is not a rejection", Qa.device.hasObject(By.textContains("거부")))
                fillSetupAndConnect(fx.base, Qa.syntheticToken())
                Qa.until(60_000, "root reloaded with empty storage", { "url=${Qa.currentUrl()} title=${Qa.jsString("document.title")}" }) {
                    Qa.jsString("document.title") == "fresh" && Qa.jsString("location.pathname") == "/"
                }
                assertEquals(false, Qa.canGoBack())
            }
        } finally {
            fx.stop()
        }
    }
}

/** S6: the alert row is one merged toggleable unit whose accessibility node carries the label. */
@RunWith(AndroidJUnit4::class)
class S6AlertSwitchSemanticsTest {
    @After fun tearDown() = resetAll()

    @Test
    fun switchNodeIsLabelled() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val node = Qa.device.wait(Until.findObject(By.text("에이전트 알림").checkable(true)), 30_000)
            assertNotNull("no checkable node labelled with the alert text", node)
            assertFalse(node.isChecked)
            val switches = Qa.device.findObjects(By.clazz("android.widget.Switch"))
            assertEquals("exactly one switch node", 1, switches.size)
            assertEquals("the switch node itself carries the label", "에이전트 알림", switches[0].text)
            assertNull("no unlabeled second control", Qa.device.findObject(By.clazz("android.widget.Switch").text("")))
        }
    }
}
