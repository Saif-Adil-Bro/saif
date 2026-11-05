package com.saif.mosque.data.ledger

/**
 * Exact accounting for the isolated correction experiment, not a writer or a
 * production authorization check. The caller must atomically verify snapshots,
 * permissions, receipt identity and every aggregate against security rules.
 */
object LedgerCorrectionMath {
    sealed interface Entry { val amount: Long }
    data class Contribution(override val amount: Long, val fund: String, val donor: String = "") : Entry
    data class Expense(override val amount: Long, val fund: String) : Entry
    data class Transfer(override val amount: Long, val source: String, val destination: String) : Entry

    data class Delta(val funds: Map<String, Long>, val donors: Map<String, Long>, val mosque: Long)
    data class Result(val funds: Map<String, Long>, val donors: Map<String, Long>, val mosque: Long)

    /** Reverse the old effect and optionally apply its replacement as one net change. */
    fun difference(old: Entry, replacement: Entry?): Delta {
        validate(old)
        if (replacement != null) {
            validate(replacement)
            require(old::class == replacement::class) { "Ledger kind cannot change" }
            require(old !is Transfer) { "Transfer edits are not supported by the prototype" }
        }
        val funds = linkedMapOf<String, Long>()
        val donors = linkedMapOf<String, Long>()
        var mosque = 0L
        fun add(map: MutableMap<String, Long>, id: String, value: Long) {
            map[id] = Math.addExact(map[id] ?: 0L, value)
        }
        fun apply(entry: Entry, direction: Long) {
            val value = Math.multiplyExact(entry.amount, direction)
            when (entry) {
                is Contribution -> {
                    add(funds, entry.fund, value)
                    if (entry.donor.isNotEmpty()) add(donors, entry.donor, value)
                    mosque = Math.addExact(mosque, value)
                }
                is Expense -> { add(funds, entry.fund, -value); mosque = Math.addExact(mosque, -value) }
                is Transfer -> { add(funds, entry.source, -value); add(funds, entry.destination, value) }
            }
        }
        apply(old, -1)
        if (replacement != null) apply(replacement, 1)
        // Retain zero deltas: the prototype receipt requires a fresh marker on
        // every affected fund/donor, even for a metadata-only correction.
        return Delta(funds.toMap(), donors.toMap(), mosque)
    }

    /** Check final balances, not an artificial intermediate reversal. */
    fun apply(delta: Delta, funds: Map<String, Long>, donors: Map<String, Long>, mosque: Long): Result {
        fun changed(existing: Map<String, Long>, changes: Map<String, Long>): Map<String, Long> =
            changes.mapValues { (id, value) -> checked(existing[id] ?: throw InvalidLedgerAccount(), value) }
        return Result(changed(funds, delta.funds), changed(donors, delta.donors), checked(mosque, delta.mosque))
    }

    private fun checked(before: Long, delta: Long): Long {
        if (before < 0 || before > LedgerMath.MAX_POISHA) throw InvalidLedgerAmount()
        val result = try { Math.addExact(before, delta) } catch (_: ArithmeticException) { throw InvalidLedgerAmount() }
        if (result < 0) throw LedgerOverdraft()
        if (result > LedgerMath.MAX_POISHA) throw InvalidLedgerAmount()
        return result
    }
    private fun validId(id: String): Boolean = id.isNotEmpty() && id.length <= 1500 && !id.contains('/')
    private fun validate(entry: Entry) {
        if (entry.amount <= 0 || entry.amount > LedgerMath.MAX_POISHA) throw InvalidLedgerAmount()
        when (entry) {
            is Contribution -> if (!validId(entry.fund) || (entry.donor.isNotEmpty() && !validId(entry.donor))) throw InvalidLedgerAccount()
            is Expense -> if (!validId(entry.fund)) throw InvalidLedgerAccount()
            is Transfer -> if (!validId(entry.source) || !validId(entry.destination) || entry.source == entry.destination) throw InvalidLedgerAccount()
        }
    }
}
