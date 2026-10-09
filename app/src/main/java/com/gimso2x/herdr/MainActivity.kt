package com.gimso2x.herdr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            Text("herdr")
        }
    }

    companion object {
        /** Pane to open, set by status-watcher notifications (handled in todo 7). */
        const val EXTRA_PANE_ID = "pane_id"
    }
}
