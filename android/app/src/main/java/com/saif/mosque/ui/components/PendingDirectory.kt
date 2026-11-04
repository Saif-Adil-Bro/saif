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
                                        "CONFLICT" -> R.string.sync_conflict_help
                                        else -> R.string.sync_check_record
                                    }),
                                        style = MaterialTheme.typography.bodySmall)
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
