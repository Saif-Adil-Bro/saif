package com.saif.mosque.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.screens.ContributionsScreen
import com.saif.mosque.ui.screens.DashboardScreen
import com.saif.mosque.ui.screens.DonorsScreen
import com.saif.mosque.ui.screens.ExpensesScreen
import com.saif.mosque.ui.screens.FundsScreen
import com.saif.mosque.ui.screens.LoginScreen
import com.saif.mosque.ui.screens.MembersScreen
import com.saif.mosque.ui.screens.MoreScreen
import com.saif.mosque.ui.screens.PlatformScreen
import com.saif.mosque.ui.screens.ReportsScreen
import com.saif.mosque.ui.screens.TransfersScreen
import com.saif.mosque.ui.viewmodel.AuthUiState
import com.saif.mosque.ui.viewmodel.AuthViewModel
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.internetConnectivity
import com.saif.mosque.ui.components.LocalOfflineMode
import com.saif.mosque.ui.components.OfflineBanner

@Composable
fun MosqueNavHost(container: AppContainer, isWideScreen: Boolean) {
    val authVm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        AuthViewModel(container.authRepository, container.session, container.context.internetConnectivity())
    }
    val authState by authVm.state.collectAsStateWithLifecycle()

    when (val s = authState) {
        is AuthUiState.Loading -> com.saif.mosque.ui.components.CenteredProgress()
        is AuthUiState.Guest -> {
            val nav = rememberNavController()
            NavHost(nav, Screen.Login.route) {
                composable(Screen.Login.route) { LoginScreen(authVm, isWideScreen) }
            }
        }
        is AuthUiState.Authed -> androidx.compose.runtime.key(s.user.id, s.user.role, s.user.mosqueId) {
            val nav = rememberNavController()
            val start = if (Permissions.isPlatform(s.user.role)) Screen.Platform.route else Screen.Dashboard.route
            androidx.compose.runtime.CompositionLocalProvider(LocalOfflineMode provides s.offline) {
            NavHost(nav, start) {
                composable(Screen.Login.route) { LoginScreen(authVm, isWideScreen) }
                composable(Screen.Platform.route) { PlatformScreen(container, authVm, s.user) }
                composable(Screen.Dashboard.route) { MosqueShell(nav, s.user, R.string.dashboard) { DashboardScreen(container, s.user) } }
                composable(Screen.Funds.route) { MosqueShell(nav, s.user, R.string.funds) { FundsScreen(container, s.user) } }
                composable(Screen.Contributions.route) { MosqueShell(nav, s.user, R.string.contributions) { ContributionsScreen(container, s.user) } }
                composable(Screen.Expenses.route) { MosqueShell(nav, s.user, R.string.expenses) { ExpensesScreen(container, s.user) } }
                composable(Screen.Donors.route) { MosqueShell(nav, s.user, R.string.donors) { DonorsScreen(container, s.user) } }
                composable(Screen.Members.route) { MosqueShell(nav, s.user, R.string.members) { MembersScreen(container, s.user) } }
                composable(Screen.Transfers.route) { MosqueShell(nav, s.user, R.string.transfers) { TransfersScreen(container, s.user) } }
                composable(Screen.Reports.route) { MosqueShell(nav, s.user, R.string.reports) { ReportsScreen(container, s.user) } }
                composable(Screen.More.route) { MosqueShell(nav, s.user, R.string.more) { MoreScreen(nav, container, authVm, s.user) } }
            }
            }
        }
    }
}

private data class NavItem(val screen: Screen, val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int)

private val mosqueNavItems = listOf(
    NavItem(Screen.Dashboard, Icons.Filled.Dashboard, R.string.dashboard),
    NavItem(Screen.Funds, Icons.Filled.AccountBalance, R.string.funds),
    NavItem(Screen.Contributions, Icons.Filled.Payments, R.string.contributions),
    NavItem(Screen.Expenses, Icons.Filled.ReceiptLong, R.string.expenses),
    NavItem(Screen.More, Icons.Filled.MoreHoriz, R.string.more)
)

/** One navigation shell for phones and tablets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosqueShell(
    nav: NavHostController,
    user: AppUser,
    titleRes: Int,
    content: @Composable () -> Unit
) {
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val isSecondary = currentRoute in setOf(Screen.Donors.route, Screen.Members.route,
        Screen.Transfers.route, Screen.Reports.route)
    fun selected(item: NavItem) = currentRoute == item.screen.route ||
        (isSecondary && item.screen == Screen.More)
    fun navigate(item: NavItem) {
        nav.navigate(item.screen.route) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight().statusBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    mosqueNavItems.forEach { item ->
                        NavigationRailItem(selected = selected(item), onClick = { navigate(item) },
                            icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                            label = { Text(stringResource(item.labelRes), maxLines = 1) },
                            alwaysShowLabel = true)
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column {
                    TopAppBar(
                        title = { Text(stringResource(titleRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (isSecondary) IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground)
                    )
                    if (LocalOfflineMode.current) OfflineBanner()
                    }
                },
                bottomBar = {
                    if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        mosqueNavItems.forEach { item ->
                            NavigationBarItem(selected = selected(item), onClick = { navigate(item) },
                                icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes), maxLines = 1) },
                                alwaysShowLabel = false,
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) { content() }
                }
            }
        }
    }
}
