package org.ethereumphone.andyclaw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.ethereumphone.andyclaw.navigation.AppNavigation
import org.ethereumphone.andyclaw.ui.theme.AndyClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as NodeApp).permissionRequester = PermissionRequester(this)

        requestBatteryOptimizationExemption()

        val isEthOS = getSystemService("wallet") != null
        if (isEthOS) {
            Log.i("MainActivity", "ethOS detected (wallet service present), skipping foreground service")
        } else {
            Log.i("MainActivity", "Not ethOS (no wallet service), starting foreground service")
            NodeForegroundService.start(this)
        }

        enableEdgeToEdge()
        setContent {
            AndyClawTheme {
                AppNavigation()
            }
        }
    }

    override fun onDestroy() {
        (application as NodeApp).permissionRequester = null
        super.onDestroy()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i("MainActivity", "Already exempt from battery optimizations")
            return
        }
        Log.i("MainActivity", "Requesting battery optimization exemption for background network access")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
