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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.ConfirmDialog
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FundsViewModel(
    private val repo: LedgerRepository,
    private val mosqueId: String,
    private val user: AppUser
) : ViewModel() {
    val state: StateFlow<UiState<List<Fund>>> = repo.observeFunds(mosqueId)
        .map { UiState.Success(it) as UiState<List<Fund>> }
        .catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    val canEdit = Permissions.canEditFunds(user.role)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    fun add(fund: Fund) = mutate { repo.addFund(mosqueId, fund) }
    fun update(fund: Fund) = mutate { repo.updateFund(mosqueId, fund) }
    fun delete(fund: Fund) = mutate { repo.deleteFund(mosqueId, fund.id) }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _messages.emit(Msg.Res(R.string.saved_ok))
            } catch (e: Exception) {
                _messages.emit(Msg.Text(e.message ?: "error"))
            }
        }
    }
}

@Composable
fun FundsScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: FundsViewModel = viewModel { FundsViewModel(container.ledgerRepository, mosqueId, user) }
    val canEdit = vm.canEdit && !com.saif.mosque.ui.components.LocalOfflineMode.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var editing by remember { mutableStateOf<Fund?>(null) }
    var deleting by remember { mutableStateOf<Fund?>(null) }

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
            if (canEdit) FloatingActionButton(onClick = { editing = Fund() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_fund))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        StateBox(state) { funds ->
            if (funds.isEmpty()) {
                EmptyBox()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(inner).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
                ) {
                    items(funds, key = { it.id }) { fund ->
                        com.saif.mosque.ui.components.RecordCard(
                            title = "${fund.code} · ${fund.name}", supporting = fund.nameEn,
                            amount = Format.money(fund.balance), amountLabel = stringResource(R.string.balance),
                            status = if (!fund.active) stringResource(R.string.inactive) else null,
                            onEdit = if (canEdit) ({ editing = fund }) else null,
                            onDelete = if (canEdit && fund.active) ({ deleting = fund }) else null,
                            archive = true, deleteLabel = stringResource(R.string.archive))
                    }
                }
            }
        }
    }

    editing?.let { target ->
        FundFormDialog(
            initial = target,
            isNew = target.id.isEmpty(),
            onDismiss = { editing = null },
            onSave = { fund ->
                if (target.id.isEmpty()) vm.add(fund) else vm.update(fund.copy(id = target.id))
                editing = null
            }
        )
    }

    deleting?.let { fund ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete),
            message = stringResource(R.string.delete_fund_q),
            onConfirm = { vm.delete(fund) },
            onDismiss = { deleting = null },
            confirmLabel = stringResource(R.string.archive), enabled = canEdit
        )
    }
}

@Composable
private fun FundFormDialog(
    initial: Fund,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Fund) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var code by remember { mutableStateOf(initial.code) }
    var nameEn by remember { mutableStateOf(initial.nameEn) }
    var description by remember { mutableStateOf(initial.description) }
    var active by remember { mutableStateOf(initial.active) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.add_fund else R.string.edit_fund)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(stringResource(R.string.fund_name), name, { name = it }, error = error)
                LabeledField(stringResource(R.string.fund_code), code, { code = it.uppercase() })
                LabeledField(stringResource(R.string.fund_name_en), nameEn, { nameEn = it })
                LabeledField(stringResource(R.string.description), description, { description = it }, singleLine = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.active), Modifier.weight(1f))
                    val activeLabel = stringResource(R.string.active)
                    Switch(checked = active, onCheckedChange = { active = it },
                        modifier = Modifier.semantics { contentDescription = activeLabel })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !com.saif.mosque.ui.components.LocalOfflineMode.current, onClick = {
                if (Validators.required(name) != null || Validators.required(code) != null) {
                    error = "required"; return@TextButton
                }
                onSave(initial.copy(
                    name = name.trim(), code = code.trim().uppercase(), nameEn = nameEn.trim(),
                    description = description.trim(), active = active
                ))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
