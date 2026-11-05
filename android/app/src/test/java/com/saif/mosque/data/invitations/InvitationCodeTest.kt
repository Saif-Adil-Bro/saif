package com.saif.mosque.data.invitations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class InvitationCodeTest {
    @Test fun roundTripsUnicodePunctuationAndOuterWhitespace() {
        val address = InvitationAddress("মসজিদ-01.a", "invite_Ab-123-🕌")
        assertEquals(address, InvitationCode.decode("  ${InvitationCode.encode(address)}\n"))
    }
    @Test fun rejectsMalformedUnknownAndNoncanonicalCodes() {
        for (code in listOf("", "MSH2.bQ.aQ", "MSH1.bQ.aQ.extra", "MSH1.bQ.aQ==", "MSH1._w.aQ", "MSH1.Lw.aQ", "MSH1.bR.aQ", "MSH1.bQ..aQ", "MSH1.${"A".repeat(5000)}.aQ")) {
            assertNull(code, InvitationCode.decode(code))
        }
    }
    @Test fun forbidsUnsafePathsAndOverlongUtf8() {
        for (id in listOf("", ".", "..", "a/b", "__name__", "a\u0000b", "\uD800", "অ".repeat(501))) {
            assertThrows(IllegalArgumentException::class.java) { InvitationCode.encode(InvitationAddress(id, "invite")) }
        }
        val longest = InvitationAddress("a".repeat(1500), "b".repeat(1500))
        assertEquals(longest, InvitationCode.decode(InvitationCode.encode(longest)))
    }
}
