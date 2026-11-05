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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Expense
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.repository.InsufficientBalanceException
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.ConfirmDialog
import com.saif.mosque.ui.components.DatePickerField
import com.saif.mosque.ui.components.DropdownField
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.MoneyText
import com.saif.mosque.ui.components.StateBox
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.UiState
import com.saif.mosque.util.Validators
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class ExpensesViewModel(
    private val repo: LedgerRepository,
    private val queue: com.saif.mosque.data.ledger.LedgerQueue,
    private val mosqueId: String,
    private val user: AppUser
) : ViewModel() {
    val canEdit = Permissions.canEditExpenses(user.role)

    val state: StateFlow<UiState<Pair<List<Expense>, List<Fund>>>> =
        combine(repo.observeExpenses(mosqueId), repo.observeFunds(mosqueId)) { es, funds ->
            UiState.Success(es to funds) as UiState<Pair<List<Expense>, List<Fund>>>
        }
            .catch { emit(UiState.Error(it.message ?: "error")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()
    val pending = queue.store.observe(user.id, mosqueId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun retry(row: com.saif.mosque.data.ledger.PrototypeQueuedLedger) = viewModelScope.launch {
        try { queue.retry(row.draft) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { _messages.emit(Msg.Res(R.string.local_save_failed)) }
    }
    fun discard(row: com.saif.mosque.data.ledger.PrototypeQueuedLedger) = viewModelScope.launch {
        try { queue.discard(row.draft) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { _messages.emit(Msg.Res(R.string.local_save_failed)) }
    }

    fun add(e: Expense, done: (Msg?) -> Unit) = mutate(done, local = true) {
        queue.enqueue(com.saif.mosque.data.ledger.PrototypeLedgerDraft(id = java.util.UUID.randomUUID().toString(), uid = user.id, mosqueId = mosqueId,
            kind = "expenses", amountPoisha = com.saif.mosque.data.ledger.LedgerMath.poisha(e.amount, true),
            sourceFundId = e.accountId, title = e.title, category = e.category, paidTo = e.paidTo,
            receiptNo = e.receiptNo, date = e.date, note = e.note, createdAt = System.currentTimeMillis()))
    }
    fun update(old: Expense, e: Expense, done: (Msg?) -> Unit) = mutate(done) { repo.updateExpense(mosqueId, old, e, user.id) }
    fun delete(e: Expense) = mutate(null) { repo.deleteExpense(mosqueId, e) }

    private fun mutate(done: ((Msg?) -> Unit)?, local: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); done?.invoke(null); _messages.emit(Msg.Res(if (local) R.string.local_pending_saved else R.string.saved_ok)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val error = if (e is InsufficientBalanceException) Msg.Res(R.string.insufficient_balance)
                    else Msg.Text(e.message ?: "error")
                if (done != null) done(error) else _messages.emit(error)
            }
        }
    }
}

@Composable
fun ExpensesScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: ExpensesViewModel = viewModel { ExpensesViewModel(container.ledgerRepository, container.ledgerQueue, mosqueId, user) }
    val offline = com.saif.mosque.ui.components.LocalOfflineMode.current
    val canEdit = vm.canEdit && !offline
    val pending by vm.pending.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var ym by remember { mutableStateOf(Format.currentYearMonth()) }
    var fundFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var deleting by remember { mutableStateOf<Expense?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.messages) {
        vm.messages.collect { msg ->
            snackbar.showSnackbar(when (msg) {
                is Msg.Res -> context.getString(msg.resId)
                is Msg.Text -> msg.message
            })
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (vm.canEdit) FloatingActionButton(onClick = { editing = Expense(date = Format.todayIso()) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_expense))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
        com.saif.mosque.ui.components.PendingLedger(pending, !offline, onRetry = { vm.retry(it) }, onDiscard = { vm.discard(it) })
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
        StateBox(state) { (all, funds) ->
            val monthPrefix = String.format(java.util.Locale.US, "%04d-%02d", ym.first, ym.second)
            val list = all
                .filter { it.date.startsWith(monthPrefix) }
                .filter { fundFilter == null || it.accountId == fundFilter }

            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = {
                            val d = LocalDate.of(ym.first, ym.second, 1).minusMonths(1)
                            ym = d.year to d.monthValue
                        }) { Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.prev_month)) }
                        Text(Format.monthLabel(context, ym.second, ym.first),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val d = LocalDate.of(ym.first, ym.second, 1).plusMonths(1)
                            ym = d.year to d.monthValue
                        }) { Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.next_month)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = fundFilter == null, onClick = { fundFilter = null },
                            label = { Text(stringResource(R.string.all_funds)) })
                        funds.forEach { f ->
                            FilterChip(selected = fundFilter == f.id, onClick = { fundFilter = f.id },
                                label = { Text(f.code) })
                        }
                    }
                }
                if (list.isEmpty()) item { EmptyBox() }
                items(list, key = { it.id }) { e ->
                    val fundName = funds.firstOrNull { it.id == e.accountId }?.let { "${it.code} ${it.name}" } ?: "—"
                    com.saif.mosque.ui.components.RecordCard(
                        title = e.title,
                        supporting = listOf(fundName, Format.date(e.date), e.paidTo.takeIf { it.isNotBlank() }, e.receiptNo.takeIf { it.isNotBlank() }?.let { "#$it" }).filterNotNull().joinToString(" · "),
                        amount = Format.money(e.amount), amountColor = MaterialTheme.colorScheme.error,
                        onEdit = if (canEdit) ({ editing = e }) else null,
                        onDelete = if (canEdit) ({ deleting = e }) else null)
                }
            }
        }
    }
        }
    }

    editing?.let { target ->
        ExpenseFormDialog(
            initial = target,
            funds = (state as? UiState.Success)?.data?.second ?: emptyList(),
            isNew = target.id.isEmpty(),
            saving = saving, saveError = saveError,
            onDismiss = { editing = null; saveError = null },
            onSave = { e ->
                if (!saving) {
                    saving = true; saveError = null
                    val done: (Msg?) -> Unit = { result ->
                        saving = false
                        if (result == null) editing = null else saveError = when (result) {
                            is Msg.Res -> context.getString(result.resId)
                            is Msg.Text -> result.message
                        }
                    }
                    if (target.id.isEmpty()) vm.add(e, done) else vm.update(target, e.copy(id = target.id), done)
                }
            }
        )
    }
    deleting?.let { e ->
        ConfirmDialog(stringResource(R.string.confirm_delete), stringResource(R.string.delete_question),
            onConfirm = { vm.delete(e) }, onDismiss = { deleting = null }, enabled = canEdit)
    }
}

@Composable
internal fun ExpenseFormDialog(
    initial: Expense,
    funds: List<Fund>,
    isNew: Boolean,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var category by remember { mutableStateOf(initial.category) }
    var accountId by remember { mutableStateOf(initial.accountId) }
    var amount by remember { mutableStateOf(if (initial.amount > 0) initial.amount.toString() else "") }
    var date by remember { mutableStateOf(initial.date.ifBlank { Format.todayIso() }) }
    var paidTo by remember { mutableStateOf(initial.paidTo) }
    var receipt by remember { mutableStateOf(initial.receiptNo) }
    var note by remember { mutableStateOf(initial.note) }
    var attempted by remember { mutableStateOf(false) }
    val tooLong = stringResource(R.string.invitation_field_too_long)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (isNew) R.string.add_expense else R.string.edit_expense)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(
                    stringResource(R.string.expense_title), title, { title = it },
                    error = if (attempted) Validators.required(title) ?: tooLong.takeIf { title.trim().length > 1000 } else null,
                    enabled = !saving
                )
                LabeledField(
                    stringResource(R.string.category), category, { category = it },
                    error = if (attempted) tooLong.takeIf { category.trim().length > 1000 } else null,
                    enabled = !saving
                )
                DropdownField(
                    label = stringResource(R.string.account),
                    options = funds.filter { it.active || it.id == initial.accountId }.map { it.id to "${it.code} — ${it.name}" },
                    selected = accountId,
                    onSelect = { accountId = it },
                    enabled = !saving,
                    error = if (attempted && accountId.isBlank()) "required" else null
                )
                LabeledField(
                    stringResource(R.string.amount), amount, { amount = it },
                    error = if (attempted) Validators.amount(amount) else null,
                    keyboardType = KeyboardType.Decimal, enabled = !saving
                )
                DatePickerField(stringResource(R.string.date), date, { date = it }, enabled = !saving)
                LabeledField(
                    stringResource(R.string.paid_to), paidTo, { paidTo = it },
                    error = if (attempted) tooLong.takeIf { paidTo.trim().length > 1000 } else null,
                    enabled = !saving
                )
                LabeledField(
                    stringResource(R.string.receipt_no), receipt, { receipt = it },
                    error = if (attempted) tooLong.takeIf { receipt.trim().length > 1000 } else null,
                    enabled = !saving
                )
                LabeledField(
                    stringResource(R.string.note), note, { note = it }, singleLine = false,
                    error = if (attempted) tooLong.takeIf { note.trim().length > 1000 } else null,
                    enabled = !saving
                )
                saveError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.sync_check_record), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && (isNew || !com.saif.mosque.ui.components.LocalOfflineMode.current), onClick = {
                attempted = true
                if (Validators.required(title) != null || accountId.isBlank() || Validators.amount(amount) != null) return@TextButton
                if (listOf(title, category, paidTo, receipt, note).any { it.trim().length > 1000 }) return@TextButton
                onSave(initial.copy(
                    title = title.trim(), category = category.trim(), accountId = accountId,
                    amount = amount.toDouble(), date = date, paidTo = paidTo.trim(),
                    receiptNo = receipt.trim(), note = note.trim()
                ))
            }) { Text(stringResource(if (saving) R.string.saving else R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
