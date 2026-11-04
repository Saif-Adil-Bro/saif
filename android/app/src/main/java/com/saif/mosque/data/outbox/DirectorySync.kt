package com.saif.mosque.data.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.gson.Gson
import com.saif.mosque.MosqueApp
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

object DirectorySync {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<DirectorySyncWorker>()
            .setInputData(workDataOf("id" to id)).setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("directory-outbox-$id", ExistingWorkPolicy.REPLACE, request)
    }
    fun scheduleRecovery(context: Context) {
        val request = PeriodicWorkRequestBuilder<DirectoryRecoveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "directory-outbox-recovery", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    suspend fun schedulePending(context: Context, uid: String) {
        for (draft in DirectoryOutbox.get(context).list(uid)) {
            if (draft.state == "PENDING") schedule(context, draft.id)
        }
        scheduleRecovery(context)
    }
}

/** Recovery also covers a process dying between the SQLite commit and WorkManager scheduling. */
class DirectoryRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = (applicationContext as MosqueApp).container.auth.currentUser?.uid ?: return Result.success()
        return try {
            DirectorySync.schedulePending(applicationContext, uid)
            Result.success()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.retry() }
    }
}

class DirectorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val store = DirectoryOutbox.get(applicationContext)
        val draft = store.find(id) ?: return Result.success()
        if (draft.state != "PENDING") return Result.success()
        val container = (applicationContext as MosqueApp).container
        val auth = container.auth
        // Never run another user's drafts. A later sign-in reschedules them.
        if (auth.currentUser?.uid != draft.uid) return Result.success()
        val db = container.db
        val ref = db.collection("mosques").document(draft.mosqueId).collection(draft.kind).document(draft.id)
        suspend fun committed(): Boolean {
            var snap = ref.get(Source.SERVER).await()
            if (snap.metadata.hasPendingWrites()) {
                // SERVER reads still overlay local writes. Never mistake a queued write for an acknowledgement.
                db.waitForPendingWrites().await()
                snap = ref.get(Source.SERVER).await()
            }
            return snap.exists() && DirectorySyncPolicy.ownsReceipt(draft.id, draft.uid,
                snap.getString("syncOpId"), snap.getString("syncOwner"), snap.metadata.hasPendingWrites())
        }
        try {
            val profile = db.collection("users").document(draft.uid).get(Source.SERVER).await()
            if (!profile.exists() || !DirectorySyncPolicy.permitted(draft.kind, profile.getString("role"),
                    profile.getBoolean("active") == true, profile.getString("mosqueId"), draft.mosqueId)) {
                store.markBlocked(id, "PERMISSION")
                return Result.success()
            }
            val mosque = db.collection("mosques").document(draft.mosqueId).get(Source.SERVER).await()
            if (!mosque.exists() || mosque.getBoolean("active") != true) {
                store.markBlocked(id, "PERMISSION")
                return Result.success()
            }
            if (committed()) { store.remove(id, draft.uid); return Result.success() }
            // An existing document without our immutable receipt is a conflict, never overwrite it.
            if (ref.get(Source.SERVER).await().exists()) { store.markBlocked(id, "CONFLICT"); return Result.success() }
            if (auth.currentUser?.uid != draft.uid) return Result.success()
            val gson = Gson()
            val fields: Map<String, Any> = when (draft.kind) {
                "funds" -> gson.fromJson(draft.payload, Fund::class.java).let { f -> mapOf(
                    "mosqueId" to draft.mosqueId, "name" to f.name, "code" to f.code,
                    "nameEn" to f.nameEn, "description" to f.description, "active" to f.active, "balance" to 0,
                    "syncOpId" to draft.id, "syncOwner" to draft.uid) }
                "donors" -> gson.fromJson(draft.payload, Donor::class.java).let { d -> mapOf(
                    "mosqueId" to draft.mosqueId, "name" to d.name, "phone" to d.phone, "address" to d.address,
                    "active" to d.active, "totalDonated" to 0, "createdAt" to draft.createdAt,
                    "syncOpId" to draft.id, "syncOwner" to draft.uid) }
                else -> { store.markBlocked(id, "INVALID"); return Result.success() }
            }
            val parent = db.collection("mosques").document(draft.mosqueId)
            val counter = if (draft.kind == "funds") "fundCount" else "donorCount"
            db.batch().set(ref, fields).update(parent, mapOf(counter to FieldValue.increment(1),
                "directoryOpId" to draft.id, "directoryOpKind" to draft.kind)).commit().await()
            // A resolved commit is an acknowledgement; retain the row if local removal fails.
            store.remove(id, draft.uid)
            return Result.success()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // A duplicate retry can lose the race with the first attempt. Verify its receipt before failing.
            if (auth.currentUser?.uid == draft.uid) {
                try { if (committed()) { store.remove(id, draft.uid); return Result.success() } }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* still uncertain */ }
            }
            val transient = e is FirebaseNetworkException || (e is FirebaseFirestoreException && e.code in setOf(
                FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED, FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED))
            if (transient) return Result.retry()
            store.markBlocked(id, if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                "PERMISSION" else "UNKNOWN")
            return Result.success()
        }
    }
}
