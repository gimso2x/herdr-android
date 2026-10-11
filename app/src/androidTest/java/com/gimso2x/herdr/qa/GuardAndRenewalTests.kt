package com.gimso2x.herdr.qa

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.shell.AccessTestHooks
import com.gimso2x.herdr.shell.LiveQaGuard
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** G1: the guard, exercised against a fixture WebSocket/HTTP endpoint on the override origin. */
@RunWith(AndroidJUnit4::class)
class G1GuardFixtureTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun guardFiltersFramesAndBlocksMutations() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val frames = LinkedBlockingQueue<String>()
        val fx = Fixture()
        fx.routes["/"] = {
            fx.html(
                "g1",
                "<div id=m>g1</div>",
                """
                var ws = new WebSocket('ws://' + location.host + '/ws');
                ws.onopen = function () {
                  ws.send(JSON.stringify({type: 'role', mode: 'interact'}));
                  ws.send(JSON.stringify({type: 'resize', cols: 80, rows: 24}));
                  ws.send(JSON.stringify({type: 'input', data: 'x'}));
                  fetch('/x', {method: 'POST'}).catch(function () {
                    // detach is on the guard's allow list: it is the in-order end marker.
                    ws.send(JSON.stringify({type: 'detach'}));
                    window.__g1done = true;
                  });
                };
                """.trimIndent(),
            )
        }
        fx.routes["/ws"] = {
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(text)
                    if (JSONObject(text).optString("type") == "role") {
                        webSocket.send("""{"type":"role-ack","mode":"observe"}""")
                        webSocket.send("""{"type":"snapshot","snapshot":{}}""")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
            })
        }
        fx.start()
        try {
            LiveQaGuard.enable(fx.base)
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page finished its sends", 30_000, "window.__g1done === true")
                val seen = ArrayList<JSONObject>()
                while (true) {
                    val text = frames.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("fixture never saw the detach marker")
                    val frame = JSONObject(text)
                    if (frame.getString("type") == "detach") break
                    seen += frame
                }
                assertEquals("only the role frame may reach the server", 1, seen.size)
                assertEquals("role", seen[0].getString("type"))
                assertEquals("observe", seen[0].getString("mode"))
                assertEquals(0, fx.count("/x"))
                assertEquals(2L, (Qa.js("window.__qaDropped") as Number).toLong())
                assertEquals(1L, (Qa.js("window.__qaBlocked") as Number).toLong())
                // Server -> client frames are counted by type only: the fixture answers the role frame with a
                // role-ack and a snapshot, and the guard must have counted exactly those two (no payload kept).
                Qa.awaitJs(
                    "incoming frame types counted",
                    30_000,
                    "window.__qaIncoming === 2 && window.__qaIncomingTypes['role-ack'] === 1 && window.__qaIncomingTypes['snapshot'] === 1",
                )
            }
        } finally {
            fx.stop()
        }
    }
}

private fun cookieEntry(value: String) = "CF_Authorization=$value"

/** A1: silent renewal while MainActivity stays open (alerts off). */
@RunWith(AndroidJUnit4::class)
class A1RenewalTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun cookieIsRenewedWhileOpen() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val clock = FakeClock()
        val fx = Fixture()
        fx.clock = clock
        fx.routes["/"] = { fx.html("a1", "<div id=m>a1</div>") }
        fx.routes["/probe"] = { MockResponse().setBody("ok") }
        fx.start()
        try {
            val captured = CompletableDeferred<Long>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            AccessTestHooks.clock = clock
            AccessTestHooks.sleeper = { millis ->
                // Only the first cycle is observed and released; later cycles never wake up.
                if (calls.incrementAndGet() == 1) {
                    captured.complete(millis)
                    release.await()
                } else {
                    awaitCancellation()
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('m') !== null")
                Qa.js("(window.__qaMarker = 1)")
                assertEquals(1, fx.healthRequests())
                val requested = runBlocking { withTimeout(30_000) { captured.await() } }
                assertTrue("sleeper asked for $requested ms, expected 100 s +- 1 s", abs(requested - 100_000) <= 1_000)

                clock.advance(requested)
                release.complete(Unit)

                Qa.until(30_000, "second /api/health bootstrap") { fx.healthRequests() >= 2 }
                assertEquals(2, fx.issued.size)
                val fresh = fx.issued[1]
                Qa.until(30_000, "renewed cookie installed in the WebView") {
                    Qa.cookieFor(fx.base)?.contains(cookieEntry(fresh)) == true
                }
                Qa.js("(function(){fetch('/probe');return 1})()")
                Qa.until(30_000, "probe request recorded") { fx.count("/probe") >= 1 }
                val probe = fx.log.first { it.path == "/probe" }
                assertTrue("page fetch did not carry the renewed cookie", probe.getHeader("Cookie")?.contains(cookieEntry(fresh)) == true)

                assertEquals("page was reloaded", 1L, (Qa.js("window.__qaMarker") as Number).toLong())
                assertEquals("page was requested again", 1, fx.count("/"))
                assertFalse(Qa.liveActivityNames().contains("SetupActivity"))
            }
        } finally {
            fx.stop()
        }
    }
}

/** A2: the renewal is rejected (403): Setup opens with the rejection message. */
@RunWith(AndroidJUnit4::class)
class A2RenewalRejectedTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun rejectedRenewalOpensSetup() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val clock = FakeClock()
        val fx = Fixture()
        fx.clock = clock
        fx.healthStatus = { n -> if (n >= 2) 403 else 200 }
        fx.routes["/"] = { fx.html("a2", "<div id=m>a2</div>") }
        fx.start()
        try {
            val captured = CompletableDeferred<Long>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            AccessTestHooks.clock = clock
            AccessTestHooks.sleeper = { millis ->
                if (calls.incrementAndGet() == 1) {
                    captured.complete(millis)
                    release.await()
                } else {
                    awaitCancellation()
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                Qa.awaitJs("fixture page", 30_000, "document.getElementById('m') !== null")
                val requested = runBlocking { withTimeout(30_000) { captured.await() } }
                assertTrue(abs(requested - 100_000) <= 1_000)
                assertFalse(Qa.liveActivityNames().contains("SetupActivity"))
                clock.advance(requested)
                release.complete(Unit)
                Qa.until(30_000, "second /api/health bootstrap") { fx.healthRequests() >= 2 }
                val shown = Qa.device.wait(Until.hasObject(By.textContains("토큰이 거부되었습니다")), 30_000)
                assertNotNull(shown)
                assertTrue("Setup did not show the rejection", shown == true)
            }
        } finally {
            fx.stop()
        }
    }
}
