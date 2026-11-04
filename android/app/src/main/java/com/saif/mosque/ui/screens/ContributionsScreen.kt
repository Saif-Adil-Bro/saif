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
import com.saif.mosque.data.model.Contribution
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
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

class ContributionsViewModel(
    private val repo: LedgerRepository,
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

    fun add(c: Contribution) = mutate { repo.addContribution(mosqueId, c, user.id) }
    fun update(old: Contribution, new: Contribution) = mutate { repo.updateContribution(mosqueId, old, new, user.id) }
    fun delete(c: Contribution) = mutate { repo.deleteContribution(mosqueId, c) }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); _messages.emit(Msg.Res(R.string.saved_ok)) }
            catch (e: Exception) { _messages.emit(Msg.Text(e.message ?: "error")) }
        }
    }
}

@Composable
fun ContributionsScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: ContributionsViewModel = viewModel { ContributionsViewModel(container.ledgerRepository, mosqueId, user) }
    val canEdit = vm.canEdit && !com.saif.mosque.ui.components.LocalOfflineMode.current
    val state by vm.state.collectAsStateWithLifecycle()
    val donors by vm.donors.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var ym by remember { mutableStateOf(Format.currentYearMonth()) }
    var fundFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Contribution?>(null) }
    var deleting by remember { mutableStateOf<Contribution?>(null) }

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
            if (canEdit) FloatingActionButton(onClick = { editing = Contribution(date = Format.todayIso()) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_contribution))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        StateBox(state) { (all, funds) ->
            val monthPrefix = String.format(java.util.Locale.US, "%04d-%02d", ym.first, ym.second)
            val list = all
                .filter { it.date.startsWith(monthPrefix) }
                .filter { fundFilter == null || it.accountId == fundFilter }

            LazyColumn(
                Modifier.fillMaxSize().padding(inner).padding(16.dp),
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

    editing?.let { target ->
        ContributionFormDialog(
            initial = target,
            funds = (state as? UiState.Success)?.data?.second ?: emptyList(),
            donors = donors,
            isNew = target.id.isEmpty(),
            onDismiss = { editing = null },
            onSave = { c ->
                if (target.id.isEmpty()) vm.add(c) else vm.update(target, c.copy(id = target.id))
                editing = null
            }
        )
    }
    deleting?.let { c ->
        ConfirmDialog(stringResource(R.string.confirm_delete), stringResource(R.string.delete_question),
            onConfirm = { vm.delete(c) }, onDismiss = { deleting = null }, enabled = canEdit)
    }
}

@Composable
private fun ContributionFormDialog(
    initial: Contribution,
    funds: List<Fund>,
    donors: List<Donor>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Contribution) -> Unit
) {
    var donorId by remember { mutableStateOf(initial.donorId) }
    var donorName by remember { mutableStateOf(initial.donorName) }
    var accountId by remember { mutableStateOf(initial.accountId) }
    var amount by remember { mutableStateOf(if (initial.amount > 0) initial.amount.toString() else "") }
    var date by remember { mutableStateOf(initial.date.ifBlank { Format.todayIso() }) }
    var receipt by remember { mutableStateOf(initial.receiptNo) }
    var note by remember { mutableStateOf(initial.note) }
    var error by remember { mutableStateOf<String?>(null) }

    val donorOptions = donors.filter { it.active || it.id == initial.donorId }.map { it.id to it.name } + ("" to stringResource(R.string.donor_name_free))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.add_contribution else R.string.edit_contribution)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = stringResource(R.string.donor),
                    options = donorOptions,
                    selected = donorId,
                    onSelect = { donorId = it }
                )
                if (donorId.isBlank()) {
                    LabeledField(stringResource(R.string.donor_name_free), donorName, { donorName = it }, error = error)
                }
                DropdownField(
                    label = stringResource(R.string.account),
                    options = funds.filter { it.active || it.id == initial.accountId }.map { it.id to "${it.code} — ${it.name}" },
                    selected = accountId,
                    onSelect = { accountId = it }
                )
                LabeledField(stringResource(R.string.amount), amount, { amount = it },
                    error = error, keyboardType = KeyboardType.Decimal)
                DatePickerField(stringResource(R.string.date), date, { date = it })
                LabeledField(stringResource(R.string.receipt_no), receipt, { receipt = it })
                LabeledField(stringResource(R.string.note), note, { note = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(enabled = !com.saif.mosque.ui.components.LocalOfflineMode.current, onClick = {
                if (donorId.isBlank() && Validators.required(donorName) != null) { error = "required"; return@TextButton }
                if (accountId.isBlank()) { error = "required"; return@TextButton }
                if (Validators.amount(amount) != null) { error = "amount"; return@TextButton }
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
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
