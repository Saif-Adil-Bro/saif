package com.saif.mosque.util

import com.saif.mosque.data.outbox.DirectorySyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorySyncPolicyTest {
    @Test fun rolesMustStillBeActiveAndInTheOriginalTenant() {
        assertTrue(DirectorySyncPolicy.permitted("funds", "SUPERADMIN", true, null, "a"))
        assertTrue(DirectorySyncPolicy.permitted("donors", "SUPERADMIN", true, null, "a"))
        assertTrue(DirectorySyncPolicy.permitted("funds", "ADMIN", true, "a", "a"))
        assertTrue(DirectorySyncPolicy.permitted("donors", "CASHIER", true, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("funds", "CASHIER", true, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("donors", "MEMBER", true, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("donors", "ADMIN", false, "a", "a"))
        assertFalse(DirectorySyncPolicy.permitted("donors", "ADMIN", true, "b", "a"))
        assertFalse(DirectorySyncPolicy.permitted("expenses", "ADMIN", true, "a", "a"))
    }
    @Test fun diagnosticsIdentifyOnlyTheFailedCheck() {
        fun check(exists: Boolean = true, role: String? = "ADMIN", active: Boolean = true, mosque: String? = "a") =
            DirectorySyncPolicy.profileFailure(exists, "funds", role, active, mosque, "a")
        assertNull(check())
        assertEquals("PROFILE_MISSING", check(exists = false))
        assertEquals("PROFILE_INACTIVE", check(active = false))
        assertEquals("PROFILE_TENANT", check(mosque = "other"))
        assertEquals("PROFILE_ROLE", check(role = "MEMBER"))
        assertEquals("PROFILE_DENIED", DirectorySyncPolicy.permissionFailure("PROFILE"))
        assertEquals("MOSQUE_DENIED", DirectorySyncPolicy.permissionFailure("MOSQUE"))
        assertEquals("RECEIPT_DENIED", DirectorySyncPolicy.permissionFailure("RECEIPT"))
        assertEquals("WRITE_DENIED", DirectorySyncPolicy.permissionFailure("WRITE"))
        assertEquals("PERMISSION", DirectorySyncPolicy.permissionFailure("OTHER"))
    }
    @Test fun localPendingOverlayIsNotAnAcknowledgedReceipt() {
        assertFalse(DirectorySyncPolicy.ownsReceipt("id", "u", "id", "u", true))
        assertFalse(DirectorySyncPolicy.ownsReceipt("id", "u", "id", "other", false))
        assertFalse(DirectorySyncPolicy.ownsReceipt("id", "u", "different", "u", false))
        assertTrue(DirectorySyncPolicy.ownsReceipt("id", "u", "id", "u", false))
    }
}
