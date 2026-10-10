package com.gimso2x.herdr

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gimso2x.herdr.access.AccessCookieBootstrapper
import com.gimso2x.herdr.access.BootstrapResult
import com.gimso2x.herdr.access.CookieInstaller
import com.gimso2x.herdr.ui.HerdrTheme
import com.gimso2x.herdr.ui.SetupError
import com.gimso2x.herdr.ui.SetupLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Owner entry for the Cloudflare Access service token; validates it before saving anything. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HerdrTheme {
                val model: SetupViewModel = viewModel()
                LaunchedEffect(Unit) {
                    // Only the first open shows the extra; later recreations keep the model's state.
                    model.showInitialError(SetupError.fromExtra(intent.getStringExtra(EXTRA_ERROR)))
                }
                LaunchedEffect(model.done) {
                    if (model.done) {
                        // The very first successful setup continues into the onboarding checklist.
                        val next = if (AppPreferences(this@SetupActivity).onboardingDone) {
                            MainActivity::class.java
                        } else {
                            OnboardingActivity::class.java
                        }
                        startActivity(Intent(this@SetupActivity, next))
                        finish()
                    }
                }
                SetupScreen(model)
            }
        }
    }

    companion object {
        /** [SetupError.name] to show when the screen opens (set by MainActivity on a rejected token). */
        const val EXTRA_ERROR = "setup_error"

        fun intent(context: Context, error: SetupError? = null): Intent =
            Intent(context, SetupActivity::class.java).apply {
                if (error != null) putExtra(EXTRA_ERROR, error.name)
            }
    }
}

/** Holds form state in memory only: never written to saved state, logs or disk before validation. */
class SetupViewModel(application: Application) : AndroidViewModel(application) {
    var clientId by mutableStateOf("")
        private set
    var clientSecret by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<SetupError?>(null)
        private set
    var done by mutableStateOf(false)
        private set

    private var initialErrorShown = false

    val canConnect: Boolean
        get() = !busy && SetupLogic.credentials(clientId, clientSecret) != null

    fun showInitialError(initial: SetupError?) {
        if (initialErrorShown) return
        initialErrorShown = true
        if (error == null) error = initial
    }

    fun onClientId(value: String) {
        if (SetupLogic.accepts(value)) clientId = value
    }

    fun onClientSecret(value: String) {
        if (SetupLogic.accepts(value)) clientSecret = value
    }

    fun connect() {
        if (busy) return
        val token = SetupLogic.credentials(clientId, clientSecret) ?: return
        busy = true
        error = null
        viewModelScope.launch {
            val app = getApplication<HerdrApplication>()
            val bootstrapper = AccessCookieBootstrapper(OkHttpClient(), HerdrConfig.baseUrl)
            when (val outcome = bootstrapper.fetch(token)) {
                is BootstrapResult.Success -> {
                    val stored = withContext(Dispatchers.IO) { app.tokenStore.put(token) }
                    if (stored) {
                        CookieInstaller(HerdrConfig.baseUrl).install(outcome.setCookieHeader)
                        clientSecret = ""
                        done = true
                    } else {
                        error = SetupError.STORE_FAILED
                    }
                }
                else -> error = SetupLogic.errorFor(outcome)
            }
            busy = false
        }
    }
}

@StringRes
private fun SetupError.message(): Int = when (this) {
    SetupError.REJECTED -> R.string.setup_error_rejected
    SetupError.NO_COOKIE -> R.string.setup_error_no_cookie
    SetupError.NETWORK -> R.string.setup_error_network
    SetupError.UNEXPECTED -> R.string.setup_error_unexpected
    SetupError.STORE_FAILED -> R.string.setup_error_store
}

@Composable
private fun SetupScreen(model: SetupViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = model.clientId,
                onValueChange = model::onClientId,
                label = { Text(stringResource(R.string.setup_client_id)) },
                singleLine = true,
                enabled = !model.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.clientSecret,
                onValueChange = model::onClientSecret,
                label = { Text(stringResource(R.string.setup_client_secret)) },
                singleLine = true,
                enabled = !model.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            model.error?.let {
                Text(
                    stringResource(it.message()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = model::connect,
                enabled = model.canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (model.busy) R.string.setup_connecting else R.string.setup_connect))
            }
        }
    }
}
