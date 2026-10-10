package com.gimso2x.herdr.ui

/** Checklist rows that carry a button, in display order. */
enum class OnboardingStep { NOTIFICATIONS, BATTERY, AUTO_REVOKE, SAMSUNG }

enum class StepStatus {
    DONE,
    TODO,

    /** Informational row without a live status (Samsung hint). */
    INFO,
}

data class OnboardingRow(val step: OnboardingStep, val status: StepStatus)

/**
 * What the onboarding checklist shows (no Android types). [showWarning] is the gentle "not done yet"
 * line for the two settings alerts cannot work without; it never blocks "시작하기".
 */
data class OnboardingState(val rows: List<OnboardingRow>, val showWarning: Boolean) {
    companion object {
        private const val R = 30

        fun from(
            notificationsEnabled: Boolean,
            ignoringBattery: Boolean,
            autoRevokeExempt: Boolean,
            isSamsung: Boolean,
            sdk: Int,
        ): OnboardingState {
            fun status(done: Boolean) = if (done) StepStatus.DONE else StepStatus.TODO
            val rows = buildList {
                add(OnboardingRow(OnboardingStep.NOTIFICATIONS, status(notificationsEnabled)))
                add(OnboardingRow(OnboardingStep.BATTERY, status(ignoringBattery)))
                // The "unused apps" switch only exists from Android 11.
                if (sdk >= R) add(OnboardingRow(OnboardingStep.AUTO_REVOKE, status(autoRevokeExempt)))
                if (isSamsung) add(OnboardingRow(OnboardingStep.SAMSUNG, StepStatus.INFO))
            }
            return OnboardingState(rows, showWarning = !notificationsEnabled || !ignoringBattery)
        }
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
