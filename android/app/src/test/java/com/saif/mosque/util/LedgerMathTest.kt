package com.saif.mosque.util

import com.saif.mosque.data.ledger.InvalidLedgerAccount
import com.saif.mosque.data.ledger.InvalidLedgerAmount
import com.saif.mosque.data.ledger.LedgerMath
import com.saif.mosque.data.ledger.LedgerOverdraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LedgerMathTest {
    @Test fun moneyConversionIsExactAndRejectsFractionalPoisha() {
        assertEquals(29L, LedgerMath.poisha(0.29, positive = true))
        assertEquals(12345L, LedgerMath.poisha(123.45, positive = true))
        assertThrows(InvalidLedgerAmount::class.java) { LedgerMath.poisha(0.001, true) }
        assertThrows(InvalidLedgerAmount::class.java) { LedgerMath.poisha(0.30000000000000004, true) }
        assertThrows(InvalidLedgerAmount::class.java) { LedgerMath.poisha(Double.NaN, true) }
        assertThrows(InvalidLedgerAmount::class.java) { LedgerMath.poisha(Double.POSITIVE_INFINITY, true) }
        assertThrows(InvalidLedgerAmount::class.java) { LedgerMath.poisha(0.0, true) }
        assertEquals(0.29, LedgerMath.taka(29), 0.0)
    }
    @Test fun contributionAndExpenseUpdateAllAffectedTotals() {
        val b = LedgerMath.Balances(fund = 10000, mosque = 10000, donor = 0)
        val added = LedgerMath.contribution(b, 1234)
        assertEquals(LedgerMath.Balances(11234, 11234, 1234), added)
        assertEquals(LedgerMath.Balances(8734, 8734, 1234), LedgerMath.expense(added, 2500))
        assertEquals(null, LedgerMath.contribution(b.copy(donor = null), 1).donor)
    }
    @Test fun rejectsOverdraftAndOverflowIncludingMosqueOrDonor() {
        assertThrows(LedgerOverdraft::class.java) { LedgerMath.expense(LedgerMath.Balances(100, 99), 100) }
        assertThrows(LedgerOverdraft::class.java) { LedgerMath.expense(LedgerMath.Balances(99, 100), 100) }
        assertThrows(InvalidLedgerAmount::class.java) { LedgerMath.contribution(LedgerMath.Balances(0, 0, LedgerMath.MAX_POISHA), 1) }
        assertThrows(InvalidLedgerAmount::class.java) { LedgerMath.contribution(LedgerMath.Balances(0, LedgerMath.MAX_POISHA), 1) }
    }
    @Test fun transfersConserveTotalAndRejectSameFundOrInsufficientBalance() {
        val b = LedgerMath.Balances(fund = 10000, mosque = 10000, destination = 0)
        assertEquals(LedgerMath.Balances(7500, 10000, destination = 2500), LedgerMath.transfer(b, 2500, "a", "b"))
        assertThrows(InvalidLedgerAccount::class.java) { LedgerMath.transfer(b, 1, "a", "a") }
        assertThrows(LedgerOverdraft::class.java) { LedgerMath.transfer(b, 10001, "a", "b") }
        assertThrows(InvalidLedgerAccount::class.java) { LedgerMath.transfer(b.copy(destination = null), 1, "a", "b") }
    }
}
