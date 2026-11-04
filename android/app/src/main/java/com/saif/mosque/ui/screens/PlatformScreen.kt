@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.saif.mosque.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.CreatedMosque
import com.saif.mosque.data.model.Mosque
import com.saif.mosque.data.repository.DemoMosqueProtectedException
import com.saif.mosque.data.repository.PlatformRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.ConfirmDialog
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.components.StatCard
import com.saif.mosque.ui.components.StateBox
import com.saif.mosque.ui.viewmodel.AuthViewModel
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg
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

// ---------------- ViewModel ----------------

class PlatformViewModel(private val repo: PlatformRepository) : ViewModel() {
    val state: StateFlow<UiState<List<Mosque>>> = repo.observeMosques()
        .map { mosques -> UiState.Success(mosques) as UiState<List<Mosque>> }
        .catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    var created: CreatedMosque? by mutableStateOf(null)
        private set

    fun create(mosque: Mosque, adminName: String, adminEmail: String, adminPhone: String, adminPassword: String) {
        viewModelScope.launch {
            val res = repo.createMosque(mosque, adminName, adminEmail, adminPhone, adminPassword)
            res.onSuccess {
                created = it
                _messages.emit(Msg.Res(R.string.mosque_created))
            }.onFailure { e -> _messages.emit(Msg.Text(e.message ?: "error")) }
        }
    }

    fun update(mosque: Mosque) {
        viewModelScope.launch {
            try {
                repo.updateMosque(mosque)
                _messages.emit(Msg.Res(R.string.saved_ok))
            } catch (e: Exception) {
                _messages.emit(Msg.Text(e.message ?: "error"))
            }
        }
    }

    fun setActive(mosque: Mosque, active: Boolean) {
        viewModelScope.launch {
            val res = repo.setActive(mosque, active)
            res.onFailure { e ->
                _messages.emit(
                    if (e is DemoMosqueProtectedException) Msg.Res(R.string.demo_mosque_protected)
                    else Msg.Text(e.message ?: "error")
                )
            }
        }
    }

    fun dismissCreated() { created = null }
}

// ---------------- Screen ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformScreen(container: AppContainer, authVm: AuthViewModel, user: AppUser) {
    val vm: PlatformViewModel = viewModel { PlatformViewModel(container.platformRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val offline = com.saif.mosque.ui.components.LocalOfflineMode.current
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.All) }
    var editing by remember { mutableStateOf<Mosque?>(null) }   // null=closed, Mosque()=new, else edit
    var pendingDeactivate by remember { mutableStateOf<Mosque?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(vm.messages) {
        vm.messages.collect { msg ->
            snackbar.showSnackbar(when (msg) {
                is Msg.Res -> context.getString(msg.resId)
                is Msg.Text -> msg.message
            })
        }
    }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = { Text(stringResource(R.string.platform_console)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    IconButton(onClick = { confirmLogout = true }) {
                        Icon(Icons.Filled.Logout, contentDescription = stringResource(R.string.logout))
                    }
                }
            )
            if (offline) com.saif.mosque.ui.components.OfflineBanner()
            }
        },
        floatingActionButton = {
            if (!offline) FloatingActionButton(onClick = { editing = Mosque() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_mosque))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
        StateBox(state) { mosques ->
            val filtered = mosques.filter { m ->
                (filter == Filter.All || (filter == Filter.Active) == m.active) &&
                    (query.isBlank() || listOf(m.name, m.nameEn, m.address, m.district, m.email)
                        .any { it.contains(query, ignoreCase = true) })
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
            ) {
                item {
                    Text(stringResource(R.string.platform_summary), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    com.saif.mosque.ui.components.StatsGrid(listOf(
                        com.saif.mosque.ui.components.StatItem(stringResource(R.string.total_mosques), Format.bn(mosques.size)),
                        com.saif.mosque.ui.components.StatItem(stringResource(R.string.active_mosques), Format.bn(mosques.count { it.active })),
                        com.saif.mosque.ui.components.StatItem(stringResource(R.string.total_users), Format.bn(mosques.sumOf { it.memberCount })),
                        com.saif.mosque.ui.components.StatItem(stringResource(R.string.combined_balance), Format.money(mosques.sumOf { it.totalBalance }))
                    ))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search_mosque)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(stringResource(f.labelRes)) }
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item { com.saif.mosque.ui.components.EmptyBox() }
                }
                items(filtered, key = { it.id }) { m ->
                    MosqueCard(
                        mosque = m,
                        canEdit = !offline,
                        onEdit = { editing = m },
                        onToggle = { active ->
                            if (active) vm.setActive(m, true)
                            else pendingDeactivate = m
                        }
                    )
                }
            }
        }
        }
        }
    }

    // create / edit dialog
    editing?.let { target ->
        MosqueFormDialog(
            initial = target,
            isNew = target.id.isEmpty(),
            onDismiss = { editing = null },
            onSave = { mosque, adminName, adminEmail, adminPhone, adminPassword ->
                if (target.id.isEmpty()) {
                    vm.create(mosque, adminName, adminEmail, adminPhone, adminPassword)
                } else {
                    vm.update(mosque.copy(id = target.id, active = target.active,
                        totalBalance = target.totalBalance, fundCount = target.fundCount,
                        donorCount = target.donorCount, memberCount = target.memberCount,
                        createdAt = target.createdAt))
                }
                editing = null
            }
        )
    }

    // one-time credentials modal (spec 1.9)
    vm.created?.let { created ->
        CredentialsDialog(created, onDismiss = { vm.dismissCreated() })
    }

    if (confirmLogout) ConfirmDialog(
        title = stringResource(R.string.logout), message = stringResource(R.string.log_out_confirm),
        onConfirm = { authVm.logout() }, onDismiss = { confirmLogout = false },
        confirmLabel = stringResource(R.string.logout))

    pendingDeactivate?.let { m ->
        ConfirmDialog(
            title = stringResource(R.string.deactivate),
            message = stringResource(R.string.mosque_deactivated_warn),
            onConfirm = { vm.setActive(m, false) },
            onDismiss = { pendingDeactivate = null },
            confirmLabel = stringResource(R.string.deactivate), enabled = !offline
        )
    }
}

private enum class Filter(val labelRes: Int) {
    All(R.string.all), Active(R.string.active), Inactive(R.string.inactive)
}

@Composable
private fun MosqueCard(mosque: Mosque, canEdit: Boolean, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(mosque.name, style = MaterialTheme.typography.titleMedium)
                    val location = listOf(mosque.address, mosque.upazila, mosque.district)
                        .filter { it.isNotBlank() }.joinToString(", ")
                    if (location.isNotBlank()) Text(location, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canEdit) IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.balance), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Format.money(mosque.totalBalance), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
            Text("${stringResource(R.string.members)} · ${Format.bn(mosque.memberCount)}    " +
                "${stringResource(R.string.donors)} · ${Format.bn(mosque.donorCount)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (mosque.active) R.string.active else R.string.inactive),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = if (mosque.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Switch(checked = mosque.active, onCheckedChange = { onToggle(it) }, enabled = canEdit,
                    modifier = Modifier.semantics { contentDescription = mosque.name })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MosqueFormDialog(
    initial: Mosque,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Mosque, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var nameEn by remember { mutableStateOf(initial.nameEn) }
    var address by remember { mutableStateOf(initial.address) }
    var district by remember { mutableStateOf(initial.district) }
    var upazila by remember { mutableStateOf(initial.upazila) }
    var phone by remember { mutableStateOf(initial.phone) }
    var email by remember { mutableStateOf(initial.email) }
    var year by remember { mutableStateOf(initial.establishedYear?.toString() ?: "") }
    var regNo by remember { mutableStateOf(initial.registrationNo) }
    var adminName by remember { mutableStateOf("") }
    var adminEmail by remember { mutableStateOf("") }
    var adminPhone by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.new_mosque else R.string.edit_mosque)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(stringResource(R.string.mosque_name), name, { name = it }, error = error)
                LabeledField(stringResource(R.string.mosque_name_en), nameEn, { nameEn = it })
                LabeledField(stringResource(R.string.address), address, { address = it })
                LabeledField(stringResource(R.string.district), district, { district = it })
                LabeledField(stringResource(R.string.upazila), upazila, { upazila = it })
                LabeledField(stringResource(R.string.phone), phone, { phone = it }, keyboardType = KeyboardType.Phone)
                LabeledField(stringResource(R.string.email), email, { email = it }, keyboardType = KeyboardType.Email)
                LabeledField(stringResource(R.string.established_year), year, { year = it }, keyboardType = KeyboardType.Number)
                LabeledField(stringResource(R.string.registration_no), regNo, { regNo = it })
                if (isNew) {
                    SectionTitle(stringResource(R.string.admin_details))
                    LabeledField(stringResource(R.string.admin_name), adminName, { adminName = it })
                    LabeledField(stringResource(R.string.admin_email), adminEmail, { adminEmail = it }, keyboardType = KeyboardType.Email)
                    LabeledField(stringResource(R.string.admin_phone), adminPhone, { adminPhone = it }, keyboardType = KeyboardType.Phone)
                    com.saif.mosque.ui.components.PasswordField(stringResource(R.string.admin_password_optional), adminPassword, { adminPassword = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !com.saif.mosque.ui.components.LocalOfflineMode.current, onClick = {
                val nameErr = Validators.required(name)
                if (isNew) {
                    if (nameErr != null || Validators.required(adminName) != null ||
                        Validators.email(adminEmail) != null ||
                        (adminPassword.isNotBlank() && Validators.password(adminPassword) != null)) {
                        error = "required"
                        return@TextButton
                    }
                } else if (nameErr != null) { error = "required"; return@TextButton }
                onSave(
                    initial.copy(
                        name = name.trim(), nameEn = nameEn.trim(), address = address.trim(),
                        district = district.trim(), upazila = upazila.trim(), phone = phone.trim(),
                        email = email.trim(), establishedYear = year.toIntOrNull(),
                        registrationNo = regNo.trim()
                    ),
                    adminName, adminEmail, adminPhone, adminPassword
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Spec 1.9: one-time credential modal with copy buttons. */
@Composable
private fun CredentialsDialog(created: CreatedMosque, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.credentials_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.credentials_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                CredentialRow(stringResource(R.string.mosque_name), created.mosque.name, clipboard, context)
                CredentialRow(stringResource(R.string.email), created.adminEmail, clipboard, context)
                CredentialRow(stringResource(R.string.temp_password), created.temporaryPassword, clipboard, context)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(
                    "${context.getString(R.string.mosque_name)}: ${created.mosque.name}\n" +
                        "${context.getString(R.string.email)}: ${created.adminEmail}\n" +
                        "${context.getString(R.string.temp_password)}: ${created.temporaryPassword}"
                ))
                onDismiss()
            }) { Text(stringResource(R.string.copy_all)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun CredentialRow(label: String, value: String, clipboard: androidx.compose.ui.platform.ClipboardManager, context: android.content.Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(value))
        }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy)) }
    }
}
