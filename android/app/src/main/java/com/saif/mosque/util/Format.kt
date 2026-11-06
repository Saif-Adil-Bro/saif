package com.saif.mosque.util

import android.content.Context
import com.saif.mosque.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bengali formatting helpers (spec section 9): digits, currency (৳), dates. */
object Format {

    private val BN_DIGITS = "০১২৩৪৫৬৭৮৯"
    private val ASCII_DIGITS = "0123456789"

    /** Convert ASCII digits to Bengali digits. */
    fun bn(value: String): String = buildString {
        for (c in value) {
            val i = ASCII_DIGITS.indexOf(c)
            append(if (i >= 0) BN_DIGITS[i] else c)
        }
    }

    fun bn(value: Int): String = bn(value.toString())
    fun bn(value: Long): String = bn(value.toString())

    /** BDT currency with Bengali digits and grouping: ৳১,২৩৪.５০ */
    fun money(amount: Double): String {
        val grouped = String.format(java.util.Locale.US, "%,.2f", amount)
        return "৳" + bn(grouped)
    }

    /** ISO "yyyy-MM-dd" -> "dd/MM/yyyy" (Bengali digits). */
    fun date(iso: String): String {
        return try {
            val d = LocalDate.parse(iso)
            bn(d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
        } catch (e: Exception) {
            iso
        }
    }

    fun todayIso(): String =
        LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun isoToEpochMilli(iso: String): Long = try {
        LocalDate.parse(iso).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }

    fun epochMilliToIso(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** "MMMM yyyy" in Bengali via resources/CLDR (e.g. জানুয়ারি ২০২৬), digits converted to Bengali. */
    fun monthLabel(context: Context, month: Int, year: Int): String {
        val months = runCatching { context.resources.getStringArray(R.array.months) }.getOrNull()
        val monthName = months?.getOrNull(month - 1)
            ?: LocalDate.of(year, month, 1).format(DateTimeFormatter.ofPattern("MMMM", java.util.Locale.forLanguageTag("bn")))
        return "$monthName ${bn(year)}"
    }

    /** Current year-month as Pair(year, month). */
    fun currentYearMonth(): Pair<Int, Int> {
        val now = LocalDate.now()
        return now.year to now.monthValue
    }

    fun yearMonthIso(year: Int, month: Int): Pair<String, String> {
        val start = LocalDate.of(year, month, 1)
        val end = start.withDayOfMonth(start.lengthOfMonth())
        return start.format(DateTimeFormatter.ISO_LOCAL_DATE) to
            end.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    /** Last 7 days ISO date range as Pair(startDate, endDate) inclusive. */
    fun last7DaysIso(): Pair<String, String> {
        val today = LocalDate.now()
        val start = today.minusDays(6)
        return start.format(DateTimeFormatter.ISO_LOCAL_DATE) to
            today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    /** Formats date range in Bengali: "dd/MM/yyyy হতে dd/MM/yyyy পর্যন্ত" */
    fun dateRangeBengali(startIso: String, endIso: String): String =
        "${date(startIso)} হতে ${date(endIso)} পর্যন্ত"
}
