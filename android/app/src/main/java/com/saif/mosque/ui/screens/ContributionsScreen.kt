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
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.saif.mosque.data.model.Contribution
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.data.ledger.LedgerMath
import com.saif.mosque.data.ledger.PrototypeLedgerDraft
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.ConfirmDialog
import com.saif.mosque.ui.components.DatePickerField
import com.saif.mosque.ui.components.DropdownField
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.FullWidthDialogProperties
import com.saif.mosque.ui.components.fullWidthDialog
import com.saif.mosque.ui.components.FridayCashCounterDialog
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.MoneyText
import com.saif.mosque.ui.components.ReceiptSlipDialog
import com.saif.mosque.ui.components.StateBox
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.ReceiptData
import com.saif.mosque.util.ReceiptHelper
import com.saif.mosque.util.UiState
import com.saif.mosque.util.Validators
import java.util.UUID
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

class ContributionsViewModel(
    private val repo: LedgerRepository,
    private val queue: com.saif.mosque.data.ledger.LedgerQueue,
    private val mosqueId: String,
    private val user: AppUser
) : ViewModel() {
    val canEdit = Permissions.canEditContributions(user.role)

    val state: StateFlow<UiState<Pair<List<Contribution>, List<Fund>>>> =
        combine(
            repo.observeContributions(mosqueId),
            repo.observeFunds(mosqueId)
        ) { cs, funds -> UiState.Success(cs to funds) as UiState<Pair<List<Contribution>, List<Fund>>> }
            .catch { emit(UiState.Error(it.message ?: "error")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _donors = MutableSharedFlow<List<Donor>>(replay = 1)
    val donors: StateFlow<List<Donor>> = repo.observeDonors(mosqueId)
        .map { it.filter { d -> d.active } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun add(c: Contribution, done: (Msg?) -> Unit) = mutate(done, local = true) {
        queue.enqueue(com.saif.mosque.data.ledger.PrototypeLedgerDraft(id = java.util.UUID.randomUUID().toString(), uid = user.id, mosqueId = mosqueId,
            kind = "contributions", amountPoisha = com.saif.mosque.data.ledger.LedgerMath.poisha(c.amount, true),
            sourceFundId = c.accountId, donorId = c.donorId, donorName = c.donorName,
            receiptNo = c.receiptNo, date = c.date, note = c.note, createdAt = System.currentTimeMillis()))
    }
    fun update(old: Contribution, new: Contribution, done: (Msg?) -> Unit) = mutate(done) { repo.updateContribution(mosqueId, old, new, user.id) }
    fun delete(c: Contribution) = mutate(null) { repo.deleteContribution(mosqueId, c) }

    private fun mutate(done: ((Msg?) -> Unit)?, local: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); done?.invoke(null); _messages.emit(Msg.Res(if (local) R.string.local_pending_saved else R.string.saved_ok)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val error = Msg.Text(e.message ?: "error")
                if (done != null) done(error) else _messages.emit(error)
            }
        }
    }
}

@Composable
fun ContributionsScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: ContributionsViewModel = viewModel { ContributionsViewModel(container.ledgerRepository, container.ledgerQueue, mosqueId, user) }
    val offline = com.saif.mosque.ui.components.LocalOfflineMode.current
    val canEdit = vm.canEdit && !offline
    val pending by vm.pending.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val donors by vm.donors.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var ym by remember { mutableStateOf(Format.currentYearMonth()) }
    var fundFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Contribution?>(null) }
    var deleting by remember { mutableStateOf<Contribution?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val mosqueFlow = remember(mosqueId) { container.ledgerRepository.observeMosque(mosqueId).catch { emit(null) } }
    val mosqueInfo by mosqueFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var showCashCounter by remember { mutableStateOf(false) }
    var activeReceiptData by remember { mutableStateOf<ReceiptData?>(null) }

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
            if (vm.canEdit) FloatingActionButton(onClick = { editing = Contribution(date = Format.todayIso()) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_contribution))
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

                        FilledTonalIconButton(onClick = { showCashCounter = true }) {
                            Icon(Icons.Filled.Calculate, contentDescription = "জুমার বাক্স গণনা")
                        }
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
                items(list, key = { it.id }) { c ->
                    val fundName = funds.firstOrNull { it.id == c.accountId }?.let { "${it.code} ${it.name}" } ?: "—"
                    com.saif.mosque.ui.components.RecordCard(
                        title = c.donorName.ifBlank { "—" },
                        supporting = listOf(fundName, Format.date(c.date), c.receiptNo.takeIf { it.isNotBlank() }?.let { "#$it" }).filterNotNull().joinToString(" · "),
                        amount = Format.money(c.amount), amountColor = MaterialTheme.colorScheme.primary,
                        onEdit = if (canEdit) ({ editing = c }) else null,
                        onDelete = if (canEdit) ({ deleting = c }) else null)
                }
            }
        }
    }
        }
    }

    editing?.let { target ->
        ContributionFormDialog(
            initial = target,
            funds = (state as? UiState.Success)?.data?.second ?: emptyList(),
            donors = donors,
            isNew = target.id.isEmpty(),
            saving = saving, saveError = saveError,
            onDismiss = { editing = null; saveError = null },
            onSave = { c ->
                if (!saving) {
                    saving = true; saveError = null
                    val done: (Msg?) -> Unit = { result ->
                        saving = false
                        if (result == null) {
                            editing = null
                            val funds = (state as? UiState.Success)?.data?.second ?: emptyList()
                            val fName = funds.firstOrNull { it.id == c.accountId }?.let { "${it.code} ${it.name}" } ?: ""
                            activeReceiptData = ReceiptData(
                                mosqueName = mosqueInfo?.name ?: "",
                                mosqueAddress = mosqueInfo?.address ?: "",
                                receiptNo = c.receiptNo,
                                donorName = c.donorName,
                                donorPhone = donors.find { it.id == c.donorId }?.phone ?: "",
                                amount = c.amount,
                                date = c.date,
                                fundName = fName,
                                purpose = "মসজিদ দান/অনুদানের",
                                receivedBy = user.name
                            )
                        } else saveError = when (result) {
                            is Msg.Res -> context.getString(result.resId)
                            is Msg.Text -> result.message
                        }
                    }
                    if (target.id.isEmpty()) vm.add(c, done) else vm.update(target, c.copy(id = target.id), done)
                }
            }
        )
    }
    deleting?.let { c ->
        ConfirmDialog(stringResource(R.string.confirm_delete), stringResource(R.string.delete_question),
            onConfirm = { vm.delete(c) }, onDismiss = { deleting = null }, enabled = canEdit)
    }

    if (showCashCounter) {
        val funds = (state as? UiState.Success)?.data?.second ?: emptyList()
        FridayCashCounterDialog(
            funds = funds,
            onDismiss = { showCashCounter = false },
            onSaveCollection = { amount, fundId, date, receiptNo, note ->
                scope.launch {
                    try {
                        container.ledgerQueue.enqueue(
                            PrototypeLedgerDraft(
                                id = UUID.randomUUID().toString(),
                                uid = user.id,
                                mosqueId = mosqueId,
                                kind = "contributions",
                                amountPoisha = LedgerMath.poisha(amount, true),
                                sourceFundId = fundId,
                                donorId = "",
                                donorName = "জুমার নামাজ দান বাক্স",
                                receiptNo = receiptNo,
                                date = date,
                                note = note,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        val fundObj = funds.find { it.id == fundId }
                        activeReceiptData = ReceiptData(
                            mosqueName = mosqueInfo?.name ?: "",
                            mosqueAddress = mosqueInfo?.address ?: "",
                            receiptNo = receiptNo,
                            donorName = "জুমার নামাজ দান বাক্স",
                            donorPhone = "",
                            amount = amount,
                            date = date,
                            fundName = fundObj?.name ?: "সাধারণ তহবিল",
                            purpose = "জুমার নামাজ দান বাক্স কালেকশন",
                            receivedBy = user.name
                        )
                    } catch (_: Exception) {
                    } finally {
                        showCashCounter = false
                    }
                }
            }
        )
    }

    activeReceiptData?.let { receipt ->
        ReceiptSlipDialog(
            receipt = receipt,
            onDismiss = { activeReceiptData = null }
        )
    }
}

@Composable
internal fun ContributionFormDialog(
    initial: Contribution,
    funds: List<Fund>,
    donors: List<Donor>,
    isNew: Boolean,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (Contribution) -> Unit
) {
    var donorId by remember { mutableStateOf(initial.donorId) }
    var donorName by remember { mutableStateOf(initial.donorName) }
    var accountId by remember { mutableStateOf(initial.accountId) }
    var amount by remember { mutableStateOf(if (initial.amount > 0) initial.amount.toString() else "") }
    var date by remember { mutableStateOf(initial.date.ifBlank { Format.todayIso() }) }
    var receipt by remember {
        mutableStateOf(
            if (initial.receiptNo.isNotBlank()) initial.receiptNo
            else ReceiptHelper.generateReceiptNo("REC", date)
        )
    }
    var note by remember { mutableStateOf(initial.note) }
    var attempted by remember { mutableStateOf(false) }
    val tooLong = stringResource(R.string.invitation_field_too_long)

    val donorOptions = donors.filter { it.active || it.id == initial.donorId }.map { it.id to it.name } + ("" to stringResource(R.string.donor_name_free))

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(8),
        title = { Text(stringResource(if (isNew) R.string.add_contribution else R.string.edit_contribution)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = stringResource(R.string.donor),
                    options = donorOptions,
                    selected = donorId,
                    onSelect = {
                        if (donorId.isNotBlank() && it.isBlank()) donorName = ""
                        donorId = it
                    },
                    enabled = !saving
                )
                if (donorId.isBlank()) {
                    LabeledField(
                        stringResource(R.string.donor_name_free), donorName, { donorName = it },
                        error = if (attempted) Validators.required(donorName) ?: tooLong.takeIf { donorName.trim().length > 1000 } else null,
                        enabled = !saving
                    )
                }
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
                    stringResource(R.string.receipt_no), receipt, { receipt = it },
                    error = if (attempted) tooLong.takeIf { receipt.trim().length > 1000 } else null,
                    trailingIcon = {
                        IconButton(onClick = { receipt = ReceiptHelper.generateReceiptNo("REC", date) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "রশিদ নং তৈরি করুন")
                        }
                    },
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
                if (donorId.isBlank() && (Validators.required(donorName) != null || donorName.trim().length > 1000)) return@TextButton
                if (accountId.isBlank()) return@TextButton
                if (Validators.amount(amount) != null) return@TextButton
                if (receipt.trim().length > 1000 || note.trim().length > 1000) return@TextButton
                onSave(initial.copy(
                    donorId = donorId,
                    donorName = if (donorId.isBlank()) donorName.trim()
                                 else donors.firstOrNull { it.id == donorId }?.name ?: donorName,
                    accountId = accountId,
                    amount = amount.toDouble(),
                    date = date,
                    receiptNo = receipt.trim(),
                    note = note.trim()
                ))
            }) { Text(stringResource(if (saving) R.string.saving else R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
