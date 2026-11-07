package com.saif.mosque.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.model.Contribution
import com.saif.mosque.data.model.DashboardData
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Expense
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.Mosque
import com.saif.mosque.data.model.RecentTx
import com.saif.mosque.data.model.Transfer
import com.saif.mosque.util.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay

/** Dashboard aggregation (spec module: dashboard) — computed from live streams. */
class DashboardRepository(
    private val db: FirebaseFirestore,
    private val session: SessionManager
) {
    private val ledger = LedgerRepository(db, session)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeDashboard(mosqueId: String): Flow<DashboardData> = flow {
        while (true) {
            emit(Format.currentYearMonth())
            delay(60_000)
        }
    }.distinctUntilChanged().flatMapLatest { (year, month) ->
        observeDashboardMonth(mosqueId, year, month)
    }

    private fun observeDashboardMonth(mosqueId: String, year: Int, month: Int): Flow<DashboardData> {
        val (start, end) = Format.yearMonthIso(year, month)
        val (weekStart, weekEnd) = Format.last7DaysIso()

        return combine(
            listOf(
                ledger.observeFunds(mosqueId),
                ledger.observeMonthContributions(mosqueId, start, end),
                ledger.observeMonthExpenses(mosqueId, start, end),
                ledger.observeDonors(mosqueId),
                ledger.observeRecentTransfers(mosqueId),
                ledger.observeMosque(mosqueId),
                ledger.observeRecentContributions(mosqueId),
                ledger.observeRecentExpenses(mosqueId),
                ledger.observeMonthContributions(mosqueId, weekStart, weekEnd),
                ledger.observeMonthExpenses(mosqueId, weekStart, weekEnd)
            )
        ) { arr ->
            @Suppress("UNCHECKED_CAST")
            val funds = arr[0] as List<Fund>
            @Suppress("UNCHECKED_CAST")
            val contributions = arr[1] as List<Contribution>
            @Suppress("UNCHECKED_CAST")
            val expenses = arr[2] as List<Expense>
            @Suppress("UNCHECKED_CAST")
            val donors = arr[3] as List<Donor>
            @Suppress("UNCHECKED_CAST")
            val transfers = arr[4] as List<Transfer>
            val mosque = arr[5] as Mosque?
            @Suppress("UNCHECKED_CAST")
            val recentContributions = arr[6] as List<Contribution>
            @Suppress("UNCHECKED_CAST")
            val recentExpenses = arr[7] as List<Expense>
            @Suppress("UNCHECKED_CAST")
            val weeklyContributions = arr[8] as List<Contribution>
            @Suppress("UNCHECKED_CAST")
            val weeklyExpenses = arr[9] as List<Expense>

            val recent = (
                recentContributions.map {
                    RecentTx(it.id, "contribution", it.donorName.ifBlank { "—" }, it.amount, it.date, it.createdAt)
                } + recentExpenses.map {
                    RecentTx(it.id, "expense", it.title, -it.amount, it.date, it.createdAt)
                } + transfers.map {
                    RecentTx(it.id, "transfer", "Transfer", -it.amount, it.date, it.createdAt)
                }
                ).sortedWith(compareByDescending<RecentTx> { it.date }.thenByDescending { it.createdAt }).take(10)

            val weeklyIncome = weeklyContributions.sumOf { Math.round(it.amount * 100L) } / 100.0
            val weeklyExpense = weeklyExpenses.sumOf { Math.round(it.amount * 100L) } / 100.0

            val todayIso = Format.todayIso()
            val todayIncome = contributions.filter { it.date == todayIso }.sumOf { Math.round(it.amount * 100L) } / 100.0
            val todayExpense = expenses.filter { it.date == todayIso }.sumOf { Math.round(it.amount * 100L) } / 100.0

            DashboardData(
                totalBalance = mosque?.totalBalance ?: (funds.sumOf { Math.round(it.balance * 100L) } / 100.0),
                monthlyIncome = contributions.sumOf { Math.round(it.amount * 100L) } / 100.0,
                monthlyExpense = expenses.sumOf { Math.round(it.amount * 100L) } / 100.0,
                weeklyIncome = weeklyIncome,
                weeklyExpense = weeklyExpense,
                weeklyStartDate = weekStart,
                weeklyEndDate = weekEnd,
                todayIncome = todayIncome,
                todayExpense = todayExpense,
                activeDonors = donors.count { it.active },
                fundCount = funds.count { it.active },
                fundBalances = funds.sortedByDescending { it.balance }.map { it to it.balance },
                recent = recent
            )
        }
    }
}
