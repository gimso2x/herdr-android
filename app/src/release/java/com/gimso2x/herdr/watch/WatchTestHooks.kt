package com.gimso2x.herdr.watch

/** Release offers neither token injection nor a message-delivery test entry point. */
object WatchTestHooks {
    val firebaseToken: String? get() = null
    const val externalRegistration: Boolean = false
    fun received(event: PushEvent, posted: Boolean, workId: String?) = Unit
    fun pageFinished(url: String?) = Unit
}
