package com.gimso2x.herdr.watch

/** Which active notifications a `resolved` push withdraws; free of Android types so it is unit tested. */
object AlertWithdraw {
    /** One active notification: its tag, id and the [WatchNotifications.EXTRA_EVENT_ID] extra it carries. */
    data class Active(val tag: String?, val id: Int, val eventId: String?)

    /**
     * The (tag, id) of every alert of the answered event, whatever its tag: the bare pane key of a generic or
     * local alert, or the machine key a remote alert moved to with its detail. An alert of any other event,
     * such as a later finished that replaced it under the same tag, is kept.
     */
    fun targets(active: List<Active>, resolves: String): List<Pair<String?, Int>> =
        active.filter { it.id == WatchNotifications.NOTIFY_ALERT && it.eventId == resolves }.map { it.tag to it.id }
}
