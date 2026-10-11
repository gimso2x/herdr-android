package com.gimso2x.herdr.watch

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug-only listener enables instrumentation to await actual Android notification events. */
class PushNotificationObserver : NotificationListenerService() {
    override fun onListenerConnected() { connection.value = true }
    override fun onListenerDisconnected() { connection.value = false }
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.packageName == packageName) changes.tryEmit(Change(notification, false))
    }
    override fun onNotificationRemoved(notification: StatusBarNotification) {
        if (notification.packageName == packageName) changes.tryEmit(Change(notification, true))
    }

    data class Change(val notification: StatusBarNotification, val removed: Boolean)
    companion object {
        private val connection = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connection.asStateFlow()
        private val changes = MutableSharedFlow<Change>(extraBufferCapacity = 64)
        val events: SharedFlow<Change> = changes.asSharedFlow()
    }
}
