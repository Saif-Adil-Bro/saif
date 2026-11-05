package com.saif.mosque.ui.invitations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saif.mosque.R
import com.saif.mosque.data.invitations.InvitationCode
import com.saif.mosque.data.invitations.PrototypeJoinCoordinator
import com.saif.mosque.data.invitations.PrototypeJoinMessage
import com.saif.mosque.data.invitations.PrototypeJoinPhase
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.PasswordField
import com.saif.mosque.util.Validators
import kotlinx.coroutines.launch

/** Experimental flow. Do not add a live route before rules and rollout are ready. */
@Composable
internal fun PrototypeJoinFlow(coordinator: PrototypeJoinCoordinator, onClose: () -> Unit) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // deliberately not saveable
    var isNew by remember { mutableStateOf(true) }
    var attempted by remember { mutableStateOf(false) }
    LaunchedEffect(coordinator) { coordinator.restore() }
    LaunchedEffect(state.phase) { if (state.phase != PrototypeJoinPhase.ACCOUNT) password = "" }
    val message = state.message?.let { stringResource(when (it) {
        PrototypeJoinMessage.INVALID_CODE -> R.string.invitation_code_invalid
        PrototypeJoinMessage.STORAGE_ERROR -> R.string.join_storage_error
        PrototypeJoinMessage.AUTH_UNCERTAIN -> R.string.join_auth_uncertain
        PrototypeJoinMessage.VERIFICATION_REQUESTED -> R.string.join_verification_requested
        PrototypeJoinMessage.VERIFICATION_UNCONFIRMED -> R.string.join_verification_uncertain
        PrototypeJoinMessage.VERIFY_EMAIL -> R.string.join_verify_email
        PrototypeJoinMessage.REDEEM_UNCONFIRMED -> R.string.join_redeem_uncertain
        PrototypeJoinMessage.ACTION_FAILED -> R.string.error_generic
    }) }
    if (state.phase == PrototypeJoinPhase.CODE) {
        PrototypeInvitationCodeDialog(state.busy, message, onClose) { address ->
            scope.launch { coordinator.useCode(InvitationCode.encode(address)) }
        }
        return
    }
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.invitation_join_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.phase) {
                    PrototypeJoinPhase.ACCOUNT -> {
                        Text(stringResource(R.string.join_account_help), style = MaterialTheme.typography.bodySmall)
                        LabeledField(stringResource(R.string.email), email, { email = it }, enabled = !state.busy,
                            error = if (attempted) Validators.email(email) else null, keyboardType = KeyboardType.Email)
                        PasswordField(stringResource(R.string.password), password, { password = it }, enabled = !state.busy,
                            error = if (attempted) if (isNew) Validators.password(password) else Validators.required(password) else null)
                        Button(enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            attempted = true
                            if (Validators.email(email) == null &&
                                (if (isNew) Validators.password(password) else Validators.required(password)) == null) {
                                val currentEmail = email; val currentPassword = password
                                scope.launch { if (isNew) coordinator.signUp(currentEmail, currentPassword)
                                    else coordinator.signIn(currentEmail, currentPassword) }
                            }
                        }) { Text(stringResource(if (isNew) R.string.join_create_account else R.string.sign_in)) }
                        TextButton(enabled = !state.busy, onClick = { isNew = !isNew; attempted = false; password = "" }) {
                            Text(stringResource(if (isNew) R.string.join_have_account else R.string.join_create_account))
                        }
                    }
                    PrototypeJoinPhase.VERIFY -> {
                        Text(stringResource(R.string.join_verify_help), style = MaterialTheme.typography.bodySmall)
                        state.identity?.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Button(enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                            onClick = { scope.launch { coordinator.redeem() } }) { Text(stringResource(R.string.invitation_continue)) }
                        TextButton(enabled = !state.busy, onClick = { scope.launch { coordinator.resendVerification() } }) {
                            Text(stringResource(R.string.join_resend))
                        }
                        TextButton(enabled = !state.busy, onClick = { scope.launch { coordinator.changeAccount() } }) {
                            Text(stringResource(R.string.join_change_account))
                        }
                    }
                    PrototypeJoinPhase.COMPLETE -> Text(stringResource(R.string.join_complete), style = MaterialTheme.typography.bodyMedium)
                    PrototypeJoinPhase.CODE -> Unit
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                if (state.phase != PrototypeJoinPhase.COMPLETE) {
                    TextButton(enabled = !state.busy, onClick = { scope.launch { coordinator.clearReference() } }) {
                        Text(stringResource(R.string.join_change_code))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text(stringResource(R.string.close)) } })
}
