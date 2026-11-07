package com.saif.mosque.data.model

import com.google.firebase.firestore.DocumentId

/** Roles (spec 2.1). Stored in Firestore as the enum name, e.g. "SUPERADMIN". */
enum class Role {
    SUPERADMIN, ADMIN, PRESIDENT, SECRETARY, TREASURER, CASHIER, IMAM, MEMBER
}

/** /users/{uid} — committee member or platform superadmin (mosqueId == null). */
data class AppUser(
    @DocumentId val id: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val role: Role = Role.MEMBER,
    val mosqueId: String? = null,
    val designation: String = "",
    val active: Boolean = true,
    val createdAt: Long = 0L
)

/** /mosques/{id} — platform-level mosque document with cached stats (spec 1.2). */
data class Mosque(
    @DocumentId val id: String = "",
    val name: String = "",
    val nameEn: String = "",
    val address: String = "",
    val district: String = "",
    val upazila: String = "",
    val phone: String = "",
    val email: String = "",
    val establishedYear: Int? = null,
    val registrationNo: String = "",
    val active: Boolean = true,
    val totalBalance: Double = 0.0,
    val fundCount: Int = 0,
    val donorCount: Int = 0,
    val memberCount: Int = 0,
    val createdAt: Long = 0L
)

/** /mosques/{id}/funds/{doc} — fund / account (spec starter funds: GEN CHD CON WAK UTL). */
data class Fund(
    @DocumentId val id: String = "",
    val mosqueId: String = "",
    val code: String = "",
    val name: String = "",
    val nameEn: String = "",
    val balance: Double = 0.0,
    val active: Boolean = true,
    val description: String = ""
)

/** /mosques/{id}/donors/{doc} */
data class Donor(
    @DocumentId val id: String = "",
    val mosqueId: String = "",
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val active: Boolean = true,
    val totalDonated: Double = 0.0,
    val createdAt: Long = 0L
)

/** /mosques/{id}/contributions/{doc} — date is ISO "yyyy-MM-dd". */
data class Contribution(
    @DocumentId val id: String = "",
    val mosqueId: String = "",
    val donorId: String = "",
    val donorName: String = "",
    val accountId: String = "",
    val amount: Double = 0.0,
    val date: String = "",
    val note: String = "",
    val receiptNo: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L
)

/** /mosques/{id}/expenses/{doc} — date is ISO "yyyy-MM-dd". */
data class Expense(
    @DocumentId val id: String = "",
    val mosqueId: String = "",
    val accountId: String = "",
    val title: String = "",
    val category: String = "",
    val amount: Double = 0.0,
    val date: String = "",
    val paidTo: String = "",
    val receiptNo: String = "",
    val note: String = "",
    val status: String = "approved",
    val approvedBy: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L
)

/** /mosques/{id}/transfers/{doc} */
data class Transfer(
    @DocumentId val id: String = "",
    val mosqueId: String = "",
    val fromAccountId: String = "",
    val toAccountId: String = "",
    val amount: Double = 0.0,
    val date: String = "",
    val note: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L
)

/** Computed dashboard payload (not stored). */
data class DashboardData(
    val totalBalance: Double = 0.0,
    val monthlyIncome: Double = 0.0,
    val monthlyExpense: Double = 0.0,
    val weeklyIncome: Double = 0.0,
    val weeklyExpense: Double = 0.0,
    val weeklyStartDate: String = "",
    val weeklyEndDate: String = "",
    val todayIncome: Double = 0.0,
    val todayExpense: Double = 0.0,
    val activeDonors: Int = 0,
    val fundCount: Int = 0,
    val fundBalances: List<Pair<Fund, Double>> = emptyList(),
    val recent: List<RecentTx> = emptyList()
)

data class RecentTx(
    val id: String,
    val type: String, // "contribution" | "expense" | "transfer"
    val title: String,
    val amount: Double,
    val date: String,
    val createdAt: Long = 0L
)

/** Computed monthly report (spec: month-end income/expense report). */
data class MonthlyReport(
    val year: Int,
    val month: Int,
    val income: Double,
    val expense: Double,
    val byFund: List<Pair<String, Double>>,
    val contributions: List<Contribution>,
    val expenses: List<Expense>,
    val transfers: List<Transfer> = emptyList(),
    val openingBalance: Double = 0.0,
    val closingBalance: Double = 0.0
)

data class DailyReport(
    val dateIso: String,
    val income: Double,
    val expense: Double,
    val netChange: Double,
    val contributions: List<Contribution> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val transfers: List<Transfer> = emptyList()
)

data class WeeklyReport(
    val startDate: String,
    val endDate: String,
    val income: Double,
    val expense: Double,
    val netChange: Double,
    val contributions: List<Contribution> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val transfers: List<Transfer> = emptyList()
)

/** Result of atomic mosque provisioning (spec 1.5–1.9): one-time credentials. */
data class CreatedMosque(
    val mosque: Mosque,
    val adminEmail: String,
    val temporaryPassword: String
)

/** /mosques/{id}/ledgerMutations/{opId} — immutable correction or void receipt. */
data class LedgerMutation(
    @DocumentId val id: String = "",
    val uid: String = "",
    val kind: String = "",
    val targetId: String = "",
    val action: String = "",
    val oldAmountPoisha: Long = 0L,
    val newAmountPoisha: Long = 0L,
    val before: Map<String, Any> = emptyMap(),
    val after: Map<String, Any> = emptyMap()
)

data class ReceiptChainVerification(
    val valid: Boolean,
    val mutationsCount: Int,
    val activeEntriesCount: Int,
    val voidedEntriesCount: Int,
    val totalBalancePoisha: Long,
    val issues: List<String> = emptyList()
)

