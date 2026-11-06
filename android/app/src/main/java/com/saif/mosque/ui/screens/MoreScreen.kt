package com.saif.mosque.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.ConfirmDialog
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.navigation.Screen
import com.saif.mosque.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(nav: NavHostController, container: AppContainer, authVm: AuthViewModel, user: AppUser) {
    var confirmLogout by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.current_user), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(user.name, style = MaterialTheme.typography.titleLarge)
                        Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(roleLabel(user.role), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.quick_access)) }
        item { MenuCard(stringResource(R.string.donors), Icons.Filled.People) { nav.navigate(Screen.Donors.route) } }
        item { MenuCard(stringResource(R.string.members), Icons.Filled.Groups) { nav.navigate(Screen.Members.route) } }
        item { MenuCard(stringResource(R.string.transfers), Icons.Filled.SwapHoriz) { nav.navigate(Screen.Transfers.route) } }
        item { MenuCard(stringResource(R.string.reports), Icons.Filled.Assignment) { nav.navigate(Screen.Reports.route) } }
        if (user.mosqueId != null) {
            item { MenuCard(stringResource(R.string.audit_trail_title), Icons.Filled.Assignment) { showAudit = true } }
        }
        item {
            MenuCard(stringResource(R.string.logout), Icons.Filled.Logout, destructive = true) { confirmLogout = true }
        }
    }
    if (showAudit && user.mosqueId != null) {
        AuditTrailDialog(container = container, mosqueId = user.mosqueId, onDismiss = { showAudit = false })
    }
    if (confirmLogout) ConfirmDialog(
        title = stringResource(R.string.logout), message = stringResource(R.string.log_out_confirm),
        onConfirm = { authVm.logout() }, onDismiss = { confirmLogout = false },
        confirmLabel = stringResource(R.string.logout))
}

@Composable
private fun AuditTrailDialog(container: AppContainer, mosqueId: String, onDismiss: () -> Unit) {
    val mutationsFlow = remember(mosqueId) {
        container.ledgerRepository.observeLedgerMutations(mosqueId).catch { emit(emptyList()) }
    }
    val mutations by mutationsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var verification by remember { mutableStateOf<com.saif.mosque.data.model.ReceiptChainVerification?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runVerify() {
        if (verifying) return
        verifying = true
        scope.launch {
            try {
                verification = container.ledgerRepository.verifyReceiptChain(mosqueId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                verification = com.saif.mosque.data.model.ReceiptChainVerification(
                    valid = false,
                    mutationsCount = mutations.size,
                    activeEntriesCount = 0,
                    voidedEntriesCount = 0,
                    totalBalancePoisha = 0L,
                    issues = listOf(e.message ?: "Verification error")
                )
            } finally {
                verifying = false
            }
        }
    }

    LaunchedEffect(mosqueId) {
        runVerify()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audit_trail_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                verification?.let { v ->
                    val statusColor = if (v.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Text(
                        stringResource(if (v.valid) R.string.audit_verified_ok else R.string.audit_verified_failed),
                        style = MaterialTheme.typography.titleSmall,
                        color = statusColor
                    )
                    Text(
                        stringResource(R.string.audit_mutations_count, v.mutationsCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        stringResource(R.string.audit_active_voided, v.activeEntriesCount, v.voidedEntriesCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                    v.issues.forEach { issue ->
                        Text(issue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                mutations.take(25).forEach { m ->
                    val actionLabel = if (m.action == "delete") stringResource(R.string.audit_action_delete)
                    else stringResource(R.string.audit_action_update)
                    val oldTaka = com.saif.mosque.util.Format.money(m.oldAmountPoisha / 100.0)
                    val newTaka = com.saif.mosque.util.Format.money(m.newAmountPoisha / 100.0)
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("$actionLabel · ${m.kind} (${m.targetId.take(8)})", style = MaterialTheme.typography.labelLarge)
                            Text("$oldTaka → $newTaka", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !verifying, onClick = { runVerify() }) {
                Text(stringResource(R.string.audit_verify_button))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun MenuCard(title: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(42.dp).background(
                if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = tint)
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
