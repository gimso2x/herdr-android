package com.gimso2x.herdr.qa

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gimso2x.herdr.AppPreferences
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.SettingsActivity
import com.gimso2x.herdr.SettingsViewModel
import com.gimso2x.herdr.watch.AlertKind
import com.gimso2x.herdr.watch.AppForeground
import com.gimso2x.herdr.watch.PaneLink
import com.gimso2x.herdr.watch.PushEvent
import com.gimso2x.herdr.watch.PushNotificationObserver
import com.gimso2x.herdr.watch.PushRegistration
import com.gimso2x.herdr.watch.PushStore
import com.gimso2x.herdr.watch.WatchNotifications
import com.gimso2x.herdr.watch.WatchTestHooks
import com.gimso2x.herdr.watch.WatcherController
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual authenticated push-event HTTP path; no /api/session route or polling seam exists. */
class WatchFixture {
    val fx = Fixture()
    val registration = CountDownLatch(1)
    val deletion = CountDownLatch(1)
    val detailEntered = CountDownLatch(1)
    val releaseDetail = CountDownLatch(1)
    @Volatile var detailStatus = 200
    init {
        fx.routes["/"] = { fx.html("push fixture", "<div id=m>fixture</div>") }
        fx.routes["/api/android-push/devices"] = { request ->
            check(request.getHeader("Cookie").orEmpty().startsWith("CF_Authorization="))
            if (request.method == "POST") registration.countDown() else deletion.countDown()
            MockResponse().setResponseCode(204)
        }
    }
    fun start(): WatchFixture { fx.start(); return this }
    fun event(pane: String = PANE_1, kind: AlertKind = AlertKind.NEEDS_INPUT, at: Long = System.currentTimeMillis(), machine: String? = null): PushEvent {
        val e = PushEvent(UUID.randomUUID().toString(), kind, pane, fx.base, at)
        fx.routes["/api/android-push/events/${e.eventId}"] = { request ->
            check(request.method == "GET")
            check(request.getHeader("Cookie").orEmpty().startsWith("CF_Authorization="))
            detailEntered.countDown()
            check(releaseDetail.await(20, TimeUnit.SECONDS)) { "fixture detail never released" }
            MockResponse().setResponseCode(detailStatus).setHeader("Content-Type", "application/json")
                .setBody(JSONObject().put("event_id", e.eventId).put("kind", if (kind == AlertKind.NEEDS_INPUT) "needs_input" else "finished")
                    .put("pane_id", pane).put("server_origin", fx.base).put("created_at", at)
                    .put("workspace", "fixture").put("tab", if (pane == PANE_1) "main" else "logs")
                    .put("agent", "omp").put("pane_title", "Fixture task").put("detail", "Fixture detail.\nmore")
                    .apply { if (machine != null) put("machine_id", machine) }.toString())
        }
        return e
    }
    fun stop() { releaseDetail.countDown(); fx.stop() }
    companion object { const val PANE_1 = "qa:p1"; const val PANE_2 = "qa:p2" }
}

object PushQa {
    val notifications: NotificationManager get() = checkNotNull(Qa.targetContext.getSystemService(NotificationManager::class.java))
    val listener: String get() = "${Qa.PKG}/com.gimso2x.herdr.watch.PushNotificationObserver"
    fun connectListener() = runBlocking {
        Qa.shell("cmd notification allow_listener $listener")
        withTimeout(20_000) { PushNotificationObserver.connected.first { it } }
    }
    fun clean() {
        Qa.shell("cmd notification disallow_listener $listener")
        Qa.hooksReset()
        notifications.cancelAll()
    }
    fun background() = runBlocking {
        val signal = async(start = CoroutineStart.UNDISPATCHED) { AppForeground.state.first { !it } }
        Qa.device.pressHome()
        withTimeout(20_000) { signal.await() }
    }
    fun data(event: PushEvent): Map<String, String> = mapOf("type" to "herdr_event", "event_id" to event.eventId,
        "kind" to if (event.kind == AlertKind.NEEDS_INPUT) "needs_input" else "finished",
        "pane_id" to event.paneId, "server_origin" to event.origin)
    fun inject(event: PushEvent): Pair<WatchTestHooks.Receipt, StatusBarNotification> = runBlocking {
        val received = async(start = CoroutineStart.UNDISPATCHED) { WatchTestHooks.receipts.first { it.eventId == event.eventId } }
        val posted = async(start = CoroutineStart.UNDISPATCHED) { PushNotificationObserver.events.first {
            !it.removed && it.notification.notification.extras.getString(WatchNotifications.EXTRA_EVENT_ID) == event.eventId
        }.notification }
        assertTrue(WatchTestHooks.receive(Qa.targetContext, data(event), event.sentAt))
        withTimeout(30_000) { received.await() to posted.await() }
    }
    fun done(receipt: WatchTestHooks.Receipt) = runBlocking {
        val info = withTimeout(30_000) { WorkManager.getInstance(Qa.targetContext)
            .getWorkInfoByIdFlow(UUID.fromString(receipt.workId!!)).first { it?.state?.isFinished == true } }
        assertEquals(WorkInfo.State.SUCCEEDED, info!!.state)
    }
    fun active(event: PushEvent): StatusBarNotification = notifications.activeNotifications.single {
        it.tag == event.paneId && it.notification.extras.getString(WatchNotifications.EXTRA_EVENT_ID) == event.eventId
    }
    fun text(n: StatusBarNotification, key: String = Notification.EXTRA_TEXT): String =
        n.notification.extras.getCharSequence(key).toString()
    fun await(latch: CountDownLatch) = runBlocking {
        assertTrue(withContext(Dispatchers.IO) { latch.await(30, TimeUnit.SECONDS) })
    }
    fun registrationDone() = runBlocking {
        val rows = withTimeout(30_000) { WorkManager.getInstance(Qa.targetContext)
            .getWorkInfosForUniqueWorkFlow(PushRegistration.WORK).first { it.isNotEmpty() && it.all { work -> work.state.isFinished } } }
        assertTrue(rows.any { it.state == WorkInfo.State.SUCCEEDED })
    }
    fun tap(notification: StatusBarNotification, encodedPane: String) = runBlocking {
        val loaded = async(start = CoroutineStart.UNDISPATCHED) { WatchTestHooks.finishedPages.first {
            it.contains("pane=$encodedPane&view=chat")
        } }
        val current = notifications.activeNotifications.single { it.key == notification.key }
        val title = text(current, Notification.EXTRA_TITLE)
        assertTrue("notification shade did not open", Qa.device.openNotification())
        val row = Qa.device.wait(Until.findObject(By.pkg("com.android.systemui").text(title)), 10_000)
        assertNotNull("posted notification was not visible in SystemUI", row)
        row!!.click()
        withTimeout(30_000) { loaded.await() }
    }
}

@RunWith(AndroidJUnit4::class)
class W1NotifyTest {
    @get:Rule val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @After fun tearDown() = PushQa.clean()

    @Test fun immediateGenericThenAuthenticatedSilentDetailAndFinished() {
        Qa.resetState(true, Qa.syntheticToken())
        val wf = WatchFixture().start()
        PushQa.connectListener()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                PushQa.await(wf.registration)
                PushQa.registrationDone()
                PushQa.background()
                val event = wf.event()
                val (receipt, generic) = PushQa.inject(event)
                assertEquals("herdr", PushQa.text(generic, Notification.EXTRA_TITLE))
                assertEquals("입력이 필요합니다", PushQa.text(generic))
                assertEquals(Notification.VISIBILITY_PRIVATE, generic.notification.visibility)
                assertEquals("입력이 필요합니다", generic.notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertFalse(PushQa.notifications.activeNotifications.any { it.id == 3001 || it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 })
                assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(event), event.sentAt))
                PushQa.await(wf.detailEntered)
                val enriched = runBlocking {
                    val update = async(start = CoroutineStart.UNDISPATCHED) { PushNotificationObserver.events.first {
                        !it.removed && it.notification.notification.extras.getString(WatchNotifications.EXTRA_EVENT_ID) == event.eventId &&
                            it.notification.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
                    }.notification }
                    wf.releaseDetail.countDown()
                    PushQa.done(receipt)
                    withTimeout(20_000) { update.await() }
                }
                assertEquals("fixture · main", PushQa.text(enriched, Notification.EXTRA_TITLE))
                assertEquals("omp · Fixture task — 입력이 필요합니다\nFixture detail.", PushQa.text(enriched, Notification.EXTRA_BIG_TEXT))
                assertTrue(enriched.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                val finished = wf.event(kind = AlertKind.FINISHED, at = event.sentAt + 1)
                val (finishedReceipt, _) = PushQa.inject(finished)
                PushQa.done(finishedReceipt)
                assertEquals(WatchNotifications.CHANNEL_FINISHED, PushQa.active(finished).notification.channelId)
                assertEquals(2, wf.fx.log.count { it.path.orEmpty().startsWith("/api/android-push/events/") })
                assertEquals(0, wf.fx.count("/api/session"))
            }
        } finally { wf.stop() }
    }

    @Test fun remoteDetailReplacingGenericAlertIsPostedSilentlyUnderMachineKey() {
        Qa.resetState(true, Qa.syntheticToken())
        val wf = WatchFixture().start()
        PushQa.connectListener()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                PushQa.registrationDone()
                PushQa.background()
                val machine = "qa-machine-2"
                val key = PaneLink.alertKey(WatchFixture.PANE_1, machine)
                val event = wf.event(machine = machine)
                val (receipt, generic) = PushQa.inject(event)
                // The generic alert is the one that alerts: pane-only key, not silenced.
                assertEquals(event.paneId, generic.tag)
                assertEquals(Notification.GROUP_ALERT_ALL, generic.notification.groupAlertBehavior)
                assertFalse(generic.notification.group == "silent")
                PushQa.await(wf.detailEntered)
                val detail = runBlocking {
                    val update = async(start = CoroutineStart.UNDISPATCHED) { PushNotificationObserver.events.first {
                        !it.removed && it.notification.tag == key &&
                            it.notification.notification.extras.getString(WatchNotifications.EXTRA_EVENT_ID) == event.eventId
                    }.notification }
                    wf.releaseDetail.countDown()
                    PushQa.done(receipt)
                    withTimeout(20_000) { update.await() }
                }
                assertEquals("fixture · main", PushQa.text(detail, Notification.EXTRA_TITLE))
                // The pane-key alert is gone; only the machine-key detail remains for this event.
                val active = PushQa.notifications.activeNotifications.filter {
                    it.id == WatchNotifications.NOTIFY_ALERT &&
                        it.notification.extras.getString(WatchNotifications.EXTRA_EVENT_ID) == event.eventId }
                assertEquals(listOf(key), active.map { it.tag })
                // setSilent(true): not-summary notification gets GROUP_ALERT_SUMMARY in the "silent" group, no sound/vibrate.
                val n = active.single().notification
                assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
                assertEquals("silent", n.group)
                assertEquals(0, n.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
                assertTrue(n.sound == null && n.vibrate == null)
                assertEquals(WatchNotifications.CHANNEL_NEEDS_INPUT, n.channelId)
            }
        } finally { wf.stop() }
    }

    @Test fun dismissedNotificationIsNotResurrectedByLateDetail() {
        Qa.resetState(true, Qa.syntheticToken())
        val wf = WatchFixture().start()
        PushQa.connectListener()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                PushQa.background()
                val event = wf.event()
                val (receipt, _) = PushQa.inject(event)
                PushQa.await(wf.detailEntered)
                runBlocking {
                    val removed = async(start = CoroutineStart.UNDISPATCHED) { PushNotificationObserver.events.first {
                        it.removed && it.notification.tag == event.paneId
                    } }
                    PushQa.notifications.cancel(event.paneId, WatchNotifications.NOTIFY_ALERT)
                    withTimeout(20_000) { removed.await() }
                }
                wf.releaseDetail.countDown()
                PushQa.done(receipt)
                assertFalse(PushQa.notifications.activeNotifications.any { it.tag == event.paneId })
            }
        } finally { wf.stop() }
    }

    @Test fun failedDetailLeavesGenericAndWrongOriginExpiredAndForegroundMessagesStayQuiet() {
        Qa.resetState(true, Qa.syntheticToken())
        val wf = WatchFixture().start()
        wf.detailStatus = 404
        wf.releaseDetail.countDown()
        PushQa.connectListener()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { withTimeout(20_000) { AppForeground.state.first { it } } }
                val foreground = wf.event()
                assertTrue(WatchTestHooks.receive(Qa.targetContext, PushQa.data(foreground)))
                assertFalse(PushQa.notifications.activeNotifications.any { it.tag == foreground.paneId })
                PushQa.background()
                val event = wf.event(at = foreground.sentAt + 1)
                assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(event) + ("server_origin" to "https://other.example")))
                assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(event), System.currentTimeMillis() - PushEvent.TTL_MILLIS))
                val (receipt, _) = PushQa.inject(event)
                PushQa.done(receipt)
                assertEquals("herdr", PushQa.text(PushQa.active(event), Notification.EXTRA_TITLE))
                assertEquals("입력이 필요합니다", PushQa.text(PushQa.active(event)))
            }
        } finally { wf.stop() }
    }

    @Test fun lateDetailCannotOverwriteNewerEventOfTheSameKind() {
        Qa.resetState(true, Qa.syntheticToken())
        val wf = WatchFixture().start()
        wf.detailStatus = 404
        wf.releaseDetail.countDown()
        PushQa.connectListener()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                PushQa.background()
                val old = wf.event()
                val first = PushQa.inject(old)
                PushQa.done(first.first)
                val newer = wf.event(at = old.sentAt + 1)
                val second = PushQa.inject(newer)
                PushQa.done(second.first)
                WatchNotifications.showDetail(Qa.targetContext, old, old.genericAlert().copy(detail = "Stale fixture text"))
                assertEquals("입력이 필요합니다", PushQa.text(PushQa.active(newer), Notification.EXTRA_BIG_TEXT))
                assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(wf.event(at = old.sentAt - 1)), old.sentAt - 1))
            }
        } finally { wf.stop() }
    }
}

@RunWith(AndroidJUnit4::class)
class W2TapNotificationTest {
    @get:Rule val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @After fun tearDown() = PushQa.clean()
    @Test fun tapOpensExactPaneColdAndWarm() {
        Qa.resetState(true, Qa.syntheticToken())
        val wf = WatchFixture().start()
        wf.releaseDetail.countDown()
        PushQa.connectListener()
        try {
            val first = wf.event()
            lateinit var notification: StatusBarNotification
            ActivityScenario.launch(MainActivity::class.java).use {
                PushQa.background()
                val result = PushQa.inject(first)
                PushQa.done(result.first)
                notification = result.second
            }
            PushQa.tap(notification, "qa%3Ap1")
            PushQa.background()
            val second = wf.event(WatchFixture.PANE_2)
            val result = PushQa.inject(second)
            PushQa.done(result.first)
            PushQa.tap(result.second, "qa%3Ap2")
        } finally { wf.stop() }
    }
}

@RunWith(AndroidJUnit4::class)
class W3ToggleTest {
    @After fun tearDown() = PushQa.clean()
    @Test fun settingsSwitchReconcilesRegistrationAndImmediatelyRejectsDisabledDelivery() {
        Qa.resetState(false, Qa.syntheticToken())
        val wf = WatchFixture().start()
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use {
                val off = Qa.device.wait(Until.findObject(By.checkable(true)), 30_000)
                assertNotNull(off)
                assertFalse(off.isChecked)
                off.click()
                PushQa.await(wf.registration)
                PushQa.registrationDone()
                assertTrue(AppPreferences(Qa.targetContext).alertsEnabled)
                val on = Qa.device.wait(Until.findObject(By.checkable(true).checked(true)), 30_000)
                on.click()
                PushQa.await(wf.deletion)
                PushQa.registrationDone()
                assertFalse(AppPreferences(Qa.targetContext).alertsEnabled)
                assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(wf.event())))
                assertTrue(Qa.app.pushStore.registrations().isEmpty())
                Qa.screenshot("W3PushSettings.png")
            }
        } finally { wf.stop() }
    }
}

@RunWith(AndroidJUnit4::class)
class W4PermissionOffTest {
    @After fun tearDown() = Qa.hooksReset()
    @Test fun settingsShowsPermissionOff() {
        check(Qa.targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED)
        Qa.resetState(false, Qa.syntheticToken())
        ActivityScenario.launch(SettingsActivity::class.java).use {
            assertTrue(Qa.device.wait(Until.hasObject(By.text("알림 권한이 꺼져 있습니다")), 30_000) == true)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class PushReconciliationTest {
    @After fun tearDown() = PushQa.clean()
    @Test fun tokenRotationSignoutAndServerChangeReconcileAuthenticatedDeviceState() {
        Qa.resetState(true, Qa.syntheticToken())
        val first = WatchFixture()
        first.fx.start(override = false)
        check(Qa.app.tokenStore.putServer(first.fx.base))
        com.gimso2x.herdr.HerdrConfig.setServerAddress(first.fx.base)
        try {
            WatcherController.start(Qa.targetContext)
            PushQa.await(first.registration)
            PushQa.registrationDone()
            val rotated = CountDownLatch(1)
            first.fx.routes["/api/android-push/devices"] = { request ->
                check(request.getHeader("Cookie").orEmpty().startsWith("CF_Authorization="))
                if (request.method == "POST") {
                    check(JSONObject(request.body.readUtf8()).getString("fcm_token") == "qa-rotated-token")
                    rotated.countDown()
                } else first.deletion.countDown()
                MockResponse().setResponseCode(204)
            }
            PushRegistration.tokenChanged(Qa.targetContext, "qa-rotated-token")
            PushQa.await(rotated)
            PushQa.registrationDone()
            val second = WatchFixture()
            second.fx.start(override = false)
            try {
                val model = Qa.onMain { SettingsViewModel(Qa.app) }
                Qa.onMain {
                    model.onServerInput(second.fx.base)
                    model.changeServer { throw AssertionError("fixture server change rejected") }
                }
                PushQa.await(first.deletion)
                PushQa.await(second.registration)
                PushQa.registrationDone()
                assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(first.event())))
                Qa.onMain { model.clearToken() }
                assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(second.event())))
                PushQa.await(second.deletion)
                PushQa.registrationDone()
                assertTrue(Qa.app.pushStore.registrations().isEmpty())
            } finally { second.stop() }
        } finally { first.stop() }
    }

    @Test fun failedUnregisterStillDisablesLocallyAndReenableRepairsRegistration() {
        Qa.resetState(true, Qa.syntheticToken())
        val wf = WatchFixture().start()
        try {
            WatcherController.start(Qa.targetContext)
            PushQa.await(wf.registration)
            PushQa.registrationDone()
            val failed = CountDownLatch(1)
            val repaired = CountDownLatch(1)
            wf.fx.routes["/api/android-push/devices"] = { request ->
                if (request.method == "DELETE") {
                    failed.countDown()
                    MockResponse().setResponseCode(503)
                } else {
                    repaired.countDown()
                    MockResponse().setResponseCode(204)
                }
            }
            val model = Qa.onMain { SettingsViewModel(Qa.app) }
            Qa.onMain { model.setAlerts(false) }
            assertFalse(WatchTestHooks.receive(Qa.targetContext, PushQa.data(wf.event())))
            PushQa.await(failed)
            runBlocking {
                withTimeout(30_000) { WorkManager.getInstance(Qa.targetContext)
                    .getWorkInfosForUniqueWorkFlow(PushRegistration.WORK).first { rows ->
                        rows.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
                    } }
            }
            assertTrue(Qa.app.pushStore.registrations().isNotEmpty())
            Qa.onMain { model.setAlerts(true) }
            PushQa.await(repaired)
            PushQa.registrationDone()
            assertTrue(Qa.app.pushStore.enabled)
        } finally { wf.stop() }
    }
}
