package com.saif.mosque.data.ledger

import java.math.BigDecimal

/**
 * Experimental arithmetic for the isolated Spark ledger rules prototype.
 * It is not yet connected to the production repository or an offline queue.
 */
object LedgerMath {
    const val MAX_POISHA = 900_000_000_000_000L

    data class Balances(
        val fund: Long,
        val mosque: Long,
        val donor: Long? = null,
        val destination: Long? = null
    )

    /** Converts canonical taka values exactly; fractional poisha and unsafe amounts fail. */
    fun poisha(value: Double, positive: Boolean = false): Long {
        if (!value.isFinite()) throw InvalidLedgerAmount()
        val cents = try { BigDecimal.valueOf(value).movePointRight(2).longValueExact() }
            catch (_: ArithmeticException) { throw InvalidLedgerAmount() }
        if (cents < 0 || cents > MAX_POISHA || (positive && cents == 0L)) throw InvalidLedgerAmount()
        return cents
    }

    fun taka(poisha: Long): Double {
        validate(poisha)
        return poisha.toDouble() / 100.0
    }

    fun contribution(b: Balances, amount: Long): Balances {
        validate(b); positive(amount)
        return b.copy(fund = add(b.fund, amount), mosque = add(b.mosque, amount),
            donor = b.donor?.let { add(it, amount) })
    }
    fun expense(b: Balances, amount: Long): Balances {
        validate(b); positive(amount)
        if (b.fund < amount || b.mosque < amount) throw LedgerOverdraft()
        return b.copy(fund = b.fund - amount, mosque = b.mosque - amount)
    }
    fun transfer(b: Balances, amount: Long, sourceId: String, destinationId: String): Balances {
        validate(b); positive(amount)
        if (sourceId.isBlank() || destinationId.isBlank() || sourceId == destinationId) throw InvalidLedgerAccount()
        val destination = b.destination ?: throw InvalidLedgerAccount()
        if (b.fund < amount) throw LedgerOverdraft()
        return b.copy(fund = b.fund - amount, destination = add(destination, amount))
    }

    private fun positive(value: Long) { validate(value); if (value == 0L) throw InvalidLedgerAmount() }
    private fun validate(b: Balances) { validate(b.fund); validate(b.mosque); b.donor?.let(::validate); b.destination?.let(::validate) }
    private fun validate(n: Long) { if (n < 0L || n > MAX_POISHA) throw InvalidLedgerAmount() }
    private fun add(a: Long, b: Long): Long {
        val total = try { Math.addExact(a, b) } catch (_: ArithmeticException) { throw InvalidLedgerAmount() }
        validate(total)
        return total
    }
}

class InvalidLedgerAmount : IllegalArgumentException("Invalid amount or balance")
class LedgerOverdraft : IllegalArgumentException("Insufficient balance")
class InvalidLedgerAccount : IllegalArgumentException("Invalid transfer accounts")
