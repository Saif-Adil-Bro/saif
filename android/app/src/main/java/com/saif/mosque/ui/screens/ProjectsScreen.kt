package com.saif.mosque.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saif.mosque.R
import com.saif.mosque.data.ledger.LedgerMath
import com.saif.mosque.data.ledger.LedgerQueue
import com.saif.mosque.data.ledger.PrototypeLedgerDraft
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.cleanDescription
import com.saif.mosque.data.model.isProjectFund
import com.saif.mosque.data.model.projectBudget
import com.saif.mosque.data.model.withProjectBudget
import com.saif.mosque.data.outbox.DirectoryOutbox
import com.saif.mosque.data.outbox.DirectorySync
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.ReceiptSlipDialog
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.ReceiptData
import com.saif.mosque.util.UiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ProjectsViewModel(
    private val repo: LedgerRepository,
    private val queue: LedgerQueue,
    private val outbox: DirectoryOutbox,
    private val auth: com.google.firebase.auth.FirebaseAuth,
    private val context: android.content.Context,
    private val mosqueId: String,
    val user: AppUser
) : ViewModel() {
    val canEdit = Permissions.canEditFunds(user.role)

    val state: StateFlow<UiState<List<Fund>>> = repo.observeFunds(mosqueId)
        .map { list ->
            // Prioritize funds that are project funds or have budgets
            val projects = list.filter { it.isProjectFund() || it.code in setOf("CON", "WAK") }
            UiState.Success(if (projects.isNotEmpty()) projects else list) as UiState<List<Fund>>
        }
        .catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    fun addProject(
        code: String,
        name: String,
        budget: Double,
        description: String,
        onDone: () -> Unit
    ) = viewModelScope.launch {
        try {
            val baseFund = Fund(
                code = code.trim().uppercase(),
                name = name.trim(),
                nameEn = "",
                active = true,
                description = description.trim()
            )
            val fundWithBudget = baseFund.withProjectBudget(budget)
            val draft = outbox.addFund(user.id, mosqueId, fundWithBudget)
            runCatching {
                DirectorySync.schedule(context, draft.id)
                DirectorySync.scheduleRecovery(context)
            }
            onDone()
            _messages.emit(Msg.Res(R.string.saved_ok))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(Msg.Text(e.message ?: "error"))
        }
    }

    fun updateProjectBudget(
        fund: Fund,
        newBudget: Double,
        onDone: () -> Unit
    ) = viewModelScope.launch {
        try {
            val updated = fund.withProjectBudget(newBudget)
            val draft = outbox.editFund(user.id, mosqueId, fund, updated)
            runCatching {
                DirectorySync.schedule(context, draft.id)
                DirectorySync.scheduleRecovery(context)
            }
            onDone()
            _messages.emit(Msg.Res(R.string.saved_ok))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(Msg.Text(e.message ?: "error"))
        }
    }

    fun collectDonation(
        fund: Fund,
        donorName: String,
        donorPhone: String,
        amount: Double,
        date: String,
        receiptNo: String,
        note: String,
        onDone: (ReceiptData) -> Unit
    ) = viewModelScope.launch {
        try {
            val draft = PrototypeLedgerDraft(
                id = UUID.randomUUID().toString(),
                uid = user.id,
                mosqueId = mosqueId,
                kind = "contributions",
                amountPoisha = LedgerMath.poisha(amount, true),
                sourceFundId = fund.id,
                donorId = "",
                donorName = donorName.trim().ifBlank { "সাধারণ দাতা" },
                receiptNo = receiptNo.trim(),
                date = date.trim(),
                note = "${fund.name}: ${note.trim()}",
                createdAt = System.currentTimeMillis()
            )
            queue.enqueue(draft)

            val receipt = ReceiptData(
                mosqueName = "",
                receiptNo = receiptNo.trim(),
                donorName = donorName.trim(),
                donorPhone = donorPhone.trim(),
                amount = amount,
                date = date.trim(),
                fundName = fund.name,
                purpose = "${fund.name} অনুদান",
                receivedBy = user.name
            )
            onDone(receipt)
            _messages.emit(Msg.Res(R.string.local_pending_saved))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(Msg.Text(e.message ?: "error"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val vm: ProjectsViewModel = viewModel {
        ProjectsViewModel(
            container.ledgerRepository,
            container.ledgerQueue,
            container.outbox,
            container.auth,
            container.context,
            mosqueId,
            user
        )
    }

    val state by vm.state.collectAsStateWithLifecycle()
    var showAddProjectDialog by remember { mutableStateOf(false) }
    var collectingForFund by remember { mutableStateOf<Fund?>(null) }
    var editingBudgetForFund by remember { mutableStateOf<Fund?>(null) }
    var activeReceiptData by remember { mutableStateOf<ReceiptData?>(null) }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (vm.canEdit) {
                FloatingActionButton(
                    onClick = { showAddProjectDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "নতুন প্রজেক্ট")
                }
            }
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("বিশেষ ইভেন্ট ও প্রজেক্ট ফান্ড", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("নির্মাণ, এসি, ইফতার ও ঈদ বোনাস ট্র্যাকার", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                is UiState.Loading -> com.saif.mosque.ui.components.CenteredProgress()
                is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is UiState.Success -> {
                    val list = s.data
                    if (list.isEmpty()) {
                        EmptyBox("কোন বিশেষ প্রজেক্ট বা ইভেন্ট ফান্ড পাওয়া যায়নি। '+' বাটনে চেপে নতুন প্রজেক্ট তৈরি করুন।")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(list, key = { it.id }) { fund ->
                                ProjectFundCard(
                                    fund = fund,
                                    canEdit = vm.canEdit,
                                    onCollect = { collectingForFund = fund },
                                    onEditBudget = { editingBudgetForFund = fund }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Project Dialog
    if (showAddProjectDialog) {
        AddProjectDialog(
            onDismiss = { showAddProjectDialog = false },
            onConfirm = { code, name, budget, desc ->
                vm.addProject(code, name, budget, desc) {
                    showAddProjectDialog = false
                }
            }
        )
    }

    // Edit Budget Dialog
    editingBudgetForFund?.let { fund ->
        EditProjectBudgetDialog(
            fund = fund,
            currentBudget = fund.projectBudget() ?: 0.0,
            onDismiss = { editingBudgetForFund = null },
            onSave = { newBudget ->
                vm.updateProjectBudget(fund, newBudget) {
                    editingBudgetForFund = null
                }
            }
        )
    }

    // Collect Donation for Project Dialog
    collectingForFund?.let { fund ->
        CollectProjectDonationDialog(
            fund = fund,
            onDismiss = { collectingForFund = null },
            onConfirm = { donorName, donorPhone, amount, date, receiptNo, note ->
                vm.collectDonation(
                    fund = fund,
                    donorName = donorName,
                    donorPhone = donorPhone,
                    amount = amount,
                    date = date,
                    receiptNo = receiptNo,
                    note = note
                ) { receipt ->
                    collectingForFund = null
                    activeReceiptData = receipt
                }
            }
        )
    }

    // Receipt Slip Dialog
    activeReceiptData?.let { receipt ->
        ReceiptSlipDialog(
            receipt = receipt,
            onDismiss = { activeReceiptData = null }
        )
    }
}

@Composable
private fun ProjectFundCard(
    fund: Fund,
    canEdit: Boolean,
    onCollect: () -> Unit,
    onEditBudget: () -> Unit
) {
    val budget = fund.projectBudget() ?: 0.0
    val collected = fund.balance
    val progress = if (budget > 0.0) (collected / budget).toFloat().coerceIn(0f, 1f) else 1f
    val percentInt = (progress * 100).toInt()
    val remaining = (budget - collected).coerceAtLeast(0.0)

    val cleanDesc = fund.cleanDescription()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Column {
                        Text(fund.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("কোড: ${fund.code}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (canEdit) {
                    IconButton(onClick = onEditBudget, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = "বাজেট পরিবর্তন", modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (cleanDesc.isNotBlank()) {
                Text(cleanDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Stats row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("মোট আদায়", style = MaterialTheme.typography.labelSmall)
                    Text(Format.money(collected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                if (budget > 0.0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("নির্ধারিত বাজেট", style = MaterialTheme.typography.labelSmall)
                        Text(Format.money(budget), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Progress bar if budget set
            if (budget > 0.0) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("অগ্রগতি: $percentInt%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        if (remaining > 0.0) {
                            Text("বাকি: ${Format.money(remaining)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("বাজেট সম্পন্ন!", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Action: Collect donation for this project
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onCollect,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Filled.VolunteerActivism, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("দান গ্রহণ করুন", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AddProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, name: String, budget: Double, description: String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var budgetText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("নতুন প্রজেক্ট বা ড্রাইভ তৈরি") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMsg = null },
                    label = { Text("প্রজেক্টের নাম *") },
                    placeholder = { Text("যেমন: নতুন মিনার নির্মাণ, এসি ক্রয়") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; errorMsg = null },
                    label = { Text("কোড (সংক্ষিপ্ত ৩-৫ অক্ষর) *") },
                    placeholder = { Text("যেমন: MINAR, AC, IFTAR, EID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it },
                    label = { Text("লক্ষ্যমাত্রা / বাজেট (৳)") },
                    placeholder = { Text("যেমন: ৫০০০০০") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("বিবরণ (ঐচ্ছিক)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    if (name.isBlank() || code.isBlank()) {
                        errorMsg = "নাম এবং কোড আবশ্যক"
                        return@Button
                    }
                    val budget = budgetText.toDoubleOrNull() ?: 0.0
                    isSubmitting = true
                    onConfirm(code.trim(), name.trim(), budget, description.trim())
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun EditProjectBudgetDialog(
    fund: Fund,
    currentBudget: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var budgetText by remember {
        mutableStateOf(if (currentBudget > 0) currentBudget.toLong().toString() else "")
    }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("প্রজেক্ট বাজেট নির্ধারণ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("প্রজেক্ট: ${fund.name}", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it },
                    label = { Text("লক্ষ্যমাত্রা / বাজেট (৳) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val amt = budgetText.toDoubleOrNull() ?: 0.0
                    isSubmitting = true
                    onSave(amt)
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun CollectProjectDonationDialog(
    fund: Fund,
    onDismiss: () -> Unit,
    onConfirm: (donorName: String, donorPhone: String, amount: Double, date: String, receiptNo: String, note: String) -> Unit
) {
    var donorName by remember { mutableStateOf("") }
    var donorPhone by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(Format.todayIso()) }
    var receiptNo by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("${fund.name}-এ অনুদান গ্রহণ") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; errorMsg = null },
                    label = { Text("দানের পরিমাণ (৳) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = donorName,
                    onValueChange = { donorName = it },
                    label = { Text("দাতার নাম (ঐচ্ছিক)") },
                    placeholder = { Text("যেমন: হাজী আব্দুস সামাদ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = donorPhone,
                    onValueChange = { donorPhone = it },
                    label = { Text("দাতার মোবাইল নং (ঐচ্ছিক - রসিদ পাঠাতে)") },
                    placeholder = { Text("017XXXXXXXX") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("তারিখ (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = receiptNo,
                    onValueChange = { receiptNo = it },
                    label = { Text("রশিদ নং (ঐচ্ছিক)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("মন্তব্য") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    val amt = amountText.toDoubleOrNull()
                    if (amt == null || amt <= 0.0) {
                        errorMsg = "সঠিক পরিমাণ লিখুন"
                        return@Button
                    }
                    isSubmitting = true
                    onConfirm(
                        donorName.trim(),
                        donorPhone.trim(),
                        amt,
                        date.trim(),
                        receiptNo.trim(),
                        note.trim()
                    )
                }
            ) {
                Text("জমা করুন")
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
