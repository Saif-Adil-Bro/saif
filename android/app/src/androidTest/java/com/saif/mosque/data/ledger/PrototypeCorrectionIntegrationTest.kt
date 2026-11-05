package com.saif.mosque.data.ledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import com.saif.mosque.MosqueApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated emulator and test-only correction rules, never the production rules. */
@RunWith(AndroidJUnit4::class)
class PrototypeCorrectionIntegrationTest {
    @Test fun correctionReceiptsRetryAndConflicts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as MosqueApp
        val db = app.container.db
        val auth = app.container.auth
        auth.useEmulator("10.0.2.2", 9099)
        db.useEmulator("10.0.2.2", 8080)
        auth.signInWithEmailAndPassword("correction@example.test", "test-only-password").await()
        val parent = db.collection("mosques").document("correction")
        val writer = PrototypeCorrectionWriter(db) { auth.currentUser?.uid }
        suspend fun before(kind: String, id: String): Map<String, Any> =
            parent.collection(kind).document(id).get(Source.SERVER).await().data!!
        val change = PrototypeCorrectionDraft("edit", "correction-admin", "correction", "contributions", "c", "update", before("contributions", "c"),
            mapOf("accountId" to "b", "donorId" to "e", "donorName" to "Other", "amount" to 40.0, "amountPoisha" to 4000L))
        writer.commit(change)
        writer.commit(change) // Lost local acknowledgement: immutable receipt makes retry safe.
        assertEquals(3000L, parent.collection("funds").document("a").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(6000L, parent.collection("funds").document("b").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(0L, parent.collection("donors").document("d").get(Source.SERVER).await().getLong("totalDonatedPoisha"))
        assertEquals(4000L, parent.collection("donors").document("e").get(Source.SERVER).await().getLong("totalDonatedPoisha"))
        assertEquals(9000L, parent.get(Source.SERVER).await().getLong("totalBalancePoisha"))
        assertTrue(runCatching { writer.commit(change.copy(changes = change.changes + ("amount" to 39.0) + ("amountPoisha" to 3900L))) }.isFailure)
        assertTrue(runCatching { writer.commit(change.copy(id="stale")) }.isFailure)
        val delete = PrototypeCorrectionDraft("delete", "correction-admin", "correction", "expenses", "x", "delete", before("expenses", "x"))
        writer.commit(delete)
        writer.commit(delete)
        assertEquals(true, parent.collection("expenses").document("x").get(Source.SERVER).await().getBoolean("voided"))
        assertEquals(4000L, parent.collection("funds").document("a").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(10000L, parent.get(Source.SERVER).await().getLong("totalBalancePoisha"))
        val previous = before("expenses", "y")
        val drafts = listOf("one", "two").map { id ->
            PrototypeCorrectionDraft(id, "correction-admin", "correction", "expenses", "y", "update", previous, mapOf("title" to id))
        }
        val results = drafts.map { draft -> async { runCatching { writer.commit(draft) }.isSuccess } }.awaitAll()
        assertEquals(1, results.count { it })
        val actual = parent.collection("expenses").document("y").get(Source.SERVER).await().getString("title")
        assertTrue(actual in setOf("one", "two"))
        assertFalse(parent.collection("ledgerMutations").document("stale").get(Source.SERVER).await().exists())
        auth.signInWithEmailAndPassword("correction-other@example.test", "test-only-password").await()
        assertTrue(runCatching { writer.commit(change.copy(id="wrong-user")) }.isFailure)
        auth.signOut()
    }
}
