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
    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("directory-outbox-$id")
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
        if (draft.kind !in setOf("funds", "donors") || draft.action !in setOf("add", "update", "archive") ||
            (draft.action != "add" && (draft.targetId.isNullOrBlank() || draft.targetId.contains('/') || draft.expectedPayload == null))) {
            store.markBlocked(id, "INVALID")
            return Result.success()
        }
        val parent = db.collection("mosques").document(draft.mosqueId)
        val ref = parent.collection(draft.kind).document(if (draft.action == "add") draft.id else draft.targetId!!)
        val receipt = parent.collection("syncReceipts").document(draft.id)
        suspend fun committed(): Boolean {
            val acknowledgement = if (draft.action == "add") ref else receipt
            var snap = acknowledgement.get(Source.SERVER).await()
            if (snap.metadata.hasPendingWrites()) {
                // SERVER reads still overlay local writes. Never mistake a queued write for an acknowledgement.
                db.waitForPendingWrites().await()
                snap = acknowledgement.get(Source.SERVER).await()
            }
            return if (draft.action == "add") {
                snap.exists() && DirectorySyncPolicy.ownsReceipt(draft.id, draft.uid,
                    snap.getString("syncOpId"), snap.getString("syncOwner"), snap.metadata.hasPendingWrites())
            } else {
                snap.exists() && !snap.metadata.hasPendingWrites() && snap.getString("uid") == draft.uid &&
                    snap.getString("kind") == draft.kind && snap.getString("targetId") == draft.targetId &&
                    snap.getString("action") == draft.action
            }
        }
        try {
            val profile = db.collection("users").document(draft.uid).get(Source.SERVER).await()
            if (!profile.exists() || !DirectorySyncPolicy.permitted(draft.kind, profile.getString("role"),
                    profile.getBoolean("active") == true, profile.getString("mosqueId"), draft.mosqueId)) {
                store.markBlocked(id, "PERMISSION")
                return Result.success()
            }
            val mosque = parent.get(Source.SERVER).await()
            if (!mosque.exists() || mosque.getBoolean("active") != true) {
                store.markBlocked(id, "PERMISSION")
                return Result.success()
            }
            if (committed()) { store.remove(id, draft.uid); return Result.success() }
            if (auth.currentUser?.uid != draft.uid) return Result.success()
            val gson = Gson()
            if (draft.action != "add") {
                db.runTransaction { tx ->
                    val priorReceipt = tx.get(receipt)
                    if (priorReceipt.exists()) {
                        if (priorReceipt.getString("uid") != draft.uid || priorReceipt.getString("kind") != draft.kind ||
                            priorReceipt.getString("targetId") != draft.targetId || priorReceipt.getString("action") != draft.action)
                            throw DirectoryConflictException()
                    } else {
                        val snapshot = tx.get(ref)
                        val fields: Map<String, Any> = when (draft.kind) {
                            "funds" -> {
                                val current = snapshot.toObject(Fund::class.java) ?: throw DirectoryConflictException()
                                val expected = gson.fromJson(draft.expectedPayload, Fund::class.java)
                                val next = gson.fromJson(draft.payload, Fund::class.java)
                                if (current.mosqueId != draft.mosqueId || !com.saif.mosque.util.MetadataVersion.sameFund(current, expected))
                                    throw DirectoryConflictException()
                                mapOf("name" to next.name, "code" to next.code, "nameEn" to next.nameEn,
                                    "description" to next.description, "active" to next.active,
                                    "lastSyncOpId" to draft.id, "lastSyncOwner" to draft.uid)
                            }
                            else -> {
                                val current = snapshot.toObject(Donor::class.java) ?: throw DirectoryConflictException()
                                val expected = gson.fromJson(draft.expectedPayload, Donor::class.java)
                                val next = gson.fromJson(draft.payload, Donor::class.java)
                                if (current.mosqueId != draft.mosqueId || !com.saif.mosque.util.MetadataVersion.sameDonor(current, expected))
                                    throw DirectoryConflictException()
                                mapOf("name" to next.name, "phone" to next.phone, "address" to next.address,
                                    "active" to next.active, "lastSyncOpId" to draft.id, "lastSyncOwner" to draft.uid)
                            }
                        }
                        tx.update(ref, fields)
                        tx.set(receipt, mapOf("uid" to draft.uid, "kind" to draft.kind,
                            "targetId" to draft.targetId!!, "action" to draft.action))
                    }
                }.await()
                store.remove(id, draft.uid)
                return Result.success()
            }
            // An existing document without our immutable creation marker is a conflict, never overwrite it.
            if (ref.get(Source.SERVER).await().exists()) { store.markBlocked(id, "CONFLICT"); return Result.success() }
            val fields: Map<String, Any> = when (draft.kind) {
                "funds" -> gson.fromJson(draft.payload, Fund::class.java).let { f -> mapOf(
                    "mosqueId" to draft.mosqueId, "name" to f.name, "code" to f.code,
                    "nameEn" to f.nameEn, "description" to f.description, "active" to f.active, "balance" to 0, "balancePoisha" to 0L,
                    "syncOpId" to draft.id, "syncOwner" to draft.uid) }
                else -> gson.fromJson(draft.payload, Donor::class.java).let { d -> mapOf(
                    "mosqueId" to draft.mosqueId, "name" to d.name, "phone" to d.phone, "address" to d.address,
                    "active" to d.active, "totalDonated" to 0, "totalDonatedPoisha" to 0L, "createdAt" to draft.createdAt,
                    "syncOpId" to draft.id, "syncOwner" to draft.uid) }
            }
            val counter = if (draft.kind == "funds") "fundCount" else "donorCount"
            db.batch().set(ref, fields).update(parent, mapOf(counter to FieldValue.increment(1),
                "directoryOpId" to draft.id, "directoryOpKind" to draft.kind)).commit().await()
            // A resolved commit is an acknowledgement; retain the row if local removal fails.
            store.remove(id, draft.uid)
            return Result.success()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // Signing out or switching accounts is not a rejection of the original user's draft.
            if (auth.currentUser?.uid != draft.uid) return Result.success()
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
            store.markBlocked(id, when {
                e is DirectoryConflictException -> "CONFLICT"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> "PERMISSION"
                else -> "UNKNOWN"
            })
            return Result.success()
        }
    }
}

private class DirectoryConflictException : Exception("Directory record changed or receipt conflicted")
