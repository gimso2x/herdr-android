package com.gimso2x.herdr.watch

import android.content.Context
import com.gimso2x.herdr.HerdrApplication
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Debug-only injection at the actual message handler; release has no injection API. */
object WatchTestHooks {
    @Volatile var firebaseToken: String? = null
    @Volatile var externalRegistration: Boolean = false
    data class Receipt(val eventId: String, val posted: Boolean, val workId: String?)
    private val incoming = MutableSharedFlow<Receipt>(extraBufferCapacity = 16)
    val receipts: SharedFlow<Receipt> = incoming.asSharedFlow()
    private val pages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val finishedPages: SharedFlow<String> = pages.asSharedFlow()
    fun pageFinished(url: String?) { if (url != null) pages.tryEmit(url) }

    fun received(event: PushEvent, posted: Boolean, workId: String?) {
        incoming.tryEmit(Receipt(event.eventId, posted, workId))
    }

    fun receive(context: Context, data: Map<String, String>, sentAt: Long = System.currentTimeMillis()): Boolean =
        PushReceiver.receive(context, data, sentAt)

    fun reset(context: Context) {
        val app = context.applicationContext as HerdrApplication
        PushRegistration.stop(app)
        synchronized(PushStore.lock) {
            app.pushStore.token = null
            app.pushStore.clearEvents()
            app.pushStore.saveRegistrations(emptyList())
        }
        firebaseToken = null
        externalRegistration = false
    }
}
