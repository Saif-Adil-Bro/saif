package com.saif.mosque.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saif.mosque.R
import com.saif.mosque.data.model.Fund
import com.saif.mosque.util.Format
import com.saif.mosque.util.ReceiptHelper

private val WhatsAppGreen = Color(0xFF25D366)

@Composable
fun FridayAnnouncementDialog(
    mosqueName: String,
    lastWeekIncome: Double,
    lastWeekExpense: Double,
    totalBalance: Double,
    fundBalances: List<Pair<Fund, Double>>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    val cleanMosque = mosqueName.ifBlank { "বায়তুল আমান জামে মসজিদ" }
    val today = Format.todayIso()

    val announcementText = remember(cleanMosque, lastWeekIncome, lastWeekExpense, totalBalance, fundBalances) {
        buildString {
            append("আসসালামু আলাইকুম ওয়া রাহমাতুল্লাহ।\n")
            append("সম্মানিত মুসল্লিয়ান, $cleanMosque-এর সাপ্তাহিক আয়-ব্যয়ের হিসাব বিবরণী ($today):\n\n")
            append("১. বিগত ৭ দিনের মোট আয় (দান ও চাঁদা): ${Format.money(lastWeekIncome)}\n")
            append("২. বিগত ৭ দিনের মোট খরচ: ${Format.money(lastWeekExpense)}\n")
            append("৩. বর্তমান সর্বমোট তহবিল স্থিতি (ব্যালেন্স): ${Format.money(totalBalance)}\n\n")
            if (fundBalances.isNotEmpty()) {
                append("তহবিলভিত্তিক ব্যালেন্স:\n")
                fundBalances.take(5).forEach { (fund, balance) ->
                    append("• ${fund.name} (${fund.code}): ${Format.money(balance)}\n")
                }
                append("\n")
            }
            append("মসজিদের যাবতীয় উন্নয়নমূলক কাজে মুক্তহস্তে দান করার জন্য অনুরোধ করা হলো।\n")
            append("আল্লাহ আমাদের সবার দান কবুল করুন। আমীন।")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Text("জুমার খুতবার আগের ঘোষণা কার্ড", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "খুতবার পূর্বে সভাপতি বা সাধারণ সম্পাদক সরাসরি এই কার্ড দেখে মুসল্লিদের পড়ে শোনাতে পারবেন:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // High Contrast Speech Card for reading at arm's length
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            cleanMosque,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "তারিখ: $today (জুমার নামাজের হিসাব বিবরণী)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Divider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Big readable stats
                        AnnouncementStatRow(
                            label = "বিগত ৭ দিনের মোট আয়",
                            amount = Format.money(lastWeekIncome),
                            color = MaterialTheme.colorScheme.primary
                        )

                        AnnouncementStatRow(
                            label = "বিগত ৭ দিনের মোট ব্যয়",
                            amount = Format.money(lastWeekExpense),
                            color = MaterialTheme.colorScheme.error
                        )

                        AnnouncementStatRow(
                            label = "বর্তমান মোট ব্যালেন্স",
                            amount = Format.money(totalBalance),
                            color = MaterialTheme.colorScheme.onSurface,
                            isBold = true
                        )

                        if (fundBalances.isNotEmpty()) {
                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                "ফান্ডভিত্তিক স্থিতি:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            fundBalances.take(4).forEach { (fund, bal) ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${fund.code} · ${fund.name}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        Format.money(bal),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Action Buttons: Share & Copy
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            ReceiptHelper.sendViaWhatsApp(context, "", announcementText)
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = WhatsAppGreen),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("WhatsApp-এ শেয়ার")
                    }

                    FilledTonalButton(
                        onClick = {
                            ReceiptHelper.copyToClipboard(context, announcementText)
                            copied = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (copied) "কপি হয়েছে!" else "কপি করুন")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("বন্ধ করুন")
            }
        }
    )
}

@Composable
private fun AnnouncementStatRow(
    label: String,
    amount: String,
    color: Color,
    isBold: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = if (isBold) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            amount,
            style = if (isBold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
