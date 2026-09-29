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
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.navigation.AppNavigation
import org.ethereumphone.andyclaw.ui.theme.AndyClawTheme
import org.ethereumphone.andyclaw.ui.theme.SystemColorManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = application as NodeApp
        app.permissionRequester = PermissionRequester(this)

        // Only on a fresh launch: onCreate also runs for every configuration change
        // (rotation, dark mode, font scale), and re-raising the system dialog there
        // pestered a user who had already said no.
        if (savedInstanceState == null) {
            requestBatteryOptimizationExemption()
        }
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
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    app.ledController.showOpeningPattern()

                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            app.ledController.showOpeningPattern()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)

                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SystemColorManager.refresh(this)
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
            // Called from onCreate, before the activity is resumed; requestIfMissing
            // does not prompt from an activity that is not in front, so wait for it.
            lifecycle.withResumed {}
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
        // Asked once per install. A Deny is an answer; the user can still grant it from
        // system settings, and background work degrades rather than breaks without it.
        val uiPrefs = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
        if (uiPrefs.getBoolean(BATTERY_PROMPTED_KEY, false)) {
            Log.i("MainActivity", "Battery optimization exemption already requested once; not asking again")
            return
        }
        uiPrefs.edit().putBoolean(BATTERY_PROMPTED_KEY, true).apply()
        Log.i("MainActivity", "Requesting battery optimization exemption for background network access")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("MainActivity", "No battery optimization settings screen on this device", e)
        }
    }

    private companion object {
        const val UI_PREFS_NAME = "main_activity"
        const val BATTERY_PROMPTED_KEY = "batteryOptimization.prompted"
    }
}
