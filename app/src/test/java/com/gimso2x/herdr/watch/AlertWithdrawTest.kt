package com.gimso2x.herdr.watch

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertWithdrawTest {
    private val ask = UUID.randomUUID().toString()
    private val alert = WatchNotifications.NOTIFY_ALERT
    private val otherEvents = listOf(
        AlertWithdraw.Active("w1:p2", alert, UUID.randomUUID().toString()),
        // A later finished that replaced the question under the same tag stays.
        AlertWithdraw.Active("w1:p1", alert, UUID.randomUUID().toString()),
        AlertWithdraw.Active("w1:p1", 3001, ask),
        AlertWithdraw.Active("w1:p1", alert, null),
    )

    @Test fun selectsOnlyTheAnsweredAlertUnderTheLocalTag() {
        val local = AlertWithdraw.Active("w1:p1", alert, ask)
        assertEquals(listOf("w1:p1" to alert), AlertWithdraw.targets(otherEvents + local, ask))
    }

    @Test fun selectsTheAnsweredAlertUnderTheRelocatedRemoteTag() {
        val tag = PaneLink.alertKey("w1:p1", "fixture-m1")
        val remote = AlertWithdraw.Active(tag, alert, ask)
        assertEquals(listOf(tag to alert), AlertWithdraw.targets(listOf(remote) + otherEvents, ask))
    }

    @Test fun selectsNothingWhenTheAnsweredAlertIsGone() {
        assertEquals(emptyList<Pair<String?, Int>>(), AlertWithdraw.targets(otherEvents, ask))
        assertEquals(emptyList<Pair<String?, Int>>(), AlertWithdraw.targets(emptyList(), ask))
    }
}
