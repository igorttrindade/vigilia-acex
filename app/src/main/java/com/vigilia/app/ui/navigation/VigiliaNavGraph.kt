package com.vigilia.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

/**
 * Defines the main navigation routes for the Vigília app.
 */
sealed class Screen(val route: String) {
    object Setup : Screen("setup")
    object Monitoring : Screen("monitoring")
    object History : Screen("history")
}

/**
 * Navigation Graph for the Vigília app.
 *
 * @param navController The navigation controller used to handle transitions.
 */
@Composable
@Suppress("unused")
fun VigiliaNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Setup.route,
    ) {
        composable(Screen.Setup.route) {
            // SetupScreen placeholder
        }
        composable(Screen.Monitoring.route) {
            // MonitoringScreen placeholder
        }
        composable(Screen.History.route) {
            // HistoryScreen placeholder
        }
    }
}
