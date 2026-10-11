package com.gimso2x.herdr.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class WatcherDecisionTest {
    @Test fun startsOnlyWithAlertsOnAndCredentials() {
        assertEquals(StartDecision.STARTED, WatcherController.decide(true, true))
        assertEquals(StartDecision.NO_TOKEN, WatcherController.decide(true, false))
    }

    @Test fun alertsOffWinsOverMissingCredentials() {
        assertEquals(StartDecision.ALERTS_OFF, WatcherController.decide(false, true))
        assertEquals(StartDecision.ALERTS_OFF, WatcherController.decide(false, false))
    }
}
