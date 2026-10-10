package com.gimso2x.herdr.qa

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.access.ServiceToken
import com.gimso2x.herdr.shell.HerdrDownloadListener
import com.gimso2x.herdr.shell.LiveQaGuard
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Live scenarios: base = https://herdr.example.com with LiveQaGuard on. They only render and GET;
// the guard drops input/resize/... frames, rewrites the WebSocket role to observe and rejects any
// non-GET/HEAD request (L1 asserts nothing was blocked).

private const val TITLE = "herdr web ui"

private fun liveGuardOn() {
    check(LiveQaGuard.isSupported()) { "DOCUMENT_START_SCRIPT unsupported by this WebView" }
    LiveQaGuard.enable(Qa.LIVE)
}

/**
 * herdr-web-ui titles the document "herdr web ui" until a pane is selected and "<pane> · herdr"
 * afterwards (App.tsx), so the live page is recognised by either form.
 */
private const val TITLE_IS_HERDR =
    "(document.title === 'herdr web ui' || document.title.endsWith(' · herdr'))"

private fun awaitLivePage(timeoutMs: Long, extra: String = "true", detail: () -> String = { "" }) {
    Qa.until(timeoutMs, "live herdr page", detail) {
        Qa.js("$TITLE_IS_HERDR && location.host === 'herdr.example.com' && ($extra)") == true
    }
}

/** True once an element of the workspace drawer holding [quotedLabel] (lower-case JSON string) is fully inside the viewport. */
private fun labelInViewport(quotedLabel: String): String =
    "(function(){var d=document.getElementById('workspace-drawer');if(!d||getComputedStyle(d).visibility!=='visible')return false;" +
        "var els=d.querySelectorAll('*');for(var i=0;i<els.length;i++){var e=els[i];" +
        "if(e.children.length===0&&e.textContent.toLowerCase().indexOf($quotedLabel)>=0){var b=e.getBoundingClientRect();" +
        "return b.width>0&&b.height>0&&b.left>=0&&b.top>=0&&b.right<=window.innerWidth&&b.bottom<=window.innerHeight}}return false})()"

@RunWith(AndroidJUnit4::class)
class L1LiveLoadTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun liveLoad() {
        val token = LiveSupport.token()
        val label = LiveSupport.firstWorkspaceLabel(token)
        Qa.resetState(alerts = false, token = token)
        liveGuardOn()
        ActivityScenario.launch(MainActivity::class.java).use {
            // The workspace label is rendered from the /ws snapshot: seeing it proves the
            // cookie-authenticated WebSocket works.
            val quoted = JSONObject.quote(label.lowercase())
            awaitLivePage(
                60_000,
                "document.body.innerText.toLowerCase().indexOf($quoted) >= 0",
            ) {
                "titleIsHerdr=${Qa.js(TITLE_IS_HERDR)} bodyChars=${Qa.js("document.body.innerText.length")} " +
                    "inText=${Qa.js("document.body.textContent.toLowerCase().indexOf($quoted) >= 0")}"
            }
            assertEquals(0L, (Qa.js("window.__qaBlocked") as Number).toLong())
            // The label can come from the HTTP session load before the socket opens: wait (bounded)
            // for the guard to see the client's role frame instead of reading the counter once.
            Qa.until(20_000, "guard saw a role frame rewritten to observe", { "rewritten=${Qa.js("window.__qaRoleRewritten")}" }) {
                (Qa.js("window.__qaRoleRewritten") as? Number)?.toLong()?.let { it >= 1 } == true
            }

            // Open the workspace list drawer with a real tap (a client-side toggle, nothing is
            // sent), unless the page already shows it open (a tap would close it), then wait for
            // the first workspace label element to be laid out inside the viewport.
            // Closed-drawer baseline: two consecutive captures that agree (the page has stopped moving).
            var baseline = Qa.screenBitmap()
            var drift = 1.0
            Qa.until(30_000, "closed-drawer screen is stable", { "drift=$drift" }) {
                val next = Qa.screenBitmap()
                drift = Qa.changedFraction(baseline, next, 0.0, 0.85, 0.33, 0.95)
                baseline = next
                drift < 0.002
            }
            val before = baseline
            if (Qa.js("document.querySelector('button.drawer-toggle').getAttribute('aria-expanded') === 'true'") != true) {
                Qa.tap("button.drawer-toggle")
            }
            Qa.until(
                20_000,
                "workspace label visible in the open drawer",
                {
                    "drawer=${Qa.js("(function(){var d=document.getElementById('workspace-drawer');var b=d.getBoundingClientRect();" +
                        "return d.className+' vis='+getComputedStyle(d).visibility+' left='+b.left+' right='+b.right+' vw='+window.innerWidth+" +
                        "' expanded='+document.querySelector('button.drawer-toggle').getAttribute('aria-expanded')})()")}"
                },
            ) { Qa.js(labelInViewport(quoted)) == true }
            // The slide-in transition has ended and the toggle reports the drawer open.
            Qa.awaitJs(
                "drawer open and settled",
                20_000,
                "document.querySelector('button.drawer-toggle').getAttribute('aria-expanded') === 'true' && " +
                    "document.getElementById('workspace-drawer').classList.contains('is-open') && " +
                    "document.getElementById('workspace-drawer').getAnimations().length === 0",
            )
            Qa.awaitFrame()
            Qa.logFact(
                "l1 drawer: " + Qa.js(
                    "(function(){var d=document.getElementById('workspace-drawer');var b=d.getBoundingClientRect();" +
                        "var t=document.querySelector('button.drawer-toggle');" +
                        "return 'class='+d.className+' vis='+getComputedStyle(d).visibility+' left='+b.left+' right='+b.right+" +
                        "' vw='+window.innerWidth+' expanded='+t.getAttribute('aria-expanded')+' vs='+document.visibilityState})()",
                ),
            )
            assertEquals(0L, (Qa.js("window.__qaBlocked") as Number).toLong())
            // The DOM can be ahead of the display (and a banner may appear above the page): capture
            // into l1.png until the stored file itself differs in the drawer area (the left 85% of
            // the screen below the header/tab strip) from the closed-drawer baseline.
            var changed = 0.0
            Qa.until(20_000, "l1.png shows the open drawer", { "changedFraction=$changed" }) {
                changed = Qa.changedFraction(before, Qa.decodePng(Qa.screenshotBytes("l1.png")), 0.0, 0.85, 0.33, 0.95)
                changed >= 0.08
            }
            Qa.logFact("l1 screen changed fraction=$changed drawerStillOpen=${Qa.js("document.querySelector('button.drawer-toggle').getAttribute('aria-expanded')")}")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class L2BadTokenTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun badTokenShowsRejection() {
        val real = LiveSupport.token()
        Qa.resetState(alerts = false, token = ServiceToken(real.clientId, "wrong"))
        liveGuardOn()
        ActivityScenario.launch(MainActivity::class.java).use {
            val shown = Qa.device.wait(Until.hasObject(By.textContains("토큰이 거부되었습니다")), 45_000)
            assertTrue("SetupActivity did not show the rejection", shown == true)
            assertTrue(Qa.liveActivityNames().contains("SetupActivity"))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class L3ExpiryRecoveryTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun recoversAfterCookieExpiry() {
        val token = LiveSupport.token()
        Qa.resetState(alerts = false, token = token)
        liveGuardOn()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitLivePage(60_000)
            Qa.js("(window.__l3old = 1)")
            Qa.onMain {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
            }
            assertNull(Qa.cookieFor(Qa.LIVE))
            Qa.reload()
            var onAccess = false
            Qa.until(60_000, "herdr page again after the reload (new document)") {
                val host = Qa.jsString("location.host")
                if (host == Qa.ACCESS_HOST) onAccess = true
                Qa.js("window.__l3old === undefined && document.title === ${JSONObject.quote(TITLE)} && location.host === 'herdr.example.com'") == true
            }
            assertTrue("a Cloudflare Access page was displayed", !onAccess)
            assertEquals("herdr.example.com", Qa.jsString("location.host"))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class L4LiveDownloadTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun faviconDownloadsThroughTheAppListener() {
        val token = LiveSupport.token()
        Qa.resetState(alerts = false, token = token)
        liveGuardOn()
        val context = Qa.targetContext
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        DownloadAssertions.removeAll(manager)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitLivePage(60_000)
            val listener = HerdrDownloadListener(context, { Qa.LIVE }, { })
            Qa.onMain { listener.onDownloadStart("${Qa.LIVE}/favicon.png", "qa", null, "image/png", -1) }
            val row = DownloadAssertions.awaitSuccessful(manager, "/favicon.png", 60_000)
            assertEquals("image/png", row.mime)
            val head = DownloadAssertions.read(manager, row.id).take(4)
            assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), head.map { it.toInt() and 0xFF })
        }
    }
}
