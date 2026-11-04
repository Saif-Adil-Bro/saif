@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.saif.mosque.ui.screens

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val vm: DashboardViewModel = viewModel { DashboardViewModel(container.dashboardRepository, mosqueId) }
    val state by vm.state.collectAsStateWithLifecycle()

    StateBox(state, onRetry = {}) { data ->
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(stringResource(R.string.total_balance), Format.money(data.totalBalance),
                        modifier = Modifier.weight(1f))
                    StatCard(stringResource(R.string.monthly_income), Format.money(data.monthlyIncome),
                        modifier = Modifier.weight(1f),
                        valueColor = MaterialTheme.colorScheme.primary)
                    StatCard(stringResource(R.string.monthly_expense), Format.money(data.monthlyExpense),
                        modifier = Modifier.weight(1f),
                        valueColor = MaterialTheme.colorScheme.error)
                    StatCard(stringResource(R.string.active_donors), Format.bn(data.activeDonors),
                        modifier = Modifier.weight(1f))
                }
            }
            item { SectionTitle(stringResource(R.string.fund_balances)) }
            if (data.fundBalances.isEmpty()) {
                item { EmptyBox() }
            }
            items(data.fundBalances, key = { it.first.id }) { (fund, balance) ->
                val max = data.fundBalances.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fund.code} — ${fund.name}", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge)
                            MoneyText(balance)
                        }
                        LinearProgressIndicator(
                            progress = { (balance / max).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.recent_transactions)) }
            if (data.recent.isEmpty()) {
                item { EmptyBox(stringResource(R.string.no_transactions)) }
            }
            items(data.recent, key = { it.type + it.id }) { tx ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tx.title, style = MaterialTheme.typography.bodyLarge)
                            Text(Format.date(tx.date), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MoneyText(
                            tx.amount,
                            color = if (tx.amount >= 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
