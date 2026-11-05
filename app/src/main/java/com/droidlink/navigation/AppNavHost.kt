package com.droidlink.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.droidlink.presentation.common.rememberContainer
import com.droidlink.presentation.screens.AboutScreen
import com.droidlink.presentation.screens.ConnectScreen
import com.droidlink.presentation.screens.DashboardScreen
import com.droidlink.presentation.screens.DeviceInfoScreen
import com.droidlink.presentation.screens.DevicesScreen
import com.droidlink.presentation.screens.HistoryScreen
import com.droidlink.presentation.screens.HostSessionBanner
import com.droidlink.presentation.screens.LockScreen
import com.droidlink.presentation.screens.LoginScreen
import com.droidlink.presentation.screens.OnboardingScreen
import com.droidlink.presentation.screens.PairingScreen
import com.droidlink.presentation.screens.SecurityScreen
import com.droidlink.presentation.screens.SettingsScreen
import com.droidlink.presentation.screens.SplashScreen
import com.droidlink.presentation.screens.TransferScreen
import com.droidlink.presentation.screens.ViewerScreen
import com.droidlink.presentation.theme.Danger
import com.droidlink.services.HostState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val PRE_AUTH_ROUTES = setOf(Routes.SPLASH, Routes.ONBOARDING, Routes.LOGIN, Routes.LOCK)

@Composable
fun AppNavHost() {
    val container = rememberContainer()
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val host by container.hostController.state.collectAsState()
    val registration by container.auth.registration.collectAsState()
    val incomingLink by container.incomingLink.collectAsState()
    val bannerVisible = host is HostState.Active || host is HostState.Connecting

    // The relay rejected our credentials (revoked / unregistered elsewhere): back to registration.
    LaunchedEffect(registration) {
        val route = nav.currentDestination?.route
        if (registration == null && route != null && route !in PRE_AUTH_ROUTES) {
            nav.navigate(Routes.LOGIN) { popUpTo(0) }
        }
    }
    // droidlink://pair link opened from outside the app (e.g. camera app scanned the QR).
    LaunchedEffect(incomingLink) {
        val route = nav.currentDestination?.route
        if (incomingLink != null && registration != null && route != null && route !in PRE_AUTH_ROUTES && route != Routes.PAIRING) {
            nav.navigate(Routes.PAIRING)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (bannerVisible) {
            Box(Modifier.background(Danger).statusBarsPadding()) {
                HostSessionBanner(
                    state = host,
                    onPauseControl = container.hostController::pauseControl,
                    onDisconnect = container.hostController::emergencyDisconnect,
                )
            }
        }
        NavHost(
            navController = nav,
            startDestination = Routes.SPLASH,
            modifier = Modifier.weight(1f).then(if (bannerVisible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 } },
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(onFinished = {
                    scope.launch {
                        val s = container.settingsRepository.settings.first()
                        val next = when {
                            !s.onboardingDone -> Routes.ONBOARDING
                            container.auth.registration.value == null -> Routes.LOGIN
                            s.appLockEnabled -> Routes.LOCK
                            else -> Routes.DASHBOARD
                        }
                        nav.navigate(next) { popUpTo(Routes.SPLASH) { inclusive = true } }
                    }
                })
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    scope.launch {
                        container.settingsRepository.setOnboardingDone()
                        val next = if (container.auth.registration.value == null) Routes.LOGIN else Routes.DASHBOARD
                        nav.navigate(next) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    }
                })
            }
            composable(Routes.LOGIN) {
                LoginScreen(onSignedIn = { nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.LOGIN) { inclusive = true } } })
            }
            composable(Routes.LOCK) {
                LockScreen(onUnlocked = { nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.LOCK) { inclusive = true } } })
            }
            composable(Routes.DASHBOARD) { DashboardScreen(onNavigate = { nav.navigate(it) }) }
            composable(Routes.PAIRING) {
                PairingScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDevices = { nav.navigate(Routes.DEVICES) { popUpTo(Routes.PAIRING) { inclusive = true } } },
                    onOpenDashboard = { nav.popBackStack(Routes.DASHBOARD, false) },
                )
            }
            composable(Routes.DEVICES) {
                DevicesScreen(
                    onBack = { nav.popBackStack() },
                    onConnect = { nav.navigate(Routes.connect(it)) },
                    onPair = { nav.navigate(Routes.PAIRING) },
                )
            }
            composable(Routes.CONNECT, arguments = listOf(navArgument("pairingId") { type = NavType.StringType })) { entry ->
                ConnectScreen(
                    pairingId = entry.arguments?.getString("pairingId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onConnected = { sessionId -> nav.navigate(Routes.viewer(sessionId)) { popUpTo(Routes.CONNECT) { inclusive = true } } },
                )
            }
            composable(Routes.VIEWER, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
                ViewerScreen(sessionId = entry.arguments?.getString("sessionId").orEmpty(), onExit = { nav.popBackStack() })
            }
            composable(Routes.TRANSFERS) { TransferScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DEVICE_INFO) { DeviceInfoScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HISTORY) { HistoryScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SECURITY) {
                SecurityScreen(
                    onBack = { nav.popBackStack() },
                    onSignedOut = { nav.navigate(Routes.LOGIN) { popUpTo(0) } },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onAbout = { nav.navigate(Routes.ABOUT) },
                    onSecurity = { nav.navigate(Routes.SECURITY) },
                )
            }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

@Suppress("unused")
private fun NavHostController.isOn(route: String) = currentDestination?.route == route
