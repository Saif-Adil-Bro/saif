package com.saif.mosque.data.model

private val SUB_REGEX = Regex("""\[SUB:([0-9]+(?:\.[0-9]+)?)\]""")

fun Donor.monthlySubscriptionAmount(): Double? {
    val match = SUB_REGEX.find(this.address) ?: return null
    return match.groupValues[1].toDoubleOrNull()
}

fun Donor.cleanDisplayAddress(): String {
    return this.address.replace(SUB_REGEX, "").trim()
}

fun Donor.isSubscriptionMember(): Boolean {
    val amount = monthlySubscriptionAmount()
    return amount != null && amount > 0.0
}

fun Donor.withSubscriptionAmount(amount: Double, cleanAddress: String = cleanDisplayAddress()): Donor {
    val prefix = if (amount > 0.0) {
        if (amount % 1.0 == 0.0) "[SUB:${amount.toLong()}]" else "[SUB:$amount]"
    } else ""
    val finalAddress = if (prefix.isNotBlank()) {
        if (cleanAddress.isNotBlank()) "$prefix $cleanAddress" else prefix
    } else cleanAddress
    return this.copy(address = finalAddress)
}

data class MemberMonthlyStatus(
    val donor: Donor,
    val monthlyAmount: Double,
    val paidAmount: Double,
    val lastPaymentDate: String? = null,
    val lastReceiptNo: String? = null,
    val isPaid: Boolean = paidAmount >= monthlyAmount && monthlyAmount > 0.0,
    val isPartial: Boolean = paidAmount > 0.0 && paidAmount < monthlyAmount
)
