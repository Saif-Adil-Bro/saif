package com.saif.mosque.data.repository

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.ktx.authStateFlow
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
 * - 2.3 inactive mosque users are signed out immediately
 */
class AuthRepository(
    private val auth: FirebaseAuth,
    private val secondaryAuth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val session: SessionManager
) {
    /** Firebase auth state stream. */
    val authState: Flow<com.google.firebase.auth.FirebaseUser?> = auth.authStateFlow()

    /** Cached profile (role, mosqueId) for instant navigation. */
    val cachedUser: Flow<AppUser?> = session.userFlow

    suspend fun login(email: String, password: String): Result<AppUser> {
        val e = email.trim().lowercase()

        val lockMs = session.lockRemaining(e)
        if (lockMs > 0) return Result.failure(AuthException(R.string.account_locked, retryAfterMs = lockMs))

        return try {
            val cred = auth.signInWithEmailAndPassword(e, password).await()
            val uid = cred.user?.uid ?: return Result.failure(AuthException(R.string.login_failed))

            val snap = db.collection("users").document(uid).get().await()
            if (!snap.exists()) {
                auth.signOut()
                return Result.failure(AuthException(R.string.login_failed))
            }
            val user = snap.toObject(AppUser::class.java)?.copy(id = uid)
                ?: return Result.failure(AuthException(R.string.login_failed))

            if (!user.active) {
                auth.signOut()
                return Result.failure(AuthException(R.string.permission_denied))
            }

            // spec 2.3: users of an inactive mosque cannot stay signed in
            if (user.role != Role.SUPERADMIN && !user.mosqueId.isNullOrEmpty()) {
                val mosqueSnap = db.collection("mosques").document(user.mosqueId).get().await()
                if (!mosqueSnap.exists() || mosqueSnap.getBoolean("active") != true) {
                    auth.signOut()
                    return Result.failure(AuthException(R.string.mosque_inactive))
                }
            }

            session.saveUser(user)
            session.clearLock(e)
            Result.success(user)
        } catch (ex: Exception) {
            val lockMs = session.recordFailure(e)
            val resId = when (ex) {
                is FirebaseAuthInvalidUserException,
                is FirebaseAuthInvalidCredentialsException -> R.string.login_failed
                is FirebaseNetworkException -> R.string.network_error
                else -> R.string.login_failed
            }
            Result.failure(AuthException(resId, ex.message, if (lockMs > 0) lockMs else null))
        }
    }

    suspend fun logout() {
        auth.signOut()
        secondaryAuth.signOut()
        session.clear()
    }

    /** Re-fetch the /users/{uid} profile and cache it (used after cold start). */
    suspend fun refreshProfile(): Result<AppUser> {
        val uid = auth.currentUser?.uid ?: return Result.failure(AuthException(R.string.login_failed))
        return try {
            val snap = db.collection("users").document(uid).get().await()
            val user = snap.toObject(AppUser::class.java)?.copy(id = uid)
                ?: return Result.failure(AuthException(R.string.login_failed))
            session.saveUser(user)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a committee member: Firebase Auth account (via secondary app so the
     * current session is untouched) + /users/{uid} profile in one logical step.
     * Spec 2.3 self-guards are enforced client-side here and in security rules.
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
            val cred = secondaryAuth.createUserWithEmailAndPassword(email.trim(), password).await()
            val uid = cred.user!!.uid
            val user = AppUser(
                id = uid,
                name = name.trim(),
                email = email.trim().lowercase(),
                phone = phone.trim(),
                role = role,
                mosqueId = currentUser.mosqueId,
                designation = designation.trim(),
                active = true,
                createdAt = System.currentTimeMillis()
            )
            db.collection("users").document(uid).set(user).await()
            secondaryAuth.signOut()
            // maintain mosque memberCount
            currentUser.mosqueId?.let {
                db.collection("mosques").document(it)
                    .update("memberCount", com.google.firebase.firestore.FieldValue.increment(1)).await()
            }
            Result.success(user)
        } catch (ex: Exception) {
            secondaryAuth.signOut()
            val resId = when (ex) {
                is FirebaseAuthUserCollisionException -> R.string.email_in_use
                is FirebaseNetworkException -> R.string.network_error
                else -> R.string.error_generic
            }
            Result.failure(AuthException(resId, ex.message))
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
