package com.saif.mosque.util

import com.saif.mosque.data.model.Role

/**
 * Permission matrix (spec 2.2). Read access: every active mosque user can read
 * all of their own mosque's data; platform console is superadmin-only.
 */
object Permissions {

    private val mosqueRoles = setOf(
        Role.ADMIN, Role.PRESIDENT, Role.SECRETARY, Role.TREASURER,
        Role.CASHIER, Role.IMAM, Role.MEMBER
    )

    fun isPlatform(role: Role) = role == Role.SUPERADMIN
    fun isMosqueUser(role: Role) = role in mosqueRoles
    fun canViewDashboard(role: Role) = role in mosqueRoles

    fun canManageMosques(role: Role) = role == Role.SUPERADMIN

    fun canEditFunds(role: Role) = role in setOf(Role.ADMIN, Role.SECRETARY, Role.TREASURER)

    fun canEditContributions(role: Role) =
        role in setOf(Role.ADMIN, Role.SECRETARY, Role.TREASURER, Role.CASHIER)

    fun canEditExpenses(role: Role) = canEditContributions(role)

    fun canEditDonors(role: Role) = canEditContributions(role)
    fun canDeleteDonors(role: Role) = role in setOf(Role.ADMIN, Role.SECRETARY, Role.TREASURER)

    fun canEditMembers(role: Role) = role in setOf(Role.ADMIN, Role.PRESIDENT, Role.SECRETARY)

    fun canEditTransfers(role: Role) = role in setOf(Role.ADMIN, Role.SECRETARY, Role.TREASURER)
}
