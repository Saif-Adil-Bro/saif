package com.saif.mosque.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ReceiptData(
    val mosqueName: String,
    val mosqueAddress: String = "",
    val receiptNo: String,
    val donorName: String,
    val donorPhone: String = "",
    val amount: Double,
    val date: String,
    val fundName: String,
    val purpose: String,
    val receivedBy: String = ""
)

object ReceiptHelper {

    /**
     * Generates an automatic, professional receipt or voucher number.
     * Prefix examples:
     * - "REC" for regular contributions / income (e.g. REC-261106-4821)
     * - "EXP" for expenses / payment vouchers (e.g. EXP-261106-9312)
     * - "SUB" for monthly subscription dues (e.g. SUB-261106-7241)
     * - "PRJ" for special project donations (e.g. PRJ-261106-3829)
     */
    fun generateReceiptNo(prefix: String = "REC", dateIso: String = Format.todayIso()): String {
        val datePart = dateIso.replace("-", "").takeLast(6)
        val randomSuffix = (1000..9999).random()
        return "$prefix-$datePart-$randomSuffix"
    }

    fun formatDigitalSlipMessage(receipt: ReceiptData): String {
        val donorTitle = if (receipt.donorName.isNotBlank()) receipt.donorName.trim() else "সম্মানিত দ্বীনি ভাই"
        val cleanMosque = receipt.mosqueName.ifBlank { "মসজিদ" }
        val receiptTag = if (receipt.receiptNo.isNotBlank()) "রশিদ নং: #${receipt.receiptNo.trim()}" else ""
        val purposeTag = if (receipt.purpose.isNotBlank()) receipt.purpose.trim() else "দান/অনুদানের"

        return buildString {
            append("আসসালামু আলাইকুম $donorTitle,\n\n")
            append("$cleanMosque-এ $purposeTag বাবদ ${Format.money(receipt.amount)} সফলভাবে জমা হয়েছে।\n")
            if (receiptTag.isNotBlank()) append("$receiptTag\n")
            append("ফান্ড: ${receipt.fundName}\n")
            append("তারিখ: ${receipt.date}\n\n")
            append("আল্লাহ আপনার দান কবুল করুন এবং উত্তম প্রতিদান দিন। জাযাকাল্লাহু খাইরান।")
        }
    }

    fun formatDuesReminderMessage(
        mosqueName: String,
        memberName: String,
        monthLabel: String,
        monthlyAmount: Double,
        dueAmount: Double
    ): String {
        val cleanMosque = mosqueName.ifBlank { "মসজিদ" }
        val nameTitle = if (memberName.isNotBlank()) memberName.trim() else "সম্মানিত সদস্য"

        return buildString {
            append("আসসালামু আলাইকুম $nameTitle,\n\n")
            append("$cleanMosque-এর $monthLabel মাসের নির্ধারিত মাসিক চাঁদা/বেতন বাবদ ${Format.money(dueAmount)} বকেয়া রয়েছে (নির্ধারিত: ${Format.money(monthlyAmount)})।\n\n")
            append("সুবিধাজনক সময়ে মসজিদের ক্যাশিয়ার/কোষাধ্যক্ষ মহোদয়ের নিকট টাকা পরিশোধ করার জন্য বিনীত অনুরোধ করা হলো।\n\n")
            append("আল্লাহ আপনার রিজিকে বরকত দিন। জাযাকাল্লাহু খাইরান।")
        }
    }

    fun normalizeBangladeshiPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.startsWith("880") -> "+$digits"
            digits.startsWith("01") && digits.length == 11 -> "+88$digits"
            else -> phone.trim()
        }
    }

    fun sendViaWhatsApp(context: Context, phone: String, message: String): Boolean {
        return try {
            val encodedMsg = URLEncoder.encode(message, StandardCharsets.UTF_8.name())
            val normalizedPhone = normalizeBangladeshiPhone(phone).removePrefix("+")
            val uri = if (normalizedPhone.isNotBlank() && normalizedPhone.length >= 10) {
                Uri.parse("https://api.whatsapp.com/send?phone=$normalizedPhone&text=$encodedMsg")
            } else {
                Uri.parse("https://api.whatsapp.com/send?text=$encodedMsg")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            shareGeneral(context, message)
        }
    }

    fun sendViaSms(context: Context, phone: String, message: String): Boolean {
        return try {
            val normalized = normalizeBangladeshiPhone(phone)
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${if (normalized.isNotBlank()) normalized else ""}")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            shareGeneral(context, message)
        }
    }

    fun shareGeneral(context: Context, text: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val chooser = Intent.createChooser(intent, "রশিদ শেয়ার করুন").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Mosque Receipt", text)
        clipboard?.setPrimaryClip(clip)
    }

    fun printThermalPosSlip(context: Context, receipt: ReceiptData) {
        val html = buildThermalSlipHtml(receipt)
        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
                val jobName = "POS_${receipt.receiptNo.ifBlank { "Receipt" }}"
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                val pos58 = PrintAttributes.MediaSize("POS_58MM", "Thermal 58mm", 2280, 8000)
                printManager.print(
                    jobName,
                    printAdapter,
                    PrintAttributes.Builder()
                        .setMediaSize(pos58)
                        .setResolution(PrintAttributes.Resolution("pos", "pos", 203, 203))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                )
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    fun generatePlainTextSlip(receipt: ReceiptData): String {
        val line = "=".repeat(32)
        val dash = "-".repeat(32)
        return buildString {
            appendLine(line)
            appendLine(receipt.mosqueName.take(32))
            if (receipt.mosqueAddress.isNotBlank()) appendLine(receipt.mosqueAddress.take(32))
            appendLine("ক্যাশ মেমো / রশিদ")
            appendLine(line)
            if (receipt.receiptNo.isNotBlank()) appendLine("রশিদ নং: #${receipt.receiptNo}")
            appendLine("তারিখ: ${receipt.date}")
            appendLine("দাতা/সদস্য: ${receipt.donorName}")
            if (receipt.donorPhone.isNotBlank()) appendLine("ফোন: ${receipt.donorPhone}")
            appendLine("ফান্ড: ${receipt.fundName}")
            appendLine("বিবরণ: ${receipt.purpose}")
            appendLine(dash)
            appendLine("পরিশোধিত টাকা: ${Format.money(receipt.amount)}")
            appendLine(dash)
            if (receipt.receivedBy.isNotBlank()) appendLine("আদায়কারী: ${receipt.receivedBy}")
            appendLine("আল্লাহ আপনার দান কবুল করুন।")
            appendLine(line)
        }
    }

    private fun buildThermalSlipHtml(receipt: ReceiptData): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <style>
                    @page { size: 58mm auto; margin: 2mm; }
                    body {
                        font-family: 'Courier New', Courier, monospace;
                        font-size: 11px;
                        line-height: 1.35;
                        color: #000;
                        margin: 0;
                        padding: 4px;
                        width: 54mm;
                    }
                    .text-center { text-align: center; }
                    .text-right { text-align: right; }
                    .bold { font-weight: bold; }
                    .title { font-size: 13px; font-weight: bold; margin-bottom: 2px; }
                    .subtitle { font-size: 10px; margin-bottom: 6px; }
                    .divider { border-top: 1px dashed #000; margin: 5px 0; }
                    .double-divider { border-top: 2px solid #000; margin: 6px 0; }
                    .row { display: flex; justify-content: space-between; margin: 2px 0; }
                    .total-row { font-size: 13px; font-weight: bold; margin: 4px 0; }
                    .footer { font-size: 9px; margin-top: 8px; text-align: center; }
                </style>
            </head>
            <body>
                <div class="text-center">
                    <div class="title">${receipt.mosqueName.ifBlank { "মসজিদ ফান্ড" }}</div>
                    ${if (receipt.mosqueAddress.isNotBlank()) "<div class=\"subtitle\">${receipt.mosqueAddress}</div>" else ""}
                    <div class="bold">ক্যাশ মেমো / মানি রসিদ</div>
                </div>
                <div class="divider"></div>
                ${if (receipt.receiptNo.isNotBlank()) "<div class=\"row\"><span>রশিদ নং:</span><span class=\"bold\">#${receipt.receiptNo}</span></div>" else ""}
                <div class="row"><span>তারিখ:</span><span>${receipt.date}</span></div>
                <div class="row"><span>সদস্য/দাতা:</span><span class=\"bold\">${receipt.donorName}</span></div>
                ${if (receipt.donorPhone.isNotBlank()) "<div class=\"row\"><span>মোবাইল:</span><span>${receipt.donorPhone}</span></div>" else ""}
                <div class="row"><span>ফান্ড:</span><span>${receipt.fundName}</span></div>
                <div class="row"><span>বিবরণ:</span><span>${receipt.purpose}</span></div>
                <div class="double-divider"></div>
                <div class="row total-row">
                    <span>পরিশোধিত টাকা:</span>
                    <span>${Format.money(receipt.amount)}</span>
                </div>
                <div class="double-divider"></div>
                ${if (receipt.receivedBy.isNotBlank()) "<div class=\"row\"><span>আদায়কারী:</span><span>${receipt.receivedBy}</span></div>" else ""}
                <div class="footer">
                    আল্লাহ আপনার দানকে কবুল করুন এবং দুনিয়া ও আখেরাতে উত্তম প্রতিদান দান করুন। আমিন।
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
