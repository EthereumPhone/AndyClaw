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
import org.ethereumphone.andyclaw.ui.chat.ChatScreen
import org.ethereumphone.andyclaw.ui.chat.SessionListScreen
import org.ethereumphone.andyclaw.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val CHAT_WITH_SESSION = "chat/{sessionId}"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as NodeApp
    val startDestination = remember {
        if (app.userStoryManager.exists()) Routes.CHAT else Routes.ONBOARDING
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CHAT) {
            ChatScreen(
                sessionId = null,
                onNavigateToSessions = { navController.navigate(Routes.SESSIONS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
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
            )
        }
    }
}
