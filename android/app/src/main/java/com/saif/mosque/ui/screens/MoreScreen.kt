package com.saif.mosque.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.saif.mosque.R
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.navigation.Screen
import com.saif.mosque.ui.viewmodel.AuthViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreScreen(nav: NavHostController, container: AppContainer, authVm: AuthViewModel, user: AppUser) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.current_user), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(user.name, style = MaterialTheme.typography.titleLarge)
                Text(user.email, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(roleLabel(user.role), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuCard(stringResource(R.string.donors), Icons.Filled.People) {
                nav.navigate(Screen.Donors.route)
            }
            MenuCard(stringResource(R.string.members), Icons.Filled.AccountBalanceWallet) {
                nav.navigate(Screen.Members.route)
            }
            MenuCard(stringResource(R.string.transfers), Icons.Filled.SwapHoriz) {
                nav.navigate(Screen.Transfers.route)
            }
            MenuCard(stringResource(R.string.reports), Icons.Filled.Assignment) {
                nav.navigate(Screen.Reports.route)
            }
        }

        Card(Modifier.fillMaxWidth().clickable { authVm.logout() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
            )
        }
    }
}

@Composable
private fun MenuCard(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
