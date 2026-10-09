package com.gimso2x.herdr.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the watcher after boot. Logs exactly one `HerdrBoot` line per invocation, no secrets. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // No action check: the manifest filter admits only BOOT_COMPLETED, and starting is gated by
        // the user's alerts switch and token presence anyway.
        Log.i(TAG, message(WatcherController.start(context)))
    }

    companion object {
        const val TAG = "HerdrBoot"

        fun message(decision: StartDecision): String = when (decision) {
            StartDecision.STARTED -> "boot: starting"
            StartDecision.ALERTS_OFF -> "boot: alerts off, not starting"
            StartDecision.NO_TOKEN -> "boot: no token, not starting"
        }
    }
}
