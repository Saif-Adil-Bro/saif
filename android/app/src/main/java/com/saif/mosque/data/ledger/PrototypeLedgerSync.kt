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
import com.saif.mosque.MosqueApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Experimental only: no production screens or startup paths invoke this scheduler. */
object PrototypeLedgerSync {
    fun schedule(context: Context, draft: PrototypeLedgerDraft) {
        val request = OneTimeWorkRequestBuilder<PrototypeLedgerWorker>()
            .setInputData(workDataOf("id" to draft.id, "uid" to draft.uid, "mosqueId" to draft.mosqueId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("prototype-ledger-${draft.id}", ExistingWorkPolicy.KEEP, request)
    }
    suspend fun schedulePending(context: Context, uid: String, mosqueId: String) {
        PrototypeLedgerOutbox.get(context).list(uid, mosqueId).filter { it.state == "PENDING" }
            .forEach { schedule(context, it.draft) }
    }
}

class PrototypeLedgerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val uid = inputData.getString("uid") ?: return Result.failure()
        val mosqueId = inputData.getString("mosqueId") ?: return Result.failure()
        val container = (applicationContext as MosqueApp).container
        if (container.auth.currentUser?.uid != uid) return Result.success()
        val store = PrototypeLedgerOutbox.get(applicationContext)
        val row = try { store.list(uid, mosqueId).firstOrNull { it.draft.id == id } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return Result.failure() }
        if (row == null || row.state != "PENDING") return Result.success()
        try {
            PrototypeLedgerWriter(container.db) { container.auth.currentUser?.uid }.commit(row.draft)
        } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) {
            if (container.auth.currentUser?.uid != uid || e is LedgerIdentityChanged) return Result.success()
            val transient = e is FirebaseNetworkException || (e is FirebaseFirestoreException && e.code in setOf(
                FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED, FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED))
            if (transient) return Result.retry()
            val reason = when (e) {
                is LedgerOverdraft -> "INSUFFICIENT"
                is LedgerMigrationRequired -> "MIGRATION"
                is LedgerOperationConflict, is LedgerUnavailableAccount -> "CONFLICT"
                is IllegalArgumentException -> "INVALID"
                is FirebaseFirestoreException -> if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "PERMISSION" else "UNKNOWN"
                else -> "UNKNOWN"
            }
            store.block(id, uid, mosqueId, reason)
            return Result.success()
        }
        // A completed transaction is server-confirmed. If local removal fails,
        // retry the exact immutable operation; never convert that into a rejection.
        if (container.auth.currentUser?.uid != uid) return Result.success()
        return try { store.acknowledge(id, uid, mosqueId); Result.success() }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { Result.retry() }
    }
}
