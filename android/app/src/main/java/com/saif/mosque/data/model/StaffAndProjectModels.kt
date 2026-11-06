package com.saif.mosque.data.model

private val SALARY_REGEX = Regex("""\[SALARY:([0-9]+(?:\.[0-9]+)?)\]""")
private val PROJECT_REGEX = Regex("""\[PROJECT:([0-9]+(?:\.[0-9]+)?)\]""")

/**
 * Extension functions for staff salary extraction and encoding
 * into [AppUser.designation], fully compatible with Firestore rules.
 */
fun AppUser.salaryAmount(): Double? {
    val match = SALARY_REGEX.find(this.designation) ?: return null
    return match.groupValues[1].toDoubleOrNull()
}

fun AppUser.cleanDisplayDesignation(): String {
    return this.designation.replace(SALARY_REGEX, "").trim()
}

fun AppUser.isStaffMember(): Boolean {
    return salaryAmount() != null ||
            designation.contains("ইমাম", ignoreCase = true) ||
            designation.contains("মুয়াজ্জিন", ignoreCase = true) ||
            designation.contains("খাদেম", ignoreCase = true) ||
            designation.contains("শিক্ষক", ignoreCase = true) ||
            role == Role.IMAM
}

fun AppUser.withSalaryAmount(amount: Double, cleanDesignation: String = cleanDisplayDesignation()): AppUser {
    val tag = if (amount > 0.0) {
        if (amount % 1.0 == 0.0) "[SALARY:${amount.toLong()}]" else "[SALARY:$amount]"
    } else ""
    val finalDesignation = if (tag.isNotBlank()) {
        if (cleanDesignation.isNotBlank()) "$cleanDesignation $tag" else tag
    } else cleanDesignation
    return this.copy(designation = finalDesignation)
}

/**
 * Extension functions for project budget extraction and encoding
 * into [Fund.description], fully compatible with Firestore rules.
 */
fun Fund.projectBudget(): Double? {
    val match = PROJECT_REGEX.find(this.description) ?: return null
    return match.groupValues[1].toDoubleOrNull()
}

fun Fund.cleanDescription(): String {
    return this.description.replace(PROJECT_REGEX, "").trim()
}

fun Fund.isProjectFund(): Boolean {
    return projectBudget() != null || code == "CON" || code == "WAK" ||
            name.contains("প্রজেক্ট", ignoreCase = true) ||
            name.contains("নির্মাণ", ignoreCase = true) ||
            name.contains("সংস্কার", ignoreCase = true) ||
            name.contains("ইফতার", ignoreCase = true) ||
            name.contains("বোনাস", ignoreCase = true)
}

fun Fund.withProjectBudget(budget: Double, cleanDesc: String = cleanDescription()): Fund {
    val tag = if (budget > 0.0) {
        if (budget % 1.0 == 0.0) "[PROJECT:${budget.toLong()}]" else "[PROJECT:$budget]"
    } else ""
    val finalDesc = if (tag.isNotBlank()) {
        if (cleanDesc.isNotBlank()) "$tag $cleanDesc" else tag
    } else cleanDesc
    return this.copy(description = finalDesc)
}
