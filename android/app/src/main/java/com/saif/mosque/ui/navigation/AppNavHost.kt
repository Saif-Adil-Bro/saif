package com.saif.mosque.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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

@Composable
fun MosqueNavHost(container: AppContainer, isWideScreen: Boolean) {
    val authVm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        AuthViewModel(container.authRepository, container.session)
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
        is AuthUiState.Authed -> {
            val nav = rememberNavController()
            val start = if (Permissions.isPlatform(s.user.role)) Screen.Platform.route else Screen.Dashboard.route
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

private data class NavItem(val screen: Screen, val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int)

private val mosqueNavItems = listOf(
    NavItem(Screen.Dashboard, Icons.Filled.Dashboard, R.string.dashboard),
    NavItem(Screen.Funds, Icons.Filled.AccountBalance, R.string.funds),
    NavItem(Screen.Contributions, Icons.Filled.Payments, R.string.contributions),
    NavItem(Screen.Expenses, Icons.Filled.ReceiptLong, R.string.expenses),
    NavItem(Screen.More, Icons.Filled.MoreHoriz, R.string.more)
)

/** Shared scaffold for mosque-tenant screens: top bar + bottom navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosqueShell(
    nav: NavHostController,
    user: AppUser,
    titleRes: Int,
    content: @Composable () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                mosqueNavItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.screen.route,
                        onClick = {
                            nav.navigate(item.screen.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes)) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        androidx.compose.foundation.layout.Box(Modifier.padding(inner)) { content() }
    }
}
