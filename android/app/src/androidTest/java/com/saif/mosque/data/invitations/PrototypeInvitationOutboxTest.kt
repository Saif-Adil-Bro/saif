package com.saif.mosque.data.invitations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.Timestamp
import com.saif.mosque.data.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class PrototypeInvitationOutboxTest {
    @Test fun exactDraftSurvivesReopenAndCannotChangeIdentityOrPayload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "invitation-outbox-test.db"
        context.deleteDatabase(name)
        val original = PrototypeInvitation("i", "m", "uid", "test@example.test", "নাম", "", Role.MEMBER, "", Timestamp(Date(1_800_000_000_000L)))
        val first = PrototypeInvitationOutbox(context, name)
        first.enqueue(original); first.enqueue(original)
        assertEquals(1, first.list("uid", "m").size)
        assertTrue(runCatching { first.enqueue(original.copy(name = "Different")) }.exceptionOrNull() is InvitationConflict)
        assertTrue(runCatching { first.enqueue(original.copy(issuerUid = "other")) }.exceptionOrNull() is InvitationConflict)
        assertNull(first.find("i", "other", "m"))
        assertNull(first.find("i", "uid", "other"))
        first.close()
        val reopened = PrototypeInvitationOutbox(context, name)
        assertEquals(original, reopened.find("i", "uid", "m")?.draft)
        assertEquals("PENDING", reopened.find("i", "uid", "m")?.state)
        assertTrue(reopened.transition("i", "uid", "m", "PENDING", "BLOCKED", "PERMISSION"))
        assertTrue(!reopened.transition("i", "other", "m", "BLOCKED", "PENDING"))
        assertTrue(reopened.transition("i", "uid", "m", "BLOCKED", "PENDING"))
        reopened.close(); context.deleteDatabase(name)
    }
}
