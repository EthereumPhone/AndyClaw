package org.ethereumphone.andyclaw

import android.os.Bundle
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
}
