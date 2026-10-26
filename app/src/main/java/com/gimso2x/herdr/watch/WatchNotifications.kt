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
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.gimso2x.herdr.MainActivity
import com.gimso2x.herdr.R

object WatchNotifications {
    const val CHANNEL_NEEDS_INPUT = "herdr_needs_input"
    const val CHANNEL_FINISHED = "herdr_finished"
    const val NOTIFY_ALERT = 3002
    const val EXTRA_EVENT_ID = "herdr_event_id"
    private const val LEGACY_CHANNEL = "herdr_watch"
    private const val LEGACY_NOTIFICATION = 3001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Upgrades remove the old waiting notification/channel, without changing alert channel choices.
        manager.cancel(LEGACY_NOTIFICATION)
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_NEEDS_INPUT,
            context.getString(R.string.channel_needs_input), NotificationManager.IMPORTANCE_HIGH)
            .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_FINISHED,
            context.getString(R.string.channel_finished), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
    }

    /** Immediate generic private alert, without any server text or even the pane identifier. */
    fun showPush(context: Context, event: PushEvent): Boolean {
        if (AppForeground.isForeground() || !hasPostPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannels(context)
        manager.notify(event.paneId, NOTIFY_ALERT, notification(context, event, event.genericAlert(), false, true))
        return true
    }

    /** Never resurrect a dismissed alert or overwrite a later event (even one of the same kind). */
    fun showDetail(context: Context, event: PushEvent, alert: Alert) {
        if (AppForeground.isForeground() || !hasPostPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.activeNotifications.any { it.id == NOTIFY_ALERT && it.tag == event.paneId &&
                it.notification.extras.getString(EXTRA_EVENT_ID) == event.eventId }) {
            // The generic alert used the pane-only key (machine unknown). A remote pane moves to its
            // machine key so the same pane id on another machine never overwrites it.
            val tag = PaneLink.alertKey(event.paneId, alert.machineId)
            if (tag != event.paneId) manager.cancel(event.paneId, NOTIFY_ALERT)
            manager.notify(tag, NOTIFY_ALERT, notification(context, event, alert, true, false))
        }
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(LEGACY_NOTIFICATION)
        for (notification in manager.activeNotifications) {
            if (notification.id == NOTIFY_ALERT) manager.cancel(notification.tag, notification.id)
        }
    }

    private fun notification(context: Context, event: PushEvent, alert: Alert, silent: Boolean, generic: Boolean): Notification {
        val channel = if (event.kind == AlertKind.NEEDS_INPUT) CHANNEL_NEEDS_INPUT else CHANNEL_FINISHED
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(AlertText.lockScreenText(event.kind)).build()
        val body = if (generic) AlertText.lockScreenText(event.kind) else alert.body
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (generic) context.getString(R.string.app_name) else alert.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (generic) body else alert.bigText))
            .setContentIntent(openApp(context, event.paneId, alert.machineId))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(silent)
            .addExtras(Bundle().apply { putString(EXTRA_EVENT_ID, event.eventId) })
            .setPriority(if (event.kind == AlertKind.NEEDS_INPUT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    fun hasPostPermission(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Exact pane and machine identity are part of Intent.filterEquals, so unrelated panes never share extras. */
    private fun openApp(context: Context, paneId: String, machineId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse(PaneLink.uri(paneId, machineId))
            putExtra(MainActivity.EXTRA_PANE_ID, paneId)
        }
        return PendingIntent.getActivity(context, NOTIFY_ALERT, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
