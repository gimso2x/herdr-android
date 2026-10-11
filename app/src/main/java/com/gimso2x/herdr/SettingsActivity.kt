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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import com.gimso2x.herdr.access.ServerAddress
import com.gimso2x.herdr.access.clearAllCookies
import com.gimso2x.herdr.shell.RefreshOutcome
import com.gimso2x.herdr.ui.SetupError
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
                    onServerChangeFailed = { error ->
                        // Without a token, or when the new Access application rejects it, the address alone cannot fix it.
                        val addressOnly = SetupLogic.addressOnlyRecovery(model.maskedClientId != null, error)
                        startActivity(SetupActivity.intent(this, error, addressOnly = addressOnly))
                    },
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
            server = withContext(Dispatchers.IO) { HerdrConfig.baseUrlOrNull }
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
            // Cached bootstrap result, web storage and the running page of MainActivity go with the token.
            app.endSession(SessionEnd.SIGN_OUT)
            maskedClientId = null
        }
    }

    /** Stored server address shown in the settings, or null when none. */
    var server by mutableStateOf<String?>(null)
        private set
    var serverInput by mutableStateOf("")
        private set
    var serverBusy by mutableStateOf(false)
        private set

    private fun normalizedInput(): String? = SetupLogic.address(serverInput, allowLoopback = BuildConfig.DEBUG)

    val serverInputInvalid: Boolean
        get() = serverInput.isNotBlank() && normalizedInput() == null

    val canChangeServer: Boolean
        get() = !serverBusy && normalizedInput().let { it != null && it != server }

    fun onServerInput(value: String) {
        if (value.length <= ServerAddress.MAX_LENGTH + 16) serverInput = value
    }

    /**
     * Stores the new address, wipes the cookies of the old one and bootstraps again against the new
     * one; anything but a fresh cookie sends the user to the setup screen (address only) with the reason.
     */
    fun changeServer(onFailed: (SetupError) -> Unit) {
        val address = normalizedInput() ?: return
        if (serverBusy) return
        serverBusy = true
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { app.tokenStore.putServer(address) }
            if (!stored) {
                serverBusy = false
                onFailed(SetupError.STORE_FAILED)
                return@launch
            }
            WatcherController.stop(app)
            clearAllCookies()
            HerdrConfig.setServerAddress(address)
            app.endSession(SessionEnd.SERVER_CHANGE)
            val outcome = RefreshOutcome.of(app.accessSession.ensureFresh(force = true))
            server = address
            serverInput = ""
            serverBusy = false
            if (outcome is RefreshOutcome.Fresh) {
                WatcherController.start(app)
            } else {
                onFailed(if (outcome is RefreshOutcome.NeedsSetup) outcome.error else SetupError.NETWORK)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    model: SettingsViewModel,
    onBack: () -> Unit,
    onReplaceToken: () -> Unit,
    onServerChangeFailed: (SetupError) -> Unit,
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

            Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleMedium)
            Text(
                model.server ?: stringResource(R.string.settings_server_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = model.serverInput,
                onValueChange = model::onServerInput,
                label = { Text(stringResource(R.string.setup_server_address)) },
                placeholder = { Text(stringResource(R.string.setup_server_placeholder)) },
                singleLine = true,
                enabled = !model.serverBusy,
                isError = model.serverInputInvalid,
                supportingText = if (model.serverInputInvalid) {
                    { Text(stringResource(R.string.setup_server_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { model.changeServer(onServerChangeFailed) },
                enabled = model.canChangeServer,
            ) {
                Text(stringResource(if (model.serverBusy) R.string.settings_server_changing else R.string.settings_server_change))
            }

            HorizontalDivider()

            // One merged, toggleable unit: accessibility services read the label as the switch's name.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = model.alertsEnabled, role = Role.Switch, onValueChange = model::setAlerts),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_alerts), style = MaterialTheme.typography.titleMedium)
                Switch(checked = model.alertsEnabled, onCheckedChange = null)
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
