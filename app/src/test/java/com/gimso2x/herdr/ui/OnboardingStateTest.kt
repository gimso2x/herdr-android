package com.gimso2x.herdr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    private fun steps(s: OnboardingState) = s.rows.map { it.step }

    private fun status(s: OnboardingState, step: OnboardingStep) = s.rows.single { it.step == step }.status

    @Test
    fun nothingDoneListsTheThreeSettingsAndWarns() {
        val s = OnboardingState.from(false, false, false, isSamsung = false, sdk = 34)
        assertEquals(listOf(OnboardingStep.NOTIFICATIONS, OnboardingStep.BATTERY, OnboardingStep.AUTO_REVOKE), steps(s))
        assertTrue(s.rows.all { it.status == StepStatus.TODO })
        assertTrue(s.showWarning)
    }

    @Test
    fun allDoneHasNoWarning() {
        val s = OnboardingState.from(true, true, true, isSamsung = false, sdk = 34)
        assertTrue(s.rows.all { it.status == StepStatus.DONE })
        assertFalse(s.showWarning)
    }

    @Test
    fun warningFollowsNotificationsAndBatteryOnly() {
        assertTrue(OnboardingState.from(false, true, true, false, 34).showWarning)
        assertTrue(OnboardingState.from(true, false, true, false, 34).showWarning)
        assertFalse(OnboardingState.from(true, true, false, false, 34).showWarning)
    }

    @Test
    fun samsungAddsAnInfoRowWithoutStatus() {
        val s = OnboardingState.from(true, true, true, isSamsung = true, sdk = 34)
        assertEquals(OnboardingStep.SAMSUNG, steps(s).last())
        assertEquals(StepStatus.INFO, status(s, OnboardingStep.SAMSUNG))
        assertFalse(OnboardingState.from(true, true, true, isSamsung = false, sdk = 34).rows.any { it.step == OnboardingStep.SAMSUNG })
    }

    @Test
    fun autoRevokeRowNeedsAndroid11() {
        assertFalse(OnboardingState.from(true, true, true, false, sdk = 29).rows.any { it.step == OnboardingStep.AUTO_REVOKE })
        assertEquals(StepStatus.TODO, status(OnboardingState.from(true, true, false, false, sdk = 30), OnboardingStep.AUTO_REVOKE))
    }

    @Test
    fun statusesAreIndependent() {
        val s = OnboardingState.from(true, false, true, false, 34)
        assertEquals(StepStatus.DONE, status(s, OnboardingStep.NOTIFICATIONS))
        assertEquals(StepStatus.TODO, status(s, OnboardingStep.BATTERY))
        assertEquals(StepStatus.DONE, status(s, OnboardingStep.AUTO_REVOKE))
    }

    @Test
    fun notificationButtonRequestsFirstThenFallsBackToSettings() {
        val request = NotificationButtonAction.REQUEST_PERMISSION
        val settings = NotificationButtonAction.OPEN_SETTINGS
        // First time: the system dialog.
        assertEquals(request, OnboardingActions.notificationButton(34, permissionGranted = false, asked = false, showRationale = false))
        // Denied once: the dialog can still be shown.
        assertEquals(request, OnboardingActions.notificationButton(34, permissionGranted = false, asked = true, showRationale = true))
        // Denied permanently: no dialog any more.
        assertEquals(settings, OnboardingActions.notificationButton(34, permissionGranted = false, asked = true, showRationale = false))
        // Granted but notifications switched off in settings, or no runtime permission at all.
        assertEquals(settings, OnboardingActions.notificationButton(34, permissionGranted = true, asked = true, showRationale = false))
        assertEquals(settings, OnboardingActions.notificationButton(32, permissionGranted = false, asked = false, showRationale = false))
    }
}
