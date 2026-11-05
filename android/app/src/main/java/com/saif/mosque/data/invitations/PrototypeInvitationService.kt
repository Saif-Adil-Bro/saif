package com.saif.mosque.data.invitations

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.saif.mosque.data.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** Immutable invitation payload. Persist this complete value before scheduling a retry. */
data class PrototypeInvitation(
    val id: String, val mosqueId: String, val issuerUid: String,
    val email: String, val name: String, val phone: String, val role: Role,
    val designation: String, val expiresAt: Timestamp
)

enum class PrototypeInvitationStatus { PENDING, ACCEPTED, REVOKED }
class InvitationIdentityChanged : IllegalStateException("Signed-in account changed")
class InvitationNeedsVerification : IllegalStateException("Verified email is required")
class InvitationConflict : IllegalStateException("Invitation or profile conflicts with the request")
class InvitationPendingWrite : IllegalStateException("A local Firestore write has not been confirmed")

/**
 * Experimental online-only Spark onboarding client, for isolated emulator rules.
 * Not connected to UI, Auth signup, a durable outbox, or production rules.
 * Security depends on the reciprocal Firestore rules, not these client checks.
 */
class PrototypeInvitationService(private val db: FirebaseFirestore, private val auth: FirebaseAuth) {
    fun newId(mosqueId: String): String {
        require(validId(mosqueId))
        return db.collection("mosques").document(mosqueId).collection("invitations").document().id
    }

    suspend fun issue(draft: PrototypeInvitation): PrototypeInvitationStatus {
        validatePrototypeInvitation(draft)
        ensureUid(draft.issuerUid)
        val ref = db.collection("mosques").document(draft.mosqueId).collection("invitations").document(draft.id)
        val expected = fields(draft)
        try {
            // Rules allow create but not replacement. A stable ID is essential.
            ref.set(expected).await()
            ensureUid(draft.issuerUid)
            return PrototypeInvitationStatus.PENDING
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ensureUid(draft.issuerUid)
            // A lost acknowledgement can be recovered, but local pending writes
            // must never be mistaken for a server-confirmed invitation.
            val saved = try { ref.get(Source.SERVER).await() }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { throw e }
            ensureUid(draft.issuerUid)
            if (saved.metadata.hasPendingWrites()) throw InvitationPendingWrite()
            val actual = saved.data ?: throw e
            return savedStatus(actual, expected)
        }
    }

    /** Re-check current server status before sharing a previously confirmed code. */
    suspend fun inspect(draft: PrototypeInvitation): PrototypeInvitationStatus {
        validatePrototypeInvitation(draft)
        ensureUid(draft.issuerUid)
        val snap = db.collection("mosques").document(draft.mosqueId).collection("invitations").document(draft.id)
            .get(Source.SERVER).await()
        ensureUid(draft.issuerUid)
        if (snap.metadata.hasPendingWrites()) throw InvitationPendingWrite()
        return savedStatus(snap.data ?: throw InvitationConflict(), fields(draft))
    }

    private fun savedStatus(actual: Map<String, Any>, expected: Map<String, Any>): PrototypeInvitationStatus {
        if (expected.any { (key, value) -> key != "status" && actual[key] != value }) throw InvitationConflict()
        return when (actual["status"]) {
            "pending" -> if (actual.keys == expected.keys) PrototypeInvitationStatus.PENDING else throw InvitationConflict()
            "accepted" -> if (actual.keys == expected.keys + "acceptedBy" &&
                (actual["acceptedBy"] as? String)?.let(::validId) == true) PrototypeInvitationStatus.ACCEPTED else throw InvitationConflict()
            "revoked" -> if (actual.keys == expected.keys) PrototypeInvitationStatus.REVOKED else throw InvitationConflict()
            else -> throw InvitationConflict()
        }
    }

    /** Idempotent revoke; a concurrently accepted invitation is never reported as revoked. */
    suspend fun revoke(mosqueId: String, inviteId: String, issuerUid: String) {
        require(validId(mosqueId) && validId(inviteId) && validId(issuerUid))
        ensureUid(issuerUid)
        val ref = db.collection("mosques").document(mosqueId).collection("invitations").document(inviteId)
        try {
            ref.update("status", "revoked").await()
            ensureUid(issuerUid)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ensureUid(issuerUid)
            val saved = try { ref.get(Source.SERVER).await() }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { throw e }
            ensureUid(issuerUid)
            if (saved.metadata.hasPendingWrites()) throw InvitationPendingWrite()
            if (saved.getString("status") != "revoked") throw e
        }
    }

    /** Returns only after the atomic batch is server-confirmed or proven committed. */
    suspend fun redeem(mosqueId: String, inviteId: String) {
        require(validId(mosqueId) && validId(inviteId))
        val user = auth.currentUser ?: throw InvitationIdentityChanged()
        val uid = user.uid
        // Reload and refresh the token: a verification email may have been opened
        // elsewhere after this session was created. Never accept a local flag alone.
        user.reload().await()
        ensureUid(uid)
        val token = user.getIdToken(true).await()
        ensureUid(uid)
        val email = token.claims["email"] as? String ?: throw InvitationNeedsVerification()
        if (token.claims["email_verified"] != true || email != email.lowercase(Locale.ROOT) || !validEmail(email))
            throw InvitationNeedsVerification()
        val parent = db.collection("mosques").document(mosqueId)
        val invitation = parent.collection("invitations").document(inviteId)
        val profile = db.collection("users").document(uid)
        val claim = parent.collection("memberClaims").document(email)
        val oldProfile = profile.get(Source.SERVER).await()
        ensureUid(uid)
        if (oldProfile.metadata.hasPendingWrites()) throw InvitationPendingWrite()
        if (oldProfile.exists()) {
            if (oldProfile.getString("inviteId") != inviteId || oldProfile.getString("mosqueId") != mosqueId ||
                oldProfile.getString("email") != email || oldProfile.getBoolean("active") != true) throw InvitationConflict()
            val accepted = invitation.get(Source.SERVER).await()
            val existingClaim = claim.get(Source.SERVER).await()
            val mosque = parent.get(Source.SERVER).await()
            ensureUid(uid)
            if (accepted.metadata.hasPendingWrites() || existingClaim.metadata.hasPendingWrites() || mosque.metadata.hasPendingWrites())
                throw InvitationPendingWrite()
            if (accepted.getString("status") != "accepted" || accepted.getString("acceptedBy") != uid ||
                existingClaim.getString("uid") != uid || existingClaim.getString("inviteId") != inviteId ||
                !mosque.exists() || mosque.getBoolean("active") != true) throw InvitationConflict()
            return
        }
        val invite = invitation.get(Source.SERVER).await()
        ensureUid(uid)
        if (invite.metadata.hasPendingWrites()) throw InvitationPendingWrite()
        if (!invite.exists() || invite.getString("status") != "pending" || invite.getString("email") != email || invite.getString("mosqueId") != mosqueId)
            throw InvitationConflict()
        val claimed = claim.get(Source.SERVER).await()
        ensureUid(uid)
        if (claimed.metadata.hasPendingWrites()) throw InvitationPendingWrite()
        if (claimed.exists()) throw InvitationConflict()
        fun text(key: String): String = invite.getString(key) ?: throw InvitationConflict()
        val role = text("role")
        if (role !in allowedRoles) throw InvitationConflict()
        val profileData = mapOf("name" to text("name"), "email" to email, "phone" to text("phone"),
            "role" to role, "mosqueId" to mosqueId, "designation" to text("designation"),
            "active" to true, "createdAt" to System.currentTimeMillis(), "inviteId" to inviteId)
        ensureUid(uid)
        db.batch().set(profile, profileData).set(claim, mapOf("uid" to uid, "inviteId" to inviteId))
            .update(invitation, mapOf("status" to "accepted", "acceptedBy" to uid))
            .update(parent, mapOf("memberCount" to FieldValue.increment(1), "memberOpId" to uid))
            .commit().await()
        ensureUid(uid)
    }

    private fun fields(d: PrototypeInvitation): Map<String, Any> = mapOf(
        "mosqueId" to d.mosqueId, "email" to d.email, "name" to d.name, "phone" to d.phone,
        "role" to d.role.name, "designation" to d.designation, "createdBy" to d.issuerUid,
        "status" to "pending", "expiresAt" to d.expiresAt)
    private fun ensureUid(uid: String) { if (auth.currentUser?.uid != uid) throw InvitationIdentityChanged() }
    private fun validId(id: String) = id.isNotEmpty() && id.length <= 1500 && !id.contains('/')
    private fun validEmail(email: String) = email.length in 4..254 && Regex("^[^@/\\s]+@[^@/\\s]+$").matches(email)
    private val allowedRoles = setOf("ADMIN", "PRESIDENT", "SECRETARY", "TREASURER", "CASHIER", "IMAM", "MEMBER")
}

internal fun validatePrototypeInvitation(d: PrototypeInvitation) {
    fun id(value: String) = value.isNotEmpty() && value.length <= 1500 && !value.contains('/')
    fun email(value: String) = value.length in 4..254 && Regex("^[^@/\\s]+@[^@/\\s]+$").matches(value)
    require(id(d.id) && id(d.mosqueId) && id(d.issuerUid))
    require(email(d.email) && d.email == d.email.lowercase(Locale.ROOT))
    require(d.name.isNotBlank() && d.name.length <= 1000 && d.phone.length <= 1000 && d.designation.length <= 1000)
    require(d.role in setOf(Role.ADMIN, Role.PRESIDENT, Role.SECRETARY, Role.TREASURER, Role.CASHIER, Role.IMAM, Role.MEMBER))
}
