package com.saif.mosque.data.outbox

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "directory-outbox-test-${java.util.UUID.randomUUID()}.db"
    private val store = DirectoryOutbox(context, name)
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    @Test fun draftsSurviveReopenAndStayScopedToAccountTenantAndKind() = runBlocking {
        val one = store.addFund("u1", "a", Fund(name = "Fund", code = "A"))
        val two = store.addDonor("u1", "a", Donor(name = "Donor"))
        store.addFund("u2", "a", Fund(name = "Other", code = "B"))
        store.addFund("u1", "b", Fund(name = "Elsewhere", code = "C"))
        assertNotEquals(one.id, two.id)
        assertEquals(listOf(one.id), store.list("u1", "a", "funds").map { it.id })
        store.close()
        val reopened = DirectoryOutbox(context, name)
        try { assertEquals(one.id, reopened.find(one.id)?.id) } finally { reopened.close() }
    }

    @Test fun blockedRowsCanOnlyBeRetriedByTheirOwnerAndTenant() = runBlocking {
        val draft = store.addDonor("u1", "a", Donor(name = "D"))
        store.markBlocked(draft.id, "PERMISSION")
        assertFalse(store.retry(draft.id, "u2", "a"))
        assertFalse(store.retry(draft.id, "u1", "b"))
        assertEquals("BLOCKED", store.find(draft.id)?.state)
        assertTrue(store.retry(draft.id, "u1", "a"))
        store.remove(draft.id, "u2")
        assertEquals("PENDING", store.find(draft.id)?.state)
        store.remove(draft.id, "u1")
        assertEquals(null, store.find(draft.id))
    }
}
