package com.gimso2x.herdr.qa

import android.Manifest
import android.app.Notification
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.gimso2x.herdr.HerdrConfig
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.SettingsActivity
import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.watch.PushApi
import com.gimso2x.herdr.watch.PushNotificationObserver
import com.gimso2x.herdr.watch.PushStore
import com.gimso2x.herdr.watch.WatchNotifications
import com.gimso2x.herdr.watch.WatchTestHooks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Dedicated google_apis device phase, never included in the ordinary AOSP fixture suite. */
@RunWith(AndroidJUnit4::class)
class FcmLiveDeliveryTest {
    @get:Rule val grant: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun realFirebaseDeliveryPostsPrivateAlertAndRoutesExactSyntheticPane() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        fun uuid(key: String): String = (args.getString(key) ?: UUID.randomUUID().toString()).also {
            check(UUID.fromString(it).toString() == it) { "$key must be canonical UUID" }
        }
        val installation = uuid("fcmInstallationId")
        val eventId = uuid("fcmEventId")
        val token = LiveSupport.token()
        val origin = LiveSupport.baseUrl()
        Qa.resetState(alerts = true, token = token, server = origin)
        WatchTestHooks.firebaseToken = null // MUST obtain the actual Firebase Task, not a fixture token
        WatchTestHooks.externalRegistration = true // only the supplied disposable installation is registered
        val api = PushApi()
        var cookie: String? = null
        var fixture: Fixture? = null
        fun stage(value: String, error: Throwable? = null) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("herdr_fcm_stage", value)
                if (error != null) {
                    putString("herdr_fcm_error_class", error.javaClass.name)
                    if (error.message in setOf("SERVICE_NOT_AVAILABLE", "FIS_AUTH_ERROR", "AUTHENTICATION_FAILED")) {
                        putString("herdr_fcm_error_code", error.message)
                    }
                }
            })
        }
        try {
            stage("listener_connecting")
            PushQa.connectListener()
            stage("listener_connected")
            synchronized(PushStore.lock) { Qa.app.pushStore.enabled = true }
            ActivityScenario.launch(SettingsActivity::class.java).use {
                PushQa.background()
                stage("background")
                // Subscribe to the actual service callback AND Android's post event before the server
                // learns this installation. Registration is the parent's fs.watch send handshake.
                val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                    WatchTestHooks.receipts.first { it.eventId == eventId }
                }
                val posted = async(start = CoroutineStart.UNDISPATCHED) {
                    PushNotificationObserver.events.first { !it.removed &&
                        it.notification.notification.extras.getString(WatchNotifications.EXTRA_EVENT_ID) == eventId }.notification
                }
                val firebaseToken = withTimeout(60_000) {
                    suspendCancellableCoroutine<String> { continuation ->
                        FirebaseMessaging.getInstance().token
                            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                            .addOnCanceledListener { continuation.cancel() }
                    }
                }
                stage("firebase_token_received")
                val fresh = withTimeout(30_000) { Qa.app.accessSession.ensureFresh() }
                assertTrue("live Access bootstrap failed", fresh is BootstrapResult.Success)
                cookie = (fresh as BootstrapResult.Success).setCookieHeader.substringBefore(';')
                stage("access_authenticated")
                Log.i("HerdrFcmQa", "HERDR_FCM_READY installation_id=$installation event_id=$eventId")
                assertEquals("live bridge registration", 204, api.register(origin, cookie!!, installation, firebaseToken))
                stage("device_registered")
                val delivered = withTimeout(180_000) { receipt.await() to posted.await() }
                stage("notification_received")
                assertTrue("Firebase callback did not post an alert", delivered.first.posted)
                assertEquals("qa:fcm-live", delivered.second.tag)
                assertEquals(WatchNotifications.CHANNEL_NEEDS_INPUT, delivered.second.notification.channelId)
                assertEquals(Notification.VISIBILITY_PRIVATE, delivered.second.notification.visibility)
                assertEquals("herdr", PushQa.text(delivered.second, Notification.EXTRA_TITLE))
                assertEquals("입력이 필요합니다", PushQa.text(delivered.second))
                assertFalse(PushQa.notifications.activeNotifications.any { it.id == 3001 })
                PushQa.done(delivered.first)

                // Tap exercises the actual PendingIntent and MainActivity routing, but render a local
                // synthetic fixture rather than capture or expose the owner's private server page.
                fixture = Fixture().also { fx ->
                    fx.expectedToken = token
                    fx.routes["/"] = { fx.html("FCM delivery fixture", "<div id=m>FCM fixture</div>") }
                    fx.start()
                }
                WatchTestHooks.firebaseToken = "qa-fixture-fcm-token"
                PushQa.tap(delivered.second, "qa%3Afcm-live")
                assertTrue(Qa.mainActivityExists())
                stage("exact_pane_opened")
            }
        } catch (error: Throwable) {
            stage("failed", error)
            throw error
        } finally {
            try {
                // Always issue DELETE even if POST's response was lost after it reached the bridge.
                if (cookie != null) {
                    assertEquals("live bridge cleanup", 204, withTimeout(30_000) { api.unregister(origin, cookie!!, installation) })
                }
            } finally {
                fixture?.stop()
                HerdrConfig.setDebugBaseUrl(null)
                PushQa.clean()
                Qa.app.tokenStore.clear()
                Qa.app.tokenStore.clearServer()
                HerdrConfig.setServerAddress(null)
                Qa.onMain {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                }
            }
        }
    }
}
