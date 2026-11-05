package com.saif.mosque.data.invitations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.saif.mosque.MosqueApp
import com.saif.mosque.data.model.Role
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Dedicated Auth/Firestore emulators and test-only invitation rules. */
@RunWith(AndroidJUnit4::class)
class PrototypeInvitationIntegrationTest {
    @Test fun verifiedOnboardingRetriesAndFailuresDoNotDuplicateMembers() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as MosqueApp
        val db = app.container.db
        val auth = app.container.auth
        auth.useEmulator("10.0.2.2", 9099)
        db.useEmulator("10.0.2.2", 8080)
        suspend fun login(email: String) { auth.signInWithEmailAndPassword(email, "test-only-password").await() }
        val service = PrototypeInvitationService(db, auth)
        val parent = db.collection("mosques").document("invite-test")
        login("inv-admin@example.test")
        val queue = PrototypeInvitationQueue(app, auth)
        val queued = PrototypeInvitation(service.newId("invite-test"), "invite-test", "inv-admin",
            "queue@example.test", "Offline draft", "", Role.MEMBER, "", Timestamp(Date(System.currentTimeMillis() + 86_400_000)))
        suspend fun syncInvitation(d: PrototypeInvitation): ListenableWorker.Result =
            TestListenableWorkerBuilder<PrototypeInvitationWorker>(app).setInputData(workDataOf(
                "id" to d.id, "uid" to d.issuerUid, "mosqueId" to d.mosqueId)).build().doWork()
        db.disableNetwork().await()
        queue.store.enqueue(queued)
        assertEquals(ListenableWorker.Result.retry(), withTimeout(20_000) { syncInvitation(queued) })
        assertEquals("PENDING", queue.store.find(queued.id, queued.issuerUid, queued.mosqueId)?.state)
        db.enableNetwork().await()
        login("inv-wrong@example.test")
        assertEquals(ListenableWorker.Result.success(), syncInvitation(queued))
        assertEquals("PENDING", queue.store.find(queued.id, queued.issuerUid, queued.mosqueId)?.state)
        login("inv-admin@example.test")
        PrototypeInvitationSync.schedule(app, queued)
        withTimeout(45_000) { while (queue.store.find(queued.id, queued.issuerUid, queued.mosqueId)?.state != "READY") delay(250) }
        assertEquals(PrototypeInvitationStatus.PENDING, queue.checkSharingStatus(queued))
        // Simulate a crash after server commit but before the local acknowledgement.
        val values = android.content.ContentValues().apply { put("state", "PENDING"); putNull("failure") }
        queue.store.writableDatabase.update("invites", values, "id = ?", arrayOf(queued.id))
        assertEquals(ListenableWorker.Result.success(), syncInvitation(queued))
        assertEquals("READY", queue.store.find(queued.id, queued.issuerUid, queued.mosqueId)?.state)
        service.revoke("invite-test", queued.id, "inv-admin")
        assertEquals(PrototypeInvitationStatus.REVOKED, queue.checkSharingStatus(queued))
        assertEquals(1L, parent.get(Source.SERVER).await().getLong("memberCount"))
        val invitation = PrototypeInvitation(service.newId("invite-test"), "invite-test", "inv-admin",
            "inv-member@example.test", "Member", "", Role.MEMBER, "", Timestamp(Date(System.currentTimeMillis() + 86_400_000)))
        assertEquals(PrototypeInvitationStatus.PENDING, service.issue(invitation))
        // Re-issuing an identical immutable request verifies the saved server value.
        assertEquals(PrototypeInvitationStatus.PENDING, service.issue(invitation))
        assertTrue(runCatching { service.issue(invitation.copy(name="Different")) }.exceptionOrNull() is InvitationConflict)
        assertTrue(runCatching { service.issue(invitation.copy(id=service.newId("invite-test"), role=Role.SUPERADMIN)) }.isFailure)
        val unverifiedInvite = invitation.copy(id=service.newId("invite-test"), email="inv-unverified@example.test")
        val existingInvite = invitation.copy(id=service.newId("invite-test"), email="inv-existing@example.test")
        val revoked = invitation.copy(id=service.newId("invite-test"), email="inv-revoked@example.test")
        service.issue(unverifiedInvite); service.issue(existingInvite); service.issue(revoked)
        service.revoke("invite-test", revoked.id, "inv-admin")
        service.revoke("invite-test", revoked.id, "inv-admin")
        assertEquals(PrototypeInvitationStatus.REVOKED, service.issue(revoked))
        login("inv-wrong@example.test")
        assertTrue(runCatching { service.redeem("invite-test", invitation.id) }.isFailure)
        login("inv-unverified@example.test")
        assertTrue(runCatching { service.redeem("invite-test", unverifiedInvite.id) }.exceptionOrNull() is InvitationNeedsVerification)
        assertFalse(db.collection("users").document("inv-unverified").get(Source.SERVER).await().exists())
        login("inv-existing@example.test")
        assertTrue(runCatching { service.redeem("invite-test", existingInvite.id) }.exceptionOrNull() is InvitationConflict)
        login("inv-revoked@example.test")
        assertTrue(runCatching { service.redeem("invite-test", revoked.id) }.exceptionOrNull() is InvitationConflict)
        login("inv-member@example.test")
        db.disableNetwork().await()
        assertTrue(withTimeout(20_000) { runCatching { service.redeem("invite-test", invitation.id) }.isFailure })
        db.enableNetwork().await()
        service.redeem("invite-test", invitation.id)
        service.redeem("invite-test", invitation.id) // Recover a lost acknowledgement without incrementing twice.
        val profile = db.collection("users").document("inv-member").get(Source.SERVER).await()
        assertEquals("MEMBER", profile.getString("role"))
        assertEquals(invitation.id, profile.getString("inviteId"))
        assertEquals(2L, parent.get(Source.SERVER).await().getLong("memberCount"))
        val claim = parent.collection("memberClaims").document("inv-member@example.test").get(Source.SERVER).await()
        assertEquals("inv-member", claim.getString("uid"))
        login("inv-admin@example.test")
        assertEquals(PrototypeInvitationStatus.ACCEPTED, service.issue(invitation))
        assertTrue(runCatching { service.revoke("invite-test", invitation.id, "inv-admin") }.isFailure)
        assertEquals(2L, parent.get(Source.SERVER).await().getLong("memberCount"))

        // Sign up in a separate FirebaseApp: the signed-in admin is unaffected.
        val nextInvite = invitation.copy(id=service.newId("invite-test"), email="inv-new@example.test")
        service.issue(nextInvite)
        assertTrue(runCatching { PrototypeOnboardingSession(auth, db) }.isFailure)
        val onboardingApp = PrototypeOnboardingEnvironment.get(app)
        assertEquals(onboardingApp, PrototypeOnboardingEnvironment.get(app))
        val secondaryAuth = FirebaseAuth.getInstance(onboardingApp)
        val secondaryDb = FirebaseFirestore.getInstance(onboardingApp)
        secondaryAuth.useEmulator("10.0.2.2", 9099)
        secondaryDb.useEmulator("10.0.2.2", 8080)
        assertTrue(runCatching { PrototypeOnboardingSession(secondaryAuth, db) }.isFailure)
        secondaryAuth.signOut()
        val failedMail = PrototypeOnboardingSession(secondaryAuth, secondaryDb) { throw IllegalStateException("test mail failure") }
        assertEquals(PrototypeSignupResult.ACCOUNT_CREATED_VERIFICATION_UNCONFIRMED, failedMail.signUp("inv-mail-failure@example.test", "test-only-password"))
        assertEquals("inv-admin", auth.currentUser?.uid)
        failedMail.signOut()
        // A created Auth account is recoverable after email delivery failure.
        val onboarding = prototypeOnboardingSession(onboardingApp)
        onboarding.signIn("inv-mail-failure@example.test", "test-only-password")
        assertEquals(PrototypeVerificationResult.REQUESTED, onboarding.resendVerification())
        onboarding.signOut()
        val referenceStore = PrototypeJoinPreferences(app, "join-reference-integration-test")
        referenceStore.write(null)
        val coordinator = PrototypeJoinCoordinator(PrototypeJoinFirebaseGateway(onboarding), referenceStore)
        coordinator.restore()
        coordinator.useCode(InvitationCode.encode(InvitationAddress("invite-test", nextInvite.id)))
        assertEquals(PrototypeJoinPhase.ACCOUNT, coordinator.state.value.phase)
        coordinator.signUp("INV-NEW@example.test", "test-only-password")
        assertEquals(PrototypeJoinPhase.VERIFY, coordinator.state.value.phase)
        assertEquals(PrototypeJoinMessage.VERIFICATION_REQUESTED, coordinator.state.value.message)
        assertEquals("inv-admin", auth.currentUser?.uid)
        val pendingUid = secondaryAuth.currentUser!!.uid
        assertTrue(runCatching { onboarding.signUp("different@example.test", "test-only-password") }.isFailure)
        assertEquals(pendingUid, secondaryAuth.currentUser?.uid)
        coordinator.redeem()
        assertEquals(PrototypeJoinMessage.VERIFY_EMAIL, coordinator.state.value.message)
        // Simulate process/session recovery before opening the verification email.
        coordinator.changeAccount()
        val restored = PrototypeJoinCoordinator(PrototypeJoinFirebaseGateway(onboarding), referenceStore)
        restored.restore()
        assertEquals(PrototypeJoinPhase.ACCOUNT, restored.state.value.phase)
        restored.signIn("inv-new@example.test", "test-only-password")
        restored.resendVerification()
        assertEquals(PrototypeJoinMessage.VERIFICATION_REQUESTED, restored.state.value.message)
        val code = withContext(Dispatchers.IO) {
            val project = onboardingApp.options.projectId!!
            val connection = URL("http://10.0.2.2:9099/emulator/v1/projects/$project/oobCodes").openConnection() as HttpURLConnection
            try {
                val codes = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("oobCodes")
                (0 until codes.length()).map { codes.getJSONObject(it) }.last {
                    it.optString("email") == "inv-new@example.test" && it.optString("requestType") == "VERIFY_EMAIL"
                }.getString("oobCode")
            } finally { connection.disconnect() }
        }
        secondaryAuth.applyActionCode(code).await()
        assertEquals(PrototypeVerificationResult.ALREADY_VERIFIED, onboarding.resendVerification())
        restored.redeem()
        assertEquals(PrototypeJoinPhase.COMPLETE, restored.state.value.phase)
        // Recovery after server commit re-checks the receipt without re-counting.
        val afterCommit = PrototypeJoinCoordinator(PrototypeJoinFirebaseGateway(onboarding), referenceStore)
        afterCommit.restore()
        assertEquals(PrototypeJoinPhase.VERIFY, afterCommit.state.value.phase)
        afterCommit.redeem()
        assertEquals(PrototypeJoinPhase.COMPLETE, afterCommit.state.value.phase)
        assertEquals("inv-admin", auth.currentUser?.uid)
        assertEquals(3L, parent.get(Source.SERVER).await().getLong("memberCount"))
        val joinedUser = db.collection("users").document(secondaryAuth.currentUser!!.uid)
        assertTrue(joinedUser.get(Source.SERVER).await().exists())
        joinedUser.update("active", false).await()
        val deactivated = PrototypeJoinCoordinator(PrototypeJoinFirebaseGateway(onboarding), referenceStore)
        deactivated.restore()
        deactivated.redeem()
        assertEquals(PrototypeJoinPhase.VERIFY, deactivated.state.value.phase)
        assertEquals(PrototypeJoinMessage.REDEEM_UNCONFIRMED, deactivated.state.value.message)
        afterCommit.clearReference()
        assertNull(referenceStore.read())
        onboarding.signOut()
        auth.signOut()
    }
}
