package com.vigilia.app.ui.navigation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
        containerColor = Color(0xFF0F0F0F),
        contentColor = AccentAmber,
        tonalElevation = 0.dp,
    ) {
        items.forEach { screen ->
            val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = null) },
                label = {
                    Text(
                        text = screen.label,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                selected = selected,
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
                    selectedIconColor = Color(0xFF0A0A0A),
                    selectedTextColor = AccentAmber,
                    unselectedIconColor = Color(0xFF4B5563),
                    unselectedTextColor = Color(0xFF4B5563),
                    indicatorColor = AccentAmber,
                ),
            )
        }
    }
}

@Composable
fun ActiveMonitoringBanner() {
    val infiniteTransition = rememberInfiniteTransition(label = "banner_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A0F00))
            .padding(vertical = 6.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(dotAlpha)
                    .background(AccentAmber, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Monitoramento ativo",
                color = AccentAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
