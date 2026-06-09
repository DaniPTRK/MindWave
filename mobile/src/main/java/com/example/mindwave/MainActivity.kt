package com.example.mindwave

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.mindwave.ui.navigation.MindWaveApp
import com.example.mindwave.ui.theme.MindWaveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryOptimizationExemption()
        setContent {
            MindWaveTheme {
                MindWaveApp()
            }
        }
    }

    /**
     * Ask the user to exempt MindWave from Doze/battery optimisation so the
     * nightly FL WorkManager round can actually fire at 2 AM.
     */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Exception) {
            }
        }
    }
}