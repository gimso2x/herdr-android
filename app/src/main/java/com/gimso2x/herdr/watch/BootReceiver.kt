package com.gimso2x.herdr.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the watcher after boot. Logs exactly one `HerdrBoot` line per BOOT_COMPLETED, no secrets. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // The receiver is exported: ignore anything but the system's BOOT_COMPLETED.
        if (!isBootAction(intent?.action)) return
        Log.i(TAG, message(WatcherController.start(context)))
    }

    companion object {
        const val TAG = "HerdrBoot"

        fun isBootAction(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED

        fun message(decision: StartDecision): String = when (decision) {
            StartDecision.STARTED -> "boot: starting"
            StartDecision.ALERTS_OFF -> "boot: alerts off, not starting"
            StartDecision.NO_TOKEN -> "boot: no token, not starting"
            StartDecision.START_FAILED -> "boot: start failed"
        }
    }
}
