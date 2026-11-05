package com.saif.mosque.data.invitations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Source
import com.saif.mosque.MosqueApp
import com.saif.mosque.data.model.Role
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

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
        auth.signOut()
    }
}
