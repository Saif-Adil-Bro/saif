package com.saif.mosque.data.ledger

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.saif.mosque.MosqueApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** WorkManager scheduler and worker for offline-resilient v2 ledger corrections (`ledgerMutations`). */
object PrototypeCorrectionSync {
    fun schedule(context: Context, draft: PrototypeCorrectionDraft) {
        val request = OneTimeWorkRequestBuilder<PrototypeCorrectionWorker>()
            .setInputData(workDataOf("id" to draft.id, "uid" to draft.uid, "mosqueId" to draft.mosqueId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val scope = java.security.MessageDigest.getInstance("SHA-256")
            .digest((draft.uid + "\u0000" + draft.mosqueId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ledger-correction-scope-$scope",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    suspend fun schedulePending(context: Context, uid: String, mosqueId: String) {
        PrototypeCorrectionOutbox.get(context).list(uid, mosqueId).firstOrNull()
            ?.takeIf { it.state == "PENDING" }
            ?.let { schedule(context, it.draft) }
    }
}

class PrototypeCorrectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val mosqueId = inputData.getString("mosqueId") ?: return Result.failure()
        val container = (applicationContext as MosqueApp).container
        val store = PrototypeCorrectionOutbox.get(applicationContext)
        while (container.auth.currentUser?.uid == uid) {
            val row = try {
                store.list(uid, mosqueId).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return Result.retry()
            }
            if (row == null || row.state != "PENDING") return Result.success()
            val result = syncRow(store, row, uid, mosqueId)
            if (result != Result.success()) return result
        }
        return Result.success()
    }

    private suspend fun syncRow(
        store: PrototypeCorrectionOutbox,
        row: PrototypeQueuedCorrection,
        uid: String,
        mosqueId: String
    ): Result {
        val container = (applicationContext as MosqueApp).container
        val id = row.draft.id
        try {
            var profile = container.db.collection("users").document(uid).get(Source.SERVER).await()
            if (profile.metadata.hasPendingWrites()) {
                container.db.waitForPendingWrites().await()
                profile = container.db.collection("users").document(uid).get(Source.SERVER).await()
            }
            val role = profile.getString("role")
            val allowed = role == "SUPERADMIN" || (profile.getString("mosqueId") == mosqueId &&
                (role in setOf("ADMIN", "SECRETARY", "TREASURER") ||
                    (row.draft.kind != "transfers" && role == "CASHIER")))
            if (!profile.exists() || profile.metadata.hasPendingWrites() || profile.getBoolean("active") != true || !allowed) {
                store.block(id, uid, mosqueId, "PERMISSION")
                return Result.success()
            }
            if (container.auth.currentUser?.uid != uid) return Result.success()
            PrototypeCorrectionWriter(container.db) { container.auth.currentUser?.uid }.commit(row.draft)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            if (container.auth.currentUser?.uid != uid || e is LedgerIdentityChanged) return Result.success()
            val transient = e is FirebaseNetworkException || (e is FirebaseFirestoreException && e.code in setOf(
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
                FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED
            ))
            if (transient) return Result.retry()
            val reason = when (e) {
                is LedgerOverdraft -> "INSUFFICIENT"
                is LedgerVersionPending -> "WAIT_MIGRATION"
                is LedgerMigrationRequired -> "MIGRATION"
                is LedgerOperationConflict, is LedgerUnavailableAccount -> "CONFLICT"
                is IllegalArgumentException -> "INVALID"
                is FirebaseFirestoreException -> if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "PERMISSION" else "UNKNOWN"
                else -> "UNKNOWN"
            }
            store.block(id, uid, mosqueId, reason)
            return Result.success()
        }
        if (container.auth.currentUser?.uid != uid) return Result.success()
        return try {
            store.acknowledge(id, uid, mosqueId)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
