package com.saif.mosque.util

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.saif.mosque.data.model.MemberMonthlyStatus
import com.saif.mosque.data.model.Mosque

object SubscriptionPdfExporter {

    fun printMonthlyReport(
        context: Context,
        mosque: Mosque?,
        monthYearLabel: String,
        fundName: String,
        members: List<MemberMonthlyStatus>
    ) {
        val totalMembers = members.size
        val totalTarget = members.sumOf { it.monthlyAmount }
        val totalCollected = members.sumOf { it.paidAmount }
        val totalPending = (totalTarget - totalCollected).coerceAtLeast(0.0)
        val paidCount = members.count { it.isPaid }
        val pendingCount = totalMembers - paidCount

        val mosqueName = mosque?.name?.ifBlank { "মসজিদ হিসাব" } ?: "মসজিদ হিসাব"
        val mosqueLocation = listOfNotNull(mosque?.address, mosque?.upazila, mosque?.district)
            .filter { it.isNotBlank() }.joinToString(", ")

        val html = buildHtml(
            mosqueName = mosqueName,
            mosqueLocation = mosqueLocation,
            monthYearLabel = monthYearLabel,
            fundName = fundName,
            totalMembers = totalMembers,
            totalTarget = totalTarget,
            totalCollected = totalCollected,
            totalPending = totalPending,
            paidCount = paidCount,
            pendingCount = pendingCount,
            members = members
        )

        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
                val jobName = "Subscription_Report_${monthYearLabel.replace(" ", "_")}"
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    printAdapter,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                )
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun buildHtml(
        mosqueName: String,
        mosqueLocation: String,
        monthYearLabel: String,
        fundName: String,
        totalMembers: Int,
        totalTarget: Double,
        totalCollected: Double,
        totalPending: Double,
        paidCount: Int,
        pendingCount: Int,
        members: List<MemberMonthlyStatus>
    ): String {
        val sb = StringBuilder()
        sb.append("""
            <!DOCTYPE html>
            <html lang="bn">
            <head>
                <meta charset="utf-8">
                <title>মাসিক বেতন ও চাঁদা রিপোর্ট</title>
                <style>
                    @page { size: A4 portrait; margin: 12mm 10mm; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Kalpurush", "SolaimanLipi", sans-serif;
                        color: #1a1a1a;
                        margin: 0;
                        padding: 10px;
                        font-size: 13px;
                    }
                    .header { text-align: center; border-bottom: 2px solid #005a36; padding-bottom: 8px; margin-bottom: 12px; }
                    .header h1 { margin: 0 0 4px 0; color: #005a36; font-size: 22px; }
                    .header p { margin: 0; color: #555; font-size: 12px; }
                    .report-title {
                        text-align: center;
                        font-size: 16px;
                        font-weight: bold;
                        background: #f0f7f4;
                        padding: 6px;
                        margin-bottom: 12px;
                        border-radius: 4px;
                        color: #004d2e;
                    }
                    .meta-bar {
                        display: flex;
                        justify-content: space-between;
                        margin-bottom: 12px;
                        font-weight: 500;
                        font-size: 13px;
                    }
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(4, 1fr);
                        gap: 8px;
                        margin-bottom: 14px;
                    }
                    .summary-box {
                        border: 1px solid #d5dfdb;
                        background: #fafcfb;
                        padding: 8px;
                        text-align: center;
                        border-radius: 4px;
                    }
                    .summary-box .val { font-size: 15px; font-weight: bold; margin-top: 3px; color: #005a36; }
                    .summary-box .pending { color: #b71c1c; }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        margin-bottom: 24px;
                        font-size: 12px;
                    }
                    th, td {
                        border: 1px solid #c2cec9;
                        padding: 6px 8px;
                        text-align: left;
                    }
                    th {
                        background: #e6f0ec;
                        color: #004d2e;
                        font-weight: 600;
                    }
                    .text-right { text-align: right; }
                    .text-center { text-align: center; }
                    .status-paid { color: #0a7029; font-weight: bold; }
                    .status-pending { color: #b71c1c; font-weight: bold; }
                    .signatures {
                        display: flex;
                        justify-content: space-between;
                        margin-top: 40px;
                        padding-top: 10px;
                    }
                    .sig-line {
                        width: 28%;
                        text-align: center;
                        border-top: 1px dashed #777;
                        padding-top: 5px;
                        font-size: 12px;
                    }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>$mosqueName</h1>
                    ${if (mosqueLocation.isNotBlank()) "<p>$mosqueLocation</p>" else ""}
                </div>
                <div class="report-title">মাসিক বেতন ও চাঁদা আদায়ের তালিকা ও রিপোর্ট</div>
                <div class="meta-bar">
                    <div><strong>মাস:</strong> $monthYearLabel</div>
                    <div><strong>ফান্ড:</strong> $fundName</div>
                </div>
                <div class="summary-grid">
                    <div class="summary-box">
                        <div>মোট সদস্য</div>
                        <div class="val">$totalMembers জন</div>
                    </div>
                    <div class="summary-box">
                        <div>মোট নির্ধারণ</div>
                        <div class="val">৳ ${String.format("%.0f", totalTarget)}</div>
                    </div>
                    <div class="summary-box">
                        <div>আদায়কৃত ($paidCount জন)</div>
                        <div class="val">৳ ${String.format("%.0f", totalCollected)}</div>
                    </div>
                    <div class="summary-box">
                        <div>বকেয়া / বাকি ($pendingCount জন)</div>
                        <div class="val pending">৳ ${String.format("%.0f", totalPending)}</div>
                    </div>
                </div>
                <table>
                    <thead>
                        <tr>
                            <th class="text-center" style="width: 35px;">নং</th>
                            <th>সদস্যের নাম</th>
                            <th>মোবাইল নম্বর</th>
                            <th class="text-right">নির্ধারণ</th>
                            <th class="text-right">আদায়</th>
                            <th class="text-center">অবস্থা</th>
                            <th>তারিখ ও রসিদ</th>
                            <th style="width: 70px;">স্বাক্ষর</th>
                        </tr>
                    </thead>
                    <tbody>
        """.trimIndent())

        members.forEachIndexed { index, m ->
            val statusHtml = if (m.isPaid) {
                """<span class="status-paid">পরিশোধিত</span>"""
            } else if (m.isPartial) {
                """<span style="color:#d97706;font-weight:bold;">আংশিক</span>"""
            } else {
                """<span class="status-pending">বাকি</span>"""
            }
            val dateReceipt = listOfNotNull(m.lastPaymentDate, m.lastReceiptNo?.let { "রসিদ: $it" })
                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }

            sb.append("""
                <tr>
                    <td class="text-center">${index + 1}</td>
                    <td><strong>${escapeHtml(m.donor.name)}</strong></td>
                    <td>${escapeHtml(m.donor.phone.ifBlank { "—" })}</td>
                    <td class="text-right">৳ ${String.format("%.0f", m.monthlyAmount)}</td>
                    <td class="text-right">৳ ${String.format("%.0f", m.paidAmount)}</td>
                    <td class="text-center">$statusHtml</td>
                    <td>$dateReceipt</td>
                    <td></td>
                </tr>
            """.trimIndent())
        }

        sb.append("""
                    </tbody>
                </table>
                <div class="signatures">
                    <div class="sig-line">আদায়কারী / ক্যাশিয়ার</div>
                    <div class="sig-line">সাধারণ সম্পাদক</div>
                    <div class="sig-line">সভাপতি</div>
                </div>
            </body>
            </html>
        """.trimIndent())

        return sb.toString()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
