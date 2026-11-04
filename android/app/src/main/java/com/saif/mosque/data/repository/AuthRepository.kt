package com.saif.mosque.data.repository

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.saif.mosque.util.authStateChangesFlow
import com.saif.mosque.util.snapshotsFlow
import kotlinx.coroutines.flow.map
import com.saif.mosque.R
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

/**
 * Firebase Auth based authentication (spec module 0).
 * - 0.1 email+password login, 0.2 logout, 0.3 current session (cached profile)
 * - 0.4 lockout: 5 failures -> 15 min (client-side; see README for Cloud Function)
 * - The UI observes profile and mosque changes after server validation.
 */
class AuthRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val session: SessionManager
) {
    /** Firebase auth state stream. */
    val authState: Flow<com.google.firebase.auth.FirebaseUser?> = auth.authStateChangesFlow()

    /** Cached profile (role, mosqueId) for instant navigation. */
    val cachedUser: Flow<AppUser?> = session.userFlow

    suspend fun login(email: String, password: String): Result<Unit> {
        val e = email.trim().lowercase()

        val lockMs = session.lockRemaining(e)
        if (lockMs > 0) return Result.failure(AuthException(R.string.account_locked, retryAfterMs = lockMs))

        return try {
            auth.signInWithEmailAndPassword(e, password).await()
            session.clearLock(e)
            // The auth observer validates profile and mosque status before navigation.
            Result.success(Unit)
        } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) {
            auth.signOut()
            session.clear()
            val lockMs = if (ex is FirebaseAuthInvalidUserException || ex is FirebaseAuthInvalidCredentialsException)
                session.recordFailure(e) else 0L
            val resId = when (ex) {
                is FirebaseAuthInvalidUserException, is FirebaseAuthInvalidCredentialsException -> R.string.login_failed
                is FirebaseNetworkException -> R.string.network_error
                else -> R.string.login_failed
            }
            Result.failure(AuthException(resId, ex.message, if (lockMs > 0) lockMs else null))
        }
    }

    suspend fun logout() {
        auth.signOut()
        session.clear()
    }

    /** Validate the persisted Firebase session against current server-side status. */
    suspend fun refreshProfile(): Result<AppUser> {
        val uid = auth.currentUser?.uid ?: return Result.failure(AuthException(R.string.login_failed))
        return try {
            val snap = db.collection("users").document(uid).get(com.google.firebase.firestore.Source.SERVER).await()
            val user = snap.toObject(AppUser::class.java)?.copy(id = uid)
                ?: throw AuthException(R.string.login_failed)
            if (!user.active) throw AuthException(R.string.permission_denied)
            if (user.role != Role.SUPERADMIN) {
                val mosqueId = user.mosqueId ?: throw AuthException(R.string.permission_denied)
                val mosque = db.collection("mosques").document(mosqueId)
                    .get(com.google.firebase.firestore.Source.SERVER).await()
                if (!mosque.exists() || mosque.getBoolean("active") != true)
                    throw AuthException(R.string.mosque_inactive)
            }
            if (auth.currentUser?.uid != uid) throw AuthException(R.string.login_failed)
            session.saveUser(user)
            Result.success(user)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    /** Watch permissions while signed in, so deactivation or role changes take effect. */
    fun watchProfile(uid: String): Flow<AppUser?> =
        db.collection("users").document(uid).snapshotsFlow().map { snapshot ->
            snapshot.toObject(AppUser::class.java)?.copy(id = uid)
        }

    fun watchMosqueActive(mosqueId: String): Flow<Boolean> =
        db.collection("mosques").document(mosqueId).snapshotsFlow().map { it.exists() && it.getBoolean("active") == true }

    /**
     * Provision a committee member through the trusted callable backend.
     */
    suspend fun createMember(
        currentUser: AppUser,
        name: String,
        email: String,
        phone: String,
        role: Role,
        designation: String,
        password: String
    ): Result<AppUser> {
        if (currentUser.role != Role.SUPERADMIN && currentUser.mosqueId.isNullOrEmpty()) {
            return Result.failure(AuthException(R.string.permission_denied))
        }
        return try {
            val result = com.google.firebase.functions.FirebaseFunctions.getInstance().getHttpsCallable("mutate")
                .call(mapOf("operation" to "createMember", "mosqueId" to (currentUser.mosqueId ?: ""),
                    "value" to mapOf("name" to name, "email" to email, "phone" to phone,
                        "role" to role.name, "designation" to designation, "password" to password)))
                .await().data as Map<*, *>
            Result.success(AppUser(id = result["uid"] as String, name = name, email = email,
                phone = phone, role = role, mosqueId = currentUser.mosqueId, designation = designation))
        } catch (ex: Exception) {
            Result.failure(AuthException(R.string.error_generic, ex.message))
        }
    }

    companion object {
        /** Secure random temporary password (spec 1.8). */
        fun generateTempPassword(length: Int = 12): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789@#\$%"
            val rnd = SecureRandom()
            return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}

class AuthException(
    val resId: Int,
    message: String? = null,
    val retryAfterMs: Long? = null
) : Exception(message)
