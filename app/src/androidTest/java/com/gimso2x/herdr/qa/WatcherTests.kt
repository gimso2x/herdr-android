package com.gimso2x.herdr.qa

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.gimso2x.herdr.AppPreferences
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.SettingsActivity
import com.gimso2x.herdr.access.ServiceToken
import com.gimso2x.herdr.watch.AppForeground
import com.gimso2x.herdr.watch.PollOutcome
import com.gimso2x.herdr.watch.PollResult
import com.gimso2x.herdr.watch.StatusWatcherService
import com.gimso2x.herdr.watch.WatchNotifications
import com.gimso2x.herdr.watch.WatchTestHooks
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A herdr stand-in: Access bootstrap, a page and `/api/session` with test-controlled pane states. */
class WatchFixture {
    val fx = Fixture()
    private val states = ConcurrentHashMap<String, String>()

    init {
        states[PANE_1] = "idle"
        states[PANE_2] = "idle"
        fx.routes["/"] = { fx.html("watch", "<div id=m>watch</div>") }
        fx.routes["/api/session"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody(sessionJson())
        }
        fx.routes["/api/pane/prompt"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody(promptJson())
        }
        fx.routes["/api/pane/conversation"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody(conversationJson())
        }
    }

    fun start(): WatchFixture {
        fx.start()
        return this
    }

    fun set(pane: String, status: String) {
        states[pane] = status
    }

    private fun sessionJson(): String {
        val panes = JSONArray()
        for ((id, tab, title) in listOf(Triple(PANE_1, "w1:t1", "task one"), Triple(PANE_2, "w1:t2", "task two"))) {
            panes.put(
                JSONObject()
                    .put("pane_id", id)
                    .put("workspace_id", "w1")
                    .put("tab_id", tab)
                    .put("agent_status", states[id])
                    .put("agent", "omp")
                    .put("terminal_title_stripped", title),
            )
        }
        val workspaces = JSONArray().put(JSONObject().put("workspace_id", "w1").put("label", "ws-a"))
        val tabs = JSONArray()
            .put(JSONObject().put("tab_id", "w1:t1").put("workspace_id", "w1").put("label", "main"))
            .put(JSONObject().put("tab_id", "w1:t2").put("workspace_id", "w1").put("label", "logs"))
        return JSONObject()
            .put("snapshot", JSONObject().put("workspaces", workspaces).put("tabs", tabs).put("panes", panes))
            .toString()
    }

    /** Shapes of GET /api/pane/prompt and GET /api/pane/conversation (see AlertDetail). */
    private fun promptJson(): String = JSONObject()
        .put(
            "prompt",
            JSONObject()
                .put("id", "fixture-prompt")
                .put("agent", "omp")
                .put("kind", "question")
                .put("title", "Fixture")
                .put("question", "$PROMPT_QUESTION\nsecond line")
                .put("options", JSONArray()),
        )
        .put("suggestion", JSONObject.NULL)
        .toString()

    private fun conversationJson(): String {
        val parts = JSONArray()
            .put(JSONObject().put("kind", "thinking").put("text", "hmm"))
            .put(JSONObject().put("kind", "text").put("text", "$LAST_ANSWER\nmore"))
        val turns = JSONArray()
            .put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("kind", "text").put("text", "go"))))
            .put(JSONObject().put("role", "assistant").put("parts", parts))
        return JSONObject().put("source", "omp-transcript").put("turns", turns).put("cursor", JSONObject.NULL).toString()
    }

    /** Requests the watcher made for alert details: (method, path-with-query). */
    fun detailRequests(): List<Pair<String, String>> =
        fx.log.filter { it.path.orEmpty().startsWith("/api/pane/") }.map { it.method.orEmpty() to it.path.orEmpty() }

    companion object {
        const val PANE_1 = "w1:p1"
        const val PANE_2 = "w1:p2"
        const val TITLE_1 = "ws-a · main"
        const val TITLE_2 = "ws-a · logs"
        const val BODY_1 = "omp · task one — 입력이 필요합니다"
        const val BODY_1_DONE = "omp · task one — 작업을 마쳤습니다"
        const val PROMPT_QUESTION = "Run the fixture migration?"
        const val LAST_ANSWER = "Fixture finished the task."
    }
}

private object Watch {
    val notifications: NotificationManager
        get() = Qa.targetContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun awaitRunning(running: Boolean = true) {
        runBlocking { withTimeout(30_000) { StatusWatcherService.isRunning.first { it == running } } }
    }

    /** One poll through the test hook; waits for it to land in lastPoll and requires success. */
    fun poll(): PollResult {
        val result = runBlocking { withTimeout(30_000) { WatchTestHooks.pollNow() } }
        assertEquals("poll outcome", PollOutcome.OK, result.outcome)
        runBlocking { withTimeout(10_000) { WatchTestHooks.lastPoll.first { it.sequence >= result.sequence } } }
        return result
    }

    /** Home, then wait until the process lifecycle really reports background (it lags ~700 ms). */
    fun goBackground() {
        Qa.device.pressHome()
        Qa.until(20_000, "app reported in background") { !AppForeground.isForeground() }
    }

    fun awaitAlert(channel: String, pane: String): StatusBarNotification {
        var found: StatusBarNotification? = null
        Qa.until(20_000, "notification on $channel for $pane") {
            found = notifications.activeNotifications.firstOrNull { it.notification.channelId == channel && it.tag == pane }
            found != null
        }
        return found!!
    }

    fun title(n: StatusBarNotification): String = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    fun text(n: StatusBarNotification): String = n.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    fun bigText(n: StatusBarNotification): String =
        n.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    /** Opens the shade and taps the notification titled [title]. */
    fun tapNotification(title: String) {
        Qa.device.openNotification()
        val entry = Qa.device.wait(Until.findObject(By.text(title)), 20_000)
        assertNotNull("notification '$title' not in the shade", entry)
        entry.click()
    }

    /** Drives [pane] idle -> working -> blocked (61 s turn) so the NEEDS_INPUT alert fires. */
    fun blocked(fixture: WatchFixture, clock: FakeClock, pane: String) {
        fixture.set(pane, "working")
        poll()
        clock.advance(61_000)
        fixture.set(pane, "blocked")
        poll()
    }
}

/** W1: observed transitions raise the two alert kinds while the app is in the background. */
@RunWith(AndroidJUnit4::class)
class W1NotifyTest {
    @get:Rule val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @After fun tearDown() {
        Qa.hooksReset()
        Watch.notifications.cancelAll()
    }

    @Test
    fun needsInputThenFinished() {
        Qa.resetState(alerts = true, token = Qa.syntheticToken())
        val clock = FakeClock()
        WatchTestHooks.clock = clock
        val wf = WatchFixture().start()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Watch.awaitRunning()
                Watch.goBackground()
                Watch.poll() // seeds both panes as idle

                Watch.blocked(wf, clock, WatchFixture.PANE_1)
                val needsInput = Watch.awaitAlert(WatchNotifications.CHANNEL_NEEDS_INPUT, WatchFixture.PANE_1)
                assertEquals(
                    NotificationManager.IMPORTANCE_HIGH,
                    Watch.notifications.getNotificationChannel(WatchNotifications.CHANNEL_NEEDS_INPUT).importance,
                )
                assertEquals(WatchFixture.TITLE_1, Watch.title(needsInput))
                assertEquals(WatchFixture.BODY_1, Watch.text(needsInput))
                assertEquals(
                    "${WatchFixture.BODY_1}\n${WatchFixture.PROMPT_QUESTION}",
                    Watch.bigText(needsInput),
                )

                wf.set(WatchFixture.PANE_1, "working")
                Watch.poll()
                wf.set(WatchFixture.PANE_1, "idle")
                Watch.poll()
                val finished = Watch.awaitAlert(WatchNotifications.CHANNEL_FINISHED, WatchFixture.PANE_1)
                assertEquals(WatchFixture.TITLE_1, Watch.title(finished))
                assertEquals(WatchFixture.BODY_1_DONE, Watch.text(finished))
                assertEquals(
                    "${WatchFixture.BODY_1_DONE}\n${WatchFixture.LAST_ANSWER}",
                    Watch.bigText(finished),
                )
                // Exactly one read-only GET per alert; nothing else touched the pane endpoints.
                assertEquals(
                    listOf(
                        "GET" to "/api/pane/prompt?pane_id=w1%3Ap1",
                        "GET" to "/api/pane/conversation?pane_id=w1%3Ap1",
                    ),
                    wf.detailRequests(),
                )
            }
        } finally {
            wf.fx.stop()
        }
    }
}

/** W2: tapping an alert opens that pane's chat, cold (no activity) and warm (onNewIntent). */
@RunWith(AndroidJUnit4::class)
class W2TapNotificationTest {
    @get:Rule val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @After fun tearDown() {
        Qa.hooksReset()
        Watch.notifications.cancelAll()
    }

    @Test
    fun tapOpensThePane() {
        Qa.resetState(alerts = true, token = Qa.syntheticToken())
        val clock = FakeClock()
        WatchTestHooks.clock = clock
        val wf = WatchFixture().start()
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                Watch.awaitRunning()
                Watch.goBackground()
                Watch.poll()
                Watch.blocked(wf, clock, WatchFixture.PANE_1)
                Watch.awaitAlert(WatchNotifications.CHANNEL_NEEDS_INPUT, WatchFixture.PANE_1)
            } finally {
                // The watcher service keeps the process alive; the next tap starts a cold activity.
                scenario.close()
            }
            Qa.until(20_000, "MainActivity gone") { !Qa.mainActivityExists() }

            Qa.recordLaunchIntents()
            Watch.tapNotification(WatchFixture.TITLE_1)
            Qa.until(30_000, "cold start opened pane 1 chat", { "url=${Qa.currentUrl()} launches=${Qa.launchIntents}" }) {
                Qa.currentUrl()?.contains("pane=w1%3Ap1&view=chat") == true
            }

            // Warm: the activity stays alive in the background, then a second alert is tapped.
            Watch.goBackground()
            Watch.blocked(wf, clock, WatchFixture.PANE_2)
            Watch.awaitAlert(WatchNotifications.CHANNEL_NEEDS_INPUT, WatchFixture.PANE_2)
            assertTrue(Qa.mainActivityExists())
            Watch.tapNotification(WatchFixture.TITLE_2)
            Qa.until(30_000, "warm onNewIntent opened pane 2 chat", { "url=${Qa.currentUrl()}" }) {
                Qa.currentUrl()?.contains("pane=w1%3Ap2&view=chat") == true
            }
        } finally {
            wf.fx.stop()
        }
    }
}

// --- W3: the host (ci/instrumented.sh) force-stops the app and broadcasts BOOT_COMPLETED ---------

private val W3_TOKEN = ServiceToken("qa-w3-id", "qa-w3-secret")

/** W3 phase 1: synthetic token + alerts on; the host then delivers BOOT_COMPLETED to the stopped app. */
@RunWith(AndroidJUnit4::class)
class W3SeedOnTest {
    @Test
    fun seedAlertsOn() {
        Qa.resetState(alerts = true, token = W3_TOKEN, server = Qa.SAMPLE)
        assertNotNull(Qa.app.tokenStore.get())
        assertTrue(AppPreferences(Qa.targetContext).alertsEnabled)
    }
}

/** W3 phase 2: alerts off; the host repeats the broadcast and expects the receiver to refuse. */
@RunWith(AndroidJUnit4::class)
class W3SeedOffTest {
    @Test
    fun seedAlertsOff() {
        Qa.resetState(alerts = false, token = W3_TOKEN, server = Qa.SAMPLE)
        assertNotNull(Qa.app.tokenStore.get())
        assertFalse(AppPreferences(Qa.targetContext).alertsEnabled)
    }
}

/** W3 phase 3: the Settings switch stops and starts the service. */
@RunWith(AndroidJUnit4::class)
class W3ToggleTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun settingsSwitchTogglesTheService() {
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        val wf = WatchFixture().start()
        try {
            assertFalse(StatusWatcherService.isRunning.value)
            ActivityScenario.launch(SettingsActivity::class.java).use {
                val off = Qa.device.wait(Until.findObject(By.checkable(true)), 30_000)
                assertNotNull("alert switch not found", off)
                assertFalse(off.isChecked)
                off.click()
                Watch.awaitRunning(true)
                assertTrue(AppPreferences(Qa.targetContext).alertsEnabled)

                val on = Qa.device.wait(Until.findObject(By.checkable(true).checked(true)), 30_000)
                assertNotNull("switch did not turn on", on)
                on.click()
                Watch.awaitRunning(false)
                assertFalse(AppPreferences(Qa.targetContext).alertsEnabled)
            }
        } finally {
            wf.fx.stop()
        }
    }
}

/** W4: with POST_NOTIFICATIONS revoked by the host, Settings says so. */
@RunWith(AndroidJUnit4::class)
class W4PermissionOffTest {
    @After fun tearDown() = Qa.hooksReset()

    @Test
    fun settingsShowsPermissionOff() {
        check(Qa.targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED) {
            "POST_NOTIFICATIONS must be revoked by the host before this phase"
        }
        Qa.resetState(alerts = false, token = Qa.syntheticToken())
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val shown = Qa.device.wait(Until.hasObject(By.text("알림 권한이 꺼져 있습니다")), 30_000)
            assertTrue("Settings did not show the notification warning", shown == true)
        }
    }
}
