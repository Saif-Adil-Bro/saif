package com.saif.mosque.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.google.firebase.auth.FirebaseAuth
import com.saif.mosque.R
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.ledger.LedgerMath
import com.saif.mosque.data.ledger.LedgerQueue
import com.saif.mosque.data.ledger.PrototypeLedgerDraft
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Contribution
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.MemberMonthlyStatus
import com.saif.mosque.data.model.Mosque
import com.saif.mosque.data.model.cleanDisplayAddress
import com.saif.mosque.data.model.isSubscriptionMember
import com.saif.mosque.data.model.monthlySubscriptionAmount
import com.saif.mosque.data.model.withSubscriptionAmount
import com.saif.mosque.data.outbox.DirectoryOutbox
import com.saif.mosque.data.outbox.DirectorySync
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.di.AppContainer
import com.saif.mosque.ui.components.EmptyBox
import com.saif.mosque.ui.components.LabeledField
import com.saif.mosque.ui.components.MoneyText
import com.saif.mosque.ui.components.StatCard
import com.saif.mosque.util.Format
import com.saif.mosque.util.Msg
import com.saif.mosque.util.Permissions
import com.saif.mosque.util.SubscriptionPdfExporter
import com.saif.mosque.util.UiState
import com.saif.mosque.util.VoiceExtractedItem
import com.saif.mosque.util.VoiceLedgerParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

class SubscriptionViewModel(
    private val repo: LedgerRepository,
    private val queue: LedgerQueue,
    private val outbox: DirectoryOutbox,
    private val auth: FirebaseAuth,
    private val context: Context,
    private val mosqueId: String,
    private val user: AppUser,
    private val session: SessionManager
) : ViewModel() {

    val canEdit = Permissions.canEditDonors(user.role)
    val canCollect = Permissions.canEditContributions(user.role)

    private val _messages = MutableSharedFlow<Msg>()
    val messages = _messages.asSharedFlow()

    private val _yearMonth = MutableStateFlow(Format.currentYearMonth())
    val yearMonth: StateFlow<Pair<Int, Int>> = _yearMonth

    val mosque: StateFlow<Mosque?> = repo.observeMosque(mosqueId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val funds: StateFlow<List<Fund>> = repo.observeFunds(mosqueId)
        .map { it.filter { f -> f.active } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedFundId: StateFlow<String?> = session.subscriptionFundFlow(mosqueId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allDonors: StateFlow<List<Donor>> = repo.observeDonors(mosqueId)
        .map { it.filter { d -> d.active } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val monthContributions: StateFlow<List<Contribution>> = _yearMonth.flatMapLatest { (y, m) ->
        val (start, end) = Format.yearMonthIso(y, m)
        repo.observeMonthContributions(mosqueId, start, end).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val membersStatus: StateFlow<UiState<List<MemberMonthlyStatus>>> = combine(
        allDonors,
        monthContributions
    ) { donors, contributions ->
        val subDonors = donors.filter { it.isSubscriptionMember() }
        val list = subDonors.map { d ->
            val pledged = d.monthlySubscriptionAmount() ?: 0.0
            val related = contributions.filter { c ->
                (c.donorId.isNotBlank() && c.donorId == d.id) ||
                    (c.donorId.isBlank() && c.donorName.trim().equals(d.name.trim(), ignoreCase = true))
            }
            val paid = related.sumOf { Math.round(it.amount * 100) } / 100.0
            val lastContribution = related.maxByOrNull { it.createdAt }
            MemberMonthlyStatus(
                donor = d,
                monthlyAmount = pledged,
                paidAmount = paid,
                lastPaymentDate = lastContribution?.date,
                lastReceiptNo = lastContribution?.receiptNo?.ifBlank { null }
            )
        }.sortedWith(compareBy<MemberMonthlyStatus> { it.isPaid }.thenBy { it.donor.name })
        UiState.Success(list) as UiState<List<MemberMonthlyStatus>>
    }.catch { emit(UiState.Error(it.message ?: "error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    var voiceReviewItems by mutableStateOf<List<VoiceExtractedItem>?>(null)
        private set

    fun prevMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).minusMonths(1)
        _yearMonth.value = d.year to d.monthValue
    }

    fun nextMonth() {
        val d = LocalDate.of(_yearMonth.value.first, _yearMonth.value.second, 1).plusMonths(1)
        _yearMonth.value = d.year to d.monthValue
    }

    fun selectFund(fundId: String) = viewModelScope.launch {
        session.setSubscriptionFund(mosqueId, fundId)
    }

    fun effectiveFund(fundsList: List<Fund>, savedId: String?): Fund? {
        if (fundsList.isEmpty()) return null
        if (!savedId.isNullOrBlank()) {
            fundsList.find { it.id == savedId }?.let { return it }
        }
        return fundsList.find { it.code.equals("CHD", ignoreCase = true) }
            ?: fundsList.find { it.code.equals("GEN", ignoreCase = true) }
            ?: fundsList.firstOrNull()
    }

    fun addMember(name: String, amount: Double, phone: String, address: String, onDone: () -> Unit) {
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val cleanAddress = address.trim()
        val d = Donor(
            name = cleanName,
            phone = cleanPhone,
            address = cleanAddress
        ).withSubscriptionAmount(amount, cleanAddress)

        viewModelScope.launch {
            try {
                val draft = outbox.addDonor(user.id, mosqueId, d)
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
    }

    fun updateMember(old: Donor, name: String, amount: Double, phone: String, address: String, onDone: () -> Unit) {
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val cleanAddress = address.trim()
        val next = old.copy(
            name = cleanName,
            phone = cleanPhone,
            address = cleanAddress
        ).withSubscriptionAmount(amount, cleanAddress)

        viewModelScope.launch {
            try {
                val draft = outbox.editDonor(user.id, mosqueId, old, next)
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
    }

    fun deleteMember(donor: Donor) = viewModelScope.launch {
        try {
            val draft = outbox.editDonor(user.id, mosqueId, donor, donor.copy(active = false), archive = true)
            runCatching {
                DirectorySync.schedule(context, draft.id)
                DirectorySync.scheduleRecovery(context)
            }
            _messages.emit(Msg.Res(R.string.deleted_ok))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(Msg.Text(e.message ?: "error"))
        }
    }

    fun collectDues(
        donor: Donor,
        amount: Double,
        fundId: String,
        date: String,
        receiptNo: String,
        note: String,
        onDone: () -> Unit
    ) = viewModelScope.launch {
        try {
            queue.enqueue(
                PrototypeLedgerDraft(
                    id = UUID.randomUUID().toString(),
                    uid = user.id,
                    mosqueId = mosqueId,
                    kind = "contributions",
                    amountPoisha = LedgerMath.poisha(amount, true),
                    sourceFundId = fundId,
                    donorId = donor.id,
                    donorName = donor.name,
                    receiptNo = receiptNo.trim(),
                    date = date,
                    note = note.trim(),
                    createdAt = System.currentTimeMillis()
                )
            )
            onDone()
            _messages.emit(Msg.Res(R.string.local_pending_saved))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.emit(Msg.Text(e.message ?: "error"))
        }
    }

    fun onVoiceRecognized(spokenText: String) {
        val extracted = VoiceLedgerParser.parse(spokenText, allDonors.value)
        if (extracted.isEmpty()) {
            viewModelScope.launch { _messages.emit(Msg.Res(R.string.no_voice_match)) }
        } else {
            voiceReviewItems = extracted
        }
    }

    fun dismissVoiceReview() {
        voiceReviewItems = null
    }

    fun updateVoiceItem(index: Int, updated: VoiceExtractedItem) {
        val current = voiceReviewItems?.toMutableList() ?: return
        if (index in current.indices) {
            current[index] = updated
            voiceReviewItems = current
        }
    }

    fun removeVoiceItem(index: Int) {
        val current = voiceReviewItems?.toMutableList() ?: return
        if (index in current.indices) {
            current.removeAt(index)
            voiceReviewItems = if (current.isEmpty()) null else current
        }
    }

    fun commitVoiceCollection(fundId: String, items: List<VoiceExtractedItem>) = viewModelScope.launch {
        val (y, m) = _yearMonth.value
        val today = LocalDate.now()
        val paymentDate = if (today.year == y && today.monthValue == m) {
            today.toString()
        } else {
            LocalDate.of(y, m, 1).toString()
        }
        val monthLabel = Format.monthLabel(context, m, y)
        val note = "${context.getString(R.string.monthly_subscription)}: $monthLabel"

        var count = 0
        for (item in items) {
            if (item.amount <= 0.0) continue
            val donorId = item.matchedDonorId
            val donorName = item.matchedDonorName ?: item.rawName.trim()

            try {
                var actualDonorId = donorId
                if (actualDonorId.isNullOrBlank()) {
                    // Create new donor with subscription pledge
                    val newDonor = Donor(
                        name = donorName,
                        phone = "",
                        address = ""
                    ).withSubscriptionAmount(item.amount)
                    val draft = outbox.addDonor(user.id, mosqueId, newDonor)
                    actualDonorId = draft.id
                    runCatching {
                        DirectorySync.schedule(context, draft.id)
                        DirectorySync.scheduleRecovery(context)
                    }
                }

                queue.enqueue(
                    PrototypeLedgerDraft(
                        id = UUID.randomUUID().toString(),
                        uid = user.id,
                        mosqueId = mosqueId,
                        kind = "contributions",
                        amountPoisha = LedgerMath.poisha(item.amount, true),
                        sourceFundId = fundId,
                        donorId = actualDonorId ?: "",
                        donorName = donorName,
                        receiptNo = "",
                        date = paymentDate,
                        note = note,
                        createdAt = System.currentTimeMillis()
                    )
                )
                count++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep processing remaining items
            }
        }
        voiceReviewItems = null
        _messages.emit(Msg.Res(R.string.saved_ok))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(container: AppContainer, user: AppUser) {
    val mosqueId = user.mosqueId ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val vm: SubscriptionViewModel = viewModel {
        SubscriptionViewModel(
            container.ledgerRepository,
            container.ledgerQueue,
            container.outbox,
            container.auth,
            container.context,
            mosqueId,
            user,
            container.session
        )
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            val text = when (msg) {
                is Msg.Res -> context.getString(msg.resId)
                is Msg.Text -> msg.message
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    val ym by vm.yearMonth.collectAsStateWithLifecycle()
    val fundsList by vm.funds.collectAsStateWithLifecycle()
    val savedFundId by vm.savedFundId.collectAsStateWithLifecycle()
    val allDonorsList by vm.allDonors.collectAsStateWithLifecycle()
    val mosqueInfo by vm.mosque.collectAsStateWithLifecycle()
    val state by vm.membersStatus.collectAsStateWithLifecycle()

    val currentFund = remember(fundsList, savedFundId) {
        vm.effectiveFund(fundsList, savedFundId)
    }

    var selectedTab by remember { mutableStateOf(0) } // 0: All, 1: Pending, 2: Paid
    var showAddDialog by remember { mutableStateOf(false) }
    var editingMember by remember { mutableStateOf<Donor?>(null) }
    var collectingForMember by remember { mutableStateOf<MemberMonthlyStatus?>(null) }

    // Speech Recognition Activity Result Launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                vm.onVoiceRecognized(spoken)
            }
        }
    }

    fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_input_hint))
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                }
                speechLauncher.launch(fallback)
            } catch (_: Exception) {
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_generic)) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (vm.canEdit) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_subscription_member))
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
            // Month navigation and Action bar
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.prevMonth() }) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.prev_month))
                    }
                    Text(
                        Format.monthLabel(context, ym.second, ym.first),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { vm.nextMonth() }) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.next_month))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalIconButton(onClick = { launchSpeech() }) {
                        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.voice_input))
                    }
                    IconButton(onClick = {
                        val currentList = (state as? UiState.Success)?.data ?: emptyList()
                        val fundTitle = currentFund?.let { "${it.code} ${it.name}" } ?: "সাধারণ তহবিল"
                        val monthLabel = Format.monthLabel(context, ym.second, ym.first)
                        SubscriptionPdfExporter.printMonthlyReport(
                            context = context,
                            mosque = mosqueInfo,
                            monthYearLabel = monthLabel,
                            fundName = fundTitle,
                            members = currentList
                        )
                    }) {
                        Icon(Icons.Filled.Print, contentDescription = stringResource(R.string.print_pdf_report))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Fund selection dropdown
            if (fundsList.isNotEmpty()) {
                var fundDropdownExpanded by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.destination_fund) + ":",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    ExposedDropdownMenuBox(
                        expanded = fundDropdownExpanded,
                        onExpandedChange = { fundDropdownExpanded = !fundDropdownExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = currentFund?.let { "${it.code} - ${it.name}" } ?: stringResource(R.string.select_option),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fundDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = fundDropdownExpanded,
                            onDismissRequest = { fundDropdownExpanded = false }
                        ) {
                            fundsList.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text("${f.code} - ${f.name}") },
                                    onClick = {
                                        vm.selectFund(f.id)
                                        fundDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                is UiState.Loading -> com.saif.mosque.ui.components.CenteredProgress()
                is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is UiState.Success -> {
                    val list = s.data
                    val totalMembers = list.size
                    val totalTarget = list.sumOf { it.monthlyAmount }
                    val totalCollected = list.sumOf { it.paidAmount }
                    val totalPending = (totalTarget - totalCollected).coerceAtLeast(0.0)
                    val paidCount = list.count { it.isPaid }
                    val pendingCount = totalMembers - paidCount

                    val filteredList = when (selectedTab) {
                        1 -> list.filter { !it.isPaid }
                        2 -> list.filter { it.isPaid }
                        else -> list
                    }

                    // KPI Summary Cards
                    com.saif.mosque.ui.components.StatsGrid(
                        listOf(
                            com.saif.mosque.ui.components.StatItem(
                                stringResource(R.string.total_members),
                                "$totalMembers জন"
                            ),
                            com.saif.mosque.ui.components.StatItem(
                                stringResource(R.string.collected_amount),
                                Format.money(totalCollected),
                                MaterialTheme.colorScheme.primary
                            ),
                            com.saif.mosque.ui.components.StatItem(
                                stringResource(R.string.pending_amount),
                                Format.money(totalPending),
                                if (totalPending > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            ),
                            com.saif.mosque.ui.components.StatItem(
                                stringResource(R.string.collection_rate),
                                if (totalTarget > 0) "${((totalCollected / totalTarget) * 100).toInt()}%" else "০%"
                            )
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    // Filter Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            label = { Text("${stringResource(R.string.all)} ($totalMembers)") }
                        )
                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            label = { Text("${stringResource(R.string.pending_status)} ($pendingCount)") }
                        )
                        FilterChip(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            label = { Text("${stringResource(R.string.collected_status)} ($paidCount)") }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (filteredList.isEmpty()) {
                        EmptyBox()
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(filteredList, key = { it.donor.id }) { item ->
                                SubscriptionMemberCard(
                                    status = item,
                                    canCollect = vm.canCollect,
                                    canEdit = vm.canEdit,
                                    onCall = { phone ->
                                        try {
                                            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                                                data = Uri.parse("tel:$phone")
                                            }
                                            context.startActivity(dialIntent)
                                        } catch (_: Exception) {
                                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.call_failed)) }
                                        }
                                    },
                                    onCollect = { collectingForMember = item },
                                    onEdit = { editingMember = item.donor },
                                    onDelete = { vm.deleteMember(item.donor) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Member Dialog
    if (showAddDialog) {
        AddOrEditMemberDialog(
            initialDonor = null,
            allExistingDonors = allDonorsList,
            onDismiss = { showAddDialog = false },
            onSave = { name, amount, phone, address ->
                vm.addMember(name, amount, phone, address) { showAddDialog = false }
            }
        )
    }

    // Edit Member Dialog
    editingMember?.let { donorToEdit ->
        AddOrEditMemberDialog(
            initialDonor = donorToEdit,
            allExistingDonors = emptyList(),
            onDismiss = { editingMember = null },
            onSave = { name, amount, phone, address ->
                vm.updateMember(donorToEdit, name, amount, phone, address) { editingMember = null }
            }
        )
    }

    // Collect Dues Dialog
    collectingForMember?.let { memberStatus ->
        val fund = currentFund ?: fundsList.firstOrNull()
        if (fund != null) {
            val (y, m) = ym
            val today = LocalDate.now()
            val initialDate = if (today.year == y && today.monthValue == m) today.toString() else LocalDate.of(y, m, 1).toString()
            val remainingAmount = (memberStatus.monthlyAmount - memberStatus.paidAmount).coerceAtLeast(0.0)
            val initialAmount = if (remainingAmount > 0.0) remainingAmount else memberStatus.monthlyAmount
            val monthLabel = Format.monthLabel(context, m, y)

            CollectDuesDialog(
                memberName = memberStatus.donor.name,
                monthLabel = monthLabel,
                fundName = "${fund.code} - ${fund.name}",
                initialAmount = initialAmount,
                initialDate = initialDate,
                onDismiss = { collectingForMember = null },
                onConfirm = { amount, date, receiptNo, note ->
                    vm.collectDues(
                        donor = memberStatus.donor,
                        amount = amount,
                        fundId = fund.id,
                        date = date,
                        receiptNo = receiptNo,
                        note = note
                    ) { collectingForMember = null }
                }
            )
        }
    }

    // Voice Review Dialog
    vm.voiceReviewItems?.let { items ->
        val fund = currentFund ?: fundsList.firstOrNull()
        if (fund != null) {
            VoiceReviewDialog(
                items = items,
                availableDonors = allDonorsList,
                fundName = "${fund.code} - ${fund.name}",
                onDismiss = { vm.dismissVoiceReview() },
                onItemUpdate = { idx, updated -> vm.updateVoiceItem(idx, updated) },
                onItemDelete = { idx -> vm.removeVoiceItem(idx) },
                onCommitAll = { vm.commitVoiceCollection(fund.id, items) }
            )
        }
    }
}

@Composable
private fun SubscriptionMemberCard(
    status: MemberMonthlyStatus,
    canCollect: Boolean,
    canEdit: Boolean,
    onCall: (String) -> Unit,
    onCollect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // Row 1: Name and Status Badge
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    status.donor.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = when {
                        status.isPaid -> MaterialTheme.colorScheme.primaryContainer
                        status.isPartial -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Text(
                        text = when {
                            status.isPaid -> stringResource(R.string.collected_status)
                            status.isPartial -> stringResource(R.string.partial_status)
                            else -> stringResource(R.string.pending_status)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            status.isPaid -> MaterialTheme.colorScheme.onPrimaryContainer
                            status.isPartial -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Row 2: Amounts
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${stringResource(R.string.total_target)}: ${Format.money(status.monthlyAmount)} / মাস",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${stringResource(R.string.collected_amount)}: ${Format.money(status.paidAmount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status.paidAmount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Row 3: Phone with Call Button (if present)
            if (status.donor.phone.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Phone,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            status.donor.phone,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Direct Call Button
                    FilledTonalButton(
                        onClick = { onCall(status.donor.phone) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.call_member), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.call_member), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Address if present
            val address = status.donor.cleanDisplayAddress()
            if (address.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action row
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Collect Button
                if (canCollect) {
                    Button(
                        onClick = onCollect,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (status.isPaid) stringResource(R.string.add) else stringResource(R.string.collect_dues),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                // Edit / Delete buttons
                if (canEdit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddOrEditMemberDialog(
    initialDonor: Donor?,
    allExistingDonors: List<Donor>,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: Double, phone: String, address: String) -> Unit
) {
    var name by remember { mutableStateOf(initialDonor?.name ?: "") }
    var amountText by remember {
        mutableStateOf(
            initialDonor?.monthlySubscriptionAmount()?.let {
                if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
            } ?: ""
        )
    }
    var phone by remember { mutableStateOf(initialDonor?.phone ?: "") }
    var address by remember { mutableStateOf(initialDonor?.cleanDisplayAddress() ?: "") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Donor picker for new member
    val nonSubDonors = remember(allExistingDonors) {
        allExistingDonors.filter { !it.isSubscriptionMember() }
    }
    var existingDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialDonor == null) stringResource(R.string.add_subscription_member)
                else stringResource(R.string.edit_subscription_member)
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // If creating new member and there are regular donors without subscriptions
                if (initialDonor == null && nonSubDonors.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = existingDropdownExpanded,
                        onExpandedChange = { existingDropdownExpanded = !existingDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = stringResource(R.string.select_existing_donor),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = existingDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        ExposedDropdownMenu(
                            expanded = existingDropdownExpanded,
                            onDismissRequest = { existingDropdownExpanded = false }
                        ) {
                            nonSubDonors.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text("${d.name} (${d.phone.ifBlank { "ফোন নম্বর নেই" }})") },
                                    onClick = {
                                        name = d.name
                                        phone = d.phone
                                        address = d.address
                                        existingDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMsg = null },
                    label = { Text(stringResource(R.string.name) + " *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; errorMsg = null },
                    label = { Text(stringResource(R.string.monthly_amount) + " *") },
                    placeholder = { Text("৫০০") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; errorMsg = null },
                    label = { Text(stringResource(R.string.phone) + " (ঐচ্ছিক)") },
                    placeholder = { Text("01XXXXXXXXX") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.address) + " (ঐচ্ছিক)") },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedAmount = VoiceLedgerParser.normalizeBengaliDigits(amountText).toDoubleOrNull()
                if (name.isBlank()) {
                    errorMsg = "নাম আবশ্যক"
                    return@Button
                }
                if (parsedAmount == null || parsedAmount <= 0.0) {
                    errorMsg = "সঠিক মাসিক টাকার পরিমাণ দিন"
                    return@Button
                }
                onSave(name.trim(), parsedAmount, phone.trim(), address.trim())
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun CollectDuesDialog(
    memberName: String,
    monthLabel: String,
    fundName: String,
    initialAmount: Double,
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, date: String, receiptNo: String, note: String) -> Unit
) {
    var amountText by remember {
        mutableStateOf(if (initialAmount % 1.0 == 0.0) initialAmount.toLong().toString() else initialAmount.toString())
    }
    var date by remember { mutableStateOf(initialDate) }
    var receiptNo by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("মাসিক বেতন/চাঁদা: $monthLabel") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collect_for_month, monthLabel)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "সদস্য: $memberName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "ফান্ড: $fundName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; errorMsg = null },
                    label = { Text(stringResource(R.string.amount) + " (৳) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text(stringResource(R.string.date) + " (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = receiptNo,
                    onValueChange = { receiptNo = it },
                    label = { Text("রসিদ নং (ঐচ্ছিক)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = VoiceLedgerParser.normalizeBengaliDigits(amountText).toDoubleOrNull()
                if (amt == null || amt <= 0.0) {
                    errorMsg = "সঠিক পরিমাণ দিন"
                    return@Button
                }
                onConfirm(amt, date.trim(), receiptNo.trim(), note.trim())
            }) {
                Text(stringResource(R.string.collect_dues))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceReviewDialog(
    items: List<VoiceExtractedItem>,
    availableDonors: List<Donor>,
    fundName: String,
    onDismiss: () -> Unit,
    onItemUpdate: (Int, VoiceExtractedItem) -> Unit,
    onItemDelete: (Int) -> Unit,
    onCommitAll: () -> Unit
) {
    val totalAmount = items.sumOf { it.amount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.voice_results_title), fontWeight = FontWeight.Bold)
                Text(
                    "মোট ${items.size} জন · সর্বমোট ৳ ${String.format("%.0f", totalAmount)} (ফান্ড: $fundName)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items.size) { idx ->
                    val item = items[idx]
                    var nameText by remember(item.rawName) { mutableStateOf(item.rawName) }
                    var amountText by remember(item.amount) {
                        mutableStateOf(if (item.amount % 1.0 == 0.0) item.amount.toLong().toString() else item.amount.toString())
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    item.matchedDonorName ?: item.rawName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onItemDelete(idx) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = nameText,
                                    onValueChange = {
                                        nameText = it
                                        val matched = VoiceLedgerParser.findBestMatchingDonor(it, availableDonors)
                                        onItemUpdate(
                                            idx,
                                            item.copy(
                                                rawName = it,
                                                matchedDonorId = matched?.id,
                                                matchedDonorName = matched?.name
                                            )
                                        )
                                    },
                                    label = { Text(stringResource(R.string.name)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.2f)
                                )

                                OutlinedTextField(
                                    value = amountText,
                                    onValueChange = {
                                        amountText = it
                                        val num = VoiceLedgerParser.normalizeBengaliDigits(it).toDoubleOrNull() ?: 0.0
                                        onItemUpdate(idx, item.copy(amount = num))
                                    },
                                    label = { Text("টাকা (৳)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (item.matchedDonorId != null) {
                                Text(
                                    "✓ সদস্য হিসেবে চিহ্নিত: ${item.matchedDonorName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    "+ নতুন সদস্য হিসেবে তৈরি হবে",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCommitAll) {
                Text(stringResource(R.string.collect_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
