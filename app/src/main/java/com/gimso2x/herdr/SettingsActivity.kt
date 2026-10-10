package com.gimso2x.herdr

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gimso2x.herdr.ui.HerdrTheme
import com.gimso2x.herdr.ui.SetupLogic
import com.gimso2x.herdr.watch.WatcherController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Token status, alert switch and notification-permission status. Opened by the launcher shortcut. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HerdrTheme {
                val model: SettingsViewModel = viewModel()
                // Token, alert and permission state can change while we are away (Setup, system settings).
                LifecycleResumeEffect(Unit) {
                    model.refresh()
                    onPauseOrDispose {}
                }
                SettingsScreen(
                    model = model,
                    onBack = { finish() },
                    onReplaceToken = { startActivity(SetupActivity.intent(this)) },
                    onOpenNotificationSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                        )
                    },
                    onOpenBattery = { SystemSettings.requestIgnoreBatteryOptimizations(this) },
                    onOpenAutoRevoke = { SystemSettings.openAutoRevokeSettings(this) },
                    onShowOnboarding = { startActivity(OnboardingActivity.intent(this)) },
                )
            }
        }
    }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app: HerdrApplication get() = getApplication()
    private val prefs = AppPreferences(application)

    /** Masked client id of the stored token, or null when none is stored. */
    var maskedClientId by mutableStateOf<String?>(null)
        private set
    var alertsEnabled by mutableStateOf(prefs.alertsEnabled)
        private set
    var notificationsEnabled by mutableStateOf(true)
        private set

    var ignoringBattery by mutableStateOf(false)
        private set
    var autoRevokeExempt by mutableStateOf(false)
        private set

    fun refresh() {
        alertsEnabled = prefs.alertsEnabled
        notificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled()
        ignoringBattery = SystemSettings.ignoringBatteryOptimizations(app)
        autoRevokeExempt = SystemSettings.autoRevokeExempt(app)
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) { app.tokenStore.get() }
            maskedClientId = token?.let { SetupLogic.maskClientId(it.clientId) }
        }
    }

    fun setAlerts(enabled: Boolean) {
        prefs.alertsEnabled = enabled
        alertsEnabled = enabled
        if (enabled) WatcherController.start(app) else WatcherController.stop(app)
    }

    fun clearToken() {
        WatcherController.stop(app)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.tokenStore.clear() }
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            maskedClientId = null
        }
    }
}

@Composable
private fun SettingsScreen(
    model: SettingsViewModel,
    onBack: () -> Unit,
    onReplaceToken: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenAutoRevoke: () -> Unit,
    onShowOnboarding: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            }

            Text(stringResource(R.string.settings_token), style = MaterialTheme.typography.titleMedium)
            Text(
                model.maskedClientId ?: stringResource(R.string.settings_token_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onReplaceToken) { Text(stringResource(R.string.settings_token_replace)) }
                OutlinedButton(onClick = model::clearToken, enabled = model.maskedClientId != null) {
                    Text(stringResource(R.string.settings_token_clear))
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_alerts), style = MaterialTheme.typography.titleMedium)
                Switch(checked = model.alertsEnabled, onCheckedChange = model::setAlerts)
            }
            if (!model.notificationsEnabled) {
                Text(
                    stringResource(R.string.settings_notifications_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onOpenNotificationSettings) {
                    Text(stringResource(R.string.settings_open_notification_settings))
                }
            }

            HorizontalDivider()

            SettingStatusRow(stringResource(R.string.settings_battery), model.ignoringBattery)
            OutlinedButton(onClick = onOpenBattery) { Text(stringResource(R.string.onboarding_battery_button)) }
            SettingStatusRow(stringResource(R.string.settings_auto_revoke), model.autoRevokeExempt)
            OutlinedButton(onClick = onOpenAutoRevoke) { Text(stringResource(R.string.onboarding_auto_revoke_button)) }

            HorizontalDivider()

            OutlinedButton(onClick = onShowOnboarding) { Text(stringResource(R.string.settings_show_onboarding)) }
        }
    }
}

@Composable
private fun SettingStatusRow(title: String, done: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (done) R.string.onboarding_status_done else R.string.onboarding_status_todo),
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
