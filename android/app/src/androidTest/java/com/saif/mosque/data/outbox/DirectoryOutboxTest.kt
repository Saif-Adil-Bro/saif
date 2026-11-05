package com.saif.mosque.data.outbox

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "directory-outbox-test-${java.util.UUID.randomUUID()}.db"
    private val store = DirectoryOutbox(context, name)
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    @Test fun draftsSurviveReopenAndStayScopedToAccountTenantAndKind() = runBlocking {
        val one = store.addFund("u1", "a", Fund(name = "Fund", code = "A"))
        val two = store.addDonor("u1", "a", Donor(name = "Donor"))
        store.addFund("u2", "a", Fund(name = "Other", code = "B"))
        store.addFund("u1", "b", Fund(name = "Elsewhere", code = "C"))
        assertNotEquals(one.id, two.id)
        assertEquals(listOf(one.id), store.list("u1", "a", "funds").map { it.id })
        store.close()
        val reopened = DirectoryOutbox(context, name)
        try { assertEquals(one.id, reopened.find(one.id)?.id) } finally { reopened.close() }
    }

    @Test fun blockedRowsCanOnlyBeRetriedByTheirOwnerAndTenant() = runBlocking {
        val draft = store.addDonor("u1", "a", Donor(name = "D"))
        store.markBlocked(draft.id, "PERMISSION")
        assertFalse(store.retry(draft.id, "u2", "a"))
        assertFalse(store.retry(draft.id, "u1", "b"))
        assertEquals("BLOCKED", store.find(draft.id)?.state)
        assertTrue(store.retry(draft.id, "u1", "a"))
        store.remove(draft.id, "u2")
        assertEquals("PENDING", store.find(draft.id)?.state)
        store.remove(draft.id, "u1")
        assertEquals(null, store.find(draft.id))
    }

    @Test fun migrationPreservesVersionOnePendingDrafts() = runBlocking {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE drafts (id TEXT PRIMARY KEY NOT NULL, uid TEXT NOT NULL,
                mosque_id TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL,
                created_at INTEGER NOT NULL, state TEXT NOT NULL, failure TEXT)""")
            db.execSQL("INSERT INTO drafts VALUES ('old-id','u','a','funds','Old','{}',1,'PENDING',NULL)")
            db.version = 1
        }
        val old = store.find("old-id")
        assertEquals("add", old?.action)
        assertEquals("PENDING", old?.state)
        assertEquals(null, old?.targetId)
    }

    @Test fun editAndArchiveKeepExpectedStateAndScopedDiscard() = runBlocking {
        val old = Fund(id = "fund-id", mosqueId = "a", name = "Original", code = "A")
        val edit = store.editFund("u1", "a", old, old.copy(name = "Updated"))
        val archive = store.editFund("u1", "a", old, old.copy(active = false), archive = true)
        assertEquals("update", store.find(edit.id)?.action)
        assertEquals("fund-id", store.find(edit.id)?.targetId)
        assertTrue(store.find(edit.id)?.expectedPayload?.contains("Original") == true)
        assertEquals("archive", store.find(archive.id)?.action)
        assertFalse(store.discardBlocked(edit.id, "u1", "a"))
        store.markBlocked(edit.id, "CONFLICT")
        assertFalse(store.discardBlocked(edit.id, "u2", "a"))
        assertFalse(store.discardBlocked(edit.id, "u1", "b"))
        assertTrue(store.discardBlocked(edit.id, "u1", "a"))
        assertEquals(null, store.find(edit.id))
    }
}
