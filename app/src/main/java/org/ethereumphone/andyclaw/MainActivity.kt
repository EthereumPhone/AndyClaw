package org.ethereumphone.andyclaw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.ethereumphone.andyclaw.navigation.AppNavigation
import org.ethereumphone.andyclaw.ui.theme.AndyClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as NodeApp).permissionRequester = PermissionRequester(this)
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
