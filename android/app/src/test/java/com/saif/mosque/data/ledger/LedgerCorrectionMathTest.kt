package com.saif.mosque.data.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LedgerCorrectionMathTest {
    private val math = LedgerCorrectionMath
    @Test fun contributionMovesMoneyAndDonorExactly() {
        val delta = math.difference(LedgerCorrectionMath.Contribution(5000, "a", "d"), LedgerCorrectionMath.Contribution(4000, "b", "e"))
        assertEquals(mapOf("a" to -5000L, "b" to 4000L), delta.funds)
        assertEquals(mapOf("d" to -5000L, "e" to 4000L), delta.donors)
        assertEquals(-1000L, delta.mosque)
        val result = math.apply(delta, mapOf("a" to 8000L, "b" to 2000L), mapOf("d" to 5000L, "e" to 0L), 10000L)
        assertEquals(mapOf("a" to 3000L, "b" to 6000L), result.funds)
        assertEquals(mapOf("d" to 0L, "e" to 4000L), result.donors)
        assertEquals(9000L, result.mosque)
    }
    @Test fun netDifferenceDoesNotTemporarilyOverdrawAndPreservesMarkers() {
        val delta = math.difference(LedgerCorrectionMath.Contribution(900, "a", "d"), LedgerCorrectionMath.Contribution(1200, "a", "d"))
        assertEquals(mapOf("a" to 300L), delta.funds)
        assertEquals(400L, math.apply(delta, mapOf("a" to 100L), mapOf("d" to 900L), 100L).funds["a"])
        val same = math.difference(LedgerCorrectionMath.Expense(100, "a"), LedgerCorrectionMath.Expense(100, "a"))
        assertEquals(mapOf("a" to 0L), same.funds)
        assertEquals(0L, same.mosque)
    }
    @Test fun deletionsReverseExpenseAndTransferWithoutChangingTransferTotal() {
        val expense = math.difference(LedgerCorrectionMath.Expense(1000, "a"), null)
        assertEquals(9000L, math.apply(expense, mapOf("a" to 8000L), emptyMap(), 10000L).funds["a"])
        val transfer = math.difference(LedgerCorrectionMath.Transfer(2000, "a", "b"), null)
        assertEquals(mapOf("a" to 2000L, "b" to -2000L), transfer.funds)
        assertEquals(0L, transfer.mosque)
        assertThrows(LedgerOverdraft::class.java) { math.apply(transfer, mapOf("a" to 8000L, "b" to 1000L), emptyMap(), 10000L) }
    }
    @Test fun missingAccountsInvalidAmountsAndOverflowsAreRejected() {
        val d = math.difference(LedgerCorrectionMath.Contribution(1, "a"), null)
        assertThrows(InvalidLedgerAccount::class.java) { math.apply(d, emptyMap(), emptyMap(), 1) }
        assertThrows(LedgerOverdraft::class.java) { math.apply(d, mapOf("a" to 0L), emptyMap(), 1) }
        assertThrows(InvalidLedgerAmount::class.java) { math.difference(LedgerCorrectionMath.Expense(0, "a"), null) }
        assertThrows(InvalidLedgerAccount::class.java) { math.difference(LedgerCorrectionMath.Transfer(1, "a", "a"), null) }
        assertThrows(IllegalArgumentException::class.java) { math.difference(LedgerCorrectionMath.Expense(1, "a"), LedgerCorrectionMath.Contribution(1, "a")) }
        assertThrows(IllegalArgumentException::class.java) { math.difference(LedgerCorrectionMath.Transfer(1, "a", "b"), LedgerCorrectionMath.Transfer(2, "a", "b")) }
        val e = math.difference(LedgerCorrectionMath.Expense(1, "a"), null)
        assertThrows(InvalidLedgerAmount::class.java) { math.apply(e, mapOf("a" to LedgerMath.MAX_POISHA), emptyMap(), 0) }
    }
}
