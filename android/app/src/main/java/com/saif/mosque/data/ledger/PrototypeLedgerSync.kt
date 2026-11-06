package com.saif.mosque.data.ledger

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import com.saif.mosque.MosqueApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** WIP v2 scheduler. Production use requires the coordinated ledger migration. */
object PrototypeLedgerSync {
    fun schedule(context: Context, draft: PrototypeLedgerDraft) {
        val request = OneTimeWorkRequestBuilder<PrototypeLedgerWorker>()
            .setInputData(workDataOf("id" to draft.id, "uid" to draft.uid, "mosqueId" to draft.mosqueId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Append a wake-up behind any current worker. KEEP can lose a wake-up if
        // insertion races the worker's final empty read; periodic recovery is a fallback.
        // The unique chain still serializes a tenant/account's local insertion order.
        val scope = java.security.MessageDigest.getInstance("SHA-256")
            .digest((draft.uid + "\u0000" + draft.mosqueId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        WorkManager.getInstance(context).enqueueUniqueWork("ledger-scope-$scope", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        scheduleRecovery(context)
    }
    fun scheduleRecovery(context: Context) {
        val request = PeriodicWorkRequestBuilder<PrototypeLedgerRecoveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "prototype-ledger-recovery", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    suspend fun ensureMosqueV2Initialized(db: com.google.firebase.firestore.FirebaseFirestore, mosqueId: String) {
        val parentRef = db.collection("mosques").document(mosqueId)
        val mosqueSnap = parentRef.get(Source.SERVER).await()
        if (!mosqueSnap.exists() || mosqueSnap.metadata.hasPendingWrites() || mosqueSnap.getBoolean("active") != true) return
        if (mosqueSnap.getLong("ledgerVersion") == 2L) return

        val fundsSnap = parentRef.collection("funds").get(Source.SERVER).await()
        for (f in fundsSnap.documents) {
            if (!f.contains("balancePoisha")) {
                val balance = (f.get("balance") as? Number)?.toDouble() ?: 0.0
                val poisha = LedgerMath.poisha(balance, positive = false)
                f.reference.update("balancePoisha", poisha).await()
            }
        }
        val donorsSnap = parentRef.collection("donors").get(Source.SERVER).await()
        for (d in donorsSnap.documents) {
            if (!d.contains("totalDonatedPoisha")) {
                val donated = (d.get("totalDonated") as? Number)?.toDouble() ?: 0.0
                val poisha = LedgerMath.poisha(donated, positive = false)
                d.reference.update("totalDonatedPoisha", poisha).await()
            }
        }
        val totalBalance = (mosqueSnap.get("totalBalance") as? Number)?.toDouble() ?: 0.0
        val totalPoisha = LedgerMath.poisha(totalBalance, positive = false)
        parentRef.update(mapOf(
            "ledgerVersion" to 2L,
            "totalBalancePoisha" to totalPoisha,
            "ledgerInitOp" to "v2-init"
        )).await()
    }

    suspend fun recoverScope(context: Context, uid: String, mosqueId: String) {
        val app = context.applicationContext as MosqueApp
        if (app.container.auth.currentUser?.uid != uid) return
        val store = PrototypeLedgerOutbox.get(context)
        val waiting = store.list(uid, mosqueId).filter { it.state == "BLOCKED" && it.failure == "WAIT_MIGRATION" }
        if (waiting.isNotEmpty()) {
            try {
                ensureMosqueV2Initialized(app.container.db, mosqueId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // If initialization fails due to permissions or network, proceed to check state
            }
            // Only a confirmed, active v2 tenant releases migration-waiting drafts.
            // Missing/inconsistent per-account fields remain blocked for investigation.
            val snap = app.container.db.collection("mosques").document(mosqueId).get(Source.SERVER).await()
            if (!snap.metadata.hasPendingWrites() && snap.getBoolean("active") == true && snap.getLong("ledgerVersion") == 2L && app.container.auth.currentUser?.uid == uid) {
                waiting.forEach { store.retry(it.draft.id, uid, mosqueId) }
            }
        }
        schedulePending(context, uid, mosqueId)
    }
    suspend fun schedulePending(context: Context, uid: String, mosqueId: String) {
        PrototypeLedgerOutbox.get(context).list(uid, mosqueId).firstOrNull()
            ?.takeIf { it.state == "PENDING" }?.let { schedule(context, it.draft) }
        scheduleRecovery(context)
    }
}

/** Startup and periodic recovery cover a crash between persistence and scheduling. */
class PrototypeLedgerRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = (applicationContext as MosqueApp).container.auth.currentUser?.uid ?: return Result.success()
        return try {
            PrototypeLedgerOutbox.get(applicationContext).pendingMosques(uid).forEach {
                PrototypeLedgerSync.recoverScope(applicationContext, uid, it)
            }
            Result.success()
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { Result.retry() }
    }
}

class PrototypeLedgerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val mosqueId = inputData.getString("mosqueId") ?: return Result.failure()
        val container = (applicationContext as MosqueApp).container
        val store = PrototypeLedgerOutbox.get(applicationContext)
        // Drain a single tenant in SQLite insertion order. The unique scope work
        // prevents independent requests from spending before earlier local income.
        while (container.auth.currentUser?.uid == uid) {
            val row = try { store.list(uid, mosqueId).firstOrNull() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { return Result.retry() }
            // An earlier blocked operation is unresolved. Never send later drafts
            // ahead of it: they may depend on its money or correction.
            if (row == null || row.state != "PENDING") return Result.success()
            val result = syncRow(store, row, uid, mosqueId)
            if (result != Result.success()) return result
        }
        return Result.success()
    }

    private suspend fun syncRow(store: PrototypeLedgerOutbox, row: PrototypeQueuedLedger, uid: String, mosqueId: String): Result {
        val container = (applicationContext as MosqueApp).container
        val id = row.draft.id
        try {
            // Firestore transactions can bypass disableNetwork's normal listener/write
            // path on some SDK versions. A SERVER read first ensures connectivity is
            // revalidated and the original actor is still eligible before sending.
            var profile = container.db.collection("users").document(uid).get(Source.SERVER).await()
            if (profile.metadata.hasPendingWrites()) {
                container.db.waitForPendingWrites().await()
                profile = container.db.collection("users").document(uid).get(Source.SERVER).await()
            }
            val role = profile.getString("role")
            val allowed = role == "SUPERADMIN" || (profile.getString("mosqueId") == mosqueId &&
                (role in setOf("ADMIN", "SECRETARY", "TREASURER") ||
                    (row.draft.kind != "transfers" && role == "CASHIER")))
            if (!profile.exists() || profile.metadata.hasPendingWrites() || profile.getBoolean("active") != true || !allowed)
                throw LedgerPermissionDenied()
            if (container.auth.currentUser?.uid != uid) return Result.success()
            if (role in setOf("SUPERADMIN", "ADMIN", "SECRETARY", "TREASURER")) {
                try {
                    PrototypeLedgerSync.ensureMosqueV2Initialized(container.db, mosqueId)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // Let writer.commit evaluate or throw appropriate ledger exception
                }
            }
            PrototypeLedgerWriter(container.db) { container.auth.currentUser?.uid }.commit(row.draft)
        } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) {
            if (container.auth.currentUser?.uid != uid || e is LedgerIdentityChanged) return Result.success()
            val transient = e is FirebaseNetworkException || (e is FirebaseFirestoreException && e.code in setOf(
                FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED, FirebaseFirestoreException.Code.ABORTED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED))
            if (transient) return Result.retry()
            val reason = when (e) {
                is LedgerPermissionDenied -> "PERMISSION"
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
        // A completed transaction is server-confirmed. If local removal fails,
        // retry the exact immutable operation; never convert that into a rejection.
        if (container.auth.currentUser?.uid != uid) return Result.success()
        return try { store.acknowledge(id, uid, mosqueId); Result.success() }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { Result.retry() }
    }
}

private class LedgerPermissionDenied : IllegalStateException("Ledger access changed")
