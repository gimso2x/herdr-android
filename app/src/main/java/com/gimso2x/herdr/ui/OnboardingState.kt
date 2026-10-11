package com.gimso2x.herdr.ui

/** Checklist rows that carry a button, in display order. */
enum class OnboardingStep { NOTIFICATIONS }

enum class StepStatus { DONE, TODO }

data class OnboardingRow(val step: OnboardingStep, val status: StepStatus)

/** Push delivery needs notification permission, not a battery or unused-app exemption. */
data class OnboardingState(val rows: List<OnboardingRow>, val showWarning: Boolean) {
    companion object {
        fun from(notificationsEnabled: Boolean): OnboardingState = OnboardingState(
            listOf(OnboardingRow(OnboardingStep.NOTIFICATIONS,
                if (notificationsEnabled) StepStatus.DONE else StepStatus.TODO)),
            showWarning = !notificationsEnabled,
        )
    }
}

enum class NotificationButtonAction {
    /** Show the system POST_NOTIFICATIONS dialog. */
    REQUEST_PERMISSION,

    /** Open the app's notification settings. */
    OPEN_SETTINGS,
}

/** Pure choice for the "알림 허용" button. */
object OnboardingActions {
    private const val TIRAMISU = 33

    /**
     * @param permissionGranted POST_NOTIFICATIONS is granted (or not needed): notifications are then off
     * by a settings toggle, which only the settings screen can change
     * @param asked the system dialog was requested before
     * @param showRationale `shouldShowRequestPermissionRationale`: true after one denial
     */
    fun notificationButton(
        sdk: Int,
        permissionGranted: Boolean,
        asked: Boolean,
        showRationale: Boolean,
    ): NotificationButtonAction = when {
        sdk < TIRAMISU || permissionGranted -> NotificationButtonAction.OPEN_SETTINGS
        // Asked before and no rationale: denied permanently, the dialog would not show any more.
        asked && !showRationale -> NotificationButtonAction.OPEN_SETTINGS
        else -> NotificationButtonAction.REQUEST_PERMISSION
    }
}
