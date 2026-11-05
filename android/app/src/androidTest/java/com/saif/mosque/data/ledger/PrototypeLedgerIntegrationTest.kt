package com.saif.mosque.data.ledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.firebase.firestore.Source
import com.saif.mosque.MosqueApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
        val store = PrototypeLedgerOutbox.get(app)
        suspend fun sync(d: PrototypeLedgerDraft): ListenableWorker.Result =
            TestListenableWorkerBuilder<PrototypeLedgerWorker>(app).setInputData(
                workDataOf("id" to d.id, "uid" to d.uid, "mosqueId" to d.mosqueId)).build().doWork()
        store.enqueue(base)
        db.disableNetwork().await()
        assertEquals(ListenableWorker.Result.retry(), withTimeout(20_000) { sync(base) })
        assertEquals("PENDING", store.list(base.uid, base.mosqueId).single().state)
        db.enableNetwork().await()
        auth.signInWithEmailAndPassword("other-ledger@example.test", "test-only-password").await()
        assertEquals(ListenableWorker.Result.success(), sync(base))
        assertEquals("PENDING", store.list(base.uid, base.mosqueId).single().state)
        auth.signInWithEmailAndPassword("ledger@example.test", "test-only-password").await()
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<PrototypeLedgerRecoveryWorker>(app).build().doWork())
        withTimeout(45_000) { while (store.list(base.uid, base.mosqueId).isNotEmpty()) delay(250) }
        store.enqueue(base) // simulate process death after commit but before removing local draft
        assertEquals(ListenableWorker.Result.success(), sync(base))
        assertTrue(store.list(base.uid, base.mosqueId).isEmpty())
        assertEquals(12000L, parent.collection("funds").document("main").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(2000L, parent.collection("donors").document("d").get(Source.SERVER).await().getLong("totalDonatedPoisha"))
        assertTrue(runCatching { writer.commit(base.copy(amountPoisha=2100)) }.isFailure)
        val legacy = base.copy(id="legacy", sourceFundId="legacy")
        store.enqueue(legacy)
        assertEquals(ListenableWorker.Result.success(), sync(legacy))
        assertEquals("MIGRATION", store.list(base.uid, base.mosqueId).single().failure)
        assertTrue(store.discardBlocked(legacy.id, legacy.uid, legacy.mosqueId))
        writer.commit(base.copy(id="expense",kind="expenses",donorId="",donorName="",title="Expense"))
        writer.commit(base.copy(id="transfer",kind="transfers",donorId="",donorName="",amountPoisha=1000,destinationFundId="target"))
        val expenses = listOf("race-a", "race-b").map { id ->
            base.copy(id=id,kind="expenses",donorId="",donorName="",title="Race",amountPoisha=8000)
        }
        expenses.forEach { store.enqueue(it) }
        expenses.map { async { sync(it) } }.awaitAll().forEach { assertEquals(ListenableWorker.Result.success(), it) }
        val blocked = store.list(base.uid, base.mosqueId).single()
        assertEquals("BLOCKED", blocked.state)
        assertEquals("INSUFFICIENT", blocked.failure)
        assertTrue(store.discardBlocked(blocked.draft.id, base.uid, base.mosqueId))
        assertEquals(1000L, parent.collection("funds").document("main").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(1000L, parent.collection("funds").document("target").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(2000L, parent.get(Source.SERVER).await().getLong("totalBalancePoisha"))
        // Offline drafts run in insertion order, even if device wall-clock values go backwards.
        val orderedIncome = base.copy(id="ordered-income", amountPoisha=1000, createdAt=999)
        val orderedExpense = base.copy(id="ordered-expense",kind="expenses",donorId="",donorName="",title="Ordered",amountPoisha=1500,createdAt=2)
        store.enqueue(orderedIncome); store.enqueue(orderedExpense)
        assertEquals(ListenableWorker.Result.success(), sync(orderedExpense))
        assertTrue(store.list(base.uid, base.mosqueId).isEmpty())
        assertEquals(500L, parent.collection("funds").document("main").get(Source.SERVER).await().getLong("balancePoisha"))
        assertEquals(1500L, parent.get(Source.SERVER).await().getLong("totalBalancePoisha"))
        assertFalse(parent.collection("contributions").document("legacy").get(Source.SERVER).await().exists())
        auth.signInWithEmailAndPassword("other-ledger@example.test", "test-only-password").await()
        assertTrue(runCatching { writer.commit(base.copy(id="wrong-user")) }.isFailure)
        auth.signOut()
    }
}
