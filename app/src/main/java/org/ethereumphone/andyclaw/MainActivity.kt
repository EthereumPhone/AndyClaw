package org.ethereumphone.andyclaw

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.navigation.AppNavigation
import org.ethereumphone.andyclaw.ui.theme.AndyClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NodeApp
        app.permissionRequester = PermissionRequester(this)

        requestBatteryOptimizationExemption()
        requestNotificationPermission()

        val isEthOS = getSystemService("wallet") != null
        if (isEthOS) {
            Log.i("MainActivity", "ethOS detected (wallet service present), skipping foreground service")
        } else {
            Log.i("MainActivity", "Not ethOS (no wallet service), starting foreground service")
            NodeForegroundService.start(this)
        }

        // Update the recent-apps label whenever the AI name changes
        lifecycleScope.launch {
            app.securePrefs.aiName.collect { name ->
                setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(name).build())
            }
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return

        val requester = (application as NodeApp).permissionRequester ?: return
        lifecycleScope.launch {
            val result = requester.requestIfMissing(listOf(Manifest.permission.POST_NOTIFICATIONS))
            Log.i("MainActivity", "POST_NOTIFICATIONS result: $result")
        }
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
