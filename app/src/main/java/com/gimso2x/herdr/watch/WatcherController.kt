package com.gimso2x.herdr.watch

import android.content.Context
import com.gimso2x.herdr.AppPreferences
import com.gimso2x.herdr.HerdrApplication
import com.gimso2x.herdr.HerdrConfig

enum class StartDecision { STARTED, ALERTS_OFF, NO_TOKEN, START_FAILED }

/** Existing activity call boundary; start reconciles push registration, not a resident service. */
object WatcherController {
    fun decide(alertsEnabled: Boolean, hasToken: Boolean): StartDecision = when {
        !alertsEnabled -> StartDecision.ALERTS_OFF
        !hasToken -> StartDecision.NO_TOKEN
        else -> StartDecision.STARTED
    }

    internal fun decide(context: Context): StartDecision {
        val app = context.applicationContext as HerdrApplication
        if (!AppPreferences(app).alertsEnabled) return StartDecision.ALERTS_OFF
        return decide(true, app.tokenStore.get() != null && HerdrConfig.isConfigured)
    }

    fun start(context: Context): StartDecision = try {
        PushRegistration.start(context)
    } catch (_: Exception) {
        StartDecision.START_FAILED
    }

    fun stop(context: Context) { PushRegistration.stop(context) }
}
