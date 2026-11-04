package com.saif.mosque.util

import android.util.Patterns

/** Validation rules (spec section 6). Returns an error message key, or null if valid. */
object Validators {

    fun email(value: String): String? =
        if (value.isBlank()) "required_field"
        else if (!Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()) "invalid_email"
        else null

    fun password(value: String): String? =
        if (value.length < 8) "weak_password" else null

    fun required(value: String): String? =
        if (value.isBlank()) "required_field" else null

    fun amount(value: String): String? {
        if (value.isBlank()) return "required_field"
        val d = value.toDoubleOrNull() ?: return "invalid_amount"
        return if (d <= 0.0) "invalid_amount" else null
    }

    /** Bangladeshi mobile: 01XXXXXXXXX (11 digits starting with 01). */
    fun bdPhone(value: String): String? {
        if (value.isBlank()) return null // optional field
        val v = value.trim()
        return if (Regex("^01[3-9]\\d{8}$").matches(v)) null else "invalid_phone"
    }

    fun year(value: String): String? {
        if (value.isBlank()) return "required_field"
        val y = value.toIntOrNull() ?: return "required_field"
        return if (y in 1900..2100) null else "required_field"
    }
}
