package com.saif.mosque.util

import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.cleanDisplayAddress
import com.saif.mosque.data.model.isSubscriptionMember
import com.saif.mosque.data.model.monthlySubscriptionAmount
import com.saif.mosque.data.model.withSubscriptionAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLedgerParserTest {

    @Test
    fun parseBengaliDigitsAndNames() {
        val input = "আলামিন ১০০, রবিউল ২০০"
        val results = VoiceLedgerParser.parse(input)
        assertEquals(2, results.size)
        assertEquals("আলামিন", results[0].rawName)
        assertEquals(100.0, results[0].amount, 0.0)
        assertEquals("রবিউল", results[1].rawName)
        assertEquals(200.0, results[1].amount, 0.0)
    }

    @Test
    fun parseEnglishDigitsAndTakaSuffix() {
        val input = "Alamin 500 taka, Robiul 1000 tk"
        val results = VoiceLedgerParser.parse(input)
        assertEquals(2, results.size)
        assertEquals("Alamin", results[0].rawName)
        assertEquals(500.0, results[0].amount, 0.0)
        assertEquals("Robiul", results[1].rawName)
        assertEquals(1000.0, results[1].amount, 0.0)
    }

    @Test
    fun parseSpokenWordsInBengali() {
        val input = "হাফেজ আলামিন একশত টাকা এবং রবিউল দুইশত টাকা ও তারেক পাঁচশত টাকা"
        val results = VoiceLedgerParser.parse(input)
        assertEquals(3, results.size)
        assertEquals("হাফেজ আলামিন", results[0].rawName)
        assertEquals(100.0, results[0].amount, 0.0)
        assertEquals("রবিউল", results[1].rawName)
        assertEquals(200.0, results[1].amount, 0.0)
        assertEquals("তারেক", results[2].rawName)
        assertEquals(500.0, results[2].amount, 0.0)
    }

    @Test
    fun parseSpokenThousands() {
        val input = "ফারুক এক হাজার, কামরুল দুই হাজার"
        val results = VoiceLedgerParser.parse(input)
        assertEquals(2, results.size)
        assertEquals("ফারুক", results[0].rawName)
        assertEquals(1000.0, results[0].amount, 0.0)
        assertEquals("কামরুল", results[1].rawName)
        assertEquals(2000.0, results[1].amount, 0.0)
    }

    @Test
    fun matchesExistingDonorsFuzzy() {
        val donors = listOf(
            Donor(id = "d1", name = "আলামিন হোসেন"),
            Donor(id = "d2", name = "মাওলানা রবিউল করিম"),
            Donor(id = "d3", name = "তারেক রহমান")
        )
        val input = "আলামিন ৫০০, রবিউল ১০০০"
        val results = VoiceLedgerParser.parse(input, donors)

        assertEquals(2, results.size)
        assertEquals("d1", results[0].matchedDonorId)
        assertEquals("আলামিন হোসেন", results[0].matchedDonorName)
        assertEquals("d2", results[1].matchedDonorId)
        assertEquals("মাওলানা রবিউল করিম", results[1].matchedDonorName)
    }

    @Test
    fun subscriptionMemberHelpers() {
        val original = Donor(id = "sub1", name = "রবিউল", phone = "01712345678", address = "উত্তর পাড়া")
        val withSub = original.withSubscriptionAmount(500.0)

        assertTrue(withSub.isSubscriptionMember())
        assertEquals(500.0, withSub.monthlySubscriptionAmount() ?: 0.0, 0.0)
        assertEquals("উত্তর পাড়া", withSub.cleanDisplayAddress())
        assertTrue(withSub.address.startsWith("[SUB:500]"))

        val removed = withSub.withSubscriptionAmount(0.0)
        assertEquals(null, removed.monthlySubscriptionAmount())
        assertEquals(false, removed.isSubscriptionMember())
        assertEquals("উত্তর পাড়া", removed.cleanDisplayAddress())
    }
}
