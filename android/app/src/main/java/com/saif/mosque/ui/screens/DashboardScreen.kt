@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.saif.mosque.ui.screens

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.DashboardData
import com.saif.mosque.data.repository.DashboardRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.MoneyText
import com.saif.mosque.ui.components.SectionTitle
import com.saif.mosque.ui.components.StatCard
import com.saif.mosque.ui.components.StateBox
import com.saif.mosque.util.Format
import com.saif.mosque.util.UiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(repo: DashboardRepository, mosqueId: String) : ViewModel() {
    val state: StateFlow<UiState<DashboardData>> = repo.observeDashboard(mosqueId)
        .map { UiState.Success(it) as UiState<DashboardData> }
        .catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)
}

@Composable
fun DashboardScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: DashboardViewModel = viewModel { DashboardViewModel(container.dashboardRepository, mosqueId) }
    val state by vm.state.collectAsStateWithLifecycle()

    StateBox(state) { data ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.welcome_user, user.name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.account_overview), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primary) {
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
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Filled.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFFE9D3A9))
                            }
                            Text(Format.money(data.totalBalance), style = MaterialTheme.typography.headlineMedium,
                                color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
            item {
                com.saif.mosque.ui.components.StatsGrid(listOf(
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.monthly_income), Format.money(data.monthlyIncome)),
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.monthly_expense), Format.money(data.monthlyExpense), MaterialTheme.colorScheme.error),
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.active_donors), Format.bn(data.activeDonors)),
                    com.saif.mosque.ui.components.StatItem(stringResource(R.string.fund_count), Format.bn(data.fundCount))
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
    }
}
