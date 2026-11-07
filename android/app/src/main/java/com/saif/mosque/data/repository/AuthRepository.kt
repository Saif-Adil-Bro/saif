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
    private val session: SessionManager,
    private val context: android.content.Context? = null
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

    suspend fun updateProfile(uid: String, newName: String, newPhone: String): Result<AppUser> {
        return try {
            val ref = db.collection("users").document(uid)
            ref.update(mapOf(
                "name" to newName.trim(),
                "phone" to newPhone.trim()
            )).await()
            refreshProfile()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(AuthException(R.string.login_failed))
        val email = user.email ?: return Result.failure(AuthException(R.string.login_failed))
        return try {
            val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email, currentPassword)
            user.reauthenticate(credential).await()
            user.updatePassword(newPassword).await()
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val res = when (e) {
                is FirebaseAuthInvalidCredentialsException -> R.string.login_failed
                is FirebaseNetworkException -> R.string.network_error
                else -> R.string.error_generic
            }
            Result.failure(AuthException(res, e.message))
        }
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email.trim().lowercase()).await()
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val res = when (e) {
                is FirebaseNetworkException -> R.string.network_error
                else -> R.string.error_generic
            }
            Result.failure(AuthException(res, e.message))
        }
    }

    /** Watch permissions while signed in, so deactivation or role changes take effect. */
    fun watchProfile(uid: String): Flow<AppUser?> =
        db.collection("users").document(uid).snapshotsFlow().map { snapshot ->
            snapshot.toObject(AppUser::class.java)?.copy(id = uid)
        }

    fun watchMosqueActive(mosqueId: String): Flow<Boolean> =
        db.collection("mosques").document(mosqueId).snapshotsFlow().map { it.exists() && it.getBoolean("active") == true }

    /**
     * Provision a committee member manually (restricted to ADMIN or SUPERADMIN).
     * Tries the trusted Cloud Functions callable backend first, falling back to
     * reciprocal rules-validated client provisioning when Cloud Functions is unavailable.
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
        if (currentUser.role != Role.ADMIN && currentUser.role != Role.SUPERADMIN) {
            return Result.failure(AuthException(R.string.permission_denied))
        }
        val mosqueId = currentUser.mosqueId
        if (currentUser.role != Role.SUPERADMIN && mosqueId.isNullOrEmpty()) {
            return Result.failure(AuthException(R.string.permission_denied))
        }
        val normalizedEmail = email.trim().lowercase(java.util.Locale.ROOT)
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val cleanDesignation = designation.trim()

        return try {
            val result = com.google.firebase.functions.FirebaseFunctions.getInstance().getHttpsCallable("mutate")
                .call(mapOf("operation" to "createMember", "mosqueId" to (mosqueId ?: ""),
                    "value" to mapOf("name" to cleanName, "email" to normalizedEmail, "phone" to cleanPhone,
                        "role" to role.name, "designation" to cleanDesignation, "password" to password)))
                .await().getData() as Map<*, *>
            Result.success(AppUser(id = result["uid"] as String, name = cleanName, email = normalizedEmail,
                phone = cleanPhone, role = role, mosqueId = mosqueId, designation = cleanDesignation))
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val isFunctionsUnavailable = (ex as? com.google.firebase.functions.FirebaseFunctionsException)?.code in setOf(
                com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE
            ) || ex.message?.contains("NOT_FOUND", ignoreCase = true) == true

            val ctx = context
            if (isFunctionsUnavailable && ctx != null && mosqueId != null) {
                return createMemberSpark(currentUser, mosqueId, cleanName, normalizedEmail, cleanPhone, role, cleanDesignation, password, ctx)
            }

            val resId = when ((ex as? com.google.firebase.functions.FirebaseFunctionsException)?.code) {
                com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND -> R.string.member_service_missing
                com.google.firebase.functions.FirebaseFunctionsException.Code.ALREADY_EXISTS -> R.string.email_in_use
                com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED -> R.string.permission_denied
                else -> R.string.error_generic
            }
            Result.failure(AuthException(resId, ex.message))
        }
    }

    private suspend fun createMemberSpark(
        adminUser: AppUser,
        mosqueId: String,
        name: String,
        normalizedEmail: String,
        phone: String,
        role: Role,
        designation: String,
        password: String,
        ctx: android.content.Context
    ): Result<AppUser> {
        val secondaryApp = com.saif.mosque.data.invitations.PrototypeOnboardingEnvironment.get(ctx)
        val secondaryAuth = com.google.firebase.auth.FirebaseAuth.getInstance(secondaryApp)
        secondaryAuth.signOut()
        return try {
            val inviteRef = db.collection("mosques").document(mosqueId).collection("invitations").document()
            val inviteId = inviteRef.id
            val now = System.currentTimeMillis()
            val expiresAt = com.google.firebase.Timestamp(java.util.Date(now + 7 * 24 * 60 * 60 * 1000L))

            val inviteData = mapOf(
                "mosqueId" to mosqueId,
                "email" to normalizedEmail,
                "name" to name,
                "phone" to phone,
                "role" to role.name,
                "designation" to designation,
                "createdBy" to adminUser.id,
                "status" to "pending",
                "expiresAt" to expiresAt
            )
            inviteRef.set(inviteData).await()

            val authResult = try {
                secondaryAuth.createUserWithEmailAndPassword(normalizedEmail, password).await()
            } catch (collision: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                inviteRef.delete().await()
                return Result.failure(AuthException(R.string.email_in_use, collision.message))
            }
            val newUid = authResult.user?.uid ?: throw IllegalStateException("User creation failed")

            val secondaryDb = FirebaseFirestore.getInstance(secondaryApp)
            val parent = secondaryDb.collection("mosques").document(mosqueId)
            val invitationDoc = parent.collection("invitations").document(inviteId)
            val profileDoc = secondaryDb.collection("users").document(newUid)
            val claimDoc = parent.collection("memberClaims").document(normalizedEmail)

            val profileData = mapOf(
                "name" to name,
                "email" to normalizedEmail,
                "phone" to phone,
                "role" to role.name,
                "mosqueId" to mosqueId,
                "designation" to designation,
                "active" to true,
                "createdAt" to now,
                "inviteId" to inviteId
            )

            secondaryDb.batch()
                .set(profileDoc, profileData)
                .set(claimDoc, mapOf("uid" to newUid, "inviteId" to inviteId))
                .update(invitationDoc, mapOf("status" to "accepted", "acceptedBy" to newUid))
                .update(parent, mapOf(
                    "memberCount" to com.google.firebase.firestore.FieldValue.increment(1),
                    "memberOpId" to newUid
                ))
                .commit().await()

            secondaryAuth.signOut()

            Result.success(AppUser(
                id = newUid,
                name = name,
                email = normalizedEmail,
                phone = phone,
                role = role,
                mosqueId = mosqueId,
                designation = designation,
                active = true
            ))
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            secondaryAuth.signOut()
            val resId = when (ex) {
                is com.google.firebase.auth.FirebaseAuthUserCollisionException -> R.string.email_in_use
                is com.google.firebase.auth.FirebaseAuthWeakPasswordException -> R.string.password_min_length
                is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> R.string.invalid_email
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
