package com.saif.mosque.data.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Durable account-scoped correction drafts (`ledgerMutations`) for offline retry. */
data class PrototypeQueuedCorrection(
    val draft: PrototypeCorrectionDraft,
    val createdAt: Long,
    val state: String,
    val failure: String?
)

class PrototypeCorrectionOutbox internal constructor(
    context: Context,
    name: String = "ledger-correction-outbox.db"
) : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    private val changed = MutableStateFlow(0L)
    private val gson = Gson()

    companion object {
        @Volatile private var shared: PrototypeCorrectionOutbox? = null
        fun get(context: Context): PrototypeCorrectionOutbox = shared ?: synchronized(this) {
            shared ?: PrototypeCorrectionOutbox(context).also { shared = it }
        }

        private val LONG_FIELDS = setOf("amountPoisha", "createdAt", "balancePoisha", "totalDonatedPoisha")

        internal fun normalizeLedgerMap(raw: Map<String, Any?>): Map<String, Any> {
            val out = linkedMapOf<String, Any>()
            for ((k, v) in raw) {
                if (v == null) continue
                out[k] = when {
                    k in LONG_FIELDS && v is Number -> v.toLong()
                    k == "amount" && v is Number -> v.toDouble()
                    else -> v
                }
            }
            val poisha = out["amountPoisha"] as? Long
            if (poisha != null && out.containsKey("amount")) {
                out["amount"] = LedgerMath.taka(poisha)
            }
            return out
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE corrections (
                id TEXT PRIMARY KEY NOT NULL,
                uid TEXT NOT NULL,
                mosque_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                target_id TEXT NOT NULL,
                action TEXT NOT NULL,
                expected_before TEXT NOT NULL,
                changes TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                failure TEXT
            )"""
        )
        db.execSQL("CREATE INDEX corrections_scope ON corrections(uid, mosque_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Correction outbox migration required: $oldVersion -> $newVersion")
    }

    suspend fun enqueue(draft: PrototypeCorrectionDraft, createdAt: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            require(draft.id.isNotEmpty() && draft.uid.isNotEmpty() && draft.mosqueId.isNotEmpty() && draft.targetId.isNotEmpty())
            require(draft.kind in setOf("contributions", "expenses", "transfers"))
            require(draft.action in setOf("update", "delete"))
            val values = ContentValues().apply {
                put("id", draft.id)
                put("uid", draft.uid)
                put("mosque_id", draft.mosqueId)
                put("kind", draft.kind)
                put("target_id", draft.targetId)
                put("action", draft.action)
                put("expected_before", gson.toJson(normalizeLedgerMap(draft.expectedBefore)))
                put("changes", gson.toJson(normalizeLedgerMap(draft.changes)))
                put("created_at", createdAt)
                put("state", "PENDING")
                putNull("failure")
            }
            writableDatabase.insertOrThrow("corrections", null, values)
            changed.update { it + 1 }
        }

    fun observe(uid: String, mosqueId: String): Flow<List<PrototypeQueuedCorrection>> =
        changed.map { list(uid, mosqueId) }

    suspend fun list(uid: String, mosqueId: String, kind: String? = null): List<PrototypeQueuedCorrection> =
        withContext(Dispatchers.IO) {
            val where = if (kind == null) "uid = ? AND mosque_id = ?" else "uid = ? AND mosque_id = ? AND kind = ?"
            val args = if (kind == null) arrayOf(uid, mosqueId) else arrayOf(uid, mosqueId, kind)
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            readableDatabase.query("corrections", null, where, args, null, null, "rowid ASC").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val beforeRaw: Map<String, Any?> = gson.fromJson(c.getString(c.getColumnIndexOrThrow("expected_before")), mapType)
                        val changesRaw: Map<String, Any?> = gson.fromJson(c.getString(c.getColumnIndexOrThrow("changes")), mapType)
                        val draft = PrototypeCorrectionDraft(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            uid = c.getString(c.getColumnIndexOrThrow("uid")),
                            mosqueId = c.getString(c.getColumnIndexOrThrow("mosque_id")),
                            kind = c.getString(c.getColumnIndexOrThrow("kind")),
                            targetId = c.getString(c.getColumnIndexOrThrow("target_id")),
                            action = c.getString(c.getColumnIndexOrThrow("action")),
                            expectedBefore = normalizeLedgerMap(beforeRaw),
                            changes = normalizeLedgerMap(changesRaw)
                        )
                        check(draft.uid == uid && draft.mosqueId == mosqueId)
                        add(
                            PrototypeQueuedCorrection(
                                draft = draft,
                                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                                state = c.getString(c.getColumnIndexOrThrow("state")),
                                failure = c.getString(c.getColumnIndexOrThrow("failure"))
                            )
                        )
                    }
                }
            }
        }

    suspend fun pendingMosques(uid: String): List<String> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            true, "corrections", arrayOf("mosque_id"),
            "uid = ? AND state = 'PENDING'", arrayOf(uid), null, null, "mosque_id ASC", null
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    suspend fun block(id: String, uid: String, mosqueId: String, failure: String): Boolean =
        withContext(Dispatchers.IO) {
            val count = writableDatabase.update(
                "corrections",
                ContentValues().apply { put("state", "BLOCKED"); put("failure", failure) },
                "id = ? AND uid = ? AND mosque_id = ? AND state = 'PENDING'",
                arrayOf(id, uid, mosqueId)
            )
            if (count > 0) changed.update { it + 1 }
            count > 0
        }

    suspend fun retry(id: String, uid: String, mosqueId: String): Boolean =
        withContext(Dispatchers.IO) {
            val count = writableDatabase.update(
                "corrections",
                ContentValues().apply { put("state", "PENDING"); putNull("failure") },
                "id = ? AND uid = ? AND mosque_id = ? AND state = 'BLOCKED'",
                arrayOf(id, uid, mosqueId)
            )
            if (count > 0) changed.update { it + 1 }
            count > 0
        }

    suspend fun acknowledge(id: String, uid: String, mosqueId: String): Boolean =
        remove(id, uid, mosqueId, "PENDING")

    suspend fun discardBlocked(id: String, uid: String, mosqueId: String): Boolean =
        remove(id, uid, mosqueId, "BLOCKED")

    private suspend fun remove(id: String, uid: String, mosqueId: String, state: String): Boolean =
        withContext(Dispatchers.IO) {
            val count = writableDatabase.delete(
                "corrections",
                "id = ? AND uid = ? AND mosque_id = ? AND state = ?",
                arrayOf(id, uid, mosqueId, state)
            )
            if (count > 0) changed.update { it + 1 }
            count > 0
        }
}
