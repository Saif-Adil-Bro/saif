@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.saif.mosque.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.ledger.LedgerMath
import com.saif.mosque.data.ledger.PrototypeLedgerDraft
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.DashboardData
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.repository.DashboardRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.FridayAnnouncementDialog
import com.saif.mosque.ui.components.FridayCashCounterDialog
import com.saif.mosque.ui.components.MoneyText
import com.saif.mosque.ui.components.ReceiptSlipDialog
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.components.StatCard
import com.saif.mosque.ui.components.StateBox
import com.saif.mosque.util.Format
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.ReceiptData
import com.saif.mosque.util.UiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class DashboardViewModel(repo: DashboardRepository, mosqueId: String) : ViewModel() {
    val state: StateFlow<UiState<DashboardData>> = repo.observeDashboard(mosqueId)
        .map { UiState.Success(it) as UiState<DashboardData> }
        .catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)
}

@Composable
fun DashboardScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: DashboardViewModel = viewModel { DashboardViewModel(container.dashboardRepository, mosqueId) }
    val state by vm.state.collectAsStateWithLifecycle()

    val mosqueFlow = remember(mosqueId) { container.ledgerRepository.observeMosque(mosqueId).catch { emit(null) } }
    val mosqueInfo by mosqueFlow.collectAsStateWithLifecycle(initialValue = null)

    var showCashCounter by remember { mutableStateOf(false) }
    var showAnnouncement by remember { mutableStateOf(false) }
    var activeReceiptData by remember { mutableStateOf<ReceiptData?>(null) }

    StateBox(state) { data ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.welcome_user, user.name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.account_overview), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Total Balance Gradient Card
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(com.saif.mosque.ui.theme.GreenDark, com.saif.mosque.ui.theme.GreenPrimary)))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.total_balance), Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f))
                                Icon(
                                    Icons.Filled.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFFE9D3A9))
                            }
                            Text(Format.money(data.totalBalance), style = MaterialTheme.typography.headlineMedium,
                                color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }

            // Jummah & Quick Tools Action Row
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showCashCounter = true }
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Calculate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Column {
                                Text("জুমার বাক্স গণনা", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("নোট ক্যাশ কাউন্টার", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showAnnouncement = true }
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Column {
                                Text("খুতবার ঘোষণা", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("১ পাতার সামারি", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                com.saif.mosque.ui.components.StatsGrid(listOf(
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.today_collection), Format.money(data.todayIncome)),
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.today_expense), Format.money(data.todayExpense), MaterialTheme.colorScheme.error),
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.monthly_income), Format.money(data.monthlyIncome)),
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.monthly_expense), Format.money(data.monthlyExpense), MaterialTheme.colorScheme.error)
                ))
            }

            item { SectionTitle(stringResource(R.string.fund_balances)) }
            if (data.fundBalances.isEmpty()) item { EmptyBox() }
            items(data.fundBalances, key = { it.first.id }) { (fund, balance) ->
                val max = data.fundBalances.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${fund.code} · ${fund.name}", style = MaterialTheme.typography.titleMedium)
                        MoneyText(balance)
                        LinearProgressIndicator(progress = { (balance / max).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            trackColor = MaterialTheme.colorScheme.primaryContainer)
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.recent_transactions)) }
            if (data.recent.isEmpty()) item { EmptyBox(stringResource(R.string.no_transactions)) }
            items(data.recent, key = { it.type + it.id }) { tx ->
                com.saif.mosque.ui.components.RecordCard(
                    title = if (tx.type == "transfer") stringResource(R.string.transfers) else tx.title,
                    supporting = Format.date(tx.date),
                    amount = Format.money(if (tx.type == "transfer") kotlin.math.abs(tx.amount) else tx.amount),
                    amountColor = when (tx.type) {
                        "expense" -> MaterialTheme.colorScheme.error
                        "contribution" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    })
            }
        }

        // Friday Cash Counter Dialog
        if (showCashCounter) {
            FridayCashCounterDialog(
                funds = data.fundBalances.map { it.first },
                onDismiss = { showCashCounter = false },
                onSaveCollection = { amount, fundId, date, receiptNo, note ->
                    scope.launch {
                        try {
                            container.ledgerQueue.enqueue(
                                PrototypeLedgerDraft(
                                    id = UUID.randomUUID().toString(),
                                    uid = user.id,
                                    mosqueId = mosqueId,
                                    kind = "contributions",
                                    amountPoisha = LedgerMath.poisha(amount, true),
                                    sourceFundId = fundId,
                                    donorId = "",
                                    donorName = "জুমার নামাজ দান বাক্স",
                                    receiptNo = receiptNo,
                                    date = date,
                                    note = note,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            val fundObj = data.fundBalances.find { it.first.id == fundId }?.first
                            activeReceiptData = ReceiptData(
                                mosqueName = mosqueInfo?.name ?: "",
                                mosqueAddress = mosqueInfo?.address ?: "",
                                receiptNo = receiptNo,
                                donorName = "জুমার নামাজ দান বাক্স",
                                donorPhone = "",
                                amount = amount,
                                date = date,
                                fundName = fundObj?.name ?: "সাধারণ তহবিল",
                                purpose = "জুমার নামাজ দান বাক্স কালেকশন",
                                receivedBy = user.name
                            )
                        } catch (_: Exception) {
                            // Queue will durable retry
                        } finally {
                            showCashCounter = false
                        }
                    }
                }
            )
        }

        // Friday Announcement Dialog
        if (showAnnouncement) {
            FridayAnnouncementDialog(
                mosqueName = mosqueInfo?.name ?: "",
                startDate = data.weeklyStartDate,
                endDate = data.weeklyEndDate,
                lastWeekIncome = data.weeklyIncome,
                lastWeekExpense = data.weeklyExpense,
                totalBalance = data.totalBalance,
                fundBalances = data.fundBalances,
                onDismiss = { showAnnouncement = false }
            )
        }

        // Receipt Slip Dialog (Thermal POS / WhatsApp)
        activeReceiptData?.let { receipt ->
            ReceiptSlipDialog(
                receipt = receipt,
                onDismiss = { activeReceiptData = null }
            )
        }
    }
}
