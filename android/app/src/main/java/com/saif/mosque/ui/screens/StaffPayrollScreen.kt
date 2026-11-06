package com.saif.mosque.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.ledger.LedgerMath
import com.saif.mosque.data.ledger.LedgerQueue
import com.saif.mosque.data.ledger.PrototypeLedgerDraft
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Expense
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.cleanDisplayDesignation
import com.saif.mosque.data.model.isStaffMember
import com.saif.mosque.data.model.salaryAmount
import com.saif.mosque.data.model.withSalaryAmount
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.ReceiptSlipDialog
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.ReceiptData
import com.saif.mosque.util.UiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

data class StaffPayrollItem(
    val user: AppUser,
    val designatedSalary: Double,
    val isPaid: Boolean,
    val paidExpense: Expense? = null
)

class StaffPayrollViewModel(
    private val repo: LedgerRepository,
    private val queue: LedgerQueue,
    private val mosqueId: String,
    val currentUser: AppUser
) : ViewModel() {
    val canEdit = Permissions.canEditExpenses(currentUser.role)

    private val _yearMonth = MutableStateFlow(Format.currentYearMonth())
    val yearMonth: StateFlow<Pair<Int, Int>> = _yearMonth

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    private val membersFlow = repo.observeMembers(mosqueId).catch { emit(emptyList()) }
    val fundsFlow = repo.observeFunds(mosqueId).catch { emit(emptyList()) }

    val state: StateFlow<UiState<Pair<List<StaffPayrollItem>, List<Fund>>>> =
        combine(_yearMonth, membersFlow, fundsFlow) { ym, members, funds ->
            val (y, m) = ym
            val (start, end) = Format.yearMonthIso(y, m)
            val monthExpenses = try {
                repo.expensesInRange(mosqueId, start, end)
            } catch (_: Exception) {
                emptyList()
            }

            val staffUsers = members.filter { it.active && it.isStaffMember() }
            val items = staffUsers.map { staff ->
                val designated = staff.salaryAmount() ?: 0.0
                val matchingExpense = monthExpenses.find { e ->
                    e.paidTo.trim().equals(staff.name.trim(), ignoreCase = true) ||
                            (e.title.contains(staff.name.trim(), ignoreCase = true) && e.category.contains("বেতন", ignoreCase = true))
                }
                StaffPayrollItem(
                    user = staff,
                    designatedSalary = designated,
                    isPaid = matchingExpense != null,
                    paidExpense = matchingExpense
                )
            }
            UiState.Success(items to funds) as UiState<Pair<List<StaffPayrollItem>, List<Fund>>>
        }.catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    fun prevMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).minusMonths(1)
        _yearMonth.value = d.year to d.monthValue
    }

    fun nextMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).plusMonths(1)
        _yearMonth.value = d.year to d.monthValue
    }

    fun updateStaffSalary(staffUser: AppUser, newSalary: Double, onDone: () -> Unit) = viewModelScope.launch {
        try {
            val updated = staffUser.withSalaryAmount(newSalary)
            repo.updateMember(mosqueId, staffUser, updated, currentUser)
            onDone()
            _messages.emit(Msg.Res(R.string.saved_ok))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(Msg.Text(e.message ?: "error"))
        }
    }

    fun paySalary(
        context: android.content.Context,
        staff: AppUser,
        netPayable: Double,
        fundId: String,
        date: String,
        voucherNo: String,
        note: String,
        onDone: (ReceiptData) -> Unit
    ) = viewModelScope.launch {
        try {
            val (y, m) = _yearMonth.value
            val monthLabel = Format.monthLabel(context, m, y)
            val cleanName = staff.name.trim()

            val draft = PrototypeLedgerDraft(
                id = UUID.randomUUID().toString(),
                uid = currentUser.id,
                mosqueId = mosqueId,
                kind = "expenses",
                amountPoisha = LedgerMath.poisha(netPayable, true),
                sourceFundId = fundId,
                title = "বেতন: $cleanName ($monthLabel)",
                category = "বেতন ও সম্মানী",
                paidTo = cleanName,
                receiptNo = voucherNo.trim(),
                date = date.trim(),
                note = note.trim(),
                createdAt = System.currentTimeMillis()
            )

            queue.enqueue(draft)

            val receipt = ReceiptData(
                mosqueName = "",
                receiptNo = voucherNo.trim(),
                donorName = cleanName,
                donorPhone = staff.phone,
                amount = netPayable,
                date = date.trim(),
                fundName = "বেতন ফান্ড",
                purpose = "মাসিক বেতন/সম্মানী ($monthLabel)",
                receivedBy = currentUser.name
            )

            onDone(receipt)
            _messages.emit(Msg.Res(R.string.local_pending_saved))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(Msg.Text(e.message ?: "error"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffPayrollScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val vm: StaffPayrollViewModel = viewModel {
        StaffPayrollViewModel(
            container.ledgerRepository,
            container.ledgerQueue,
            mosqueId,
            user
        )
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val ym by vm.yearMonth.collectAsStateWithLifecycle()

    var payingStaff by remember { mutableStateOf<StaffPayrollItem?>(null) }
    var editingSalaryFor by remember { mutableStateOf<AppUser?>(null) }
    var activeReceiptData by remember { mutableStateOf<ReceiptData?>(null) }

    LaunchedEffect(vm.messages) {
        vm.messages.collect { msg ->
            snackbar.showSnackbar(
                when (msg) {
                    is Msg.Res -> context.getString(msg.resId)
                    is Msg.Text -> msg.message
                }
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) }
    ) { innerPadding ->
        when (val s = state) {
            is UiState.Loading -> com.saif.mosque.ui.components.CenteredProgress()
            is UiState.Error -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                val (items, funds) = s.data
                val totalDesignated = items.sumOf { it.designatedSalary }
                val totalPaid = items.filter { it.isPaid }.sumOf { it.paidExpense?.amount ?: it.designatedSalary }
                val paidCount = items.count { it.isPaid }
                val pendingCount = items.size - paidCount

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 68.dp)
                ) {
                    // 1. Month navigation
                    item(key = "payroll_month_header") {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.prevMonth() }) {
                                    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.prev_month))
                                }
                                Text(
                                    Format.monthLabel(context, ym.second, ym.first),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { vm.nextMonth() }) {
                                    Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.next_month))
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    "স্টাফ বেতন শীট",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    // 2. KPI Stats
                    item(key = "payroll_kpi_stats") {
                        com.saif.mosque.ui.components.StatsGrid(
                            listOf(
                                com.saif.mosque.ui.components.StatItem("মোট কর্মী", "${items.size} জন"),
                                com.saif.mosque.ui.components.StatItem("মোট বেতন বাজেট", Format.money(totalDesignated)),
                                com.saif.mosque.ui.components.StatItem("পরিশোধিত", Format.money(totalPaid), MaterialTheme.colorScheme.primary),
                                com.saif.mosque.ui.components.StatItem("বকেয়া কর্মী", "$pendingCount জন", if (pendingCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            )
                        )
                    }

                    // 3. Staff items
                    if (items.isEmpty()) {
                        item(key = "empty_staff_box") {
                            EmptyBox("কোন স্টাফ বা ইমাম-মুয়াজ্জিন সদস্য পাওয়া যায়নি। মেম্বার্স মেনু থেকে পদবি দিয়ে সদস্য যুক্ত করুন।")
                        }
                    } else {
                        items(items, key = { it.user.id }) { item ->
                            StaffPayrollCard(
                                item = item,
                                canEdit = vm.canEdit,
                                onPaySalary = { payingStaff = item },
                                onEditSalary = { editingSalaryFor = item.user },
                                onViewReceipt = { exp ->
                                    activeReceiptData = ReceiptData(
                                        mosqueName = "",
                                        receiptNo = exp.receiptNo,
                                        donorName = exp.paidTo,
                                        donorPhone = item.user.phone,
                                        amount = exp.amount,
                                        date = exp.date,
                                        fundName = "বেতন ফান্ড",
                                        purpose = exp.title,
                                        receivedBy = exp.createdBy
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Pay Salary Dialog
    payingStaff?.let { target ->
        val funds = (state as? UiState.Success)?.data?.second ?: emptyList()
        PaySalaryDialog(
            staff = target.user,
            monthLabel = Format.monthLabel(context, ym.second, ym.first),
            baseSalary = target.designatedSalary,
            funds = funds,
            onDismiss = { payingStaff = null },
            onConfirm = { netPayable, fundId, date, voucherNo, note ->
                vm.paySalary(
                    context = context,
                    staff = target.user,
                    netPayable = netPayable,
                    fundId = fundId,
                    date = date,
                    voucherNo = voucherNo,
                    note = note
                ) { receipt ->
                    payingStaff = null
                    activeReceiptData = receipt
                }
            }
        )
    }

    // Edit Staff Salary Dialog
    editingSalaryFor?.let { staffUser ->
        EditStaffSalaryDialog(
            user = staffUser,
            currentSalary = staffUser.salaryAmount() ?: 0.0,
            onDismiss = { editingSalaryFor = null },
            onSave = { newSalary ->
                vm.updateStaffSalary(staffUser, newSalary) {
                    editingSalaryFor = null
                }
            }
        )
    }

    // Receipt Slip Dialog
    activeReceiptData?.let { receipt ->
        ReceiptSlipDialog(
            receipt = receipt,
            onDismiss = { activeReceiptData = null }
        )
    }
}

@Composable
private fun StaffPayrollCard(
    item: StaffPayrollItem,
    canEdit: Boolean,
    onPaySalary: () -> Unit,
    onEditSalary: () -> Unit,
    onViewReceipt: (Expense) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (item.isPaid) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            item.user.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            Icons.Filled.Badge,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        item.user.cleanDisplayDesignation().ifBlank { roleLabel(item.user.role) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Paid or Pending Badge
                if (item.isPaid) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("পরিশোধিত", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            "বকেয়া",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Designated Salary Row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "নির্ধারিত সম্মানী: ${Format.money(item.designatedSalary)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (canEdit) {
                        IconButton(onClick = onEditSalary, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Edit, contentDescription = "বেতন পরিবর্তন", modifier = Modifier.size(14.dp))
                        }
                    }
                }

                if (item.isPaid && item.paidExpense != null) {
                    Text(
                        "প্রদত্ত: ${Format.money(item.paidExpense.amount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Actions
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isPaid && item.paidExpense != null) {
                    FilledTonalButton(
                        onClick = { onViewReceipt(item.paidExpense) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("পে-স্লিপ দেখুন", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (canEdit) {
                    Button(
                        onClick = onPaySalary,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("বেতন পরিশোধ", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaySalaryDialog(
    staff: AppUser,
    monthLabel: String,
    baseSalary: Double,
    funds: List<Fund>,
    onDismiss: () -> Unit,
    onConfirm: (netPayable: Double, fundId: String, date: String, voucherNo: String, note: String) -> Unit
) {
    var baseText by remember { mutableStateOf(if (baseSalary > 0) baseSalary.toLong().toString() else "") }
    var bonusText by remember { mutableStateOf("0") }
    var deductionText by remember { mutableStateOf("0") }
    var selectedFundId by remember { mutableStateOf(funds.find { it.code == "GEN" }?.id ?: funds.firstOrNull()?.id ?: "") }
    var date by remember { mutableStateOf(Format.todayIso()) }
    var voucherNo by remember { mutableStateOf("PAY-${Format.todayIso().replace("-", "")}") }
    var note by remember { mutableStateOf("") }
    var fundDropdownExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val base = baseText.toDoubleOrNull() ?: 0.0
    val bonus = bonusText.toDoubleOrNull() ?: 0.0
    val deduction = deductionText.toDoubleOrNull() ?: 0.0
    val netPayable = (base + bonus - deduction).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text("বেতন পরিশোধ: ${staff.name}", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("মাস: $monthLabel", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

                // Calculation Preview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("মোট প্রদেয় বেতন (Net Payable)", style = MaterialTheme.typography.labelSmall)
                        Text(Format.money(netPayable), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("(মূল: ${Format.money(base)} + বোনাস: ${Format.money(bonus)} - কর্তন: ${Format.money(deduction)})", style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedTextField(
                    value = baseText,
                    onValueChange = { baseText = it },
                    label = { Text("মূল বেতন / সম্মানী (৳) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bonusText,
                        onValueChange = { bonusText = it },
                        label = { Text("বোনাস (৳)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = deductionText,
                        onValueChange = { deductionText = it },
                        label = { Text("অগ্রিম/কর্তন (৳)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Fund picker
                ExposedDropdownMenuBox(
                    expanded = fundDropdownExpanded,
                    onExpandedChange = { fundDropdownExpanded = !fundDropdownExpanded }
                ) {
                    val current = funds.find { it.id == selectedFundId }
                    OutlinedTextField(
                        value = current?.let { "${it.code} - ${it.name}" } ?: "পরিশোধ ফান্ড নির্বাচন",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("পরিশোধ ফান্ড *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fundDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = fundDropdownExpanded,
                        onDismissRequest = { fundDropdownExpanded = false }
                    ) {
                        funds.filter { it.active }.forEach { f ->
                            DropdownMenuItem(
                                text = { Text("${f.code} - ${f.name}") },
                                onClick = {
                                    selectedFundId = f.id
                                    fundDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("পরিশোধের তারিখ (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = voucherNo,
                    onValueChange = { voucherNo = it },
                    label = { Text("ভাউচার / রশিদ নং") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("মন্তব্য (ঐচ্ছিক)") },
                    placeholder = { Text("যেমন: ঈদুল ফিতরের বোনাস সহ") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    if (netPayable <= 0.0) {
                        errorMessage = "প্রদেয় বেতন শূন্য বা সঠিক নয়"
                        return@Button
                    }
                    if (selectedFundId.isBlank()) {
                        errorMessage = "পরিশোধ ফান্ড নির্বাচন করুন"
                        return@Button
                    }
                    isSubmitting = true
                    val fullNote = buildString {
                        append("মূল: $base, বোনাস: $bonus, কর্তন: $deduction")
                        if (note.isNotBlank()) append(" | ${note.trim()}")
                    }
                    onConfirm(netPayable, selectedFundId, date.trim(), voucherNo.trim(), fullNote)
                }
            ) {
                Text("বেতন পরিশোধ নিশ্চিত করুন")
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun EditStaffSalaryDialog(
    user: AppUser,
    currentSalary: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var salaryText by remember {
        mutableStateOf(if (currentSalary > 0) currentSalary.toLong().toString() else "")
    }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("মাসিক সম্মানী/বেতন নির্ধারণ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("কর্মী: ${user.name}", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = salaryText,
                    onValueChange = { salaryText = it },
                    label = { Text("মাসিক নির্ধারিত বেতন (৳) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val amt = salaryText.toDoubleOrNull() ?: 0.0
                    isSubmitting = true
                    onSave(amt)
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
