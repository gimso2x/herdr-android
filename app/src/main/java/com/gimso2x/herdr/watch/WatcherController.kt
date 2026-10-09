package com.gimso2x.herdr.watch

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.gimso2x.herdr.AppPreferences
import com.gimso2x.herdr.HerdrApplication

enum class StartDecision { STARTED, ALERTS_OFF, NO_TOKEN, START_FAILED }

/** Starts/stops [StatusWatcherService]. Call only from a visible activity or BOOT_COMPLETED. */
object WatcherController {
    /** Alerts switched off wins over a missing token. */
    fun decide(alertsEnabled: Boolean, hasToken: Boolean): StartDecision = when {
        !alertsEnabled -> StartDecision.ALERTS_OFF
        !hasToken -> StartDecision.NO_TOKEN
        else -> StartDecision.STARTED
    }

    internal fun decide(context: Context): StartDecision {
        val app = context.applicationContext as HerdrApplication
        // Read the cheap preference first; the token store (keystore) is only touched when needed.
        if (!AppPreferences(app).alertsEnabled) return StartDecision.ALERTS_OFF
        return decide(alertsEnabled = true, hasToken = app.tokenStore.get() != null)
    }

    fun start(context: Context): StartDecision {
        val decision = decide(context)
        if (decision != StartDecision.STARTED) return decision
        return try {
            ContextCompat.startForegroundService(context, StatusWatcherService.intent(context))
            decision
        } catch (e: Exception) {
            Log.w("HerdrWatch", "start failed: ${e.javaClass.simpleName}")
            StartDecision.START_FAILED
        }
    }

    fun stop(context: Context) {
        context.stopService(StatusWatcherService.intent(context))
    }
}
