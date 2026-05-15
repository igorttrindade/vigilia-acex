package com.vigilia.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.vigilia.app.service.MonitoringService
import com.vigilia.app.ui.history.HistoryScreen
import com.vigilia.app.ui.history.HistoryViewModel
import com.vigilia.app.ui.monitoring.MonitoringScreen
import com.vigilia.app.ui.monitoring.MonitoringViewModel
import com.vigilia.app.ui.setup.SetupScreen
import com.vigilia.app.ui.setup.SetupViewModel
import com.vigilia.app.ui.theme.AccentAmber
import com.vigilia.app.ui.theme.SurfaceDark

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Setup : Screen("setup", "Configurar", Icons.Default.Settings)
    object Monitoring : Screen("monitoring", "Monitorar", Icons.Default.MonitorHeart)
    object History : Screen("history", "Histórico", Icons.Default.History)
}

@Composable
@Suppress("unused")
fun VigiliaNavGraph(navController: NavHostController) {
    val assessment by MonitoringService.currentAssessment.collectAsState()
    val isMonitoringActive = assessment != null

    Scaffold(
        bottomBar = {
            VigiliaBottomBar(navController = navController)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isMonitoringActive) {
                ActiveMonitoringBanner()
            }
            
            NavHost(
                navController = navController,
                startDestination = Screen.Setup.route,
                modifier = Modifier.weight(1f)
            ) {
                composable(Screen.Setup.route) {
                    val setupViewModel: SetupViewModel = viewModel()
                    SetupScreen(
                        viewModel = setupViewModel,
                        onMonitoringStarted = {
                            navController.navigate(Screen.Monitoring.route) {
                                popUpTo(Screen.Setup.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(Screen.Monitoring.route) {
                    val monitoringViewModel: MonitoringViewModel = viewModel()
                    MonitoringScreen(viewModel = monitoringViewModel)
                }
                composable(Screen.History.route) {
                    val historyViewModel: HistoryViewModel = viewModel()
                    HistoryScreen(viewModel = historyViewModel)
                }
            }
        }
    }
}

@Composable
fun VigiliaBottomBar(navController: NavHostController) {
    val items = listOf(Screen.Setup, Screen.Monitoring, Screen.History)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar(
        containerColor = SurfaceDark,
        contentColor = AccentAmber,
    ) {
        items.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = null) },
                label = { Text(screen.label) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentAmber,
                    selectedTextColor = AccentAmber,
                    unselectedIconColor = Color(0xFF9CA3AF),
                    unselectedTextColor = Color(0xFF9CA3AF),
                    indicatorColor = SurfaceDark,
                ),
            )
        }
    }
}

@Composable
fun ActiveMonitoringBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentAmber)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "● Monitoramento ativo",
            color = Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
