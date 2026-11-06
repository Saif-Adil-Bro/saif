package com.saif.mosque.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.MonthlyReport
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.FridayAnnouncementDialog
import com.saif.mosque.ui.components.MoneyText
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.components.StatCard
import com.saif.mosque.util.AuditReportPdfExporter
import com.saif.mosque.util.Format
import com.saif.mosque.util.UiState
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class ReportsViewModel(
    private val repo: LedgerRepository,
    private val mosqueId: String
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<MonthlyReport>>(UiState.Loading)
    val state: StateFlow<UiState<MonthlyReport>> =
        _state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _yearMonth = MutableStateFlow(Format.currentYearMonth())
    val yearMonth: StateFlow<Pair<Int, Int>> = _yearMonth

    init { load() }

    private var loadJob: kotlinx.coroutines.Job? = null
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val (y, m) = _yearMonth.value
                val (start, end) = Format.yearMonthIso(y, m)
                val contributions = repo.contributionsInRange(mosqueId, start, end)
                val expenses = repo.expensesInRange(mosqueId, start, end)
                val byFund = contributions.groupBy { it.accountId }
                    .map { (acc, list) -> acc to (list.sumOf { Math.round(it.amount * 100) } / 100.0) }
                    .sortedByDescending { it.second }
                _state.value = UiState.Success(MonthlyReport(
                    year = y, month = m,
                    income = contributions.sumOf { Math.round(it.amount * 100) } / 100.0,
                    expense = expenses.sumOf { Math.round(it.amount * 100) } / 100.0,
                    byFund = byFund,
                    contributions = contributions.sortedWith(compareByDescending<com.saif.mosque.data.model.Contribution> { it.date }.thenByDescending { it.createdAt }),
                    expenses = expenses.sortedWith(compareByDescending<com.saif.mosque.data.model.Expense> { it.date }.thenByDescending { it.createdAt })
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "error")
            }
        }
    }

    fun prevMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).minusMonths(1)
        _yearMonth.value = d.year to d.monthValue
        load()
    }

    fun nextMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).plusMonths(1)
        _yearMonth.value = d.year to d.monthValue
        load()
    }
}

@Composable
fun ReportsScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: ReportsViewModel = viewModel { ReportsViewModel(container.ledgerRepository, mosqueId) }
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val ym by vm.yearMonth.collectAsStateWithLifecycle()

    // fund labels for the by-fund section
    val fundsFlow = remember(mosqueId) {
        container.ledgerRepository.observeFunds(mosqueId)
            .catch { emit(emptyList()) }
    }
    val fundsState by fundsFlow.collectAsState(initial = emptyList())

    val mosqueFlow = remember(mosqueId) {
        container.ledgerRepository.observeMosque(mosqueId).catch { emit(null) }
    }
    val mosqueInfo by mosqueFlow.collectAsState(initial = null)
    var showFridayAnnouncement by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { vm.prevMonth() }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.prev_month))
                }
                Text(Format.monthLabel(context, ym.second, ym.first),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.nextMonth() }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.next_month))
                }

                FilledTonalIconButton(onClick = { showFridayAnnouncement = true }) {
                    Icon(Icons.Filled.Campaign, contentDescription = "খুতবার আগের ঘোষণা")
                }

                IconButton(onClick = {
                    (state as? UiState.Success)?.data?.let { r ->
                        val fundBals = fundsState.map { it to it.balance }
                        val mLabel = Format.monthLabel(context, ym.second, ym.first)
                        AuditReportPdfExporter.printAuditReport(
                            context = context,
                            mosque = mosqueInfo,
                            periodLabel = mLabel,
                            totalIncome = r.income,
                            totalExpense = r.expense,
                            fundBalances = fundBals,
                            contributions = r.contributions,
                            expenses = r.expenses
                        )
                    }
                }) {
                    Icon(Icons.Filled.Print, contentDescription = "অডিট রিপোর্ট প্রিন্ট")
                }
            }
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                is UiState.Loading -> com.saif.mosque.ui.components.CenteredProgress()
                is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is UiState.Success -> {
                    val report = s.data
                    val fundNames = fundsState.associateBy({ it.id }, { "${it.code} ${it.name}" })
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            com.saif.mosque.ui.components.StatsGrid(listOf(
                                com.saif.mosque.ui.components.StatItem(stringResource(R.string.income), Format.money(report.income)),
                                com.saif.mosque.ui.components.StatItem(stringResource(R.string.expense), Format.money(report.expense), MaterialTheme.colorScheme.error)
                            ))
                        }
                        item { SectionTitle(stringResource(R.string.by_fund)) }
                        if (report.byFund.isEmpty()) item { EmptyBox() }
                        items(report.byFund, key = { it.first }) { (acc, total) ->
                            com.saif.mosque.ui.components.RecordCard(
                                title = fundNames[acc] ?: acc, amount = Format.money(total))
                        }
                        item { SectionTitle(stringResource(R.string.contributions_of_month)) }
                        if (report.contributions.isEmpty()) item { EmptyBox() }
                        items(report.contributions, key = { it.id }) { c ->
                            com.saif.mosque.ui.components.RecordCard(
                                title = c.donorName.ifBlank { "—" }, supporting = Format.date(c.date),
                                amount = Format.money(c.amount), amountColor = MaterialTheme.colorScheme.primary)
                        }
                        item { SectionTitle(stringResource(R.string.expenses_of_month)) }
                        if (report.expenses.isEmpty()) item { EmptyBox() }
                        items(report.expenses, key = { it.id }) { e ->
                            com.saif.mosque.ui.components.RecordCard(
                                title = e.title, supporting = Format.date(e.date),
                                amount = Format.money(e.amount), amountColor = MaterialTheme.colorScheme.error)
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    if (showFridayAnnouncement) {
        val rep = (state as? UiState.Success)?.data
        val inc = rep?.income ?: 0.0
        val exp = rep?.expense ?: 0.0
        val fundBals = fundsState.map { it to it.balance }
        val totBal = fundBals.sumOf { it.second }
        FridayAnnouncementDialog(
            mosqueName = mosqueInfo?.name ?: "",
            lastWeekIncome = inc / 4.0,
            lastWeekExpense = exp / 4.0,
            totalBalance = totBal,
            fundBalances = fundBals,
            onDismiss = { showFridayAnnouncement = false }
        )
    }
}
