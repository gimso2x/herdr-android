package com.gimso2x.herdr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gimso2x.herdr.ui.HerdrTheme
import com.gimso2x.herdr.ui.NotificationButtonAction
import com.gimso2x.herdr.ui.OnboardingActions
import com.gimso2x.herdr.ui.OnboardingRow
import com.gimso2x.herdr.ui.OnboardingState
import com.gimso2x.herdr.ui.OnboardingStep
import com.gimso2x.herdr.ui.StepStatus

/**
 * First-run checklist: notification permission, battery optimization, sleeping-app pause. Statuses are
 * re-read on every resume, so coming back from a system screen updates the rows. "시작하기" is always
 * allowed; unfinished essentials only produce a warning line.
 */
class OnboardingActivity : ComponentActivity() {
    private val prefs by lazy { AppPreferences(this) }
    private var state by mutableStateOf(OnboardingState(emptyList(), showWarning = false))

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            HerdrTheme {
                OnboardingScreen(
                    state = state,
                    onAction = ::onAction,
                    onStart = ::finishOnboarding,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        state = OnboardingState.from(
            notificationsEnabled = SystemSettings.notificationsEnabled(this),
            ignoringBattery = SystemSettings.ignoringBatteryOptimizations(this),
            autoRevokeExempt = SystemSettings.autoRevokeExempt(this),
            isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            sdk = Build.VERSION.SDK_INT,
        )
    }

    private fun onAction(step: OnboardingStep) {
        when (step) {
            OnboardingStep.NOTIFICATIONS -> requestNotifications()
            OnboardingStep.BATTERY -> SystemSettings.requestIgnoreBatteryOptimizations(this)
            OnboardingStep.AUTO_REVOKE -> SystemSettings.openAutoRevokeSettings(this)
            OnboardingStep.SAMSUNG -> SystemSettings.openAppDetails(this)
        }
    }

    private fun requestNotifications() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val action = OnboardingActions.notificationButton(
            sdk = Build.VERSION.SDK_INT,
            permissionGranted = granted,
            asked = prefs.notificationPermissionAsked,
            showRationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        )
        when (action) {
            NotificationButtonAction.REQUEST_PERMISSION -> {
                // MainActivity must not ask a second time.
                prefs.notificationPermissionAsked = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            NotificationButtonAction.OPEN_SETTINGS -> startActivity(SystemSettings.notificationSettingsIntent(this))
        }
    }

    private fun finishOnboarding() {
        prefs.notificationPermissionAsked = true
        prefs.onboardingDone = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)
    }
}

@Composable
private fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingStep) -> Unit,
    onStart: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyMedium)
                for (row in state.rows) StepCard(row, onAction)
                InfoCard(stringResource(R.string.onboarding_info_settings))
                InfoCard(stringResource(R.string.onboarding_info_silent))
                InfoCard(stringResource(R.string.onboarding_info_limits))
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.showWarning) {
                    Text(
                        stringResource(R.string.onboarding_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_start))
                }
            }
        }
    }
}

@Composable
private fun StepCard(row: OnboardingRow, onAction: (OnboardingStep) -> Unit) {
    val title: Int
    val button: Int
    when (row.step) {
        OnboardingStep.NOTIFICATIONS -> { title = R.string.onboarding_notifications; button = R.string.onboarding_notifications_button }
        OnboardingStep.BATTERY -> { title = R.string.onboarding_battery; button = R.string.onboarding_battery_button }
        OnboardingStep.AUTO_REVOKE -> { title = R.string.onboarding_auto_revoke; button = R.string.onboarding_auto_revoke_button }
        OnboardingStep.SAMSUNG -> { title = R.string.onboarding_samsung; button = R.string.onboarding_samsung_button }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            when (row.status) {
                StepStatus.DONE -> Text(
                    stringResource(R.string.onboarding_status_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                StepStatus.TODO -> Text(
                    stringResource(R.string.onboarding_status_todo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                StepStatus.INFO -> Unit
            }
            OutlinedButton(onClick = { onAction(row.step) }) { Text(stringResource(button)) }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}
