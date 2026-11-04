package com.saif.mosque.util

import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataVersionTest {
    @Test fun fundChangesConflictButConcurrentBalanceChangesDoNot() {
        val old = Fund(name = "General", code = "GEN", balance = 10.0)
        assertTrue(MetadataVersion.sameFund(old, old.copy(balance = 30.0)))
        assertFalse(MetadataVersion.sameFund(old, old.copy(name = "Other")))
        assertFalse(MetadataVersion.sameFund(old, old.copy(active = false)))
    }
    @Test fun donorChangesConflictButConcurrentDonationsDoNot() {
        val old = Donor(name = "A", phone = "123", totalDonated = 10.0)
        assertTrue(MetadataVersion.sameDonor(old, old.copy(totalDonated = 30.0)))
        assertFalse(MetadataVersion.sameDonor(old, old.copy(phone = "456")))
        assertFalse(MetadataVersion.sameDonor(old, old.copy(active = false)))
    }
    @Test fun memberChangesIncludeRoleActiveAndTenant() {
        val old = AppUser(name = "A", mosqueId = "a", role = Role.MEMBER)
        assertTrue(MetadataVersion.sameMember(old, old.copy(email = "new@example.com")))
        assertFalse(MetadataVersion.sameMember(old, old.copy(role = Role.ADMIN)))
        assertFalse(MetadataVersion.sameMember(old, old.copy(active = false)))
        assertFalse(MetadataVersion.sameMember(old, old.copy(mosqueId = "b")))
    }
}
