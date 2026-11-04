package com.saif.mosque.data.outbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

/** A durable, account- and mosque-scoped queue. Passwords and ledger entries are never stored here. */
data class DirectoryDraft(
    val id: String, val uid: String, val mosqueId: String, val kind: String,
    val name: String, val payload: String, val createdAt: Long,
    val state: String, val failure: String?,
    val action: String = "add", val targetId: String? = null, val expectedPayload: String? = null
)

class DirectoryOutbox internal constructor(context: Context, name: String = "directory-outbox.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 2) {
    private val changed = MutableStateFlow(0L)
    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE drafts (
            id TEXT PRIMARY KEY NOT NULL, uid TEXT NOT NULL, mosque_id TEXT NOT NULL,
            kind TEXT NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL,
            state TEXT NOT NULL, failure TEXT, action TEXT NOT NULL DEFAULT 'add',
            target_id TEXT, expected_payload TEXT
        )""")
        db.execSQL("CREATE INDEX drafts_scope ON drafts(uid, mosque_id, kind, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve all queued creations written by version 1.
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE drafts ADD COLUMN action TEXT NOT NULL DEFAULT 'add'")
            db.execSQL("ALTER TABLE drafts ADD COLUMN target_id TEXT")
            db.execSQL("ALTER TABLE drafts ADD COLUMN expected_payload TEXT")
        } else throw IllegalStateException("Outbox migration required: $oldVersion -> $newVersion")
    }

    suspend fun addFund(uid: String, mosqueId: String, fund: Fund): DirectoryDraft {
        require(fund.name.isNotBlank() && fund.code.isNotBlank())
        require(listOf(fund.name, fund.code, fund.nameEn, fund.description).all { it.trim().length <= 1000 })
        return add(uid, mosqueId, "funds", fund.name.trim(), gson.toJson(fund.copy(
            id = "", mosqueId = mosqueId, name = fund.name.trim(), code = fund.code.trim(),
            nameEn = fund.nameEn.trim(), description = fund.description.trim(), balance = 0.0)))
    }
    suspend fun addDonor(uid: String, mosqueId: String, donor: Donor): DirectoryDraft {
        require(donor.name.isNotBlank())
        require(listOf(donor.name, donor.phone, donor.address).all { it.trim().length <= 1000 })
        return add(uid, mosqueId, "donors", donor.name.trim(), gson.toJson(donor.copy(
            id = "", mosqueId = mosqueId, name = donor.name.trim(), phone = donor.phone.trim(),
            address = donor.address.trim(), totalDonated = 0.0)))
    }
    suspend fun editFund(uid: String, mosqueId: String, old: Fund, next: Fund, archive: Boolean = false): DirectoryDraft {
        require(old.id.isNotBlank() && !old.id.contains('/') && old.mosqueId == mosqueId && next.id == old.id)
        require(next.name.isNotBlank() && next.code.isNotBlank())
        require(listOf(next.name, next.code, next.nameEn, next.description).all { it.trim().length <= 1000 })
        val cleaned = next.copy(name = next.name.trim(), code = next.code.trim(), nameEn = next.nameEn.trim(),
            description = next.description.trim(), active = if (archive) false else next.active)
        return add(uid, mosqueId, "funds", cleaned.name, gson.toJson(cleaned),
            if (archive) "archive" else "update", old.id, gson.toJson(old))
    }
    suspend fun editDonor(uid: String, mosqueId: String, old: Donor, next: Donor, archive: Boolean = false): DirectoryDraft {
        require(old.id.isNotBlank() && !old.id.contains('/') && old.mosqueId == mosqueId && next.id == old.id)
        require(next.name.isNotBlank())
        require(listOf(next.name, next.phone, next.address).all { it.trim().length <= 1000 })
        val cleaned = next.copy(name = next.name.trim(), phone = next.phone.trim(), address = next.address.trim(),
            active = if (archive) false else next.active)
        return add(uid, mosqueId, "donors", cleaned.name, gson.toJson(cleaned),
            if (archive) "archive" else "update", old.id, gson.toJson(old))
    }
    private suspend fun add(uid: String, mosqueId: String, kind: String, name: String, payload: String,
                            action: String = "add", targetId: String? = null, expectedPayload: String? = null): DirectoryDraft =
        withContext(Dispatchers.IO) {
            require(uid.isNotBlank() && !uid.contains('/') && mosqueId.isNotBlank() && !mosqueId.contains('/'))
            val row = DirectoryDraft(UUID.randomUUID().toString(), uid, mosqueId, kind, name, payload,
                System.currentTimeMillis(), "PENDING", null, action, targetId, expectedPayload)
            val v = ContentValues().apply {
                put("id", row.id); put("uid", uid); put("mosque_id", mosqueId); put("kind", kind)
                put("name", name); put("payload", payload); put("created_at", row.createdAt)
                put("state", row.state); putNull("failure"); put("action", action)
                put("target_id", targetId); put("expected_payload", expectedPayload)
            }
            writableDatabase.insertOrThrow("drafts", null, v)
            changed.update { it + 1 }
            row
        }

    fun observe(uid: String, mosqueId: String, kind: String): Flow<List<DirectoryDraft>> =
        changed.map { list(uid, mosqueId, kind) }

    suspend fun list(uid: String, mosqueId: String? = null, kind: String? = null): List<DirectoryDraft> =
        withContext(Dispatchers.IO) {
            val where = mutableListOf("uid = ?")
            val args = mutableListOf(uid)
            if (mosqueId != null) { where += "mosque_id = ?"; args += mosqueId }
            if (kind != null) { where += "kind = ?"; args += kind }
            readableDatabase.query("drafts", null, where.joinToString(" AND "), args.toTypedArray(), null, null,
                "created_at ASC, id ASC").use { c ->
                buildList {
                    while (c.moveToNext()) add(DirectoryDraft(
                        c.getString(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("uid")),
                        c.getString(c.getColumnIndexOrThrow("mosque_id")), c.getString(c.getColumnIndexOrThrow("kind")),
                        c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("payload")),
                        c.getLong(c.getColumnIndexOrThrow("created_at")), c.getString(c.getColumnIndexOrThrow("state")),
                        c.getString(c.getColumnIndexOrThrow("failure")), c.getString(c.getColumnIndexOrThrow("action")),
                        c.getString(c.getColumnIndexOrThrow("target_id")), c.getString(c.getColumnIndexOrThrow("expected_payload"))))
                }
            }
        }

    suspend fun find(id: String): DirectoryDraft? = withContext(Dispatchers.IO) {
        readableDatabase.query("drafts", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) null else DirectoryDraft(
                c.getString(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("uid")),
                c.getString(c.getColumnIndexOrThrow("mosque_id")), c.getString(c.getColumnIndexOrThrow("kind")),
                c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("payload")),
                c.getLong(c.getColumnIndexOrThrow("created_at")), c.getString(c.getColumnIndexOrThrow("state")),
                c.getString(c.getColumnIndexOrThrow("failure")), c.getString(c.getColumnIndexOrThrow("action")),
                c.getString(c.getColumnIndexOrThrow("target_id")), c.getString(c.getColumnIndexOrThrow("expected_payload")))
        }
    }
    suspend fun markBlocked(id: String, failure: String) = withContext(Dispatchers.IO) {
        writableDatabase.update("drafts", ContentValues().apply { put("state", "BLOCKED"); put("failure", failure) },
            "id = ?", arrayOf(id))
        changed.update { it + 1 }
    }
    suspend fun retry(id: String, uid: String, mosqueId: String) = withContext(Dispatchers.IO) {
        val count = writableDatabase.update("drafts", ContentValues().apply { put("state", "PENDING"); putNull("failure") },
            "id = ? AND uid = ? AND mosque_id = ? AND state = ?", arrayOf(id, uid, mosqueId, "BLOCKED"))
        if (count > 0) changed.update { it + 1 }
        count > 0
    }
    suspend fun discardBlocked(id: String, uid: String, mosqueId: String): Boolean = withContext(Dispatchers.IO) {
        val count = writableDatabase.delete("drafts", "id = ? AND uid = ? AND mosque_id = ? AND state = ?",
            arrayOf(id, uid, mosqueId, "BLOCKED"))
        if (count > 0) changed.update { it + 1 }
        count > 0
    }
    suspend fun remove(id: String, uid: String) = withContext(Dispatchers.IO) {
        val count = writableDatabase.delete("drafts", "id = ? AND uid = ?", arrayOf(id, uid))
        if (count > 0) changed.update { it + 1 }
    }

    companion object {
        @Volatile private var shared: DirectoryOutbox? = null
        fun get(context: Context): DirectoryOutbox = shared ?: synchronized(this) {
            shared ?: DirectoryOutbox(context).also { shared = it }
        }
    }
}
