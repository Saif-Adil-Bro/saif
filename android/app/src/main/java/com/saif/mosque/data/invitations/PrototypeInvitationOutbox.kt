package com.saif.mosque.data.invitations

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.firebase.Timestamp
import com.saif.mosque.data.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Exact immutable invitation data; no credentials are stored in this queue. */
data class PrototypeQueuedInvitation(val draft: PrototypeInvitation, val state: String, val failure: String?)

class PrototypeInvitationOutbox internal constructor(context: Context, name: String = "invitation-prototype-outbox.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    private val changed = MutableStateFlow(0L)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE invites (
            id TEXT PRIMARY KEY NOT NULL, uid TEXT NOT NULL, mosque_id TEXT NOT NULL,
            email TEXT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, role TEXT NOT NULL,
            designation TEXT NOT NULL, expires_seconds INTEGER NOT NULL, expires_nanos INTEGER NOT NULL,
            state TEXT NOT NULL, failure TEXT
        )""")
        db.execSQL("CREATE INDEX invite_scope ON invites(uid, mosque_id, state)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Invitation outbox migration required: $oldVersion -> $newVersion")
    }
    suspend fun enqueue(d: PrototypeInvitation) = withContext(Dispatchers.IO) {
        validatePrototypeInvitation(d)
        val values = ContentValues().apply {
            put("id", d.id); put("uid", d.issuerUid); put("mosque_id", d.mosqueId)
            put("email", d.email); put("name", d.name); put("phone", d.phone); put("role", d.role.name)
            put("designation", d.designation); put("expires_seconds", d.expiresAt.seconds); put("expires_nanos", d.expiresAt.nanoseconds)
            put("state", "PENDING"); putNull("failure")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.insertWithOnConflict("invites", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                val existing = db.query("invites", null, "id = ?", arrayOf(d.id), null, null, null).use { c ->
                    if (c.moveToFirst()) readRow(c) else null
                }
                if (existing?.draft != d) throw InvitationConflict()
            } else changed.update { it + 1 }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun list(uid: String, mosqueId: String? = null): List<PrototypeQueuedInvitation> = withContext(Dispatchers.IO) {
        val where = if (mosqueId == null) "uid = ?" else "uid = ? AND mosque_id = ?"
        val args = if (mosqueId == null) arrayOf(uid) else arrayOf(uid, mosqueId)
        readableDatabase.query("invites", null, where, args, null, null, "rowid ASC").use { c ->
            buildList { while (c.moveToNext()) add(readRow(c)) }
        }
    }
    suspend fun find(id: String, uid: String, mosqueId: String): PrototypeQueuedInvitation? = withContext(Dispatchers.IO) {
        readableDatabase.query("invites", null, "id = ? AND uid = ? AND mosque_id = ?", arrayOf(id, uid, mosqueId), null, null, null)
            .use { if (it.moveToFirst()) readRow(it) else null }
    }
    fun observe(uid: String, mosqueId: String): Flow<List<PrototypeQueuedInvitation>> = changed.map { list(uid, mosqueId) }
    internal suspend fun transition(id: String, uid: String, mosqueId: String, from: String, to: String, failure: String? = null): Boolean = withContext(Dispatchers.IO) {
        require((from == "BLOCKED" && to == "PENDING") ||
            (from == "PENDING" && to in setOf("BLOCKED", "READY", "ACCEPTED", "REVOKED")) ||
            (from == "READY" && to in setOf("ACCEPTED", "REVOKED")))
        val count = writableDatabase.update("invites", ContentValues().apply { put("state", to); put("failure", failure) },
            "id = ? AND uid = ? AND mosque_id = ? AND state = ?", arrayOf(id, uid, mosqueId, from))
        if (count > 0) changed.update { it + 1 }
        count > 0
    }
    private fun readRow(c: Cursor): PrototypeQueuedInvitation {
        fun s(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        val d = PrototypeInvitation(s("id"), s("mosque_id"), s("uid"), s("email"), s("name"), s("phone"),
            Role.valueOf(s("role")), s("designation"), Timestamp(c.getLong(c.getColumnIndexOrThrow("expires_seconds")), c.getInt(c.getColumnIndexOrThrow("expires_nanos"))))
        validatePrototypeInvitation(d)
        return PrototypeQueuedInvitation(d, s("state"), s("failure"))
    }
    companion object {
        @Volatile private var shared: PrototypeInvitationOutbox? = null
        fun get(context: Context): PrototypeInvitationOutbox = shared ?: synchronized(this) {
            shared ?: PrototypeInvitationOutbox(context).also { shared = it }
        }
    }
}
