package com.saif.mosque.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import com.saif.mosque.data.model.Role
import com.saif.mosque.data.repository.AuthRepository
import com.saif.mosque.data.repository.AuthException
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.data.repository.SelfActionException
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.ConfirmDialog
import com.saif.mosque.ui.components.DropdownField
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.FullWidthDialogProperties
import com.saif.mosque.ui.components.fullWidthDialog
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.PasswordField
import com.saif.mosque.ui.components.StateBox
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

class MembersViewModel(
    private val ledger: LedgerRepository,
    private val authRepo: AuthRepository,
    private val mosqueId: String,
    private val user: AppUser,
    private val invitationOutbox: com.saif.mosque.data.invitations.PrototypeInvitationOutbox? = null,
    private val invitationService: com.saif.mosque.data.invitations.PrototypeInvitationService? = null,
    private val appContext: android.content.Context? = null
) : ViewModel() {
    val state: StateFlow<UiState<List<AppUser>>> = ledger.observeMembers(mosqueId)
        .map { UiState.Success(it.sortedWith(compareBy({ !it.active }, { it.name }))) as UiState<List<AppUser>> }
        .catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    val invitations: StateFlow<List<com.saif.mosque.data.invitations.PrototypeQueuedInvitation>> =
        (invitationOutbox?.observe(user.id, mosqueId) ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val canEdit = Permissions.canEditMembers(user.role)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    fun create(name: String, email: String, phone: String, role: Role, designation: String,
               password: String, done: (Msg?) -> Unit) {
        viewModelScope.launch {
            try {
                val result = authRepo.createMember(user, name, email, phone, role, designation, password)
                val error = result.exceptionOrNull()
                if (error == null) { done(null); _messages.emit(Msg.Res(R.string.saved_ok)) }
                else done(if (error is AuthException) Msg.Res(error.resId) else Msg.Res(R.string.error_generic))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { done(Msg.Res(R.string.error_generic)) }
        }
    }

    fun issueInvitation(draft: com.saif.mosque.data.invitations.PrototypeInvitation, done: (Msg?) -> Unit) {
        viewModelScope.launch {
            try {
                val outbox = invitationOutbox ?: throw IllegalStateException("Invitation outbox unavailable")
                outbox.enqueue(draft)
                val service = invitationService
                if (service != null) {
                    try {
                        val status = service.issue(draft)
                        val next = when (status) {
                            com.saif.mosque.data.invitations.PrototypeInvitationStatus.PENDING -> "READY"
                            com.saif.mosque.data.invitations.PrototypeInvitationStatus.ACCEPTED -> "ACCEPTED"
                            com.saif.mosque.data.invitations.PrototypeInvitationStatus.REVOKED -> "REVOKED"
                        }
                        outbox.transition(draft.id, draft.issuerUid, draft.mosqueId, "PENDING", next)
                        done(null)
                        _messages.emit(Msg.Res(R.string.saved_ok))
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Fall back to background worker sync when offline or transient failure occurs
                    }
                }
                appContext?.let { com.saif.mosque.data.invitations.PrototypeInvitationSync.schedule(it, draft) }
                done(null)
                _messages.emit(Msg.Res(R.string.local_pending_saved))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { done(Msg.Res(R.string.local_save_failed)) }
        }
    }

    fun prepareShare(
        item: com.saif.mosque.data.invitations.PrototypeQueuedInvitation,
        onReady: (com.saif.mosque.data.invitations.InvitationAddress) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val service = invitationService ?: return@launch
                when (service.inspect(item.draft)) {
                    com.saif.mosque.data.invitations.PrototypeInvitationStatus.PENDING ->
                        onReady(com.saif.mosque.data.invitations.InvitationAddress(item.draft.mosqueId, item.draft.id))
                    com.saif.mosque.data.invitations.PrototypeInvitationStatus.ACCEPTED -> {
                        invitationOutbox?.transition(item.draft.id, item.draft.issuerUid, item.draft.mosqueId, item.state, "ACCEPTED")
                        _messages.emit(Msg.Res(R.string.invitation_status_accepted))
                    }
                    com.saif.mosque.data.invitations.PrototypeInvitationStatus.REVOKED -> {
                        invitationOutbox?.transition(item.draft.id, item.draft.issuerUid, item.draft.mosqueId, item.state, "REVOKED")
                        _messages.emit(Msg.Res(R.string.invitation_status_revoked))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _messages.emit(Msg.Res(R.string.sync_check_permission)) }
        }
    }

    fun retryInvitation(item: com.saif.mosque.data.invitations.PrototypeQueuedInvitation) {
        viewModelScope.launch {
            try {
                if (invitationOutbox?.transition(item.draft.id, item.draft.issuerUid, item.draft.mosqueId, "BLOCKED", "PENDING") == true) {
                    appContext?.let { com.saif.mosque.data.invitations.PrototypeInvitationSync.schedule(it, item.draft) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _messages.emit(Msg.Res(R.string.error_generic)) }
        }
    }

    fun revokeInvitation(item: com.saif.mosque.data.invitations.PrototypeQueuedInvitation) {
        viewModelScope.launch {
            try {
                invitationService?.revoke(mosqueId, item.draft.id, user.id)
                if (item.state in setOf("PENDING", "READY")) {
                    invitationOutbox?.transition(item.draft.id, item.draft.issuerUid, item.draft.mosqueId, item.state, "REVOKED")
                }
                _messages.emit(Msg.Res(R.string.invitation_status_revoked))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _messages.emit(Msg.Res(R.string.error_generic)) }
        }
    }

    fun update(old: AppUser, member: AppUser, done: (Msg?) -> Unit) {
        if (member.id == user.id && (!member.active || member.role != old.role)) done(Msg.Res(R.string.cannot_deactivate_self))
        else mutate(done) { ledger.updateMember(mosqueId, old, member, user) }
    }

    fun delete(member: AppUser) {
        if (member.id == user.id) {
            viewModelScope.launch { _messages.emit(Msg.Res(R.string.cannot_delete_self)) }
        } else mutate(null) { ledger.deleteMember(mosqueId, member.id, user) }
    }

    private fun mutate(done: ((Msg?) -> Unit)?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); done?.invoke(null); _messages.emit(Msg.Res(R.string.saved_ok)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val error = Msg.Text(e.message ?: "error")
                if (done != null) done(error) else _messages.emit(error)
            }
        }
    }

}

@Composable
fun roleLabel(role: Role): String = when (role) {
    Role.SUPERADMIN -> stringResource(R.string.role_superadmin)
    Role.ADMIN -> stringResource(R.string.role_admin)
    Role.PRESIDENT -> stringResource(R.string.role_president)
    Role.SECRETARY -> stringResource(R.string.role_secretary)
    Role.TREASURER -> stringResource(R.string.role_treasurer)
    Role.CASHIER -> stringResource(R.string.role_cashier)
    Role.IMAM -> stringResource(R.string.role_imam)
    Role.MEMBER -> stringResource(R.string.role_member)
}

@Composable
fun MembersScreen(
    container: AppContainer,
    user: AppUser,
    onOpenInvitationHistory: () -> Unit = {}
) {
    val mosqueId = user.mosqueId ?: return
    val vm: MembersViewModel = viewModel {
        MembersViewModel(
            container.ledgerRepository,
            container.authRepository,
            mosqueId,
            user,
            container.invitationOutbox,
            container.invitationService,
            container.context
        )
    }
    val canEdit = vm.canEdit && !com.saif.mosque.ui.components.LocalOfflineMode.current
    val canInvite = vm.canEdit
    val canManualAdd = (user.role == Role.ADMIN || user.role == Role.SUPERADMIN) && !com.saif.mosque.ui.components.LocalOfflineMode.current
    val state by vm.state.collectAsStateWithLifecycle()
    val invitations by vm.invitations.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var editing by remember { mutableStateOf<AppUser?>(null) }
    var deleting by remember { mutableStateOf<AppUser?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var sharingAddress by remember { mutableStateOf<com.saif.mosque.data.invitations.InvitationAddress?>(null) }
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
            if (canManualAdd) FloatingActionButton(onClick = { editing = AppUser(); saveError = null }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.manual_add_member))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        StateBox(state) { members ->
            if (members.isEmpty() && !canInvite && !canManualAdd) {
                EmptyBox()
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(inner).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
                ) {
                    if (canInvite || canManualAdd) {
                        item(key = "invite-action-bar") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (canManualAdd) {
                                        Button(
                                            onClick = { editing = AppUser(); saveError = null }
                                        ) {
                                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.manual_add_member))
                                        }
                                    }
                                    if (canInvite) {
                                        OutlinedButton(
                                            onClick = { inviting = true; saveError = null }
                                        ) {
                                            Icon(Icons.Filled.Mail, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.invitation_member_title))
                                        }
                                    }
                                }

                                if (canInvite) {
                                    FilledTonalIconButton(
                                        onClick = onOpenInvitationHistory
                                    ) {
                                        BadgedBox(
                                            badge = {
                                                val pendingCount = invitations.count { it.state == "READY" || it.state == "PENDING" }
                                                if (pendingCount > 0) {
                                                    Badge { Text(pendingCount.toString()) }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Filled.History,
                                                contentDescription = stringResource(R.string.invitation_history)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (members.isEmpty()) {
                        item(key = "empty_members") {
                            EmptyBox()
                        }
                    } else {
                        items(members, key = { it.id }) { m ->
                        val canManageTarget = canEdit && m.role != Role.SUPERADMIN &&
                            (m.role != Role.ADMIN || user.role == Role.ADMIN || user.role == Role.SUPERADMIN)
                        com.saif.mosque.ui.components.RecordCard(
                            title = m.name + if (m.id == user.id) " · ${stringResource(R.string.you)}" else "",
                            supporting = listOf(m.email, roleLabel(m.role), m.designation)
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            status = if (!m.active) stringResource(R.string.inactive) else null,
                            onEdit = if (canManageTarget) ({ editing = m }) else null,
                            onDelete = if (canManageTarget && m.id != user.id) ({ deleting = m }) else null)
                    }
                }
            }
        }
    }

    if (inviting) {
        com.saif.mosque.ui.invitations.PrototypeInviteMemberDialog(
            issuerUid = user.id,
            mosqueId = mosqueId,
            issuerRole = user.role,
            saving = saving,
            error = saveError,
            onDismiss = { inviting = false; saveError = null },
            onSave = { draft ->
                if (!saving) {
                    saving = true; saveError = null
                    vm.issueInvitation(draft) { result ->
                        saving = false
                        if (result == null) inviting = false else saveError = when (result) {
                            is Msg.Res -> context.getString(result.resId)
                            is Msg.Text -> result.message
                        }
                    }
                }
            }
        )
    }

    sharingAddress?.let { addr ->
        com.saif.mosque.ui.invitations.PrototypeInvitationShareDialog(
            address = addr,
            onCopy = { code ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("invitation", code))
                sharingAddress = null
            },
            onDismiss = { sharingAddress = null }
        )
    }

    editing?.let { target ->
        MemberFormDialog(
            initial = target,
            isNew = target.id.isEmpty(), saving = saving, saveError = saveError,
            issuerRole = user.role, isSelf = target.id.isNotEmpty() && target.id == user.id,
            onDismiss = { editing = null; saveError = null },
            onSave = { name, email, phone, role, designation, active, password ->
                if (!saving) {
                    saving = true; saveError = null
                    val done: (Msg?) -> Unit = { result ->
                        saving = false
                        if (result == null) editing = null else saveError = when (result) {
                            is Msg.Res -> context.getString(result.resId)
                            is Msg.Text -> result.message
                        }
                    }
                    if (target.id.isEmpty()) vm.create(name, email, phone, role, designation, password, done)
                    else vm.update(target, target.copy(name = name, email = email, phone = phone,
                        role = role, designation = designation, active = active), done)
                }
            }
        )
    }
    deleting?.let { m ->
        ConfirmDialog(stringResource(R.string.confirm_delete), stringResource(R.string.delete_member_q),
            onConfirm = { vm.delete(m) }, onDismiss = { deleting = null }, enabled = canEdit)
    }
}

@Composable
internal fun MemberFormDialog(
    initial: AppUser,
    isNew: Boolean,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Role, String, Boolean, String) -> Unit,
    issuerRole: Role = Role.ADMIN,
    isSelf: Boolean = false
) {
    val allowedRoles = listOf(Role.ADMIN, Role.PRESIDENT, Role.SECRETARY, Role.TREASURER,
        Role.CASHIER, Role.IMAM, Role.MEMBER)
        .filter { it != Role.ADMIN || issuerRole == Role.ADMIN || issuerRole == Role.SUPERADMIN || (!isNew && initial.role == Role.ADMIN) }
    var name by remember { mutableStateOf(initial.name) }
    var email by remember { mutableStateOf(initial.email) }
    var phone by remember { mutableStateOf(initial.phone) }
    var role by remember { mutableStateOf(initial.role.takeIf { it in allowedRoles } ?: Role.MEMBER) }
    var designation by remember { mutableStateOf(initial.designation) }
    var active by remember { mutableStateOf(initial.active) }
    var password by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val tooLong = stringResource(R.string.invitation_field_too_long)

    val roleOptions = allowedRoles.map { it to roleLabel(it) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(8),
        title = { Text(stringResource(if (isNew) R.string.manual_add_member_title else R.string.edit_member)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField(stringResource(R.string.name), name, { name = it },
                    error = if (attempted) Validators.required(name) ?: tooLong.takeIf { name.trim().length > 1000 } else null, enabled = !saving)
                LabeledField(stringResource(R.string.email), email, { email = it },
                    error = if (isNew && attempted) Validators.email(email) ?: tooLong.takeIf { email.trim().length > 254 } else null, keyboardType = KeyboardType.Email,
                    readOnly = !isNew, enabled = !saving)
                LabeledField(stringResource(R.string.phone), phone, { phone = it },
                    error = if (attempted) Validators.bdPhone(phone) else null,
                    keyboardType = KeyboardType.Phone, enabled = !saving)
                DropdownField(stringResource(R.string.role), roleOptions, role, { role = it }, enabled = !saving && !isSelf)
                LabeledField(stringResource(R.string.designation), designation, { designation = it },
                    error = if (attempted) tooLong.takeIf { designation.trim().length > 1000 } else null,
                    enabled = !saving)
                if (isNew) {
                    PasswordField(stringResource(R.string.password), password, { password = it },
                        error = if (attempted) Validators.password(password) else null, enabled = !saving)
                }
                if (!isNew) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.active), Modifier.weight(1f))
                    val activeLabel = stringResource(R.string.active)
                    Switch(checked = active, onCheckedChange = { active = it }, enabled = !saving && !isSelf,
                        modifier = Modifier.semantics { contentDescription = activeLabel })
                }
                saveError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (isNew) Text(stringResource(R.string.member_check_before_retry), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && !com.saif.mosque.ui.components.LocalOfflineMode.current, onClick = {
                attempted = true
                if (Validators.required(name) != null || Validators.bdPhone(phone) != null ||
                    listOf(name, phone, designation).any { it.trim().length > 1000 } ||
                    (isNew && (Validators.email(email) != null || email.trim().length > 254 || Validators.password(password) != null))) {
                    return@TextButton
                }
                onSave(name.trim(), email.trim(), phone.trim(), role, designation.trim(), active, password)
            }) { Text(stringResource(if (saving) R.string.saving else R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
