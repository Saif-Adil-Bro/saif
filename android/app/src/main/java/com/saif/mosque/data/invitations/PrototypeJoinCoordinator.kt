package com.saif.mosque.data.invitations

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Only the invitation reference is stored. Never persist a password or an Auth token here. */
interface PrototypeJoinReferenceStore {
    suspend fun read(): String?
    suspend fun write(code: String?)
}

class PrototypeJoinPreferences(context: Context, name: String = "join-reference-prototype") : PrototypeJoinReferenceStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override suspend fun read(): String? = withContext(Dispatchers.IO) { prefs.getString("code", null) }
    override suspend fun write(code: String?) = withContext(Dispatchers.IO) {
        val edit = prefs.edit()
        if (code == null) edit.remove("code") else edit.putString("code", code)
        check(edit.commit()) { "Unable to save invitation reference" }
    }
}

data class PrototypeJoinIdentity(val uid: String, val email: String?)
interface PrototypeJoinGateway {
    fun identity(): PrototypeJoinIdentity?
    suspend fun signUp(email: String, password: String): PrototypeSignupResult
    suspend fun signIn(email: String, password: String)
    suspend fun resendVerification(): PrototypeVerificationResult
    suspend fun redeem(address: InvitationAddress)
    fun signOut()
}

class PrototypeJoinFirebaseGateway(private val session: PrototypeOnboardingSession) : PrototypeJoinGateway {
    override fun identity() = session.identity()
    override suspend fun signUp(email: String, password: String) = session.signUp(email, password)
    override suspend fun signIn(email: String, password: String) = session.signIn(email, password)
    override suspend fun resendVerification() = session.resendVerification()
    override suspend fun redeem(address: InvitationAddress) = session.redeem(address.mosqueId, address.invitationId)
    override fun signOut() = session.signOut()
}

enum class PrototypeJoinPhase { CODE, ACCOUNT, VERIFY, COMPLETE }
enum class PrototypeJoinMessage {
    INVALID_CODE, STORAGE_ERROR, AUTH_UNCERTAIN, VERIFICATION_REQUESTED,
    VERIFICATION_UNCONFIRMED, VERIFY_EMAIL, REDEEM_UNCONFIRMED, ACTION_FAILED
}
data class PrototypeJoinState(
    val phase: PrototypeJoinPhase = PrototypeJoinPhase.CODE,
    val busy: Boolean = false,
    val identity: PrototypeJoinIdentity? = null,
    val message: PrototypeJoinMessage? = null
)

/**
 * Experimental coordinator, not a production route. An invitation reference is
 * durable but Auth and Firestore are not atomic. Only a server-confirmed redeem
 * enters COMPLETE; process death falls back to retrying the exact same reference.
 */
class PrototypeJoinCoordinator(private val gateway: PrototypeJoinGateway, private val store: PrototypeJoinReferenceStore) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PrototypeJoinState())
    val state: StateFlow<PrototypeJoinState> = _state.asStateFlow()
    private var address: InvitationAddress? = null

    suspend fun restore() = guarded {
        val code = try { store.read() } catch (e: CancellationException) { throw e } catch (_: Exception) {
            _state.update { it.copy(message = PrototypeJoinMessage.STORAGE_ERROR) }; return@guarded
        }
        val decoded = code?.let(InvitationCode::decode)
        address = decoded
        val identity = gateway.identity()
        _state.update { it.copy(phase = if (decoded == null) PrototypeJoinPhase.CODE else
            if (identity == null) PrototypeJoinPhase.ACCOUNT else PrototypeJoinPhase.VERIFY,
            identity = identity, message = if (code != null && decoded == null) PrototypeJoinMessage.INVALID_CODE else null) }
    }

    suspend fun useCode(code: String) = guarded {
        if (_state.value.phase != PrototypeJoinPhase.CODE) return@guarded
        val decoded = InvitationCode.decode(code)
        if (decoded == null) { _state.update { it.copy(message = PrototypeJoinMessage.INVALID_CODE) }; return@guarded }
        try { store.write(InvitationCode.encode(decoded)) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            _state.update { it.copy(message = PrototypeJoinMessage.STORAGE_ERROR) }; return@guarded
        }
        address = decoded
        val identity = gateway.identity()
        _state.update { it.copy(phase = if (identity == null) PrototypeJoinPhase.ACCOUNT else PrototypeJoinPhase.VERIFY,
            identity = identity, message = null) }
    }

    suspend fun signUp(email: String, password: String) = guarded {
        if (_state.value.phase != PrototypeJoinPhase.ACCOUNT || address == null) return@guarded
        try {
            val result = gateway.signUp(email, password)
            val identity = gateway.identity()
            _state.update { it.copy(phase = if (identity == null) PrototypeJoinPhase.ACCOUNT else PrototypeJoinPhase.VERIFY,
                identity = identity, message = if (identity == null) PrototypeJoinMessage.AUTH_UNCERTAIN else
                    if (result == PrototypeSignupResult.VERIFICATION_REQUESTED) PrototypeJoinMessage.VERIFICATION_REQUESTED
                    else PrototypeJoinMessage.VERIFICATION_UNCONFIRMED) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            // Signup can succeed remotely even if its response is lost. Preserve
            // the address and allow sign-in/recovery, never claim creation failed.
            val identity = gateway.identity()
            _state.update { it.copy(phase = if (identity == null) PrototypeJoinPhase.ACCOUNT else PrototypeJoinPhase.VERIFY,
                identity = identity, message = PrototypeJoinMessage.AUTH_UNCERTAIN) }
        }
    }

    suspend fun signIn(email: String, password: String) = guarded {
        if (_state.value.phase != PrototypeJoinPhase.ACCOUNT || address == null) return@guarded
        try {
            gateway.signIn(email, password)
            val identity = gateway.identity()
            _state.update { it.copy(phase = if (identity == null) PrototypeJoinPhase.ACCOUNT else PrototypeJoinPhase.VERIFY,
                identity = identity, message = if (identity == null) PrototypeJoinMessage.AUTH_UNCERTAIN else null) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            val identity = gateway.identity()
            _state.update { it.copy(phase = if (identity == null) PrototypeJoinPhase.ACCOUNT else PrototypeJoinPhase.VERIFY,
                identity = identity, message = PrototypeJoinMessage.AUTH_UNCERTAIN) }
        }
    }

    suspend fun resendVerification() = guarded {
        if (_state.value.phase != PrototypeJoinPhase.VERIFY) return@guarded
        try {
            val result = gateway.resendVerification()
            _state.update { it.copy(identity = gateway.identity(), message = if (result == PrototypeVerificationResult.REQUESTED)
                PrototypeJoinMessage.VERIFICATION_REQUESTED else null) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            _state.update { it.copy(identity = gateway.identity(), message = PrototypeJoinMessage.VERIFICATION_UNCONFIRMED) }
        }
    }

    suspend fun redeem() = guarded {
        val current = address ?: return@guarded
        if (_state.value.phase != PrototypeJoinPhase.VERIFY) return@guarded
        val original = gateway.identity()
        if (original == null) {
            _state.update { it.copy(phase = PrototypeJoinPhase.ACCOUNT, identity = null, message = PrototypeJoinMessage.AUTH_UNCERTAIN) }
            return@guarded
        }
        try {
            gateway.redeem(current)
            val currentIdentity = gateway.identity()
            _state.update { it.copy(phase = if (currentIdentity?.uid == original.uid) PrototypeJoinPhase.COMPLETE else
                if (currentIdentity == null) PrototypeJoinPhase.ACCOUNT else PrototypeJoinPhase.VERIFY,
                identity = currentIdentity, message = if (currentIdentity?.uid == original.uid) null else PrototypeJoinMessage.AUTH_UNCERTAIN) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _state.update { it.copy(identity = gateway.identity(), message = if (e is InvitationNeedsVerification)
                PrototypeJoinMessage.VERIFY_EMAIL else PrototypeJoinMessage.REDEEM_UNCONFIRMED) }
        }
    }

    /** Explicit only: do not sign the primary app out or delete the Auth account. */
    suspend fun changeAccount() = guarded {
        if (_state.value.phase != PrototypeJoinPhase.VERIFY || address == null) return@guarded
        try { gateway.signOut(); _state.update { it.copy(phase = PrototypeJoinPhase.ACCOUNT, identity = null, message = null) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { _state.update { it.copy(message = PrototypeJoinMessage.ACTION_FAILED) } }
    }

    /** Explicitly clear only the local reference; it does not revoke an invitation or delete an account. */
    suspend fun clearReference() = guarded {
        try { store.write(null) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            _state.update { it.copy(message = PrototypeJoinMessage.STORAGE_ERROR) }; return@guarded
        }
        address = null
        _state.update { PrototypeJoinState(busy = true, identity = gateway.identity()) }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        _state.update { it.copy(busy = true) }
        try { block() } finally { _state.update { it.copy(busy = false) }; mutex.unlock() }
    }
}
