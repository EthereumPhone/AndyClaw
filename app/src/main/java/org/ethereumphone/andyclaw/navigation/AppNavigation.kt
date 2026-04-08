package org.ethereumphone.andyclaw.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.onboarding.OnboardingScreen
import org.ethereumphone.andyclaw.onboarding.WalletSignScreen
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.ui.chat.ChatScreen
import org.ethereumphone.andyclaw.ui.chat.SessionListScreen
import org.ethereumphone.andyclaw.ui.clawhub.ClawHubScreen
import org.ethereumphone.andyclaw.ui.heartbeatlogs.HeartbeatLogsScreen
import org.ethereumphone.andyclaw.ui.settings.AgentDisplayTestScreen
import org.ethereumphone.andyclaw.ui.settings.SettingsScreen
import org.ethereumphone.andyclaw.ui.settings.SettingsSubScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val WALLET_SIGN = "wallet_sign"
    const val CHAT = "chat"
    const val CHAT_WITH_SESSION = "chat/{sessionId}"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val SETTINGS_MODEL = "settings/model_selection"
    const val CLAWHUB = "clawhub"
    const val HEARTBEAT_LOGS = "heartbeat_logs"
    const val AGENT_DISPLAY_TEST = "agent_display_test"
    const val AGENT_TX_HISTORY = "agent_tx_history"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as NodeApp
    val startDestination = remember {
        when {
            !app.userStoryManager.exists() -> Routes.ONBOARDING
            // Existing ethOS user with missing or invalid wallet signature
            OsCapabilities.hasPrivilegedAccess &&
                !app.securePrefs.walletSignature.value.startsWith("0x") -> Routes.WALLET_SIGN
            else -> Routes.CHAT
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.ONBOARDING) {
            val activity = LocalContext.current as? android.app.Activity
            OnboardingScreen(
                onComplete = {
                    // Close the app so the user returns to ethOSLauncher's dgent fragment.
                    // The launcher's onResume() will detect the completed setup.
                    activity?.finish()
                },
            )
        }

        composable(Routes.WALLET_SIGN) {
            WalletSignScreen(
                onComplete = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.WALLET_SIGN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CHAT) {
            ChatScreen(
                sessionId = null,
                onNavigateToSessions = { navController.navigate(Routes.SESSIONS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToRoute = { route -> navController.navigate(route) },
            )
        }

        composable(
            route = Routes.CHAT_WITH_SESSION,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            ChatScreen(
                sessionId = sessionId,
                onNavigateToSessions = { navController.navigate(Routes.SESSIONS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToRoute = { route -> navController.navigate(route) },
            )
        }

        composable(Routes.SESSIONS) {
            SessionListScreen(
                onNavigateToChat = { sessionId ->
                    if (sessionId != null) {
                        navController.navigate("chat/$sessionId") {
                            popUpTo(Routes.CHAT) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.CHAT) { inclusive = true }
                        }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToClawHub = { navController.navigate(Routes.CLAWHUB) },
                onNavigateToHeartbeatLogs = { navController.navigate(Routes.HEARTBEAT_LOGS) },
                onNavigateToAgentDisplayTest = { navController.navigate(Routes.AGENT_DISPLAY_TEST) },
                onNavigateToAgentTxHistory = { navController.navigate(Routes.AGENT_TX_HISTORY) },
            )
        }

        composable(Routes.SETTINGS_MODEL) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToClawHub = { navController.navigate(Routes.CLAWHUB) },
                onNavigateToHeartbeatLogs = { navController.navigate(Routes.HEARTBEAT_LOGS) },
                onNavigateToAgentDisplayTest = { navController.navigate(Routes.AGENT_DISPLAY_TEST) },
                onNavigateToAgentTxHistory = { navController.navigate(Routes.AGENT_TX_HISTORY) },
                initialSubScreen = SettingsSubScreen.ModelSelection,
            )
        }

        composable(Routes.CLAWHUB) {
            ClawHubScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HEARTBEAT_LOGS) {
            HeartbeatLogsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Routes.AGENT_DISPLAY_TEST) {
            AgentDisplayTestScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Routes.AGENT_TX_HISTORY) {
            org.ethereumphone.andyclaw.ui.agenttx.AgentTxHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
