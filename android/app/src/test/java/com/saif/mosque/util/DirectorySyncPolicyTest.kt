package com.saif.mosque.util

import com.saif.mosque.data.outbox.DirectorySyncPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorySyncPolicyTest {
    @Test fun rolesMustStillBeActiveAndInTheOriginalTenant() {
        assertTrue(DirectorySyncPolicy.permitted("funds", "ADMIN", true, "a", "a"))
        assertTrue(DirectorySyncPolicy.permitted("donors", "CASHIER", true, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("funds", "CASHIER", true, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("donors", "MEMBER", true, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("donors", "ADMIN", false, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("donors", "ADMIN", true, "b", "a"))
        assertFalse(DirectorySyncPolicy.permitted("expenses", "ADMIN", true, "a", "a"))
    }
    @Test fun localPendingOverlayIsNotAnAcknowledgedReceipt() {
        assertFalse(DirectorySyncPolicy.ownsReceipt("id", "u", "id", "u", true))
        assertFalse(DirectorySyncPolicy.ownsReceipt("id", "u", "id", "other", false))
        assertFalse(DirectorySyncPolicy.ownsReceipt("id", "u", "different", "u", false))
        assertTrue(DirectorySyncPolicy.ownsReceipt("id", "u", "id", "u", false))
    }
}
