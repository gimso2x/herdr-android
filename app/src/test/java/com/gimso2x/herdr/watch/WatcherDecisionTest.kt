package com.gimso2x.herdr.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherDecisionTest {
    @Test
    fun startsOnlyWithAlertsOnAndAToken() {
        assertEquals(StartDecision.STARTED, WatcherController.decide(alertsEnabled = true, hasToken = true))
        assertEquals(StartDecision.NO_TOKEN, WatcherController.decide(alertsEnabled = true, hasToken = false))
    }

    @Test
    fun alertsOffWinsOverMissingToken() {
        assertEquals(StartDecision.ALERTS_OFF, WatcherController.decide(alertsEnabled = false, hasToken = true))
        assertEquals(StartDecision.ALERTS_OFF, WatcherController.decide(alertsEnabled = false, hasToken = false))
    }

    @Test
    fun bootLogLinesAreTheContract() {
        assertEquals("boot: starting", BootReceiver.message(StartDecision.STARTED))
        assertEquals("boot: alerts off, not starting", BootReceiver.message(StartDecision.ALERTS_OFF))
        assertEquals("boot: no token, not starting", BootReceiver.message(StartDecision.NO_TOKEN))
    }

    @Test
    fun startFailureIsNotLoggedAsStarting() {
        assertEquals("boot: start failed", BootReceiver.message(StartDecision.START_FAILED))
    }

    @Test
    fun onlyBootCompletedIsAccepted() {
        assertTrue(BootReceiver.isBootAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(BootReceiver.isBootAction(null))
        assertFalse(BootReceiver.isBootAction("com.example.EVIL"))
        assertFalse(BootReceiver.isBootAction("android.intent.action.MY_PACKAGE_REPLACED"))
    }
}
