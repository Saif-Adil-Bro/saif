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
import com.saif.mosque.data.ledger.PrototypeQueuedLedger
import com.saif.mosque.data.ledger.LedgerMath
import com.saif.mosque.util.Format

/** Local drafts are separate from acknowledged ledger rows and balance totals. */
@Composable
fun PendingLedger(drafts: List<PrototypeQueuedLedger>, online: Boolean,
                  onRetry: (PrototypeQueuedLedger) -> Unit, onDiscard: (PrototypeQueuedLedger) -> Unit) {
    if (drafts.isEmpty()) return
    var showPending by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<PrototypeQueuedLedger?>(null) }
    TextButton(onClick = { showPending = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.pending_count, drafts.size))
    }
    if (showPending) AlertDialog(onDismissRequest = { showPending = false }, title = { Text(stringResource(R.string.pending_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pending_explanation), style = MaterialTheme.typography.bodySmall)
                if (drafts.first().state == "BLOCKED") {
                    Text(stringResource(R.string.ledger_queue_blocked), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(drafts, key = { it.draft.id }) { queued ->
                        val d = queued.draft
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(when (d.kind) { "contributions" -> d.donorName; "expenses" -> d.title; else -> stringResource(R.string.transfers) },
                                    style = MaterialTheme.typography.titleSmall)
                                Text("${Format.money(LedgerMath.taka(d.amountPoisha))} · ${Format.date(d.date)}", style = MaterialTheme.typography.bodySmall)
                                if (d.note.isNotBlank()) Text(d.note, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(if (queued.state == "BLOCKED") R.string.sync_unconfirmed else R.string.sync_waiting),
                                    color = if (queued.state == "BLOCKED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                                if (queued.state == "BLOCKED") {
                                    Text(stringResource(when (queued.failure) {
                                        "PERMISSION" -> R.string.sync_check_permission
                                        "INSUFFICIENT" -> R.string.insufficient_balance
                                        "MIGRATION" -> R.string.ledger_migration_required
                                        "WAIT_MIGRATION" -> R.string.ledger_wait_migration
                                        else -> R.string.sync_check_record
                                    }), style = MaterialTheme.typography.bodySmall)
                                    TextButton(enabled = online, onClick = { onRetry(queued) }) { Text(stringResource(R.string.retry)) }
                                    TextButton(onClick = { discard = queued }) { Text(stringResource(R.string.discard_local)) }
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showPending = false }) { Text(stringResource(R.string.close)) } })
    discard?.let { row ->
        AlertDialog(onDismissRequest = { discard = null }, title = { Text(stringResource(R.string.discard_local)) },
            text = { Text(stringResource(R.string.discard_local_warning)) },
            confirmButton = { TextButton(onClick = { onDiscard(row); discard = null }) { Text(stringResource(R.string.discard_local)) } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
