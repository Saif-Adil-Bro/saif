package com.saif.mosque.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.DailyReport
import com.saif.mosque.data.model.MonthlyReport
import com.saif.mosque.data.model.Transfer
import com.saif.mosque.data.model.WeeklyReport
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.CenteredProgress
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.FridayAnnouncementDialog
import com.saif.mosque.ui.components.RecordCard
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.components.StatItem
import com.saif.mosque.ui.components.StatsGrid
import com.saif.mosque.util.AuditReportPdfExporter
import com.saif.mosque.util.Format
import com.saif.mosque.util.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class WeeklyReportSummary(
    val startDate: String = "",
    val endDate: String = "",
    val income: Double = 0.0,
    val expense: Double = 0.0
)

class ReportsViewModel(
    private val repo: LedgerRepository,
    private val mosqueId: String
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0) // 0: Monthly, 1: Weekly, 2: Daily
    val selectedTab: StateFlow<Int> = _selectedTab

    private val _monthlyState = MutableStateFlow<UiState<MonthlyReport>>(UiState.Loading)
    val monthlyState: StateFlow<UiState<MonthlyReport>> = _monthlyState
    // Backward compatibility for existing observers of vm.state
    val state: StateFlow<UiState<MonthlyReport>> = _monthlyState

    private val _weeklyState = MutableStateFlow<UiState<WeeklyReport>>(UiState.Loading)
    val weeklyState: StateFlow<UiState<WeeklyReport>> = _weeklyState

    private val _dailyState = MutableStateFlow<UiState<DailyReport>>(UiState.Loading)
    val dailyState: StateFlow<UiState<DailyReport>> = _dailyState

    private val _weeklySummary = MutableStateFlow(WeeklyReportSummary())
    val weekly: StateFlow<WeeklyReportSummary> = _weeklySummary

    private val _yearMonth = MutableStateFlow(Format.currentYearMonth())
    val yearMonth: StateFlow<Pair<Int, Int>> = _yearMonth

    private val _weekAnchor = MutableStateFlow(LocalDate.now())
    val weekAnchor: StateFlow<LocalDate> = _weekAnchor

    private val _dayDate = MutableStateFlow(LocalDate.now())
    val dayDate: StateFlow<LocalDate> = _dayDate

    init {
        loadMonthly()
        loadWeekly()
        loadDaily()
    }

    fun setTab(index: Int) {
        _selectedTab.value = index
    }

    private var monthlyJob: kotlinx.coroutines.Job? = null
    fun loadMonthly() {
        monthlyJob?.cancel()
        monthlyJob = viewModelScope.launch {
            _monthlyState.value = UiState.Loading
            try {
                val (y, m) = _yearMonth.value
                val (start, end) = Format.yearMonthIso(y, m)
                val contributions = repo.contributionsInRange(mosqueId, start, end)
                val expenses = repo.expensesInRange(mosqueId, start, end)
                val transfers = repo.transfersInRange(mosqueId, start, end)

                val incomePoisha = contributions.sumOf { Math.round(it.amount * 100L) }
                val expensePoisha = expenses.sumOf { Math.round(it.amount * 100L) }
                val netPoisha = incomePoisha - expensePoisha

                val byFund = contributions.groupBy { it.accountId }
                    .map { (acc, list) -> acc to (list.sumOf { Math.round(it.amount * 100L) } / 100.0) }
                    .sortedByDescending { it.second }

                // Opening & Closing Balance calculation:
                // closing = currentTotalBalance - futureNetChange
                // opening = closing - periodNetChange
                val futureContribs = repo.contributionsAfter(mosqueId, end)
                val futureExpenses = repo.expensesAfter(mosqueId, end)
                val futureNetPoisha = futureContribs.sumOf { Math.round(it.amount * 100L) } -
                    futureExpenses.sumOf { Math.round(it.amount * 100L) }

                val currentTotalPoisha = repo.getTotalBalancePoisha(mosqueId)
                val closingPoisha = currentTotalPoisha - futureNetPoisha
                val openingPoisha = closingPoisha - netPoisha

                _monthlyState.value = UiState.Success(
                    MonthlyReport(
                        year = y,
                        month = m,
                        income = incomePoisha / 100.0,
                        expense = expensePoisha / 100.0,
                        byFund = byFund,
                        contributions = contributions.sortedWith(
                            compareByDescending<com.saif.mosque.data.model.Contribution> { it.date }
                                .thenByDescending { it.createdAt }
                        ),
                        expenses = expenses.sortedWith(
                            compareByDescending<com.saif.mosque.data.model.Expense> { it.date }
                                .thenByDescending { it.createdAt }
                        ),
                        transfers = transfers.sortedWith(
                            compareByDescending<Transfer> { it.date }
                                .thenByDescending { it.createdAt }
                        ),
                        openingBalance = openingPoisha / 100.0,
                        closingBalance = closingPoisha / 100.0
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _monthlyState.value = UiState.Error(e.message ?: "error")
            }
        }
    }

    private var weeklyJob: kotlinx.coroutines.Job? = null
    fun loadWeekly() {
        weeklyJob?.cancel()
        weeklyJob = viewModelScope.launch {
            _weeklyState.value = UiState.Loading
            try {
                val (wStart, wEnd) = Format.weekRangeIso(_weekAnchor.value)
                val contributions = repo.contributionsInRange(mosqueId, wStart, wEnd)
                val expenses = repo.expensesInRange(mosqueId, wStart, wEnd)
                val transfers = repo.transfersInRange(mosqueId, wStart, wEnd)

                val incomePoisha = contributions.sumOf { Math.round(it.amount * 100L) }
                val expensePoisha = expenses.sumOf { Math.round(it.amount * 100L) }
                val netPoisha = incomePoisha - expensePoisha

                _weeklySummary.value = WeeklyReportSummary(
                    startDate = wStart,
                    endDate = wEnd,
                    income = incomePoisha / 100.0,
                    expense = expensePoisha / 100.0
                )

                _weeklyState.value = UiState.Success(
                    WeeklyReport(
                        startDate = wStart,
                        endDate = wEnd,
                        income = incomePoisha / 100.0,
                        expense = expensePoisha / 100.0,
                        netChange = netPoisha / 100.0,
                        contributions = contributions.sortedWith(
                            compareByDescending<com.saif.mosque.data.model.Contribution> { it.date }
                                .thenByDescending { it.createdAt }
                        ),
                        expenses = expenses.sortedWith(
                            compareByDescending<com.saif.mosque.data.model.Expense> { it.date }
                                .thenByDescending { it.createdAt }
                        ),
                        transfers = transfers.sortedWith(
                            compareByDescending<Transfer> { it.date }
                                .thenByDescending { it.createdAt }
                        )
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _weeklyState.value = UiState.Error(e.message ?: "error")
            }
        }
    }

    private var dailyJob: kotlinx.coroutines.Job? = null
    fun loadDaily() {
        dailyJob?.cancel()
        dailyJob = viewModelScope.launch {
            _dailyState.value = UiState.Loading
            try {
                val dayIso = _dayDate.value.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val contributions = repo.contributionsInRange(mosqueId, dayIso, dayIso)
                val expenses = repo.expensesInRange(mosqueId, dayIso, dayIso)
                val transfers = repo.transfersInRange(mosqueId, dayIso, dayIso)

                val incomePoisha = contributions.sumOf { Math.round(it.amount * 100L) }
                val expensePoisha = expenses.sumOf { Math.round(it.amount * 100L) }
                val netPoisha = incomePoisha - expensePoisha

                _dailyState.value = UiState.Success(
                    DailyReport(
                        dateIso = dayIso,
                        income = incomePoisha / 100.0,
                        expense = expensePoisha / 100.0,
                        netChange = netPoisha / 100.0,
                        contributions = contributions.sortedWith(
                            compareByDescending<com.saif.mosque.data.model.Contribution> { it.createdAt }
                        ),
                        expenses = expenses.sortedWith(
                            compareByDescending<com.saif.mosque.data.model.Expense> { it.createdAt }
                        ),
                        transfers = transfers.sortedWith(
                            compareByDescending<Transfer> { it.createdAt }
                        )
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _dailyState.value = UiState.Error(e.message ?: "error")
            }
        }
    }

    // Monthly navigation
    fun prevMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).minusMonths(1)
        _yearMonth.value = d.year to d.monthValue
        loadMonthly()
    }

    fun nextMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).plusMonths(1)
        _yearMonth.value = d.year to d.monthValue
        loadMonthly()
    }

    // Weekly navigation
    fun prevWeek() {
        _weekAnchor.value = _weekAnchor.value.minusDays(7)
        loadWeekly()
    }

    fun nextWeek() {
        _weekAnchor.value = _weekAnchor.value.plusDays(7)
        loadWeekly()
    }

    fun resetWeekToToday() {
        _weekAnchor.value = LocalDate.now()
        loadWeekly()
    }

    // Daily navigation
    fun prevDay() {
        _dayDate.value = _dayDate.value.minusDays(1)
        loadDaily()
    }

    fun nextDay() {
        _dayDate.value = _dayDate.value.plusDays(1)
        loadDaily()
    }

    fun setDayDate(date: LocalDate) {
        _dayDate.value = date
        loadDaily()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: ReportsViewModel = viewModel { ReportsViewModel(container.ledgerRepository, mosqueId) }
    val context = LocalContext.current

    val selectedTab by vm.selectedTab.collectAsStateWithLifecycle()
    val monthlyState by vm.monthlyState.collectAsStateWithLifecycle()
    val weeklyState by vm.weeklyState.collectAsStateWithLifecycle()
    val dailyState by vm.dailyState.collectAsStateWithLifecycle()

    val ym by vm.yearMonth.collectAsStateWithLifecycle()
    val weekAnchor by vm.weekAnchor.collectAsStateWithLifecycle()
    val dayDate by vm.dayDate.collectAsStateWithLifecycle()

    val fundsFlow = remember(mosqueId) {
        container.ledgerRepository.observeFunds(mosqueId).catch { emit(emptyList()) }
    }
    val fundsState by fundsFlow.collectAsState(initial = emptyList())
    val fundNames = remember(fundsState) {
        fundsState.associateBy({ it.id }, { "${it.code} ${it.name}" })
    }

    val mosqueFlow = remember(mosqueId) {
        container.ledgerRepository.observeMosque(mosqueId).catch { emit(null) }
    }
    val mosqueInfo by mosqueFlow.collectAsState(initial = null)

    var showFridayAnnouncement by remember { mutableStateOf(false) }
    var showDatePickerDialog by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Report Period Mode Filter Chips (Monthly / Weekly / Daily)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { vm.setTab(0) },
                    label = { Text(stringResource(R.string.monthly_tab)) },
                    leadingIcon = {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { vm.setTab(1) },
                    label = { Text(stringResource(R.string.weekly_tab)) },
                    leadingIcon = {
                        Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { vm.setTab(2) },
                    label = { Text(stringResource(R.string.daily_tab)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            // 2. Navigation & Actions Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (selectedTab) {
                    0 -> { // Monthly
                        IconButton(onClick = { vm.prevMonth() }) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.prev_month))
                        }
                        Text(
                            Format.monthLabel(context, ym.second, ym.first),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.nextMonth() }) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.next_month))
                        }
                        IconButton(onClick = {
                            (monthlyState as? UiState.Success)?.data?.let { r ->
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
                                    expenses = r.expenses,
                                    transfers = r.transfers,
                                    openingBalance = r.openingBalance,
                                    closingBalance = r.closingBalance,
                                    reportTitle = context.getString(R.string.monthly_report_title)
                                )
                            }
                        }) {
                            Icon(Icons.Filled.Print, contentDescription = "অডিট রিপোর্ট প্রিন্ট")
                        }
                    }

                    1 -> { // Weekly
                        IconButton(onClick = { vm.prevWeek() }) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "পূর্ববর্তী সপ্তাহ")
                        }
                        val (wStart, wEnd) = Format.weekRangeIso(weekAnchor)
                        Column(Modifier.weight(1f)) {
                            Text("সাপ্তাহিক হিসাব", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                Format.dateRangeBengali(wStart, wEnd),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.nextWeek() }) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "পরবর্তী সপ্তাহ")
                        }
                        FilledTonalIconButton(onClick = { showFridayAnnouncement = true }) {
                            Icon(Icons.Filled.Campaign, contentDescription = "খুতবার ঘোষণা")
                        }
                        IconButton(onClick = {
                            (weeklyState as? UiState.Success)?.data?.let { r ->
                                val fundBals = fundsState.map { it to it.balance }
                                val wRange = Format.dateRangeBengali(r.startDate, r.endDate)
                                AuditReportPdfExporter.printAuditReport(
                                    context = context,
                                    mosque = mosqueInfo,
                                    periodLabel = wRange,
                                    totalIncome = r.income,
                                    totalExpense = r.expense,
                                    fundBalances = fundBals,
                                    contributions = r.contributions,
                                    expenses = r.expenses,
                                    transfers = r.transfers,
                                    reportTitle = context.getString(R.string.weekly_report_title)
                                )
                            }
                        }) {
                            Icon(Icons.Filled.Print, contentDescription = "সাপ্তাহিক রিপোর্ট প্রিন্ট")
                        }
                    }

                    2 -> { // Daily
                        IconButton(onClick = { vm.prevDay() }) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "পূর্ববর্তী দিন")
                        }
                        val dayIso = dayDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { showDatePickerDialog = true }
                        ) {
                            Text(Format.date(dayIso), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                Format.dayOfWeekBengali(dayIso).ifBlank { "আজকের দিন" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.nextDay() }) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "পরবর্তী দিন")
                        }
                        IconButton(onClick = { showDatePickerDialog = true }) {
                            Icon(Icons.Filled.Today, contentDescription = stringResource(R.string.pick_date))
                        }
                        IconButton(onClick = {
                            (dailyState as? UiState.Success)?.data?.let { r ->
                                val fundBals = fundsState.map { it to it.balance }
                                val dLabel = "${Format.date(r.dateIso)} (${Format.dayOfWeekBengali(r.dateIso)})"
                                AuditReportPdfExporter.printAuditReport(
                                    context = context,
                                    mosque = mosqueInfo,
                                    periodLabel = dLabel,
                                    totalIncome = r.income,
                                    totalExpense = r.expense,
                                    fundBalances = fundBals,
                                    contributions = r.contributions,
                                    expenses = r.expenses,
                                    transfers = r.transfers,
                                    reportTitle = context.getString(R.string.daily_report_title)
                                )
                            }
                        }) {
                            Icon(Icons.Filled.Print, contentDescription = "দৈনিক রিপোর্ট প্রিন্ট")
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // 3. Tab Contents
            when (selectedTab) {
                0 -> { // Monthly View
                    when (val s = monthlyState) {
                        is UiState.Loading -> CenteredProgress()
                        is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        is UiState.Success -> {
                            val report = s.data
                            val net = report.income - report.expense
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item {
                                    StatsGrid(
                                        listOf(
                                            StatItem(stringResource(R.string.income), Format.money(report.income)),
                                            StatItem(stringResource(R.string.expense), Format.money(report.expense), MaterialTheme.colorScheme.error),
                                            StatItem(stringResource(R.string.net_balance), Format.money(net), if (net >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
                                            StatItem(stringResource(R.string.opening_balance), Format.money(report.openingBalance)),
                                            StatItem(stringResource(R.string.closing_balance), Format.money(report.closingBalance))
                                        )
                                    )
                                }

                                item { SectionTitle(stringResource(R.string.by_fund)) }
                                if (report.byFund.isEmpty()) item { EmptyBox() }
                                items(report.byFund, key = { it.first }) { (acc, total) ->
                                    RecordCard(
                                        title = fundNames[acc] ?: acc,
                                        amount = Format.money(total)
                                    )
                                }

                                if (report.transfers.isNotEmpty()) {
                                    item { SectionTitle(stringResource(R.string.transfers_of_period)) }
                                    items(report.transfers, key = { it.id }) { t ->
                                        val from = fundNames[t.fromAccountId] ?: t.fromAccountId
                                        val to = fundNames[t.toAccountId] ?: t.toAccountId
                                        RecordCard(
                                            title = "$from → $to",
                                            supporting = "${Format.date(t.date)}${if (t.note.isNotBlank()) " · ${t.note}" else ""}",
                                            amount = Format.money(t.amount),
                                            amountColor = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }

                                item { SectionTitle(stringResource(R.string.contributions_of_month)) }
                                if (report.contributions.isEmpty()) item { EmptyBox() }
                                items(report.contributions, key = { it.id }) { c ->
                                    RecordCard(
                                        title = c.donorName.ifBlank { "—" },
                                        supporting = Format.date(c.date) + if (c.receiptNo.isNotBlank()) " · #${c.receiptNo}" else "",
                                        amount = Format.money(c.amount),
                                        amountColor = MaterialTheme.colorScheme.primary
                                    )
                                }

                                item { SectionTitle(stringResource(R.string.expenses_of_month)) }
                                if (report.expenses.isEmpty()) item { EmptyBox() }
                                items(report.expenses, key = { it.id }) { e ->
                                    RecordCard(
                                        title = e.title,
                                        supporting = Format.date(e.date) + if (e.receiptNo.isNotBlank()) " · #${e.receiptNo}" else "",
                                        amount = Format.money(e.amount),
                                        amountColor = MaterialTheme.colorScheme.error
                                    )
                                }
                                item { Spacer(Modifier.height(24.dp)) }
                            }
                        }
                    }
                }

                1 -> { // Weekly View
                    when (val s = weeklyState) {
                        is UiState.Loading -> CenteredProgress()
                        is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        is UiState.Success -> {
                            val report = s.data
                            val net = report.income - report.expense
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item {
                                    StatsGrid(
                                        listOf(
                                            StatItem(stringResource(R.string.income), Format.money(report.income)),
                                            StatItem(stringResource(R.string.expense), Format.money(report.expense), MaterialTheme.colorScheme.error),
                                            StatItem(stringResource(R.string.net_balance), Format.money(net), if (net >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                        )
                                    )
                                }

                                // Quick Jummah Announcement Trigger Banner
                                item {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showFridayAnnouncement = true },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Campaign,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "জুমার খুতবার ঘোষণা কার্ড",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    "এই সপ্তাহের আয়-ব্যয় নিয়ে ১ পাতার প্রস্তুত বক্তব্য দেখুন",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                "দেখুন",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }

                                item { SectionTitle("সপ্তাহের মোট আদায় (${Format.bn(report.contributions.size)} টি)") }
                                if (report.contributions.isEmpty()) item { EmptyBox() }
                                items(report.contributions, key = { it.id }) { c ->
                                    RecordCard(
                                        title = c.donorName.ifBlank { "—" },
                                        supporting = Format.date(c.date) + if (c.receiptNo.isNotBlank()) " · #${c.receiptNo}" else "",
                                        amount = Format.money(c.amount),
                                        amountColor = MaterialTheme.colorScheme.primary
                                    )
                                }

                                item { SectionTitle("সপ্তাহের মোট ব্যয় (${Format.bn(report.expenses.size)} টি)") }
                                if (report.expenses.isEmpty()) item { EmptyBox() }
                                items(report.expenses, key = { it.id }) { e ->
                                    RecordCard(
                                        title = e.title,
                                        supporting = Format.date(e.date) + if (e.receiptNo.isNotBlank()) " · #${e.receiptNo}" else "",
                                        amount = Format.money(e.amount),
                                        amountColor = MaterialTheme.colorScheme.error
                                    )
                                }

                                if (report.transfers.isNotEmpty()) {
                                    item { SectionTitle("সপ্তাহের তহবিল স্থানান্তর (${Format.bn(report.transfers.size)} টি)") }
                                    items(report.transfers, key = { it.id }) { t ->
                                        val from = fundNames[t.fromAccountId] ?: t.fromAccountId
                                        val to = fundNames[t.toAccountId] ?: t.toAccountId
                                        RecordCard(
                                            title = "$from → $to",
                                            supporting = "${Format.date(t.date)}${if (t.note.isNotBlank()) " · ${t.note}" else ""}",
                                            amount = Format.money(t.amount),
                                            amountColor = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                item { Spacer(Modifier.height(24.dp)) }
                            }
                        }
                    }
                }

                2 -> { // Daily View
                    when (val s = dailyState) {
                        is UiState.Loading -> CenteredProgress()
                        is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        is UiState.Success -> {
                            val report = s.data
                            val net = report.income - report.expense
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item {
                                    StatsGrid(
                                        listOf(
                                            StatItem(stringResource(R.string.today_collection), Format.money(report.income)),
                                            StatItem(stringResource(R.string.today_expense), Format.money(report.expense), MaterialTheme.colorScheme.error),
                                            StatItem(stringResource(R.string.net_balance), Format.money(net), if (net >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                        )
                                    )
                                }

                                item { SectionTitle("দিনের মোট আদায় (${Format.bn(report.contributions.size)} টি)") }
                                if (report.contributions.isEmpty()) item { EmptyBox("এই দিনে কোনো আদায় নেই") }
                                items(report.contributions, key = { it.id }) { c ->
                                    val fund = fundNames[c.accountId] ?: c.accountId
                                    RecordCard(
                                        title = c.donorName.ifBlank { "—" },
                                        supporting = "$fund${if (c.receiptNo.isNotBlank()) " · #${c.receiptNo}" else ""}",
                                        amount = Format.money(c.amount),
                                        amountColor = MaterialTheme.colorScheme.primary
                                    )
                                }

                                item { SectionTitle("দিনের মোট ব্যয় (${Format.bn(report.expenses.size)} টি)") }
                                if (report.expenses.isEmpty()) item { EmptyBox("এই দিনে কোনো ব্যয় নেই") }
                                items(report.expenses, key = { it.id }) { e ->
                                    RecordCard(
                                        title = e.title,
                                        supporting = "${e.paidTo.ifBlank { "ব্যয়" }}${if (e.receiptNo.isNotBlank()) " · #${e.receiptNo}" else ""}",
                                        amount = Format.money(e.amount),
                                        amountColor = MaterialTheme.colorScheme.error
                                    )
                                }

                                if (report.transfers.isNotEmpty()) {
                                    item { SectionTitle("দিনের তহবিল স্থানান্তর (${Format.bn(report.transfers.size)} টি)") }
                                    items(report.transfers, key = { it.id }) { t ->
                                        val from = fundNames[t.fromAccountId] ?: t.fromAccountId
                                        val to = fundNames[t.toAccountId] ?: t.toAccountId
                                        RecordCard(
                                            title = "$from → $to",
                                            supporting = if (t.note.isNotBlank()) t.note else "তহবিল স্থানান্তর",
                                            amount = Format.money(t.amount),
                                            amountColor = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                                item { Spacer(Modifier.height(24.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    // Friday Announcement Dialog (Using the current active weekly report)
    if (showFridayAnnouncement) {
        val fundBals = fundsState.map { it to it.balance }
        val totBal = fundBals.sumOf { it.second }
        val wReport = (weeklyState as? UiState.Success)?.data
        val (fallbackStart, fallbackEnd) = remember(weekAnchor) { Format.weekRangeIso(weekAnchor) }

        FridayAnnouncementDialog(
            mosqueName = mosqueInfo?.name ?: "",
            startDate = wReport?.startDate ?: fallbackStart,
            endDate = wReport?.endDate ?: fallbackEnd,
            lastWeekIncome = wReport?.income ?: 0.0,
            lastWeekExpense = wReport?.expense ?: 0.0,
            totalBalance = totBal,
            fundBalances = fundBals,
            onDismiss = { showFridayAnnouncement = false }
        )
    }

    // DatePicker Dialog for Daily Tab
    if (showDatePickerDialog) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Format.isoToEpochMilli(dayDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
        )
        DatePickerDialog(
            onDismissRequest = { showDatePickerDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePickerDialog = false
                    pickerState.selectedDateMillis?.let {
                        val selectedIso = Format.epochMilliToIso(it)
                        vm.setDayDate(LocalDate.parse(selectedIso))
                    }
                }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
