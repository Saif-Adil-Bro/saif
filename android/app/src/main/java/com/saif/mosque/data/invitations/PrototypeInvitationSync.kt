package com.saif.mosque.data.invitations

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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.saif.mosque.MosqueApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** Isolated prototype; call startup recovery on sign-in when a UI is integrated. */
object PrototypeInvitationSync {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context, draft: PrototypeInvitation) {
        val request = OneTimeWorkRequestBuilder<PrototypeInvitationWorker>()
            .setInputData(workDataOf("id" to draft.id, "uid" to draft.issuerUid, "mosqueId" to draft.mosqueId))
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        val scope = java.security.MessageDigest.getInstance("SHA-256")
            .digest((draft.issuerUid + "\u0000" + draft.mosqueId + "\u0000" + draft.id).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        WorkManager.getInstance(context).enqueueUniqueWork("invitation-$scope", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        scheduleRecovery(context)
    }
    fun scheduleRecovery(context: Context) {
        val request = PeriodicWorkRequestBuilder<PrototypeInvitationRecoveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("invitation-prototype-recovery", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    suspend fun recover(context: Context, uid: String) {
        val auth = (context.applicationContext as MosqueApp).container.auth
        if (auth.currentUser?.uid != uid) return
        PrototypeInvitationOutbox.get(context).list(uid).filter { it.state == "PENDING" }.forEach { schedule(context, it.draft) }
        scheduleRecovery(context)
    }
}

class PrototypeInvitationQueue(private val context: Context, private val auth: FirebaseAuth) {
    val store = PrototypeInvitationOutbox.get(context)
    suspend fun enqueue(draft: PrototypeInvitation) {
        if (auth.currentUser?.uid != draft.issuerUid) throw InvitationIdentityChanged()
        store.enqueue(draft)
        try { PrototypeInvitationSync.schedule(context, draft) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* explicit startup/periodic recovery */ }
    }
    /** READY is only a past acknowledgement. Recheck before offering a code. */
    suspend fun checkSharingStatus(draft: PrototypeInvitation): PrototypeInvitationStatus {
        if (auth.currentUser?.uid != draft.issuerUid) throw InvitationIdentityChanged()
        val row = store.find(draft.id, draft.issuerUid, draft.mosqueId)
        if (row == null || row.draft != draft || row.state != "READY") throw InvitationConflict()
        val db = (context.applicationContext as MosqueApp).container.db
        return PrototypeInvitationService(db, auth).inspect(draft)
    }
    suspend fun retry(draft: PrototypeInvitation) {
        if (auth.currentUser?.uid != draft.issuerUid) return
        if (store.transition(draft.id, draft.issuerUid, draft.mosqueId, "BLOCKED", "PENDING")) {
            try { PrototypeInvitationSync.schedule(context, draft) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* startup/periodic recovery */ }
        }
    }
}

class PrototypeInvitationRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = (applicationContext as MosqueApp).container.auth.currentUser?.uid ?: return Result.success()
        return try { PrototypeInvitationSync.recover(applicationContext, uid); Result.success() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}

class PrototypeInvitationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val uid = inputData.getString("uid") ?: return Result.failure()
        val mosqueId = inputData.getString("mosqueId") ?: return Result.failure()
        val store = PrototypeInvitationOutbox.get(applicationContext)
        val container = (applicationContext as MosqueApp).container
        val row = try { store.find(id, uid, mosqueId) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return Result.retry() }
        if (row == null || row.state != "PENDING" || container.auth.currentUser?.uid != uid) return Result.success()
        val status = try {
            // Server-only preflight: do not rely on a cached role or unvalidated network.
            val profile = container.db.collection("users").document(uid).get(Source.SERVER).await()
            if (container.auth.currentUser?.uid != uid) return Result.success()
            if (profile.metadata.hasPendingWrites()) return Result.retry()
            val role = profile.getString("role")
            val eligible = profile.getBoolean("active") == true && (role == "SUPERADMIN" ||
                (profile.getString("mosqueId") == mosqueId && role in setOf("ADMIN", "PRESIDENT", "SECRETARY") &&
                    (row.draft.role.name != "ADMIN" || role == "ADMIN")))
            if (!profile.exists() || !eligible) {
                store.transition(id, uid, mosqueId, "PENDING", "BLOCKED", "PERMISSION")
                return Result.success()
            }
            val mosque = container.db.collection("mosques").document(mosqueId).get(Source.SERVER).await()
            if (container.auth.currentUser?.uid != uid) return Result.success()
            if (mosque.metadata.hasPendingWrites()) return Result.retry()
            if (!mosque.exists() || mosque.getBoolean("active") != true) {
                store.transition(id, uid, mosqueId, "PENDING", "BLOCKED", "MOSQUE")
                return Result.success()
            }
            if (container.auth.currentUser?.uid != uid) return Result.success()
            PrototypeInvitationService(container.db, container.auth).issue(row.draft)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (container.auth.currentUser?.uid != uid || e is InvitationIdentityChanged) return Result.success()
            if (e is FirebaseNetworkException || e is InvitationPendingWrite ||
                (e is FirebaseFirestoreException && e.code in setOf(FirebaseFirestoreException.Code.UNAVAILABLE,
                    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED, FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
                    FirebaseFirestoreException.Code.ABORTED, FirebaseFirestoreException.Code.UNAUTHENTICATED))) return Result.retry()
            return try {
                store.transition(id, uid, mosqueId, "PENDING", "BLOCKED", when {
                    e is InvitationConflict -> "CONFLICT"
                    e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> "PERMISSION"
                    else -> "UNKNOWN"
                })
                Result.success()
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { Result.retry() }
        }
        if (container.auth.currentUser?.uid != uid) return Result.success()
        val next = when (status) {
            PrototypeInvitationStatus.PENDING -> "READY"
            PrototypeInvitationStatus.ACCEPTED -> "ACCEPTED"
            PrototypeInvitationStatus.REVOKED -> "REVOKED"
        }
        return try { store.transition(id, uid, mosqueId, "PENDING", next); Result.success() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
