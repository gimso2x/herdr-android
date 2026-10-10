package com.gimso2x.herdr.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.ServiceCompat
import com.gimso2x.herdr.HerdrApplication
import com.gimso2x.herdr.HerdrConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Foreground service (specialUse) that polls `/api/session` every 15 s and raises alerts. */
class StatusWatcherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var connected: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WatchNotifications.ensureChannels(this)
        AppForeground.init()
        val app = application as HerdrApplication
        val baseUrl = HerdrConfig.baseUrl
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        val context = applicationContext
        WatchRuntime.poller = WatchPoller(
            session = app.accessSession,
            client = client,
            baseUrl = baseUrl,
            cookie = { CookieManager.getInstance().getCookie(baseUrl) },
            tracker = StatusTracker { WatchTestHooks.clock.nowMillis() },
            onAlerts = { if (scope.isActive) WatchNotifications.show(context, it) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground within 5 s of startForegroundService.
        try {
            ServiceCompat.startForeground(
                this,
                WatchNotifications.NOTIFY_WATCH,
                WatchNotifications.watchNotification(this, connected = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (_: Exception) {
            // Not allowed to run in the foreground right now (background start restrictions).
            stopSelf()
            return START_NOT_STICKY
        }
        // A sticky restart after process death (null intent) must still honour the user's switches.
        if (intent == null && WatcherController.decide(this) != StartDecision.STARTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunningFlow.value = true
        if (loop == null) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val poller = WatchRuntime.poller ?: return
        while (scope.isActive) {
            val ok = poller.pollOnce().outcome == PollOutcome.OK
            if (connected != ok) {
                connected = ok
                getSystemService(android.app.NotificationManager::class.java)
                    ?.notify(WatchNotifications.NOTIFY_WATCH, WatchNotifications.watchNotification(this, ok))
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        WatchRuntime.poller = null
        isRunningFlow.value = false
        super.onDestroy()
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 15_000L

        private val isRunningFlow = MutableStateFlow(false)

        /** True from the first successful `startForeground` until the service is destroyed. */
        val isRunning: StateFlow<Boolean> = isRunningFlow.asStateFlow()

        internal fun intent(context: Context) = Intent(context, StatusWatcherService::class.java)
    }
}
