package com.saif.mosque.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.Transfer
import com.saif.mosque.data.repository.InsufficientBalanceException
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.data.repository.SameAccountException
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

class TransfersViewModel(
    private val repo: LedgerRepository,
    private val mosqueId: String,
    private val user: AppUser
) : ViewModel() {
    val canEdit = Permissions.canEditTransfers(user.role)

    val state: StateFlow<UiState<Pair<List<Transfer>, List<Fund>>>> =
        combine(repo.observeTransfers(mosqueId), repo.observeFunds(mosqueId)) { ts, funds ->
            UiState.Success(ts to funds) as UiState<Pair<List<Transfer>, List<Fund>>>
        }
            .catch { emit(UiState.Error(it.message ?: "error")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    fun add(t: Transfer, done: (Msg?) -> Unit) {
        viewModelScope.launch {
            try {
                repo.addTransfer(mosqueId, t, user.id)
                done(null)
                _messages.emit(Msg.Res(R.string.saved_ok))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                done(when (e) {
                    is SameAccountException -> Msg.Res(R.string.same_account_error)
                    is InsufficientBalanceException -> Msg.Res(R.string.insufficient_balance)
                    else -> Msg.Text(e.message ?: "error")
                })
            }
        }
    }

    fun delete(t: Transfer) {
        viewModelScope.launch {
            try { repo.deleteTransfer(mosqueId, t); _messages.emit(Msg.Res(R.string.deleted_ok)) }
            catch (e: Exception) { _messages.emit(Msg.Text(e.message ?: "error")) }
        }
    }
}

@Composable
fun TransfersScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: TransfersViewModel = viewModel { TransfersViewModel(container.ledgerRepository, mosqueId, user) }
    val canEdit = vm.canEdit && !com.saif.mosque.ui.components.LocalOfflineMode.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var editing by remember { mutableStateOf<Transfer?>(null) }
    var deleting by remember { mutableStateOf<Transfer?>(null) }
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
            if (canEdit) FloatingActionButton(onClick = { editing = Transfer(date = Format.todayIso()) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_transfer))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        StateBox(state) { (transfers, funds) ->
            if (transfers.isEmpty()) {
                EmptyBox()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(inner).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
                ) {
                    items(transfers, key = { it.id }) { t ->
                        val from = funds.firstOrNull { it.id == t.fromAccountId }?.code ?: "?"
                        val to = funds.firstOrNull { it.id == t.toAccountId }?.code ?: "?"
                        com.saif.mosque.ui.components.RecordCard(
                            title = "$from → $to",
                            supporting = listOf(Format.date(t.date), t.note).filter { it.isNotBlank() }.joinToString(" · "),
                            amount = Format.money(t.amount),
                            onDelete = if (canEdit) ({ deleting = t }) else null)
                    }
                }
            }
        }
    }

    editing?.let { target ->
        TransferFormDialog(
            funds = (state as? UiState.Success)?.data?.second ?: emptyList(),
            saving = saving, saveError = saveError,
            onDismiss = { editing = null; saveError = null },
            onSave = { t ->
                if (!saving) {
                    saving = true; saveError = null
                    vm.add(t) { result ->
                        saving = false
                        if (result == null) editing = null else saveError = when (result) {
                            is Msg.Res -> context.getString(result.resId)
                            is Msg.Text -> result.message
                        }
                    }
                }
            }
        )
    }
    deleting?.let { t ->
        ConfirmDialog(stringResource(R.string.confirm_delete), stringResource(R.string.delete_question),
            onConfirm = { vm.delete(t) }, onDismiss = { deleting = null }, enabled = canEdit)
    }
}

@Composable
internal fun TransferFormDialog(
    funds: List<Fund>,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (Transfer) -> Unit
) {
    var fromId by remember { mutableStateOf(funds.firstOrNull { it.active }?.id ?: "") }
    var toId by remember { mutableStateOf(funds.filter { it.active }.getOrNull(1)?.id ?: "") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Format.todayIso()) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val options = funds.filter { it.active }.map { it.id to "${it.code} — ${it.name} (${Format.money(it.balance)})" }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.add_transfer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(stringResource(R.string.from_account), options, fromId, { fromId = it }, enabled = !saving)
                DropdownField(stringResource(R.string.to_account), options, toId, { toId = it }, enabled = !saving)
                LabeledField(stringResource(R.string.amount), amount, { amount = it },
                    error = error, keyboardType = KeyboardType.Decimal, enabled = !saving)
                DatePickerField(stringResource(R.string.date), date, { date = it }, enabled = !saving)
                LabeledField(stringResource(R.string.note), note, { note = it }, singleLine = false, enabled = !saving)
                saveError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.sync_check_record), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && !com.saif.mosque.ui.components.LocalOfflineMode.current, onClick = {
                if (fromId.isBlank() || toId.isBlank() || fromId == toId) { error = "same"; return@TextButton }
                if (Validators.amount(amount) != null) { error = "amount"; return@TextButton }
                onSave(Transfer(fromAccountId = fromId, toAccountId = toId,
                    amount = amount.toDouble(), date = date, note = note.trim()))
            }) { Text(stringResource(if (saving) R.string.saving else R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
