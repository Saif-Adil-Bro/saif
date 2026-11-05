package com.saif.mosque.data.invitations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeJoinCoordinatorTest {
    private class Store(var code: String? = null) : PrototypeJoinReferenceStore {
        var fail = false
        override suspend fun read(): String? { check(!fail); return code }
        override suspend fun write(code: String?) { check(!fail); this.code = code }
    }
    private class Gateway : PrototypeJoinGateway {
        var user: PrototypeJoinIdentity? = null
        var signupError: Exception? = null
        var redeemError: Exception? = null
        var onRedeem: (() -> Unit)? = null
        var signupGate: CompletableDeferred<Unit>? = null
        var signups = 0
        var redemptions = 0
        override fun identity() = user
        override suspend fun signUp(email: String, password: String): PrototypeSignupResult {
            signups++; signupGate?.await()
            signupError?.let { throw it }
            user = PrototypeJoinIdentity("u", email)
            return PrototypeSignupResult.VERIFICATION_REQUESTED
        }
        override suspend fun signIn(email: String, password: String) { user = PrototypeJoinIdentity("u", email) }
        override suspend fun resendVerification() = PrototypeVerificationResult.REQUESTED
        override suspend fun redeem(address: InvitationAddress) { redemptions++; redeemError?.let { throw it }; onRedeem?.invoke() }
        override fun signOut() { user = null }
    }
    private val valid = InvitationCode.encode(InvitationAddress("m", "invite"))

    @Test fun persistedReferenceResumesAndOnlyConfirmedRedeemCompletes() = runBlocking {
        val store = Store(); val gateway = Gateway(); val coordinator = PrototypeJoinCoordinator(gateway, store)
        coordinator.restore(); assertEquals(PrototypeJoinPhase.CODE, coordinator.state.value.phase)
        coordinator.useCode(valid); assertEquals(valid, store.code)
        coordinator.signUp("member@example.test", "private password")
        assertEquals(PrototypeJoinPhase.VERIFY, coordinator.state.value.phase)
        assertEquals(PrototypeJoinMessage.VERIFICATION_REQUESTED, coordinator.state.value.message)
        assertFalse(store.code!!.contains("password"))
        gateway.redeemError = InvitationNeedsVerification(); coordinator.redeem()
        assertEquals(PrototypeJoinPhase.VERIFY, coordinator.state.value.phase)
        assertEquals(PrototypeJoinMessage.VERIFY_EMAIL, coordinator.state.value.message)
        gateway.redeemError = IllegalStateException("uncertain"); coordinator.redeem()
        assertEquals(PrototypeJoinMessage.REDEEM_UNCONFIRMED, coordinator.state.value.message)
        assertEquals(valid, store.code)
        gateway.redeemError = null; coordinator.redeem()
        assertEquals(PrototypeJoinPhase.COMPLETE, coordinator.state.value.phase)
        val restored = PrototypeJoinCoordinator(gateway, store); restored.restore()
        assertEquals(PrototypeJoinPhase.VERIFY, restored.state.value.phase) // never trust a local completion flag
        restored.redeem(); assertEquals(PrototypeJoinPhase.COMPLETE, restored.state.value.phase)
        restored.clearReference(); assertNull(store.code)
        assertEquals(PrototypeJoinPhase.CODE, restored.state.value.phase)
    }
    @Test fun badCodesAndStorageFailuresCannotAdvance() = runBlocking {
        val store = Store(); val coordinator = PrototypeJoinCoordinator(Gateway(), store)
        coordinator.useCode("bad"); assertEquals(PrototypeJoinMessage.INVALID_CODE, coordinator.state.value.message)
        store.fail = true; coordinator.useCode(valid)
        assertEquals(PrototypeJoinPhase.CODE, coordinator.state.value.phase)
        assertEquals(PrototypeJoinMessage.STORAGE_ERROR, coordinator.state.value.message)
        store.fail = false; store.code = "bad"; coordinator.restore()
        assertEquals(PrototypeJoinMessage.INVALID_CODE, coordinator.state.value.message)
        assertEquals("bad", store.code) // corrupt input is not silently overwritten
    }
    @Test fun duplicateSubmissionIsIgnoredAndCancellationReleasesBusy() = runBlocking {
        val store = Store(); val gateway = Gateway(); val coordinator = PrototypeJoinCoordinator(gateway, store)
        coordinator.useCode(valid); gateway.signupGate = CompletableDeferred()
        val first = async { coordinator.signUp("a@example.test", "private") }; yield()
        assertTrue(coordinator.state.value.busy)
        coordinator.signUp("a@example.test", "private"); assertEquals(1, gateway.signups)
        first.cancel(); try { first.await() } catch (_: CancellationException) { }
        assertFalse(coordinator.state.value.busy)
        gateway.signupGate = null; coordinator.signUp("a@example.test", "private")
        assertEquals(PrototypeJoinPhase.VERIFY, coordinator.state.value.phase)
    }
    @Test fun identityChangeCannotProduceCompletionForAnotherAccount() = runBlocking {
        val gateway = Gateway(); val coordinator = PrototypeJoinCoordinator(gateway, Store())
        coordinator.useCode(valid); coordinator.signIn("a@example.test", "private")
        gateway.onRedeem = { gateway.user = PrototypeJoinIdentity("other", "other@example.test") }
        coordinator.redeem()
        assertEquals(PrototypeJoinPhase.VERIFY, coordinator.state.value.phase)
        assertEquals(PrototypeJoinMessage.AUTH_UNCERTAIN, coordinator.state.value.message)
        gateway.onRedeem = { gateway.user = null; throw IllegalStateException("identity changed during failure") }
        coordinator.redeem()
        assertEquals(PrototypeJoinPhase.ACCOUNT, coordinator.state.value.phase)
        assertEquals(PrototypeJoinMessage.AUTH_UNCERTAIN, coordinator.state.value.message)
    }
}
