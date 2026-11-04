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

/** Dashboard aggregation (spec module: dashboard) — computed from live streams. */
class DashboardRepository(
    private val db: FirebaseFirestore,
    private val session: SessionManager
) {
    private val ledger = LedgerRepository(db, session)

    fun observeDashboard(mosqueId: String): Flow<DashboardData> {
        val (year, month) = Format.currentYearMonth()
        val monthPrefix = String.format(java.util.Locale.US, "%04d-%02d", year, month)

        return combine(
            listOf(
                ledger.observeFunds(mosqueId),
                ledger.observeContributions(mosqueId),
                ledger.observeExpenses(mosqueId),
                ledger.observeDonors(mosqueId),
                ledger.observeTransfers(mosqueId),
                ledger.observeMosque(mosqueId)
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
            val monthContrib = contributions.filter { it.date.startsWith(monthPrefix) }
            val monthExpense = expenses.filter { it.date.startsWith(monthPrefix) }

            val recent = (
                contributions.map {
                    RecentTx(it.id, "contribution", it.donorName.ifBlank { "—" }, it.amount, it.date)
                } + expenses.map {
                    RecentTx(it.id, "expense", it.title, -it.amount, it.date)
                } + transfers.map {
                    RecentTx(it.id, "transfer", "Transfer", -it.amount, it.date)
                }
                ).sortedByDescending { it.date }.take(10)

            DashboardData(
                totalBalance = mosque?.totalBalance ?: funds.sumOf { it.balance },
                monthlyIncome = monthContrib.sumOf { it.amount },
                monthlyExpense = monthExpense.sumOf { it.amount },
                activeDonors = donors.count { it.active },
                fundCount = funds.size,
                fundBalances = funds.sortedByDescending { it.balance }.map { it to it.balance },
                recent = recent
            )
        }
    }
}
