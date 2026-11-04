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
        db.disableNetwork().await()
        val first = store.addFund("outbox-a", "sync-test-mosque", Fund(name = "Offline fund", code = "OFF"))
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

        val donor = store.addDonor("outbox-a", "sync-test-mosque", Donor(name = "Offline donor"))
        login("b@example.test")
        assertEquals(ListenableWorker.Result.success(), sync(donor.id))
        assertEquals("PENDING", store.find(donor.id)?.state)
        login("a@example.test")
        assertEquals(ListenableWorker.Result.success(), sync(donor.id))
        assertNull(store.find(donor.id))
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
        assertEquals("PERMISSION", store.find(revoked.id)?.failure)
        store.remove(revoked.id, "outbox-inactive")
        auth.signOut()
    }
}
