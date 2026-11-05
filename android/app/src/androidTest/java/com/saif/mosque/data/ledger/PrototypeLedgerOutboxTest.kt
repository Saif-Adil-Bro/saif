package com.saif.mosque.data.ledger

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrototypeLedgerOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "ledger-outbox-test-${UUID.randomUUID()}.db"
    private val store = PrototypeLedgerOutbox(context, name)
    private fun draft(id: String, uid: String = "u1", mosqueId: String = "m1") = PrototypeLedgerDraft(
        id=id, uid=uid, mosqueId=mosqueId, kind="contributions", amountPoisha=29,
        sourceFundId="fund", donorName="Anonymous", date="2026-11-05", createdAt=1)
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    @Test fun persistedDraftsAreImmutableAndScoped() = runBlocking {
        val one = draft("one")
        store.enqueue(one)
        store.enqueue(draft("two", uid="u2"))
        store.enqueue(draft("three", mosqueId="m2"))
        assertTrue(runCatching { store.enqueue(one.copy(amountPoisha=100)) }.exceptionOrNull() is SQLiteConstraintException)
        assertEquals(listOf(one), store.list("u1", "m1").map { it.draft })
        assertEquals(listOf("m1", "m2"), store.pendingMosques("u1"))
        assertEquals(listOf("m1"), store.pendingMosques("u2"))
        store.close()
        val reopened = PrototypeLedgerOutbox(context, name)
        try { assertEquals(one, reopened.list("u1", "m1").single().draft) } finally { reopened.close() }
    }
    @Test fun onlyOwnerCanRetryDiscardOrAcknowledge() = runBlocking {
        val d = draft("item")
        store.enqueue(d)
        assertFalse(store.block(d.id, "u2", "m1", "PERMISSION"))
        assertTrue(store.block(d.id, "u1", "m1", "INSUFFICIENT"))
        assertFalse(store.acknowledge(d.id, "u1", "m1"))
        assertFalse(store.retry(d.id, "u1", "m2"))
        assertFalse(store.discardBlocked(d.id, "u2", "m1"))
        assertTrue(store.retry(d.id, "u1", "m1"))
        assertFalse(store.discardBlocked(d.id, "u1", "m1"))
        assertTrue(store.acknowledge(d.id, "u1", "m1"))
        assertTrue(store.list("u1", "m1").isEmpty())
    }
    @Test fun invalidDraftNeverEntersQueue() = runBlocking {
        assertTrue(runCatching { store.enqueue(draft("bad").copy(amountPoisha=0)) }.isFailure)
        assertTrue(runCatching { store.enqueue(draft("bad").copy(date="2026-02-30")) }.isFailure)
        assertTrue(store.list("u1", "m1").isEmpty())
    }
}
