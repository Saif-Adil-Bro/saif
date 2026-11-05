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

/** Only run with the isolated prototype rules and dedicated emulator fixture. */
@RunWith(AndroidJUnit4::class)
class PrototypeLedgerIntegrationTest {
    @Test fun transactionsRetryExactlyAndConcurrentSpendsNeverOverdraw() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as MosqueApp
        val auth = app.container.auth
        val db = app.container.db
        auth.useEmulator("10.0.2.2", 9099)
        db.useEmulator("10.0.2.2", 8080)
        auth.signInWithEmailAndPassword("ledger@example.test", "test-only-password").await()
        val writer = PrototypeLedgerWriter(db) { auth.currentUser?.uid }
        val parent = db.collection("mosques").document("ledger-prototype")
        val base = PrototypeLedgerDraft(id="income", uid="ledger-admin", mosqueId="ledger-prototype",
            kind="contributions", amountPoisha=2000, sourceFundId="main", donorId="d", donorName="Donor",
            date="2026-11-05", createdAt=1)
        writer.commit(base)
        writer.commit(base) // simulate an interrupted acknowledgement; never count twice
        assertEquals(12000L, parent.collection("funds").document("main").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(2000L, parent.collection("donors").document("d").get(Source.SERVER).await().getLong("totalDonatedPoisha"))
        assertTrue(runCatching { writer.commit(base.copy(amountPoisha=2100)) }.isFailure)
        assertTrue(runCatching { writer.commit(base.copy(id="legacy", sourceFundId="legacy")) }.isFailure)
        writer.commit(base.copy(id="expense",kind="expenses",donorId="",donorName="",title="Expense"))
        writer.commit(base.copy(id="transfer",kind="transfers",donorId="",donorName="",amountPoisha=1000,destinationFundId="target"))
        val expenses = listOf("race-a", "race-b").map { id ->
            async { runCatching { writer.commit(base.copy(id=id,kind="expenses",donorId="",donorName="",title="Race",amountPoisha=8000)) } }
        }.awaitAll()
        assertEquals(1, expenses.count { it.isSuccess })
        assertEquals(1000L, parent.collection("funds").document("main").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(1000L, parent.collection("funds").document("target").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(2000L, parent.get(Source.SERVER).await().getLong("totalBalancePoisha"))
        assertFalse(parent.collection("contributions").document("legacy").get(Source.SERVER).await().exists())
        auth.signInWithEmailAndPassword("other-ledger@example.test", "test-only-password").await()
        assertTrue(runCatching { writer.commit(base.copy(id="wrong-user")) }.isFailure)
        auth.signOut()
    }
}
