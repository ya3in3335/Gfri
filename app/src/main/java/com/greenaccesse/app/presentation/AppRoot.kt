package com.greenaccesse.app.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.greenaccesse.app.presentation.components.ConnectionBadge
import com.greenaccesse.app.presentation.screens.AboutScreen
import com.greenaccesse.app.presentation.screens.ConnectionScreen
import com.greenaccesse.app.presentation.screens.DashboardScreen
import com.greenaccesse.app.presentation.screens.HistoryScreen
import com.greenaccesse.app.presentation.screens.LoginScreen
import com.greenaccesse.app.presentation.screens.PairingScreen
import com.greenaccesse.app.presentation.screens.ScannerScreen
import com.greenaccesse.app.presentation.screens.SettingsScreen

private object Routes {
    const val DASHBOARD = "dashboard"
    const val SCANNER = "scanner"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CONNECTION = "connection"
    const val PAIRING = "pairing"
    const val ABOUT = "about"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Dashboard", Icons.Outlined.Dashboard),
    Tab(Routes.SCANNER, "Scanner", Icons.Outlined.Nfc),
    Tab(Routes.HISTORY, "History", Icons.Outlined.History),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

private val titles = mapOf(
    Routes.CONNECTION to "Connection",
    Routes.PAIRING to "Pairing",
    Routes.ABOUT to "About",
)

@Composable
fun AppRoot() {
    val c = appContainer()
    val session by c.auth.session.collectAsStateWithLifecycle()
    var restoring by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        c.auth.restoreSession()
        restoring = false
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            restoring && session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            session == null -> LoginScreen()
            else -> MainScaffold()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    val c = appContainer()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val connection by c.connection.state.collectAsStateWithLifecycle()
    val isTab = tabs.any { it.route == route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isTab || route == null) "Green Accesse" else titles[route] ?: "Green Accesse") },
                navigationIcon = {
                    if (!isTab && route != null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = { ConnectionBadge(connection, Modifier.padding(end = 12.dp)) },
            )
        },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(padding)) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenConnection = { nav.navigate(Routes.CONNECTION) },
                    onOpenScanner = { nav.navigate(Routes.SCANNER) { launchSingleTop = true } },
                )
            }
            composable(Routes.SCANNER) { ScannerScreen(onOpenConnection = { nav.navigate(Routes.CONNECTION) }) }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenAbout = { nav.navigate(Routes.ABOUT) }, onOpenPairing = { nav.navigate(Routes.PAIRING) })
            }
            composable(Routes.CONNECTION) { ConnectionScreen(onPair = { nav.navigate(Routes.PAIRING) }) }
            composable(Routes.PAIRING) { PairingScreen(onDone = { nav.popBackStack() }) }
            composable(Routes.ABOUT) { AboutScreen() }
        }
    }
}
