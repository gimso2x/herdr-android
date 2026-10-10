package com.gimso2x.herdr.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.SettingsActivity
import com.gimso2x.herdr.R

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
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FINISHED,
                context.getString(R.string.channel_finished),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
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
            val channel = when (alert.kind) {
                AlertKind.NEEDS_INPUT -> CHANNEL_NEEDS_INPUT
                AlertKind.FINISHED -> CHANNEL_FINISHED
            }
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.bigText))
                .setContentIntent(openApp(context, alert.paneId))
                .setAutoCancel(true)
                .setPriority(
                    if (alert.kind == AlertKind.NEEDS_INPUT) {
                        NotificationCompat.PRIORITY_HIGH
                    } else {
                        NotificationCompat.PRIORITY_DEFAULT
                    },
                )
                .build()
            // tag = paneId: a newer alert for the same pane replaces the older one.
            manager.notify(alert.paneId, NOTIFY_ALERT, notification)
        }
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

    private fun openApp(context: Context, paneId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (paneId != null) putExtra(MainActivity.EXTRA_PANE_ID, paneId)
        }
        return PendingIntent.getActivity(
            context,
            paneId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
