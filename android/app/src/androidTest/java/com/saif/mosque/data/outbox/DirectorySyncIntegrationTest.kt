package com.saif.mosque.data.outbox

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.firebase.firestore.Source
import com.saif.mosque.MosqueApp
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** CI starts Auth and Firestore emulators on the Android emulator's host. */
@RunWith(AndroidJUnit4::class)
class DirectorySyncIntegrationTest {
    @Test fun offlineDraftRetryIdentityAndRevocationAreHandled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context.applicationContext as MosqueApp).container
        val auth = container.auth
        val db = container.db
        // Explicitly redirect BOTH services before any authentication or Firestore operation.
        auth.useEmulator("10.0.2.2", 9099)
        db.useEmulator("10.0.2.2", 8080)
        val store = container.outbox
        suspend fun login(email: String) { auth.signInWithEmailAndPassword(email, "test-only-password").await() }
        suspend fun sync(id: String): ListenableWorker.Result =
            TestListenableWorkerBuilder<DirectorySyncWorker>(context)
                .setInputData(workDataOf("id" to id)).build().doWork()
        val mosque = db.collection("mosques").document("sync-test-mosque")
        login("a@example.test")
        // Both legacy rows and explicit live markers survive the tombstone filter,
        // including when more than ten newer tombstones precede them.
        val ledger = container.ledgerRepository
        withTimeout(20_000) {
            assertEquals(setOf("legacy", "live"), ledger.observeRecentContributions("sync-test-mosque").first { it.size == 2 }.map { it.id }.toSet())
            assertEquals(setOf("legacy", "live"), ledger.observeRecentExpenses("sync-test-mosque").first { it.size == 2 }.map { it.id }.toSet())
            assertEquals(setOf("legacy", "live"), ledger.observeRecentTransfers("sync-test-mosque").first { it.size == 2 }.map { it.id }.toSet())
            assertEquals(setOf("legacy", "live"), ledger.observeMonthContributions("sync-test-mosque", "2026-11-01", "2026-11-30").first { it.size == 2 }.map { it.id }.toSet())
            assertEquals(setOf("legacy", "live"), ledger.observeMonthExpenses("sync-test-mosque", "2026-11-01", "2026-11-30").first { it.size == 2 }.map { it.id }.toSet())
            assertEquals(setOf("legacy", "live"), ledger.contributionsInRange("sync-test-mosque", "2026-11-01", "2026-11-30").map { it.id }.toSet())
            assertEquals(setOf("legacy", "live"), ledger.expensesInRange("sync-test-mosque", "2026-11-01", "2026-11-30").map { it.id }.toSet())
        }
        db.disableNetwork().await()
        val first = store.addFund("outbox-a", "sync-test-mosque", Fund(name = "টেস্ট", code = "TST", nameEn = "Test", description = "Test fund"))
        assertEquals("PENDING", store.find(first.id)?.state)
        // A disconnected Firestore must not acknowledge or discard the draft.
        assertEquals(ListenableWorker.Result.retry(), withTimeout(20_000) { sync(first.id) })
        assertEquals("PENDING", store.find(first.id)?.state)
        db.enableNetwork().await()
        // Exercise the actual WorkManager scheduling path, not just doWork directly.
        DirectorySync.schedule(context, first.id)
        withTimeout(45_000) {
            while (store.find(first.id) != null) delay(250)
        }
        assertNull(store.find(first.id))
        val fund = mosque.collection("funds").document(first.id).get(Source.SERVER).await()
        assertEquals("টেস্ট", fund.getString("name"))
        assertEquals("TST", fund.getString("code"))
        assertEquals("Test", fund.getString("nameEn"))
        assertEquals("Test fund", fund.getString("description"))
        assertEquals(first.id, fund.getString("syncOpId"))
        assertEquals("outbox-a", fund.getString("syncOwner"))
        assertEquals(1L, mosque.get(Source.SERVER).await().getLong("fundCount"))

        // Simulate process death after server commit but before removal of the local row.
        val values = ContentValues().apply {
            put("id", first.id); put("uid", first.uid); put("mosque_id", first.mosqueId)
            put("kind", first.kind); put("name", first.name); put("payload", first.payload)
            put("created_at", first.createdAt); put("state", "PENDING"); putNull("failure")
        }
        store.writableDatabase.insertOrThrow("drafts", null, values)
        assertEquals(ListenableWorker.Result.success(), sync(first.id))
        assertNull(store.find(first.id))
        assertEquals(1L, mosque.get(Source.SERVER).await().getLong("fundCount"))

        // Offline metadata edits use an atomic receipt and reject stale snapshots.
        val originalFund = fund.toObject(Fund::class.java)!!
        db.disableNetwork().await()
        val edit = store.editFund("outbox-a", "sync-test-mosque", originalFund, originalFund.copy(name = "Edited offline"))
        db.enableNetwork().await()
        assertEquals(ListenableWorker.Result.success(), sync(edit.id))
        assertNull(store.find(edit.id))
        assertEquals("Edited offline", mosque.collection("funds").document(first.id).get(Source.SERVER).await().getString("name"))
        assertEquals("outbox-a", mosque.collection("syncReceipts").document(edit.id).get(Source.SERVER).await().getString("uid"))

        // A lost acknowledgement must not replay an older edit over a newer server change.
        mosque.collection("funds").document(first.id).update("name", "Later online change").await()
        val editValues = ContentValues().apply {
            put("id", edit.id); put("uid", edit.uid); put("mosque_id", edit.mosqueId); put("kind", edit.kind)
            put("name", edit.name); put("payload", edit.payload); put("created_at", edit.createdAt)
            put("state", "PENDING"); putNull("failure"); put("action", edit.action)
            put("target_id", edit.targetId); put("expected_payload", edit.expectedPayload)
        }
        store.writableDatabase.insertOrThrow("drafts", null, editValues)
        assertEquals(ListenableWorker.Result.success(), sync(edit.id))
        assertEquals("Later online change", mosque.collection("funds").document(first.id).get(Source.SERVER).await().getString("name"))
        val currentFund = mosque.collection("funds").document(first.id).get(Source.SERVER).await().toObject(Fund::class.java)!!
        val stale = store.editFund("outbox-a", "sync-test-mosque", currentFund, currentFund.copy(name = "Stale edit"))
        mosque.collection("funds").document(first.id).update("name", "Another device").await()
        assertEquals(ListenableWorker.Result.success(), sync(stale.id))
        assertEquals("CONFLICT", store.find(stale.id)?.failure)
        assertEquals("Another device", mosque.collection("funds").document(first.id).get(Source.SERVER).await().getString("name"))
        store.discardBlocked(stale.id, "outbox-a", "sync-test-mosque")

        val donor = store.addDonor("outbox-a", "sync-test-mosque", Donor(name = "Offline donor"))
        login("b@example.test")
        assertEquals(ListenableWorker.Result.success(), sync(donor.id))
        assertEquals("PENDING", store.find(donor.id)?.state)
        login("a@example.test")
        assertEquals(ListenableWorker.Result.success(), sync(donor.id))
        assertNull(store.find(donor.id))
        assertEquals(1L, mosque.get(Source.SERVER).await().getLong("donorCount"))
        val donorRef = mosque.collection("donors").document(donor.id)
        val donorBeforeArchive = donorRef.get(Source.SERVER).await().toObject(Donor::class.java)!!
        val archive = store.editDonor("outbox-a", "sync-test-mosque", donorBeforeArchive,
            donorBeforeArchive.copy(active = false), archive = true)
        assertEquals(ListenableWorker.Result.success(), sync(archive.id))
        assertEquals(false, donorRef.get(Source.SERVER).await().getBoolean("active"))
        assertEquals("archive", mosque.collection("syncReceipts").document(archive.id).get(Source.SERVER).await().getString("action"))
        assertEquals(1L, mosque.get(Source.SERVER).await().getLong("donorCount"))

        val drafts = listOf(
            store.addFund("outbox-a", "sync-test-mosque", Fund(name = "Concurrent A", code = "A")),
            store.addFund("outbox-a", "sync-test-mosque", Fund(name = "Concurrent B", code = "B"))
        )
        drafts.map { async { sync(it.id) } }.awaitAll().forEach { assertEquals(ListenableWorker.Result.success(), it) }
        assertEquals(3L, mosque.get(Source.SERVER).await().getLong("fundCount"))

        login("inactive@example.test")
        val revoked = store.addDonor("outbox-inactive", "sync-test-mosque", Donor(name = "Blocked"))
        assertEquals(ListenableWorker.Result.success(), sync(revoked.id))
        assertEquals("BLOCKED", store.find(revoked.id)?.state)
        assertEquals("PROFILE_INACTIVE", store.find(revoked.id)?.failure)
        store.remove(revoked.id, "outbox-inactive")
        // An active administrator can read this tenant, but a missing counter
        // makes the paired write fail. Preserve the draft and expose the stage.
        login("badcounter@example.test")
        val badMosque = db.collection("mosques").document("sync-badcounter")
        // Guard against accidentally running with the emulator's open fallback rules.
        val denied = runCatching { badMosque.update("totalBalance", 12345).await() }.exceptionOrNull()
        assertEquals(com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
            (denied as? com.google.firebase.firestore.FirebaseFirestoreException)?.code)
        assertEquals(false, badMosque.get(Source.SERVER).await().contains("fundCount"))
        val bad = store.addFund("outbox-badcounter", "sync-badcounter", Fund(name = "Keep this", code = "KEP"))
        assertEquals(ListenableWorker.Result.success(), sync(bad.id))
        assertEquals("BLOCKED", store.find(bad.id)?.state)
        assertEquals("WRITE_DENIED", store.find(bad.id)?.failure)
        assertEquals(false, db.collection("mosques").document("sync-badcounter").collection("funds").document(bad.id).get(Source.SERVER).await().exists())
        store.discardBlocked(bad.id, "outbox-badcounter", "sync-badcounter")
        auth.signOut()
    }
}
