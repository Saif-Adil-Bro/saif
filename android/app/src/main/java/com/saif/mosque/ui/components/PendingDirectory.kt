package com.saif.mosque.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.saif.mosque.R
import com.saif.mosque.data.outbox.DirectoryDraft
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import com.google.gson.Gson

/** Pending rows are clearly separated from acknowledged Firestore records and balances. */
@Composable
fun PendingDirectory(drafts: List<DirectoryDraft>, online: Boolean, onRetry: (DirectoryDraft) -> Unit,
                     onDiscard: (DirectoryDraft) -> Unit = {}) {
    if (drafts.isEmpty()) return
    var showPending by remember { mutableStateOf(false) }
    var toDiscard by remember { mutableStateOf<DirectoryDraft?>(null) }
    TextButton(onClick = { showPending = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.pending_count, drafts.size))
    }
    if (showPending) AlertDialog(
        onDismissRequest = { showPending = false },
        title = { Text(stringResource(R.string.pending_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pending_explanation), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(drafts, key = { it.id }) { draft ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(draft.name, style = MaterialTheme.typography.titleSmall)
                                val details = remember(draft.kind, draft.payload) { pendingDetails(draft) }
                                if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall)
                                val inactive = remember(draft.kind, draft.payload) { pendingInactive(draft) }
                                if (inactive) Text(stringResource(R.string.inactive), style = MaterialTheme.typography.labelSmall)
                                Text(stringResource(when (draft.action) {
                                    "update" -> R.string.pending_update
                                    "archive" -> R.string.pending_archive
                                    else -> R.string.pending_add
                                }), style = MaterialTheme.typography.labelSmall)
                                Text(stringResource(if (draft.state == "BLOCKED") R.string.sync_unconfirmed else R.string.sync_waiting),
                                    color = if (draft.state == "BLOCKED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                                if (draft.state == "BLOCKED") {
                                    Text(stringResource(when (draft.failure) {
                                        "PERMISSION" -> R.string.sync_check_permission
                                        "PROFILE_MISSING", "PROFILE_INACTIVE", "PROFILE_TENANT", "PROFILE_ROLE" -> R.string.sync_profile_mismatch
                                        "MOSQUE_MISSING", "MOSQUE_INACTIVE" -> R.string.sync_mosque_unavailable
                                        "PROFILE_DENIED", "MOSQUE_DENIED", "RECEIPT_DENIED" -> R.string.sync_read_denied
                                        "WRITE_DENIED" -> R.string.sync_write_denied
                                        "CONFLICT" -> R.string.sync_conflict_help
                                        else -> R.string.sync_check_record
                                    }),
                                        style = MaterialTheme.typography.bodySmall)
                                    draft.failure?.takeIf { it in setOf("PROFILE_MISSING", "PROFILE_INACTIVE", "PROFILE_TENANT", "PROFILE_ROLE", "MOSQUE_MISSING", "MOSQUE_INACTIVE", "PROFILE_DENIED", "MOSQUE_DENIED", "RECEIPT_DENIED", "WRITE_DENIED") }?.let { code ->
                                        Text(stringResource(R.string.sync_diagnostic_code, code), style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(enabled = online, onClick = { onRetry(draft) }) { Text(stringResource(R.string.retry)) }
                                    TextButton(onClick = { toDiscard = draft }) { Text(stringResource(R.string.discard_local)) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showPending = false }) { Text(stringResource(R.string.close)) } }
    )
    toDiscard?.let { draft ->
        AlertDialog(
            onDismissRequest = { toDiscard = null },
            title = { Text(stringResource(R.string.discard_local)) },
            text = { Text(stringResource(R.string.discard_local_warning)) },
            confirmButton = { TextButton(onClick = { onDiscard(draft); toDiscard = null }) { Text(stringResource(R.string.discard_local)) } },
            dismissButton = { TextButton(onClick = { toDiscard = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/** The draft is account-scoped; show entered values so a conflict can be re-entered safely. */
private fun pendingDetails(draft: DirectoryDraft): String = runCatching {
    val gson = Gson()
    when (draft.kind) {
        "funds" -> gson.fromJson(draft.payload, Fund::class.java).let {
            listOf(it.code, it.nameEn, it.description).filter { value -> !value.isNullOrBlank() }.distinct().joinToString("\n")
        }
        "donors" -> gson.fromJson(draft.payload, Donor::class.java).let {
            listOf(it.phone, it.address).filter { value -> !value.isNullOrBlank() }.distinct().joinToString("\n")
        }
        else -> ""
    }
}.getOrDefault("")

private fun pendingInactive(draft: DirectoryDraft): Boolean = runCatching {
    when (draft.kind) {
        "funds" -> !Gson().fromJson(draft.payload, Fund::class.java).active
        "donors" -> !Gson().fromJson(draft.payload, Donor::class.java).active
        else -> false
    }
}.getOrDefault(false)
