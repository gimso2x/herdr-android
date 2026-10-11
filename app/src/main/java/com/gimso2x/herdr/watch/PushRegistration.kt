package com.gimso2x.herdr.watch

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gimso2x.herdr.AppPreferences
import com.gimso2x.herdr.HerdrApplication
import com.gimso2x.herdr.HerdrConfig
import com.gimso2x.herdr.access.BootstrapResult
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resumeWithException

object PushRegistration {
    const val WORK = "herdr-push-registration"
    internal val mutex = Mutex()

    fun eligible(app: HerdrApplication): Boolean = AppPreferences(app).alertsEnabled &&
        app.tokenStore.get() != null && HerdrConfig.isConfigured

    fun start(context: Context): StartDecision {
        val app = context.applicationContext as HerdrApplication
        val decision = WatcherController.decide(context)
        synchronized(PushStore.lock) {
            app.pushStore.enabled = decision == StartDecision.STARTED
            if (decision != StartDecision.STARTED) clearAlerts(app)
            enqueue(app)
        }
        if (decision == StartDecision.STARTED) {
            // Fixture tokens exist only in debug; real delivery obtains the Firebase Task result.
            val fixture = WatchTestHooks.firebaseToken
            if (fixture != null) tokenChanged(app, fixture)
            else FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> tokenChanged(app, token) }
        }
        return decision
    }

    fun stop(context: Context) {
        val app = context.applicationContext as HerdrApplication
        synchronized(PushStore.lock) {
            app.pushStore.enabled = false // immediate local rejection, even when DELETE cannot reach the server
            clearAlerts(app)
            enqueue(app)
        }
    }

    private fun clearAlerts(app: HerdrApplication) {
        WatchNotifications.clear(app)
        WorkManager.getInstance(app).cancelAllWorkByTag(PushDetailWorker.TAG)
    }

    fun tokenChanged(context: Context, token: String) {
        val app = context.applicationContext as HerdrApplication
        synchronized(PushStore.lock) {
            if (app.pushStore.token == token) return
            app.pushStore.token = token
            enqueue(app)
        }
    }

    private fun enqueue(context: Context) {
        if (WatchTestHooks.externalRegistration) return
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        // REPLACE cancels an obsolete in-flight request; its durable pre-POST row is still cleaned up.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = PushRegistration.mutex.withLock {
        val app = applicationContext as HerdrApplication
        val store = app.pushStore
        val api = PushApi()
        try {
            val desiredOrigin = synchronized(PushStore.lock) {
                if (store.enabled && PushRegistration.eligible(app)) HerdrConfig.baseUrl else null
            }
            val old = synchronized(PushStore.lock) { store.registrations().filter { it.origin != desiredOrigin } }
            var retry = false
            for (row in old) {
                // Signout/server change never reuse the new origin's cookie: the old origin's
                // encrypted short-lived cookie was captured before its cookie jar was wiped.
                val cookie = if (HerdrConfig.baseUrlOrNull == row.origin && app.tokenStore.get() != null) {
                    val session = app.accessSession
                    val refreshed = session.ensureFresh() as? BootstrapResult.Success
                    if (HerdrConfig.baseUrlOrNull == row.origin) refreshed?.setCookieHeader?.substringBefore(';') ?: row.cookie
                    else row.cookie
                } else row.cookie
                val status = try {
                    api.unregister(row.origin, cookie, synchronized(PushStore.lock) { store.installationId })
                } catch (_: IOException) {
                    retry = true
                    continue // an unavailable old origin must not prevent registration with the new one
                }
                if (status == 204) synchronized(PushStore.lock) {
                    store.saveRegistrations(store.registrations().filter { it.origin != row.origin })
                } else retry = true
            }
            if (desiredOrigin == null) return@withLock if (retry) Result.retry() else Result.success()
            val token = synchronized(PushStore.lock) { store.token } ?: (WatchTestHooks.firebaseToken
                ?: FirebaseMessaging.getInstance().token.awaitToken()).also {
                synchronized(PushStore.lock) { if (store.token == null) store.token = it }
            }
            val fresh = app.accessSession.ensureFresh() as? BootstrapResult.Success ?: return@withLock Result.retry()
            val cookie = fresh.setCookieHeader.substringBefore(';')
            val expires = System.currentTimeMillis() + fresh.expEpochSeconds * 1000 -
                (fresh.serverTimeMillis ?: System.currentTimeMillis())
            synchronized(PushStore.lock) {
                if (!store.enabled || !PushRegistration.eligible(app) || HerdrConfig.baseUrl != desiredOrigin || store.token != token) {
                    return@withLock Result.success()
                }
                val rows = store.registrations()
                store.saveRegistrations(rows.filter { it.origin != desiredOrigin } +
                    PushStore.Registration(desiredOrigin, cookie, expires))
            }
            // Always upsert: a cancelled DELETE may already have reached the bridge. Cached POST
            // success cannot prove the device is still registered after that uncertain result.
            val status = api.register(desiredOrigin, cookie, synchronized(PushStore.lock) { store.installationId }, token)
            if (status != 204) return@withLock Result.retry()
            if (retry) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            // Firebase/keystore unavailable: WorkManager owns bounded backoff, not an app polling loop.
            Result.retry()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Task<String>.awaitToken(): String = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) { _, _, _ -> } }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
