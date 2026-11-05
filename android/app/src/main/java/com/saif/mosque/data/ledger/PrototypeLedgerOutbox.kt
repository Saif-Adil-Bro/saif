package com.saif.mosque.data.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Durable account-scoped drafts for the work-in-progress v2 ledger. */
data class PrototypeQueuedLedger(val draft: PrototypeLedgerDraft, val state: String, val failure: String?)

class PrototypeLedgerOutbox internal constructor(context: Context, name: String = "ledger-prototype-outbox.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    private val changed = MutableStateFlow(0L)
    private val gson = Gson()
    companion object {
        @Volatile private var shared: PrototypeLedgerOutbox? = null
        fun get(context: Context): PrototypeLedgerOutbox = shared ?: synchronized(this) {
            shared ?: PrototypeLedgerOutbox(context).also { shared = it }
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entries (id TEXT PRIMARY KEY NOT NULL, uid TEXT NOT NULL,
            mosque_id TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL,
            state TEXT NOT NULL, failure TEXT)""")
        db.execSQL("CREATE INDEX entries_scope ON entries(uid, mosque_id, kind, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Ledger outbox migration required: $oldVersion -> $newVersion")
    }
    suspend fun enqueue(draft: PrototypeLedgerDraft) = withContext(Dispatchers.IO) {
        validatePrototypeDraft(draft)
        val fields = ContentValues().apply {
            put("id", draft.id); put("uid", draft.uid); put("mosque_id", draft.mosqueId)
            put("kind", draft.kind); put("payload", gson.toJson(draft)); put("created_at", draft.createdAt)
            put("state", "PENDING"); putNull("failure")
        }
        // A duplicate ID is an error, never silently overwrite a previous unacknowledged operation.
        writableDatabase.insertOrThrow("entries", null, fields)
        changed.update { it + 1 }
    }
    fun observe(uid: String, mosqueId: String, kind: String? = null): Flow<List<PrototypeQueuedLedger>> =
        changed.map { list(uid, mosqueId, kind) }

    suspend fun list(uid: String, mosqueId: String, kind: String? = null): List<PrototypeQueuedLedger> = withContext(Dispatchers.IO) {
        val where = if (kind == null) "uid = ? AND mosque_id = ?" else "uid = ? AND mosque_id = ? AND kind = ?"
        val args = if (kind == null) arrayOf(uid, mosqueId) else arrayOf(uid, mosqueId, kind)
        readableDatabase.query("entries", null, where, args, null, null, "rowid ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val draft = gson.fromJson(cursor.getString(cursor.getColumnIndexOrThrow("payload")), PrototypeLedgerDraft::class.java)
                    // Corruption must never cause a row to be sent under a different identity.
                    check(draft.id == cursor.getString(cursor.getColumnIndexOrThrow("id")) &&
                        draft.uid == uid && draft.mosqueId == mosqueId && draft.kind == cursor.getString(cursor.getColumnIndexOrThrow("kind")))
                    validatePrototypeDraft(draft)
                    add(PrototypeQueuedLedger(draft, cursor.getString(cursor.getColumnIndexOrThrow("state")),
                        cursor.getString(cursor.getColumnIndexOrThrow("failure"))))
                }
            }
        }
    }
    /** Used by recovery without exposing another account's scopes. */
    suspend fun pendingMosques(uid: String): List<String> = withContext(Dispatchers.IO) {
        readableDatabase.query(true, "entries", arrayOf("mosque_id"), "uid = ? AND (state = 'PENDING' OR (state = 'BLOCKED' AND failure = 'WAIT_MIGRATION'))",
            arrayOf(uid), null, null, "mosque_id ASC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }
    suspend fun block(id: String, uid: String, mosqueId: String, failure: String): Boolean = withContext(Dispatchers.IO) {
        require(failure in setOf("PERMISSION", "CONFLICT", "INSUFFICIENT", "MIGRATION", "WAIT_MIGRATION", "INVALID", "UNKNOWN"))
        val count = writableDatabase.update("entries", ContentValues().apply { put("state", "BLOCKED"); put("failure", failure) },
            "id = ? AND uid = ? AND mosque_id = ? AND state = 'PENDING'", arrayOf(id, uid, mosqueId))
        if (count > 0) changed.update { it + 1 }
        count > 0
    }
    suspend fun retry(id: String, uid: String, mosqueId: String): Boolean = withContext(Dispatchers.IO) {
        val count = writableDatabase.update("entries", ContentValues().apply { put("state", "PENDING"); putNull("failure") },
            "id = ? AND uid = ? AND mosque_id = ? AND state = 'BLOCKED'", arrayOf(id, uid, mosqueId))
        if (count > 0) changed.update { it + 1 }
        count > 0
    }
    /** Only call after a confirmed exact server acknowledgement, not after a local Firestore write. */
    suspend fun acknowledge(id: String, uid: String, mosqueId: String): Boolean = remove(id, uid, mosqueId, "PENDING")
    /** UI must warn that an uncertain operation may already have committed before local discard. */
    suspend fun discardBlocked(id: String, uid: String, mosqueId: String): Boolean = remove(id, uid, mosqueId, "BLOCKED")
    private suspend fun remove(id: String, uid: String, mosqueId: String, state: String): Boolean = withContext(Dispatchers.IO) {
        val count = writableDatabase.delete("entries", "id = ? AND uid = ? AND mosque_id = ? AND state = ?", arrayOf(id, uid, mosqueId, state))
        if (count > 0) changed.update { it + 1 }
        count > 0
    }
}
