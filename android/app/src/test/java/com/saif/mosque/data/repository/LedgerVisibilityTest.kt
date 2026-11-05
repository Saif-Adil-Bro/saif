package com.saif.mosque.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerVisibilityTest {
    @Test fun legacyAndExplicitLiveOnly() {
        assertTrue(visibleLedgerMarker(false, null))
        assertTrue(visibleLedgerMarker(true, false))
        assertFalse(visibleLedgerMarker(true, true))
        assertFalse(visibleLedgerMarker(true, null))
        assertFalse(visibleLedgerMarker(true, "false"))
    }
}
