package com.gimso2x.herdr.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.R
import com.gimso2x.herdr.SettingsActivity

object WatchNotifications {
    const val CHANNEL_WATCH = "herdr_watch"
    const val CHANNEL_NEEDS_INPUT = "herdr_needs_input"
    const val CHANNEL_FINISHED = "herdr_finished"
    const val NOTIFY_WATCH = 3001
    const val NOTIFY_ALERT = 3002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WATCH,
                context.getString(R.string.channel_watch),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEEDS_INPUT,
                context.getString(R.string.channel_needs_input),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FINISHED,
                context.getString(R.string.channel_finished),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
        )
    }

    fun watchNotification(context: Context, connected: Boolean): Notification {
        ensureChannels(context)
        val text = context.getString(if (connected) R.string.watch_waiting else R.string.watch_disconnected)
        return NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openSettings(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Posts [alerts] unless the app is visible or notifications are not permitted. */
    fun show(context: Context, alerts: List<Alert>) {
        if (alerts.isEmpty() || AppForeground.isForeground() || !hasPostPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)
        for (alert in alerts) {
            // tag = paneId: a newer alert for the same pane replaces the older one.
            manager.notify(alert.paneId, NOTIFY_ALERT, alertNotification(context, alert, silent = false))
        }
    }

    /**
     * Adds the detail line to an alert that is already on screen. The update is silent (no second
     * sound or heads-up) and skipped when the user dismissed the alert, or a newer alert of another
     * kind replaced it, in the meantime.
     */
    fun showDetail(context: Context, alert: Alert) {
        if (AppForeground.isForeground() || !hasPostPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = channelFor(alert.kind)
        val onScreen = manager.activeNotifications.any {
            it.id == NOTIFY_ALERT && it.tag == alert.paneId && it.notification.channelId == channel
        }
        if (onScreen) manager.notify(alert.paneId, NOTIFY_ALERT, alertNotification(context, alert, silent = true))
    }

    private fun channelFor(kind: AlertKind): String = when (kind) {
        AlertKind.NEEDS_INPUT -> CHANNEL_NEEDS_INPUT
        AlertKind.FINISHED -> CHANNEL_FINISHED
    }

    /**
     * The alert carries workspace/tab labels, the pane title and the agent's words, so on a locked
     * screen it is replaced by a [publicVersion] that only says what kind of attention is needed.
     */
    private fun alertNotification(context: Context, alert: Alert, silent: Boolean): Notification {
        val channel = channelFor(alert.kind)
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(AlertText.lockScreenText(alert.kind))
            .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.bigText))
            .setContentIntent(openApp(context, alert.paneId))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(silent)
            .setPriority(
                if (alert.kind == AlertKind.NEEDS_INPUT) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .build()
    }

    fun hasPostPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The ongoing "waiting" notification opens Settings; alert notifications open the pane instead. */
    private fun openSettings(context: Context): PendingIntent {
        val intent = Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            NOTIFY_WATCH,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * One PendingIntent per pane: the pane id is in the intent data ([PaneLink]), which
     * `Intent.filterEquals` compares, so no two panes share a PendingIntent and FLAG_UPDATE_CURRENT
     * can only refresh the extras of the same pane. The extra stays the contract with MainActivity.
     */
    private fun openApp(context: Context, paneId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (paneId != null) {
                data = Uri.parse(PaneLink.uri(paneId))
                putExtra(MainActivity.EXTRA_PANE_ID, paneId)
            }
        }
        return PendingIntent.getActivity(
            context,
            NOTIFY_ALERT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
