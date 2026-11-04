package com.saif.mosque.util

import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSessionPolicyTest {
    @Test fun permitsOnlyMatchingPreviouslyVerifiedActiveUser() {
        val user = AppUser(id = "uid-a", mosqueId = "mosque-a", role = Role.ADMIN, active = true)
        assertTrue(OfflineSessionPolicy.mayOpen("uid-a", user))
        assertFalse(OfflineSessionPolicy.mayOpen("uid-b", user))
        assertFalse(OfflineSessionPolicy.mayOpen("uid-a", user.copy(active = false)))
        assertFalse(OfflineSessionPolicy.mayOpen("uid-a", null))
    }

    @Test fun tenantUserNeedsMosqueWhilePlatformAdminDoesNot() {
        assertFalse(OfflineSessionPolicy.mayOpen("u", AppUser(id = "u", role = Role.ADMIN)))
        assertFalse(OfflineSessionPolicy.mayOpen("u", AppUser(id = "u", role = Role.ADMIN, mosqueId = " ")))
        assertTrue(OfflineSessionPolicy.mayOpen("u", AppUser(id = "u", role = Role.SUPERADMIN)))
    }
}
