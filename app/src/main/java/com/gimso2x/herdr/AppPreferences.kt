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

    private companion object {
        const val FILE = "herdr_prefs"
        const val KEY_ALERTS_ENABLED = "alerts_enabled"
    }
}
