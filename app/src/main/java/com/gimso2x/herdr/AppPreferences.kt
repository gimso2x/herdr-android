package com.gimso2x.herdr

import android.content.Context

/** Plain (non-secret) settings in SharedPreferences `herdr_prefs`. */
class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the status watcher should run. On by default. */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ALERTS_ENABLED, value).apply()
        }

    /** Whether the one-time notification permission prompt has been shown. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()
        }

    /** Whether the first-run onboarding checklist was finished ("시작하기"). */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) {
            // commit(): the next launch (or a force-stop right after) must see it.
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).commit()
        }

    private companion object {
        const val FILE = "herdr_prefs"
        const val KEY_ALERTS_ENABLED = "alerts_enabled"
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
