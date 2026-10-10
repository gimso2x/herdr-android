package com.gimso2x.herdr.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gimso2x.herdr.MainActivity
import okhttp3.mockwebserver.MockResponse
import org.hamcrest.Description
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private fun uriIs(expected: String) = object : TypeSafeMatcher<Uri>() {
    override fun matchesSafely(item: Uri): Boolean = item.toString().trimEnd('/') == expected.trimEnd('/')

    override fun describeTo(description: Description) {
        description.appendText("uri $expected")
    }
}

/** F1: external links and popups leave the app through ACTION_VIEW; the page stays put. */
@RunWith(AndroidJUnit4::class)
class F1ExternalPopupTest {
    @After fun tearDown() {
        Qa.hooksReset()
        runCatching { Intents.release() }
    }

    @Test
    fun externalLinksAndPopups() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = {
            fx.html(
                "f1",
                """
                <p><a id="a" href="https://example.com" style="display:block;height:60px">plain link</a></p>
                <p><a id="b" target="_blank" href="https://example.com/b" style="display:block;height:60px">blank link</a></p>
                <p><button id="c" style="height:60px" onclick="window.open('https://example.com/c')">window.open</button></p>
                """.trimIndent(),
            )
        }
        fx.start()
        Intents.init()
        try {
            // Never start a real browser: every outgoing intent is answered by the stub.
            intending(not(isInternal())).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent()))
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('c') !== null && document.readyState === 'complete'")
                val pageUrl = Qa.jsString("location.href")
                assertNotNull(pageUrl)
                for ((selector, expected) in listOf(
                    "#a" to "https://example.com",
                    "#b" to "https://example.com/b",
                    "#c" to "https://example.com/c",
                )) {
                    Qa.tap(selector)
                    Qa.until(20_000, "ACTION_VIEW for $expected") {
                        Intents.getIntents().any { it.action == Intent.ACTION_VIEW && it.data?.toString()?.trimEnd('/') == expected.trimEnd('/') }
                    }
                    intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(uriIs(expected))))
                    assertEquals("main WebView navigated away", pageUrl, Qa.jsString("location.href"))
                }
            }
        } finally {
            fx.stop()
        }
    }
}

/** F2: rotation keeps the page (no reload, no recreation). */
@RunWith(AndroidJUnit4::class)
class F2RotationTest {
    @After fun tearDown() {
        Qa.hooksReset()
        Qa.device.setOrientationNatural()
        Qa.device.unfreezeRotation()
    }

    @Test
    fun rotationKeepsThePage() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = { fx.html("f2", "<div id=m>f2</div>") }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('m') !== null")
                Qa.js("(window.__qaMarker = 1)")
                var before: Activity? = null
                scenario.onActivity { before = it }
                Qa.device.setOrientationLeft()
                Qa.until(20_000, "landscape configuration") {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
                Qa.awaitJs("marker survives rotation", 20_000, "window.__qaMarker === 1 && window.innerWidth > window.innerHeight")
                var after: Activity? = null
                scenario.onActivity { after = it }
                assertSame("activity was recreated by the rotation", before, after)
                assertEquals(1, fx.count("/"))
            }
        } finally {
            fx.stop()
        }
    }
}

/** F3: recreation restores the fixture URL. */
@RunWith(AndroidJUnit4::class)
class F3RecreateTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun recreateRestoresUrl() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = { fx.html("f3", "<div id=m>root</div>") }
        fx.routes["/page2"] = { fx.html("f3-page2", "<div id=m2>page2</div>") }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Qa.awaitJs("root page", 30_000, "document.getElementById('m') !== null")
                Qa.js("(function(){location.href='/page2';return 1})()")
                Qa.awaitJs("page2", 30_000, "location.pathname === '/page2' && document.getElementById('m2') !== null")
                scenario.recreate()
                Qa.awaitJs("page2 restored after recreate", 30_000, "location.pathname === '/page2' && document.readyState === 'complete'")
                assertTrue(Qa.currentUrl().orEmpty().endsWith("/page2"))
            }
        } finally {
            fx.stop()
        }
    }
}

/** F4: Back closes the keyboard first, then walks the page history, then leaves the app. */
@RunWith(AndroidJUnit4::class)
class F4BackOrderTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun backOrder() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        // "/" redirects, so the history is exactly [/a, /b].
        fx.routes["/"] = { MockResponse().setResponseCode(302).setHeader("Location", "/a") }
        fx.routes["/a"] = {
            fx.html("f4-a", "<a id=go href=\"/b\" style=\"display:block;height:80px\">to b</a><div id=a>a</div>")
        }
        fx.routes["/b"] = { fx.html("f4-b", "<input id=i type=text style=\"width:80%;height:60px\">") }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Qa.awaitJs("page /a", 30_000, "location.pathname === '/a' && document.getElementById('a') !== null")
                // A real tap: Chromium's history-manipulation intervention makes Back skip an entry
                // that was left without any user activation (a script-driven navigation).
                Qa.tap("#go")
                Qa.awaitJs("page /b", 30_000, "location.pathname === '/b' && document.getElementById('i') !== null && document.readyState === 'complete'")

                Qa.tap("#i")
                Qa.until(20_000, "input focused by the tap") { Qa.jsString("document.activeElement && document.activeElement.id") == "i" }
                Qa.requestIme()
                Qa.until(30_000, "keyboard shown") { Qa.isImeVisible() }

                Qa.device.pressBack()
                Qa.until(20_000, "keyboard hidden by Back") { !Qa.isImeVisible() }
                assertEquals("/b", Qa.jsString("location.pathname"))

                Qa.device.pressBack()
                Qa.awaitJs("Back walks the page history to /a", 20_000, "location.pathname === '/a'")

                Qa.device.pressBack()
                // The last Back leaves the app: either the activity finishes or the task moves to
                // the background (Android 12+ does that for a root launcher activity).
                Qa.until(20_000, "activity left the foreground") {
                    scenario.state == Lifecycle.State.CREATED || scenario.state == Lifecycle.State.DESTROYED
                }
            }
        } finally {
            fx.stop()
        }
    }
}

/** F5: with the keyboard shown the page viewport shrinks and a fixed bottom bar stays visible. */
@RunWith(AndroidJUnit4::class)
class F5ImeResizeTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun imeResizesTheViewport() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        fx.routes["/"] = {
            fx.html(
                "f5",
                "<input id=i type=text style=\"width:80%;height:60px;margin-top:16px\">" +
                    "<div id=bar style=\"position:fixed;left:0;right:0;bottom:0;height:48px;background:#09f\">bar</div>",
            )
        }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('bar') !== null && document.readyState === 'complete'")
                val full = (Qa.js("window.innerHeight") as Number).toDouble()
                Qa.tap("#i")
                Qa.until(20_000, "input focused by the tap") { Qa.jsString("document.activeElement && document.activeElement.id") == "i" }
                Qa.requestIme()
                Qa.until(30_000, "keyboard shown") { Qa.isImeVisible() }
                Qa.awaitJs("viewport shrinks below $full", 20_000, "window.innerHeight < $full")
                Qa.awaitJs(
                    "bar stays inside the viewport",
                    20_000,
                    "document.getElementById('bar').getBoundingClientRect().bottom <= window.innerHeight + 0.5",
                )
            }
        } finally {
            fx.stop()
        }
    }
}

/**
 * F6: viewport-height units resolve against the real viewport. A WebView whose layout params are
 * wrap_content (the default Compose's AndroidView gives a view without params) makes Chromium force a
 * zero layout height: 100vh/100dvh/100svh/100lvh all compute to 0 while innerHeight is right, and
 * herdr-web-ui's `.app { height: var(--app-height, 100dvh) }` collapses every pane to 0px (blank terminal).
 */
@RunWith(AndroidJUnit4::class)
class F6ViewportUnitsTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun viewportUnitsAreNotZero() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        // The same shape as herdr-web-ui's shell: a column as tall as the viewport unit, a flexible body.
        fx.routes["/"] = {
            fx.html(
                "f6",
                "<style>html,body{margin:0}#app{display:flex;flex-direction:column;height:100%;height:var(--app-height,100dvh)}" +
                    "#body{flex:1 1 auto;min-height:0}</style>" +
                    "<div id=app><div style=\"height:40px\">header</div><div id=body>body</div></div>",
            )
        }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('body') !== null && document.readyState === 'complete'")
                val inner = (Qa.js("window.innerHeight") as Number).toDouble()
                assertTrue("innerHeight is $inner", inner > 100)
                for (unit in listOf("vh", "dvh", "svh", "lvh")) {
                    val height = (Qa.js(
                        "(function(){var d=document.createElement('div');d.style.cssText='position:fixed;top:0;width:1px;height:100$unit';" +
                            "document.body.appendChild(d);var h=d.getBoundingClientRect().height;d.remove();return h})()",
                    ) as Number).toDouble()
                    assertEquals("100$unit", inner, height, 1.0)
                }
                val app = (Qa.js("document.getElementById('app').getBoundingClientRect().height") as Number).toDouble()
                val body = (Qa.js("document.getElementById('body').getBoundingClientRect().height") as Number).toDouble()
                assertEquals("app column height", inner, app, 1.0)
                assertTrue("body height $body", body > 100)
            }
        } finally {
            fx.stop()
        }
    }
}

/** R1: UI served by the server changes without touching the installed APK. */
@RunWith(AndroidJUnit4::class)
class R1ServedRevisionTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun newServedRevisionAppearsAfterReload() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val fx = Fixture()
        val revision = java.util.concurrent.atomic.AtomicReference("A")
        fx.routes["/"] = { fx.html("r1", "<div id=rev>${revision.get()}</div>") }
        fx.start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("revision A", 30_000, "document.getElementById('rev') !== null && document.getElementById('rev').textContent === 'A'")
                revision.set("B")
                Qa.js("(window.__r1old = 1)")
                Qa.reload()
                Qa.awaitJs("revision B in a new document", 30_000, "window.__r1old === undefined && document.getElementById('rev') !== null && document.getElementById('rev').textContent === 'B'")
            }
        } finally {
            fx.stop()
        }
    }
}

/**
 * Harness warm-up, not a product scenario: the very first soft-keyboard use after an emulator boot
 * starts the IME process and can drop the first show request, which would make whichever keyboard
 * scenario runs first fail. A Compose text field (Setup) brings the IME up with a generous bound.
 */
@RunWith(AndroidJUnit4::class)
class ImeWarmupTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun keyboardComesUp() {
        Qa.resetState(alerts = false, token = null)
        ActivityScenario.launch<com.gimso2x.herdr.SetupActivity>(com.gimso2x.herdr.SetupActivity.intent(Qa.targetContext)).use {
            val field = Qa.device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.clazz("android.widget.EditText")), 60_000)
            assertNotNull("no text field on the setup screen", field)
            field.click()
            Qa.until(90_000, "keyboard shown for the warm-up") {
                Qa.shell("dumpsys input_method").contains("mInputShown=true")
            }
            Qa.device.pressBack()
            Qa.until(30_000, "keyboard hidden after the warm-up") {
                !Qa.shell("dumpsys input_method").contains("mInputShown=true")
            }
        }
    }
}
