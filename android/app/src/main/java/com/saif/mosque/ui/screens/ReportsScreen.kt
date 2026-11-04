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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.MonthlyReport
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.MoneyText
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.components.StatCard
import com.saif.mosque.util.Format
import com.saif.mosque.util.UiState
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

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val (y, m) = _yearMonth.value
                val (start, end) = Format.yearMonthIso(y, m)
                val contributions = repo.contributionsInRange(mosqueId, start, end)
                val expenses = repo.expensesInRange(mosqueId, start, end)
                val byFund = contributions.groupBy { it.accountId }
                    .map { (acc, list) -> acc to list.sumOf { it.amount } }
                    .sortedByDescending { it.second }
                _state.value = UiState.Success(MonthlyReport(
                    year = y, month = m,
                    income = contributions.sumOf { it.amount },
                    expense = expenses.sumOf { it.amount },
                    byFund = byFund,
                    contributions = contributions.sortedByDescending { it.date },
                    expenses = expenses.sortedByDescending { it.date }
                ))
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
    val fundsState by container.ledgerRepository.observeFunds(mosqueId)
        .let { flow -> flow.collectAsStateWithLifecycleSafe() }

    Scaffold { inner ->
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatCard(stringResource(R.string.income), Format.money(report.income),
                                    modifier = Modifier.weight(1f),
                                    valueColor = MaterialTheme.colorScheme.primary)
                                StatCard(stringResource(R.string.expense), Format.money(report.expense),
                                    modifier = Modifier.weight(1f),
                                    valueColor = MaterialTheme.colorScheme.error)
                            }
                        }
                        item { SectionTitle(stringResource(R.string.by_fund)) }
                        if (report.byFund.isEmpty()) item { EmptyBox() }
                        items(report.byFund, key = { it.first }) { (acc, total) ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(fundNames[acc] ?: acc, Modifier.weight(1f))
                                    MoneyText(total)
                                }
                            }
                        }
                        item { SectionTitle(stringResource(R.string.contributions_of_month)) }
                        if (report.contributions.isEmpty()) item { EmptyBox() }
                        items(report.contributions, key = { it.id }) { c ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.donorName.ifBlank { "—" })
                                        Text(Format.date(c.date), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    MoneyText(c.amount, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        item { SectionTitle(stringResource(R.string.expenses_of_month)) }
                        if (report.expenses.isEmpty()) item { EmptyBox() }
                        items(report.expenses, key = { it.id }) { e ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.title)
                                        Text(Format.date(e.date), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    MoneyText(e.amount, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

// small helper: collect a flow as state without lifecycle import gymnastics
@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateWithLifecycleSafe(): T {
    return androidx.lifecycle.compose.collectAsStateWithLifecycle().value
}
