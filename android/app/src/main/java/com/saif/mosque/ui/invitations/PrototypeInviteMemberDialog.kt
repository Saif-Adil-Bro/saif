package com.saif.mosque.ui.invitations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.saif.mosque.R
import com.saif.mosque.data.invitations.PrototypeInvitation
import com.saif.mosque.data.model.Role
import com.saif.mosque.ui.components.DropdownField
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.screens.roleLabel
import com.saif.mosque.util.Validators
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Experimental issuer form. Saving a draft is not confirmation that it was sent. */
@Composable
internal fun PrototypeInviteMemberDialog(
    issuerUid: String, mosqueId: String, issuerRole: Role, saving: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (PrototypeInvitation) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var designation by rememberSaveable { mutableStateOf("") }
    var roleName by rememberSaveable { mutableStateOf(Role.MEMBER.name) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    // The exact immutable ID and expiry survive configuration changes and retries.
    val id by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val expiresAt by rememberSaveable { mutableStateOf(System.currentTimeMillis() + 6L * 24 * 60 * 60 * 1000) }
    val allowedRoles = listOf(Role.ADMIN, Role.PRESIDENT, Role.SECRETARY, Role.TREASURER, Role.CASHIER, Role.IMAM, Role.MEMBER)
        .filter { it != Role.ADMIN || issuerRole == Role.ADMIN || issuerRole == Role.SUPERADMIN }
    val role = allowedRoles.firstOrNull { it.name == roleName } ?: Role.MEMBER
    val options = allowedRoles.map { it to roleLabel(it) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.invitation_member_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.invitation_member_help), style = MaterialTheme.typography.bodySmall)
                LabeledField(stringResource(R.string.name), name, { name = it }, enabled = !saving,
                    error = if (attempted) Validators.required(name) else null)
                LabeledField(stringResource(R.string.email), email, { email = it }, enabled = !saving,
                    keyboardType = KeyboardType.Email, error = if (attempted) Validators.email(email) else null)
                LabeledField(stringResource(R.string.phone), phone, { phone = it }, enabled = !saving,
                    keyboardType = KeyboardType.Phone, error = if (attempted) Validators.bdPhone(phone) else null)
                DropdownField(stringResource(R.string.role), options, role, { roleName = it.name }, enabled = !saving)
                LabeledField(stringResource(R.string.designation), designation, { designation = it }, enabled = !saving)
                Text(stringResource(R.string.invitation_expiry_help), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                attempted = true
                if (Validators.required(name) == null && Validators.email(email) == null && Validators.bdPhone(phone) == null &&
                    listOf(name, phone, designation).all { it.trim().length <= 1000 }) {
                    onSave(PrototypeInvitation(id, mosqueId, issuerUid, email.trim().lowercase(Locale.ROOT),
                        name.trim(), phone.trim(), role, designation.trim(), Timestamp(Date(expiresAt))))
                }
            }) { Text(stringResource(if (saving) R.string.saving else R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
