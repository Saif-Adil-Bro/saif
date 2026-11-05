package com.saif.mosque.data.invitations

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * Separate stable FirebaseApp identity keeps signup from replacing the app's
 * primary login. This factory is experimental and is not connected to the UI.
 * Auth persistence remains associated with this named app after process restart.
 */
object PrototypeOnboardingEnvironment {
    private const val APP_NAME = "member-onboarding-prototype"

    @Synchronized
    fun get(context: Context): FirebaseApp {
        val appContext = context.applicationContext
        return FirebaseApp.getApps(appContext).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(appContext, FirebaseApp.getInstance().options, APP_NAME)
    }
}

enum class PrototypeSignupResult { VERIFICATION_REQUESTED, ACCOUNT_CREATED_VERIFICATION_UNCONFIRMED }
enum class PrototypeVerificationResult { REQUESTED, ALREADY_VERIFIED }

/**
 * Online-only Auth signup/recovery experiment. Never stores passwords. Firebase
 * Auth and Firestore cannot be atomic: a signup may survive a failed email or
 * claim. Re-sign in and retry rather than deleting an account automatically.
 */
class PrototypeOnboardingSession(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val sendVerification: suspend (FirebaseUser) -> Unit = { it.sendEmailVerification().await() }
) {
    init {
        require(auth.app.name != FirebaseApp.DEFAULT_APP_NAME && auth.app.name == db.app.name) {
            "Onboarding requires matching isolated Auth and Firestore apps"
        }
    }
    private val invitations = PrototypeInvitationService(db, auth)

    fun identity(): PrototypeJoinIdentity? = auth.currentUser?.let { PrototypeJoinIdentity(it.uid, it.email) }

    suspend fun signUp(email: String, password: String): PrototypeSignupResult {
        val normalized = normalizedEmail(email)
        require(password.length >= 8)
        check(auth.currentUser == null) { "Sign out of the onboarding session before changing accounts" }
        val result = auth.createUserWithEmailAndPassword(normalized, password).await()
        val uid = result.user?.uid ?: throw InvitationIdentityChanged()
        ensureUid(uid)
        return try {
            sendVerification(result.user!!)
            ensureUid(uid)
            PrototypeSignupResult.VERIFICATION_REQUESTED
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ensureUid(uid)
            // Account creation succeeded. Email delivery or even request completion
            // may be uncertain; never claim the message was not delivered.
            PrototypeSignupResult.ACCOUNT_CREATED_VERIFICATION_UNCONFIRMED
        }
    }

    suspend fun signIn(email: String, password: String) {
        val normalized = normalizedEmail(email)
        require(password.isNotEmpty())
        check(auth.currentUser == null) { "Sign out of the onboarding session before changing accounts" }
        val result = auth.signInWithEmailAndPassword(normalized, password).await()
        ensureUid(result.user?.uid ?: throw InvitationIdentityChanged())
    }

    suspend fun resendVerification(): PrototypeVerificationResult {
        val user = auth.currentUser ?: throw InvitationIdentityChanged()
        val uid = user.uid
        user.reload().await()
        ensureUid(uid)
        if (user.isEmailVerified) return PrototypeVerificationResult.ALREADY_VERIFIED
        sendVerification(user)
        ensureUid(uid)
        return PrototypeVerificationResult.REQUESTED
    }

    suspend fun redeem(mosqueId: String, inviteId: String) = invitations.redeem(mosqueId, inviteId)
    fun signOut() = auth.signOut()

    private fun ensureUid(uid: String) { if (auth.currentUser?.uid != uid) throw InvitationIdentityChanged() }
    private fun normalizedEmail(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        require(normalized.length in 4..254 && Regex("^[^@/\\s]+@[^@/\\s]+$").matches(normalized))
        return normalized
    }
}

/** Keep Firestore and Auth bound to the same named app. */
fun prototypeOnboardingSession(app: FirebaseApp): PrototypeOnboardingSession {
    require(app.name != FirebaseApp.DEFAULT_APP_NAME)
    val auth = FirebaseAuth.getInstance(app)
    return PrototypeOnboardingSession(auth, FirebaseFirestore.getInstance(app))
}
