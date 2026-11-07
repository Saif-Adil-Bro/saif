package com.saif.mosque.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.invitations.InvitationAddress
import com.saif.mosque.data.invitations.PrototypeQueuedInvitation
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.StatItem
import com.saif.mosque.ui.components.StatsGrid
import com.saif.mosque.ui.invitations.PrototypeInvitationShareDialog
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg

private enum class InvitationFilter {
    ALL, PENDING, ACCEPTED, REVOKED
}

@Composable
fun InvitationHistoryScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

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

    val invitations by vm.invitations.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf(InvitationFilter.ALL) }
    var sharingAddress by remember { mutableStateOf<InvitationAddress?>(null) }

    LaunchedEffect(vm.messages) {
        vm.messages.collect { msg ->
            snackbar.showSnackbar(
                when (msg) {
                    is Msg.Res -> context.getString(msg.resId)
                    is Msg.Text -> msg.message
                }
            )
        }
    }

    val totalCount = invitations.size
    val pendingCount = invitations.count { it.state == "READY" || it.state == "PENDING" }
    val acceptedCount = invitations.count { it.state == "ACCEPTED" }
    val revokedCount = invitations.count { it.state == "REVOKED" || it.state == "BLOCKED" }

    val filteredList = when (selectedFilter) {
        InvitationFilter.ALL -> invitations
        InvitationFilter.PENDING -> invitations.filter { it.state == "READY" || it.state == "PENDING" }
        InvitationFilter.ACCEPTED -> invitations.filter { it.state == "ACCEPTED" }
        InvitationFilter.REVOKED -> invitations.filter { it.state == "REVOKED" || it.state == "BLOCKED" }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // 1. KPI Stats Summary
            item(key = "invitation_stats_grid") {
                StatsGrid(
                    listOf(
                        StatItem(stringResource(R.string.total_invitations), "${Format.bn(totalCount)} টি"),
                        StatItem(stringResource(R.string.filter_pending), "${Format.bn(pendingCount)} টি", MaterialTheme.colorScheme.primary),
                        StatItem(stringResource(R.string.invitation_status_accepted), "${Format.bn(acceptedCount)} টি", MaterialTheme.colorScheme.tertiary),
                        StatItem(stringResource(R.string.invitation_status_revoked), "${Format.bn(revokedCount)} টি", if (revokedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    )
                )
            }

            // 2. Filter chips
            item(key = "invitation_filter_chips") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFilter == InvitationFilter.ALL,
                            onClick = { selectedFilter = InvitationFilter.ALL },
                            label = { Text("${stringResource(R.string.filter_all)} (${Format.bn(totalCount)})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == InvitationFilter.PENDING,
                            onClick = { selectedFilter = InvitationFilter.PENDING },
                            label = { Text("${stringResource(R.string.filter_pending)} (${Format.bn(pendingCount)})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == InvitationFilter.ACCEPTED,
                            onClick = { selectedFilter = InvitationFilter.ACCEPTED },
                            label = { Text("${stringResource(R.string.invitation_status_accepted)} (${Format.bn(acceptedCount)})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == InvitationFilter.REVOKED,
                            onClick = { selectedFilter = InvitationFilter.REVOKED },
                            label = { Text("${stringResource(R.string.invitation_status_revoked)} (${Format.bn(revokedCount)})") }
                        )
                    }
                }
            }

            // 3. Invitation Cards List
            if (filteredList.isEmpty()) {
                item(key = "empty_invitations") {
                    EmptyBox(stringResource(R.string.no_invitations))
                }
            } else {
                items(filteredList, key = { "inv-hist-${it.draft.id}" }) { inv ->
                    InvitationCard(
                        invitation = inv,
                        onShare = { vm.prepareShare(inv) { sharingAddress = it } },
                        onRevoke = { vm.revokeInvitation(inv) },
                        onRetry = { vm.retryInvitation(inv) }
                    )
                }
            }
        }
    }

    sharingAddress?.let { addr ->
        PrototypeInvitationShareDialog(
            address = addr,
            onCopy = { code ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("invitation", code))
                sharingAddress = null
            },
            onDismiss = { sharingAddress = null }
        )
    }
}

@Composable
private fun InvitationCard(
    invitation: PrototypeQueuedInvitation,
    onShare: () -> Unit,
    onRevoke: () -> Unit,
    onRetry: () -> Unit
) {
    val draft = invitation.draft
    val (statusLabel, containerColor, contentColor, icon) = when (invitation.state) {
        "READY" -> Quad(
            stringResource(R.string.invitation_status_ready),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Filled.Share
        )
        "ACCEPTED" -> Quad(
            stringResource(R.string.invitation_status_accepted),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.CheckCircle
        )
        "REVOKED" -> Quad(
            stringResource(R.string.invitation_status_revoked),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Error
        )
        "BLOCKED" -> Quad(
            stringResource(R.string.sync_unconfirmed),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Error
        )
        else -> Quad(
            stringResource(R.string.sync_waiting),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.HourglassTop
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        draft.name.ifBlank { "সদস্য" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        listOf(draft.email, roleLabel(draft.role)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = containerColor
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = contentColor)
                        Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = contentColor, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Action Buttons
            if (invitation.state == "READY" && !com.saif.mosque.ui.components.LocalOfflineMode.current) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onRevoke) {
                        Text(stringResource(R.string.invitation_revoke), color = MaterialTheme.colorScheme.error)
                    }
                    AssistChip(
                        onClick = onShare,
                        label = { Text(stringResource(R.string.invitation_share_title)) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            } else if (invitation.state == "BLOCKED") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = onRetry,
                        label = { Text(stringResource(R.string.retry)) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
