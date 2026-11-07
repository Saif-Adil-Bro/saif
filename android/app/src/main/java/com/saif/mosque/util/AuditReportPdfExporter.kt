package com.saif.mosque.util

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.saif.mosque.data.model.Contribution
import com.saif.mosque.data.model.Expense
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.Mosque

object AuditReportPdfExporter {

    fun printAuditReport(
        context: Context,
        mosque: Mosque?,
        periodLabel: String,
        totalIncome: Double,
        totalExpense: Double,
        fundBalances: List<Pair<Fund, Double>>,
        contributions: List<Contribution>,
        expenses: List<Expense>,
        transfers: List<com.saif.mosque.data.model.Transfer> = emptyList(),
        openingBalance: Double? = null,
        closingBalance: Double? = null,
        reportTitle: String = "আয়-ব্যয় অডিট রিপোর্ট ও সাধারণ সভা (AGM) বিবরণী"
    ) {
        val netBalance = totalIncome - totalExpense
        val cleanMosqueName = mosque?.name?.ifBlank { "বায়তুল আমান জামে মসজিদ" } ?: "বায়তুল আমান জামে মসজিদ"
        val mosqueLocation = listOfNotNull(mosque?.address, mosque?.upazila, mosque?.district)
            .filter { it.isNotBlank() }.joinToString(", ")

        val html = buildAuditHtml(
            mosqueName = cleanMosqueName,
            mosqueLocation = mosqueLocation,
            registrationNo = mosque?.registrationNo ?: "",
            periodLabel = periodLabel,
            reportTitle = reportTitle,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netBalance = netBalance,
            fundBalances = fundBalances,
            contributions = contributions,
            expenses = expenses,
            transfers = transfers,
            openingBalance = openingBalance,
            closingBalance = closingBalance
        )

        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
                val jobName = "Audit_Report_${periodLabel.replace(" ", "_")}"
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

    private fun buildAuditHtml(
        mosqueName: String,
        mosqueLocation: String,
        registrationNo: String,
        periodLabel: String,
        reportTitle: String,
        totalIncome: Double,
        totalExpense: Double,
        netBalance: Double,
        fundBalances: List<Pair<Fund, Double>>,
        contributions: List<Contribution>,
        expenses: List<Expense>,
        transfers: List<com.saif.mosque.data.model.Transfer>,
        openingBalance: Double?,
        closingBalance: Double?
    ): String {
        val sb = StringBuilder()
        sb.append("""
            <!DOCTYPE html>
            <html lang="bn">
            <head>
                <meta charset="utf-8">
                <title>$reportTitle</title>
                <style>
                    @page { size: A4 portrait; margin: 12mm 12mm; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Kalpurush", "SolaimanLipi", sans-serif;
                        color: #1a1a1a;
                        margin: 0;
                        padding: 10px;
                        font-size: 12px;
                    }
                    .header { text-align: center; border-bottom: 2px solid #005a36; padding-bottom: 8px; margin-bottom: 14px; }
                    .header h1 { margin: 0 0 4px 0; color: #005a36; font-size: 22px; }
                    .header p { margin: 0; color: #555; font-size: 12px; }
                    .report-title {
                        text-align: center;
                        font-size: 15px;
                        font-weight: bold;
                        color: #333;
                        margin-bottom: 12px;
                        background: #f0fdf4;
                        padding: 6px;
                        border-radius: 6px;
                        border: 1px solid #bbf7d0;
                    }
                    .summary-grid {
                        display: flex;
                        gap: 10px;
                        margin-bottom: 16px;
                    }
                    .summary-card {
                        flex: 1;
                        padding: 8px 12px;
                        background: #f8fafc;
                        border: 1px solid #e2e8f0;
                        border-radius: 6px;
                        text-align: center;
                    }
                    .summary-card .label { font-size: 11px; color: #64748b; margin-bottom: 3px; }
                    .summary-card .value { font-size: 15px; font-weight: bold; }
                    .income-val { color: #15803d; }
                    .expense-val { color: #b91c1c; }
                    .net-val { color: #0369a1; }
                    .section-title {
                        font-size: 13px;
                        font-weight: bold;
                        margin: 14px 0 6px 0;
                        color: #0f172a;
                        border-left: 3px solid #005a36;
                        padding-left: 6px;
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        margin-bottom: 12px;
                        font-size: 11px;
                    }
                    th {
                        background: #f1f5f9;
                        color: #334155;
                        text-align: left;
                        padding: 5px 8px;
                        border: 1px solid #cbd5e1;
                    }
                    td {
                        padding: 5px 8px;
                        border: 1px solid #e2e8f0;
                    }
                    tr:nth-child(even) { background: #f8fafc; }
                    .num { text-align: right; }
                    .signatures {
                        margin-top: 40px;
                        display: flex;
                        justify-content: space-between;
                        page-break-inside: avoid;
                    }
                    .sign-box {
                        text-align: center;
                        width: 22%;
                        border-top: 1px dashed #64748b;
                        padding-top: 6px;
                        font-size: 11px;
                        color: #334155;
                    }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>$mosqueName</h1>
                    ${if (mosqueLocation.isNotBlank()) "<p>$mosqueLocation</p>" else ""}
                    ${if (registrationNo.isNotBlank()) "<p>রেজিস্ট্রেশন নং: $registrationNo</p>" else ""}
                </div>

                <div class="report-title">
                    $reportTitle — $periodLabel
                </div>

                <div class="summary-grid">
                    ${if (openingBalance != null) """
                    <div class="summary-card">
                        <div class="label">প্রারম্ভিক স্থিতি</div>
                        <div class="value">${Format.money(openingBalance)}</div>
                    </div>
                    """ else ""}
                    <div class="summary-card">
                        <div class="label">মোট আদায়কৃত আয়</div>
                        <div class="value income-val">${Format.money(totalIncome)}</div>
                    </div>
                    <div class="summary-card">
                        <div class="label">মোট অনুমোদিত ব্যয়</div>
                        <div class="value expense-val">${Format.money(totalExpense)}</div>
                    </div>
                    <div class="summary-card">
                        <div class="label">নিট পরিবর্তন</div>
                        <div class="value net-val">${Format.money(netBalance)}</div>
                    </div>
                    ${if (closingBalance != null) """
                    <div class="summary-card">
                        <div class="label">সমাপনী স্থিতি</div>
                        <div class="value">${Format.money(closingBalance)}</div>
                    </div>
                    """ else ""}
                </div>

                <div class="section-title">তহবিল স্থিতি (Fund Balances)</div>
                <table>
                    <thead>
                        <tr>
                            <th style="width: 15%;">কোড</th>
                            <th style="width: 50%;">তহবিলের নাম</th>
                            <th class="num" style="width: 35%;">স্থিতি</th>
                        </tr>
                    </thead>
                    <tbody>
        """.trimIndent())

        fundBalances.forEach { (fund, balance) ->
            sb.append("""
                <tr>
                    <td><b>${fund.code}</b></td>
                    <td>${fund.name}</td>
                    <td class="num"><b>${Format.money(balance)}</b></td>
                </tr>
            """.trimIndent())
        }

        sb.append("""
                    </tbody>
                </table>
        """.trimIndent())

        if (transfers.isNotEmpty()) {
            val fundMap = fundBalances.associate { it.first.id to "${it.first.code} ${it.first.name}" }
            sb.append("""
                <div class="section-title">আন্তঃতহবিল স্থানান্তর (${Format.bn(transfers.size)} টি)</div>
                <table>
                    <thead>
                        <tr>
                            <th style="width: 15%;">তারিখ</th>
                            <th style="width: 25%;">উৎস তহবিল</th>
                            <th style="width: 25%;">গন্তব্য তহবিল</th>
                            <th style="width: 20%;">বিবরণ</th>
                            <th class="num" style="width: 15%;">পরিমাণ</th>
                        </tr>
                    </thead>
                    <tbody>
            """.trimIndent())

            transfers.take(20).forEach { t ->
                val fromName = fundMap[t.fromAccountId] ?: t.fromAccountId
                val toName = fundMap[t.toAccountId] ?: t.toAccountId
                sb.append("""
                    <tr>
                        <td>${Format.date(t.date)}</td>
                        <td>$fromName</td>
                        <td>$toName</td>
                        <td>${t.note.ifBlank { "—" }}</td>
                        <td class="num">${Format.money(t.amount)}</td>
                    </tr>
                """.trimIndent())
            }

            sb.append("""
                    </tbody>
                </table>
            """.trimIndent())
        }

        if (expenses.isNotEmpty()) {
            sb.append("""
                <div class="section-title">প্রধান ব্যয় খাতসমূহ (${Format.bn(expenses.size)} টি)</div>
                <table>
                    <thead>
                        <tr>
                            <th style="width: 15%;">তারিখ</th>
                            <th style="width: 35%;">খাত / বিবরণ</th>
                            <th style="width: 25%;">প্রাপক / ব্যক্তি</th>
                            <th class="num" style="width: 25%;">পরিমাণ</th>
                        </tr>
                    </thead>
                    <tbody>
            """.trimIndent())

            expenses.take(20).forEach { e ->
                sb.append("""
                    <tr>
                        <td>${Format.date(e.date)}</td>
                        <td>${e.title} ${if (e.category.isNotBlank()) "(${e.category})" else ""}</td>
                        <td>${e.paidTo.ifBlank { "—" }}</td>
                        <td class="num">${Format.money(e.amount)}</td>
                    </tr>
                """.trimIndent())
            }

            sb.append("""
                    </tbody>
                </table>
            """.trimIndent())
        }

        sb.append("""
                <div class="signatures">
                    <div class="sign-box">ক্যাশিয়ার</div>
                    <div class="sign-box">কোষাধ্যক্ষ</div>
                    <div class="sign-box">সাধারণ সম্পাদক</div>
                    <div class="sign-box">সভাপতি</div>
                </div>
            </body>
            </html>
        """.trimIndent())

        return sb.toString()
    }
}
