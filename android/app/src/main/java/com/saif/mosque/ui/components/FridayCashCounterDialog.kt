package com.saif.mosque.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saif.mosque.R
import com.saif.mosque.data.model.Fund
import com.saif.mosque.util.Format
import java.time.LocalDate

private val DENOMINATIONS = listOf(1000, 500, 200, 100, 50, 20, 10, 5, 2, 1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridayCashCounterDialog(
    funds: List<Fund>,
    defaultFundId: String? = null,
    onDismiss: () -> Unit,
    onSaveCollection: (
        amount: Double,
        fundId: String,
        date: String,
        receiptNo: String,
        note: String
    ) -> Unit
) {
    val counts = remember {
        mutableStateMapOf<Int, Int>().apply {
            DENOMINATIONS.forEach { put(it, 0) }
        }
    }

    var selectedFundId by remember {
        mutableStateOf(
            defaultFundId ?: funds.find { it.code == "GEN" }?.id ?: funds.firstOrNull()?.id ?: ""
        )
    }
    var collectionDate by remember { mutableStateOf(Format.todayIso()) }
    var witnessesText by remember { mutableStateOf("") }
    var fundDropdownExpanded by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val totalAmount = remember(counts.values.sum()) {
        DENOMINATIONS.sumOf { denom -> (counts[denom] ?: 0) * denom.toDouble() }
    }
    val totalNoteCount = remember(counts.values.sum()) {
        counts.values.sum()
    }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Calculate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text("জুমার বাক্স গণনা (Cash Counter)", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Total Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "সর্বমোট গণনাকৃত টাকা",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Format.money(totalAmount),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "মোট নোট ও কয়েন: ${Format.bn(totalNoteCount)} টি",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                // Reset button
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            DENOMINATIONS.forEach { counts[it] = 0 }
                        },
                        enabled = totalNoteCount > 0 && !isSubmitting
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("পুনরায় শূন্য করুন", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Denomination Rows
                Text(
                    "নোটের সংখ্যা বসান:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                DENOMINATIONS.forEach { denom ->
                    val count = counts[denom] ?: 0
                    val subtotal = count * denom.toDouble()

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (count > 0) 1.5.dp else 0.5.dp,
                                color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (count > 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Denomination Badge
                            Column(Modifier.width(80.dp)) {
                                Text(
                                    "৳ ${Format.bn(denom)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (denom >= 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "= ${Format.money(subtotal)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Stepper Controls
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (count > 0) counts[denom] = count - 1
                                    },
                                    enabled = count > 0 && !isSubmitting,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Filled.Remove, contentDescription = "কম", modifier = Modifier.size(14.dp))
                                }

                                Text(
                                    Format.bn(count),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(36.dp),
                                    textAlign = TextAlign.Center
                                )

                                FilledTonalIconButton(
                                    onClick = { counts[denom] = count + 1 },
                                    enabled = !isSubmitting,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "যোগ", modifier = Modifier.size(14.dp))
                                }

                                Spacer(Modifier.width(4.dp))

                                // Quick +5 / +10
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = { counts[denom] = count + 5 },
                                            enabled = !isSubmitting,
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("+৫", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(
                                            onClick = { counts[denom] = count + 10 },
                                            enabled = !isSubmitting,
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("+১০", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Divider(Modifier.padding(vertical = 4.dp))

                // Target Fund Dropdown
                ExposedDropdownMenuBox(
                    expanded = fundDropdownExpanded,
                    onExpandedChange = { fundDropdownExpanded = !fundDropdownExpanded }
                ) {
                    val currentFund = funds.find { it.id == selectedFundId }
                    OutlinedTextField(
                        value = currentFund?.let { "${it.code} - ${it.name}" } ?: "ফান্ড নির্বাচন করুন",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("জমা ফান্ড *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fundDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSubmitting
                    )
                    ExposedDropdownMenu(
                        expanded = fundDropdownExpanded,
                        onDismissRequest = { fundDropdownExpanded = false }
                    ) {
                        funds.filter { it.active }.forEach { f ->
                            DropdownMenuItem(
                                text = { Text("${f.code} - ${f.name}") },
                                onClick = {
                                    selectedFundId = f.id
                                    fundDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Date
                OutlinedTextField(
                    value = collectionDate,
                    onValueChange = { collectionDate = it },
                    label = { Text("তারিখ (YYYY-MM-DD)") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )

                // Witnesses / Note
                OutlinedTextField(
                    value = witnessesText,
                    onValueChange = { witnessesText = it },
                    label = { Text("বাক্স গণনাকারী / সাক্ষীদের নাম (ঐচ্ছিক)") },
                    placeholder = { Text("যেমন: ইমাম সাহেব, কোষাধ্যক্ষ, মোশাররফ") },
                    singleLine = false,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                onClick = {
                    if (totalAmount <= 0.0) {
                        errorMessage = "কমপক্ষে একটি নোট বা কয়েন যোগ করুন"
                        return@Button
                    }
                    if (selectedFundId.isBlank()) {
                        errorMessage = "জমা ফান্ড নির্বাচন করুন"
                        return@Button
                    }
                    isSubmitting = true

                    val breakdownItems = DENOMINATIONS.mapNotNull { denom ->
                        val count = counts[denom] ?: 0
                        if (count > 0) "৳$denom x $count" else null
                    }.joinToString(", ")

                    val witnessesNote = if (witnessesText.isNotBlank()) " | গণনায়: ${witnessesText.trim()}" else ""
                    val fullNote = "জুমার নামাজ দান বাক্স ($breakdownItems)$witnessesNote"
                    val autoReceiptNo = "JUMMAH-${collectionDate.replace("-", "")}"

                    onSaveCollection(
                        totalAmount,
                        selectedFundId,
                        collectionDate.trim(),
                        autoReceiptNo,
                        fullNote
                    )
                }
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("ফান্ডে জমা করুন")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSubmitting,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
