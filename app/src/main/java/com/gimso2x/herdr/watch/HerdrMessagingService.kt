package com.gimso2x.herdr.watch

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gimso2x.herdr.HerdrApplication
import com.gimso2x.herdr.HerdrConfig
import com.gimso2x.herdr.access.BootstrapResult
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException

class HerdrMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushRegistration.tokenChanged(this, token) }

    override fun onMessageReceived(message: RemoteMessage) {
        PushReceiver.receive(this, message.data, message.sentTime)
    }
}

object PushReceiver {
    fun receive(context: Context, data: Map<String, String>, sentAt: Long): Boolean {
        val app = context.applicationContext as HerdrApplication
        synchronized(PushStore.lock) {
            if (!app.pushStore.enabled || !PushRegistration.eligible(app)) return false
            val event = PushEvent.decode(data, sentAt, System.currentTimeMillis(), HerdrConfig.baseUrl) ?: return false
            val ledger = app.pushStore.ledger()
            if (!ledger.accept(event, System.currentTimeMillis())) return false
            app.pushStore.saveLedger(ledger) // persists before notifying: duplicate deliveries cannot ring again
            val posted = WatchNotifications.showPush(app, event)
            val workId = if (posted) PushDetailWorker.enqueue(app, event).toString() else null
            WatchTestHooks.received(event, posted, workId)
            return true
        }
    }
}

class PushDetailWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HerdrApplication
        val event = PushEvent.decode(mapOf("type" to "herdr_event",
            "event_id" to inputData.getString("event_id").orEmpty(),
            "kind" to inputData.getString("kind").orEmpty(),
            "pane_id" to inputData.getString("pane_id").orEmpty(),
            "server_origin" to inputData.getString("server_origin").orEmpty()),
            inputData.getLong("sent_at", 0), System.currentTimeMillis(), HerdrConfig.baseUrl) ?: return Result.success()
        fun current(): Boolean = app.pushStore.enabled && PushRegistration.eligible(app) &&
            HerdrConfig.baseUrl == event.origin && app.pushStore.ledger().isCurrent(event)
        if (!synchronized(PushStore.lock) { current() }) return Result.success()
        try {
            val fresh = app.accessSession.ensureFresh() as? BootstrapResult.Success ?: return Result.success()
            if (!synchronized(PushStore.lock) { current() }) return Result.success()
            val body = PushApi().detail(event, fresh.setCookieHeader.substringBefore(';')) ?: return Result.success()
            val alert = PushDetail.parse(body, event, System.currentTimeMillis()) ?: return Result.success()
            synchronized(PushStore.lock) {
                if (current()) WatchNotifications.showDetail(app, event, alert)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Details are optional. A failed one-shot fetch leaves the already posted generic alert.
        }
        return Result.success()
    }

    companion object {
        const val TAG = "herdr-push-detail"
        fun enqueue(context: Context, event: PushEvent): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<PushDetailWorker>()
                .setInputData(workDataOf("event_id" to event.eventId,
                    "kind" to if (event.kind == AlertKind.NEEDS_INPUT) "needs_input" else "finished",
                    "pane_id" to event.paneId, "server_origin" to event.origin, "sent_at" to event.sentAt))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$TAG:${event.paneId}", ExistingWorkPolicy.REPLACE, request)
            return request.id
        }
    }
}
