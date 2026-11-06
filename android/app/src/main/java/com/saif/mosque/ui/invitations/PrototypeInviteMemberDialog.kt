package com.saif.mosque.ui.invitations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.saif.mosque.ui.components.FullWidthDialogProperties
import com.saif.mosque.ui.components.fullWidthDialog
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
    var name by rememberSaveable(issuerUid, mosqueId) { mutableStateOf("") }
    var email by rememberSaveable(issuerUid, mosqueId) { mutableStateOf("") }
    var phone by rememberSaveable(issuerUid, mosqueId) { mutableStateOf("") }
    var designation by rememberSaveable(issuerUid, mosqueId) { mutableStateOf("") }
    var roleName by rememberSaveable(issuerUid, mosqueId) { mutableStateOf(Role.MEMBER.name) }
    var attempted by rememberSaveable(issuerUid, mosqueId) { mutableStateOf(false) }
    // The exact immutable ID and expiry survive configuration changes and retries.
    val id by rememberSaveable(issuerUid, mosqueId) { mutableStateOf(UUID.randomUUID().toString()) }
    val expiresAt by rememberSaveable(issuerUid, mosqueId) { mutableStateOf(System.currentTimeMillis() + 6L * 24 * 60 * 60 * 1000) }
    val allowedRoles = listOf(Role.ADMIN, Role.PRESIDENT, Role.SECRETARY, Role.TREASURER, Role.CASHIER, Role.IMAM, Role.MEMBER)
        .filter { it != Role.ADMIN || issuerRole == Role.ADMIN || issuerRole == Role.SUPERADMIN }
    val canIssue = issuerRole in setOf(Role.SUPERADMIN, Role.ADMIN, Role.PRESIDENT, Role.SECRETARY)
    val tooLong = stringResource(R.string.invitation_field_too_long)
    val role = allowedRoles.firstOrNull { it.name == roleName } ?: Role.MEMBER
    val options = allowedRoles.map { it to roleLabel(it) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = FullWidthDialogProperties,
        modifier = Modifier.fullWidthDialog(8).imePadding(),
        title = { Text(stringResource(R.string.invitation_member_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.invitation_member_help), style = MaterialTheme.typography.bodySmall)
                LabeledField(stringResource(R.string.name), name, { name = it }, enabled = !saving,
                    error = if (attempted) Validators.required(name) ?: tooLong.takeIf { name.trim().length > 1000 } else null)
                LabeledField(stringResource(R.string.email), email, { email = it }, enabled = !saving,
                    keyboardType = KeyboardType.Email, error = if (attempted) Validators.email(email) ?: tooLong.takeIf { email.trim().length > 254 } else null)
                LabeledField(stringResource(R.string.phone), phone, { phone = it }, enabled = !saving,
                    keyboardType = KeyboardType.Phone, error = if (attempted) Validators.bdPhone(phone) else null)
                DropdownField(stringResource(R.string.role), options, role, { roleName = it.name }, enabled = !saving)
                LabeledField(stringResource(R.string.designation), designation, { designation = it }, enabled = !saving,
                    error = if (attempted) tooLong.takeIf { designation.trim().length > 1000 } else null)
                Text(stringResource(R.string.invitation_expiry_help), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && canIssue, onClick = {
                attempted = true
                if (Validators.required(name) == null && Validators.email(email) == null && email.trim().length <= 254 && Validators.bdPhone(phone) == null &&
                    listOf(name, phone, designation).all { it.trim().length <= 1000 }) {
                    onSave(PrototypeInvitation(id, mosqueId, issuerUid, email.trim().lowercase(Locale.ROOT),
                        name.trim(), phone.trim(), role, designation.trim(), Timestamp(Date(expiresAt))))
                }
            }) { Text(stringResource(if (saving) R.string.saving else R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
