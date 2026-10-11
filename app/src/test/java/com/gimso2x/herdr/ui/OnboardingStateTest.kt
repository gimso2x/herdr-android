package com.gimso2x.herdr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    @Test
    fun notificationPermissionAloneControlsWarning() {
        val denied = OnboardingState.from(false)
        assertEquals(listOf(OnboardingRow(OnboardingStep.NOTIFICATIONS, StepStatus.TODO)), denied.rows)
        assertTrue(denied.showWarning)
        val allowed = OnboardingState.from(true)
        assertEquals(listOf(OnboardingRow(OnboardingStep.NOTIFICATIONS, StepStatus.DONE)), allowed.rows)
        assertFalse(allowed.showWarning)
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
