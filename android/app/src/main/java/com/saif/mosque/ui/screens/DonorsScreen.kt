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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.saif.mosque.data.model.Donor
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

class DonorsViewModel(
    private val repo: LedgerRepository,
    private val mosqueId: String,
    private val user: AppUser
) : ViewModel() {
    val state: StateFlow<UiState<List<Donor>>> = repo.observeDonors(mosqueId)
        .map { UiState.Success(it) }
        .catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    val canEdit = Permissions.canEditDonors(user.role)
    val canDelete = Permissions.canDeleteDonors(user.role)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    fun add(d: Donor) = mutate { repo.addDonor(mosqueId, d) }
    fun update(d: Donor) = mutate { repo.updateDonor(mosqueId, d) }
    fun delete(d: Donor) = mutate { repo.deleteDonor(mosqueId, d.id) }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); _messages.emit(Msg.Res(R.string.saved_ok)) }
            catch (e: Exception) { _messages.emit(Msg.Text(e.message ?: "error")) }
        }
    }
}

@Composable
fun DonorsScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: DonorsViewModel = viewModel { DonorsViewModel(container.ledgerRepository, mosqueId, user) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Donor?>(null) }
    var deleting by remember { mutableStateOf<Donor?>(null) }

    LaunchedEffect(vm.messages) {
        vm.messages.collect { msg ->
            snackbar.showSnackbar(when (msg) {
                is Msg.Res -> context.getString(msg.resId)
                is Msg.Text -> msg.message
            })
        }
    }

    Scaffold(
        floatingActionButton = {
            if (vm.canEdit) FloatingActionButton(onClick = { editing = Donor() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_donor))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        StateBox(state, onRetry = {}) { donors ->
            val filtered = donors.filter {
                query.isBlank() || it.name.contains(query, true) || it.phone.contains(query, true)
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(inner).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
                if (filtered.isEmpty()) item { EmptyBox() }
                items(filtered, key = { it.id }) { d ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(d.phone, d.address).joinToString(" • ") +
                                        if (!d.active) " • ${stringResource(R.string.inactive)}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${stringResource(R.string.total_donated)}: ${Format.money(d.totalDonated)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (vm.canEdit) {
                                IconButton(onClick = { editing = d }) { Icon(Icons.Filled.Edit, contentDescription = null) }
                            }
                            if (vm.canDelete) {
                                IconButton(onClick = { deleting = d }) {
                                    Icon(Icons.Filled.Delete, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        DonorFormDialog(
            initial = target,
            isNew = target.id.isEmpty(),
            onDismiss = { editing = null },
            onSave = { d ->
                if (target.id.isEmpty()) vm.add(d) else vm.update(d.copy(id = target.id))
                editing = null
            }
        )
    }
    deleting?.let { d ->
        ConfirmDialog(stringResource(R.string.confirm_delete), stringResource(R.string.delete_question),
            onConfirm = { vm.delete(d) }, onDismiss = { deleting = null })
    }
}

@Composable
private fun DonorFormDialog(
    initial: Donor,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Donor) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var phone by remember { mutableStateOf(initial.phone) }
    var address by remember { mutableStateOf(initial.address) }
    var active by remember { mutableStateOf(initial.active) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.add_donor else R.string.edit_donor)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(stringResource(R.string.name), name, { name = it }, error = error)
                LabeledField(stringResource(R.string.phone), phone, { phone = it },
                    error = Validators.bdPhone(phone)?.let { "phone" }, keyboardType = KeyboardType.Phone)
                LabeledField(stringResource(R.string.address), address, { address = it }, singleLine = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.active), Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (Validators.required(name) != null) { error = "required"; return@TextButton }
                if (Validators.bdPhone(phone) != null) return@TextButton
                onSave(initial.copy(name = name.trim(), phone = phone.trim(),
                    address = address.trim(), active = active))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
