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
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.vigilia.app.data.repository.AuthRepository
import com.vigilia.app.service.MonitoringService
import com.vigilia.app.ui.auth.AuthScreen
import com.vigilia.app.ui.auth.AuthViewModel
import com.vigilia.app.ui.auth.ForgotPasswordScreen
import com.vigilia.app.ui.auth.ResetPasswordScreen
import com.vigilia.app.terms.TermsConfig
import com.vigilia.app.ui.history.HistoryScreen
import com.vigilia.app.ui.history.HistoryViewModel
import com.vigilia.app.ui.history.SessionDetailScreen
import com.vigilia.app.ui.monitoring.MonitoringScreen
import com.vigilia.app.ui.monitoring.MonitoringViewModel
import com.vigilia.app.ui.options.OptionsScreen
import com.vigilia.app.ui.options.ProfileScreen
import com.vigilia.app.ui.options.ProfileViewModel
import com.vigilia.app.ui.options.TermsViewerScreen
import com.vigilia.app.ui.setup.SetupScreen
import com.vigilia.app.ui.setup.SetupViewModel
import com.vigilia.app.ui.terms.TermsAcceptanceScreen
import com.vigilia.app.ui.terms.TermsMode
import com.vigilia.app.ui.theme.AccentAmber

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Setup : Screen("setup", "Configurar", Icons.Default.Tune)
    object Monitoring : Screen("monitoring", "Monitorar", Icons.Default.MonitorHeart)
    object Options : Screen("options", "Opções", Icons.Default.Settings)
}

private const val ROUTE_HISTORY = "history"
private const val ROUTE_SESSION_DETAIL = "history/session"
private const val ARG_SESSION_ID = "sessionId"
private const val ROUTE_PROFILE = "options/profile"
private const val ROUTE_TERMS_VIEW = "options/terms"
private const val ROUTE_PRIVACY_VIEW = "options/privacy"

private const val ROUTE_AUTH = "auth"
private const val ROUTE_FORGOT_PASSWORD = "forgot_password"
private const val ROUTE_RESET_PASSWORD = "reset_password"
private const val ROUTE_TERMS_SIGNUP = "terms_signup"
private const val ROUTE_TERMS_LOGIN = "terms_login"
private const val ROUTE_TERMS_PERMISSION = "terms_permission"

/** SavedStateHandle key set by [ROUTE_TERMS_PERMISSION] to trigger the OS permission prompt. */
const val KEY_PENDING_PERMISSION_REQUEST = "pending_permission_request"

@Composable
@Suppress("unused")
fun VigiliaNavGraph(
    navController: NavHostController,
    startDestination: String = ROUTE_AUTH,
    isPasswordResetDeepLink: Boolean = false,
    onPasswordResetHandled: () -> Unit = {},
) {
    val assessment by MonitoringService.currentAssessment.collectAsState()
    val isMonitoringActive = assessment != null

    val authViewModel: AuthViewModel = viewModel()
    val authUiState by authViewModel.uiState.collectAsState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Navigate to reset password screen when app is opened via deep link
    LaunchedEffect(isPasswordResetDeepLink) {
        if (isPasswordResetDeepLink) {
            navController.navigate(ROUTE_RESET_PASSWORD) { launchSingleTop = true }
            onPasswordResetHandled()
        }
    }

    // Navigate back to auth when the user logs out. Also cover the terms routes so
    // signing out from mid-flow lands on the auth screen too.
    LaunchedEffect(authUiState.isLoggedIn, authUiState.requiresTermsAcceptance) {
        val onAuthFlow = currentRoute == null ||
            currentRoute == ROUTE_AUTH ||
            currentRoute == ROUTE_FORGOT_PASSWORD ||
            currentRoute == ROUTE_RESET_PASSWORD
        if (!authUiState.isLoggedIn && !authUiState.requiresTermsAcceptance && !onAuthFlow) {
            navController.navigate(ROUTE_AUTH) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Sign-up flow: quando o AuthViewModel sinaliza que os termos precisam de aceite
    // antes de criar a conta, navega para a tela de termos (modo SIGNUP).
    LaunchedEffect(authUiState.awaitingTermsForSignup) {
        if (authUiState.awaitingTermsForSignup && currentRoute == ROUTE_AUTH) {
            navController.navigate(ROUTE_TERMS_SIGNUP) { launchSingleTop = true }
        }
    }

    // Post-signIn: se o profile do usuário existente exige aceite dos termos vigentes,
    // navega para a tela de termos (modo LOGIN) em vez de deixar entrar no app.
    LaunchedEffect(authUiState.requiresTermsAcceptance) {
        if (authUiState.requiresTermsAcceptance && currentRoute != ROUTE_TERMS_LOGIN) {
            navController.navigate(ROUTE_TERMS_LOGIN) {
                popUpTo(ROUTE_AUTH) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // Após login concluído (com termos em dia), sai da tela de auth para o setup.
    LaunchedEffect(authUiState.isLoggedIn) {
        if (authUiState.isLoggedIn && (currentRoute == ROUTE_AUTH || currentRoute == ROUTE_TERMS_LOGIN)) {
            navController.navigate(Screen.Setup.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            val authRoutes = setOf(
                ROUTE_AUTH,
                ROUTE_FORGOT_PASSWORD,
                ROUTE_RESET_PASSWORD,
                ROUTE_TERMS_SIGNUP,
                ROUTE_TERMS_LOGIN,
                ROUTE_TERMS_PERMISSION,
            )
            if (currentRoute !in authRoutes) {
                VigiliaBottomBar(navController = navController)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val authRoutes = setOf(
                ROUTE_AUTH,
                ROUTE_FORGOT_PASSWORD,
                ROUTE_RESET_PASSWORD,
                ROUTE_TERMS_SIGNUP,
                ROUTE_TERMS_LOGIN,
                ROUTE_TERMS_PERMISSION,
            )
            if (isMonitoringActive && currentRoute !in authRoutes) {
                ActiveMonitoringBanner()
            }

            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.weight(1f)
            ) {
                composable(ROUTE_AUTH) {
                    AuthScreen(
                        viewModel = authViewModel,
                        onAuthSuccess = {
                            navController.navigate(Screen.Setup.route) {
                                popUpTo(ROUTE_AUTH) { inclusive = true }
                            }
                        },
                        onForgotPassword = {
                            navController.navigate(ROUTE_FORGOT_PASSWORD)
                        },
                    )
                }
                composable(ROUTE_FORGOT_PASSWORD) {
                    ForgotPasswordScreen(
                        viewModel = authViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_RESET_PASSWORD) {
                    ResetPasswordScreen(
                        viewModel = authViewModel,
                        onResetComplete = {
                            navController.navigate(ROUTE_AUTH) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }
                composable(ROUTE_TERMS_SIGNUP) {
                    TermsAcceptanceScreen(
                        mode = TermsMode.SIGNUP,
                        onAccepted = { tosAt, privacyAt ->
                            authViewModel.completeSignupAfterTerms(tosAt, privacyAt)
                            // signUp real acontece assíncrono; volta pra auth para que a
                            // AuthScreen mostre "conta criada!" (registrationPendingConfirmation)
                            // ou o LaunchedEffect de isLoggedIn navegue pro setup.
                            navController.navigate(ROUTE_AUTH) {
                                popUpTo(ROUTE_AUTH) { inclusive = true }
                            }
                        },
                        onCancel = {
                            authViewModel.cancelPendingSignup()
                            navController.navigate(ROUTE_AUTH) {
                                popUpTo(ROUTE_AUTH) { inclusive = true }
                            }
                        },
                    )
                }
                composable(ROUTE_TERMS_LOGIN) {
                    TermsAcceptanceScreen(
                        mode = TermsMode.LOGIN,
                        onAccepted = { tosAt, privacyAt ->
                            authViewModel.completeTermsAcceptanceForLogin(tosAt, privacyAt)
                        },
                        onCancel = {
                            authViewModel.signOut()
                        },
                    )
                }
                composable(ROUTE_TERMS_PERMISSION) {
                    TermsAcceptanceScreen(
                        mode = TermsMode.PERMISSION,
                        onAccepted = { tosAt, privacyAt ->
                            authViewModel.completeTermsAcceptanceForLogin(tosAt, privacyAt)
                            // Sinaliza ao SetupScreen para disparar o prompt do SO
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set(KEY_PENDING_PERMISSION_REQUEST, true)
                            navController.popBackStack()
                        },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable(Screen.Setup.route) { backStackEntry ->
                    val setupViewModel: SetupViewModel = viewModel()
                    SetupScreen(
                        viewModel = setupViewModel,
                        onMonitoringStarted = {
                            navController.navigate(Screen.Monitoring.route) {
                                popUpTo(Screen.Setup.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onRequestPermissionsGate = {
                            navController.navigate(ROUTE_TERMS_PERMISSION) { launchSingleTop = true }
                        },
                        pendingPermissionRequest = backStackEntry.savedStateHandle
                            .getStateFlow(KEY_PENDING_PERMISSION_REQUEST, false),
                        onPendingPermissionConsumed = {
                            backStackEntry.savedStateHandle[KEY_PENDING_PERMISSION_REQUEST] = false
                        },
                    )
                }
                composable(Screen.Monitoring.route) {
                    val monitoringViewModel: MonitoringViewModel = viewModel()
                    MonitoringScreen(viewModel = monitoringViewModel)
                }
                composable(Screen.Options.route) {
                    OptionsScreen(
                        onNavigateProfile = { navController.navigate(ROUTE_PROFILE) },
                        onNavigateTerms = { navController.navigate(ROUTE_TERMS_VIEW) },
                        onNavigatePrivacy = { navController.navigate(ROUTE_PRIVACY_VIEW) },
                        onNavigateHistory = { navController.navigate(ROUTE_HISTORY) },
                        onLogout = { authViewModel.signOut() },
                    )
                }
                composable(ROUTE_PROFILE) {
                    val profileViewModel: ProfileViewModel = viewModel()
                    ProfileScreen(
                        viewModel = profileViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_TERMS_VIEW) {
                    TermsViewerScreen(
                        title = "Termo de Uso",
                        versionLabel = "Versão ${TermsConfig.formatVersionForDisplay(TermsConfig.TOS_CURRENT_VERSION)}",
                        assetPath = TermsConfig.TOS_ASSET,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_PRIVACY_VIEW) {
                    TermsViewerScreen(
                        title = "Política de Privacidade",
                        versionLabel = "Versão ${TermsConfig.formatVersionForDisplay(TermsConfig.PRIVACY_CURRENT_VERSION)}",
                        assetPath = TermsConfig.PRIVACY_ASSET,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_HISTORY) {
                    val historyViewModel: HistoryViewModel = viewModel()
                    HistoryScreen(
                        viewModel = historyViewModel,
                        onSessionClick = { sessionId ->
                            navController.navigate("$ROUTE_SESSION_DETAIL/$sessionId")
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = "$ROUTE_SESSION_DETAIL/{$ARG_SESSION_ID}",
                    arguments = listOf(navArgument(ARG_SESSION_ID) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getString(ARG_SESSION_ID).orEmpty()
                    SessionDetailScreen(
                        sessionId = sessionId,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
fun VigiliaBottomBar(navController: NavHostController) {
    val items = listOf(Screen.Setup, Screen.Monitoring, Screen.Options)
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
