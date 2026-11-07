package com.saif.mosque.ui.navigation

/** Navigation routes. */
sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Platform : Screen("platform")
    data object Dashboard : Screen("dashboard")
    data object Funds : Screen("funds")
    data object Contributions : Screen("contributions")
    data object Expenses : Screen("expenses")
    data object Donors : Screen("donors")
    data object Subscriptions : Screen("subscriptions")
    data object Members : Screen("members")
    data object InvitationHistory : Screen("invitation_history")
    data object Transfers : Screen("transfers")
    data object Reports : Screen("reports")
    data object Payroll : Screen("payroll")
    data object Projects : Screen("projects")
    data object More : Screen("more")
    data object Profile : Screen("profile")
}
