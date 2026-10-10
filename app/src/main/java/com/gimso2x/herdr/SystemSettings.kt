package com.gimso2x.herdr

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Status checks and settings deep links shared by the onboarding checklist and the settings screen. */
object SystemSettings {
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** True when the system's "pause app activity if unused" is switched off for this app. */
    fun autoRevokeExempt(context: Context): Boolean = context.packageManager.isAutoRevokeWhitelisted

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    // The owner's own sideloaded app needs the exemption to get alerts on time; not a Play Store build.
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        launch(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }

    fun openAutoRevokeSettings(context: Context) {
        launch(
            context,
            Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri(context)),
            appDetailsIntent(context),
        )
    }

    fun openAppDetails(context: Context) {
        launch(context, appDetailsIntent(context), null)
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    /** Tries [primary], then [fallback]; a device without either screen simply stays where it is. */
    private fun launch(context: Context, primary: Intent, fallback: Intent?) {
        try {
            context.startActivity(primary)
        } catch (_: ActivityNotFoundException) {
            if (fallback != null) runCatching { context.startActivity(fallback) }
        } catch (_: SecurityException) {
            if (fallback != null) runCatching { context.startActivity(fallback) }
        }
    }
}
