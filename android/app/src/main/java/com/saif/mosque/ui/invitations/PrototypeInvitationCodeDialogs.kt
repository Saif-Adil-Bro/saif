package com.saif.mosque.ui.invitations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saif.mosque.R
import com.saif.mosque.data.invitations.InvitationAddress
import com.saif.mosque.data.invitations.InvitationCode
import com.saif.mosque.ui.components.LabeledField

/** Experimental handoff UI; no live route or production invitation is enabled. */
@Composable
internal fun PrototypeInvitationCodeDialog(busy: Boolean, error: String?, onDismiss: () -> Unit,
                                           onContinue: (InvitationAddress) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val parsed = if (attempted) InvitationCode.decode(code) else null
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.invitation_join_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.invitation_code_help), style = MaterialTheme.typography.bodySmall)
                LabeledField(stringResource(R.string.invitation_code), code, { code = it; attempted = false },
                    error = if (attempted && parsed == null) stringResource(R.string.invitation_code_invalid) else null,
                    enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                attempted = true
                InvitationCode.decode(code)?.let(onContinue)
            }) { Text(stringResource(if (busy) R.string.saving else R.string.invitation_continue)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Called only after an invitation has been server-confirmed. Clipboard ownership is the caller's responsibility. */
@Composable
internal fun PrototypeInvitationShareDialog(address: InvitationAddress, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val code = InvitationCode.encode(address)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invitation_share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.invitation_share_help), style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(code, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(code) }) { Text(stringResource(R.string.invitation_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
