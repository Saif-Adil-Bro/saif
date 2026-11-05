package com.saif.mosque.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.saif.mosque.util.snapshotsFlow
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Contribution
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Expense
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.Mosque
import com.saif.mosque.data.model.Transfer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.tasks.await

// Legacy entries lack this field. A malformed marker must not silently make a
// potentially voided financial record count as confirmed income or expenditure.
internal fun visibleLedgerMarker(present: Boolean, value: Any?): Boolean = !present || value == false

private fun <T : Any> QuerySnapshot.visibleLedgerRecords(type: Class<T>): List<T> =
    documents.asSequence().filter { visibleLedgerMarker(it.contains("voided"), it.get("voided")) }
        .mapNotNull { it.toObject(type) }.toList()

/** Expand only when a limited window contains too many tombstones. Legacy
 * documents lack the marker, so a server-side `voided == false` filter is unsafe.
 * Each replacement listener is removed by snapshotsFlow on cancellation. */
private fun <T : Any> Query.recentVisible(type: Class<T>): Flow<List<T>> = flow {
    var window = 10L
    while (true) {
        var expand = false
        limit(window).snapshotsFlow().takeWhile { snapshot ->
            val visible = snapshot.visibleLedgerRecords(type)
            expand = visible.size < 10 && snapshot.size().toLong() >= window && window < Int.MAX_VALUE.toLong()
            if (!expand) emit(visible.take(10))
            !expand
        }.collect { }
        if (!expand) return@flow
        window = (window * 2).coerceAtMost(Int.MAX_VALUE.toLong())
    }
}

/** Tenant streams and calls to the v2 rules-validated ledger or trusted mutation backend. */
class LedgerRepository(
    private val db: FirebaseFirestore,
    private val session: SessionManager,
    private val context: android.content.Context? = null,
    private val auth: com.google.firebase.auth.FirebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
) {
    private fun mosqueRef(mosqueId: String) = db.collection("mosques").document(mosqueId)
    private fun coll(mosqueId: String, name: String) =
        mosqueRef(mosqueId).collection(name)

    // ---------- streams ----------

    fun observeFunds(mosqueId: String): Flow<List<Fund>> =
        coll(mosqueId, "funds").orderBy("name").snapshotsFlow()
            .map { it.toObjects(Fund::class.java) }

    fun observeDonors(mosqueId: String): Flow<List<Donor>> =
        coll(mosqueId, "donors").orderBy("name").snapshotsFlow()
            .map { it.toObjects(Donor::class.java) }

    fun observeContributions(mosqueId: String): Flow<List<Contribution>> =
        coll(mosqueId, "contributions").orderBy("date", Query.Direction.DESCENDING)
            .snapshotsFlow().map { it.visibleLedgerRecords(Contribution::class.java) }

    fun observeExpenses(mosqueId: String): Flow<List<Expense>> =
        coll(mosqueId, "expenses").orderBy("date", Query.Direction.DESCENDING)
            .snapshotsFlow().map { it.visibleLedgerRecords(Expense::class.java) }

    fun observeTransfers(mosqueId: String): Flow<List<Transfer>> =
        coll(mosqueId, "transfers").orderBy("date", Query.Direction.DESCENDING)
            .snapshotsFlow().map { it.visibleLedgerRecords(Transfer::class.java) }

    fun observeMembers(mosqueId: String): Flow<List<AppUser>> =
        db.collection("users").whereEqualTo("mosqueId", mosqueId).snapshotsFlow()
            .map { it.toObjects(AppUser::class.java) }

    fun observeLedgerMutations(mosqueId: String): Flow<List<com.saif.mosque.data.model.LedgerMutation>> =
        coll(mosqueId, "ledgerMutations").snapshotsFlow()
            .map { it.toObjects(com.saif.mosque.data.model.LedgerMutation::class.java) }

    fun observeRecentContributions(mosqueId: String): Flow<List<Contribution>> =
        coll(mosqueId, "contributions").orderBy("date", Query.Direction.DESCENDING).recentVisible(Contribution::class.java)
    fun observeRecentExpenses(mosqueId: String): Flow<List<Expense>> =
        coll(mosqueId, "expenses").orderBy("date", Query.Direction.DESCENDING).recentVisible(Expense::class.java)
    fun observeRecentTransfers(mosqueId: String): Flow<List<Transfer>> =
        coll(mosqueId, "transfers").orderBy("date", Query.Direction.DESCENDING).recentVisible(Transfer::class.java)
    fun observeMonthContributions(mosqueId: String, start: String, end: String): Flow<List<Contribution>> =
        coll(mosqueId, "contributions").whereGreaterThanOrEqualTo("date", start).whereLessThanOrEqualTo("date", end)
            .snapshotsFlow().map { it.visibleLedgerRecords(Contribution::class.java) }
    fun observeMonthExpenses(mosqueId: String, start: String, end: String): Flow<List<Expense>> =
        coll(mosqueId, "expenses").whereGreaterThanOrEqualTo("date", start).whereLessThanOrEqualTo("date", end)
            .snapshotsFlow().map { it.visibleLedgerRecords(Expense::class.java) }

    fun observeMosque(mosqueId: String): Flow<Mosque?> =
        mosqueRef(mosqueId).snapshotsFlow().map { it.toObject(Mosque::class.java) }

    private suspend fun isV2Mosque(mosqueId: String): Boolean = try {
        mosqueRef(mosqueId).get().await().getLong("ledgerVersion") == 2L
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { true }

    private suspend fun commitOrQueueV2Correction(
        mosqueId: String,
        kind: String,
        targetId: String,
        action: String,
        verifySnapshot: (com.google.firebase.firestore.DocumentSnapshot) -> Boolean,
        changes: Map<String, Any> = emptyMap()
    ) {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        val snap = coll(mosqueId, kind).document(targetId).get().await()
        if (!snap.exists() || !verifySnapshot(snap)) throw StaleRecordException()
        val expectedBefore = com.saif.mosque.data.ledger.PrototypeCorrectionOutbox.normalizeLedgerMap(
            snap.data ?: throw StaleRecordException()
        )
        val draft = com.saif.mosque.data.ledger.PrototypeCorrectionDraft(
            id = java.util.UUID.randomUUID().toString(),
            uid = uid,
            mosqueId = mosqueId,
            kind = kind,
            targetId = targetId,
            action = action,
            expectedBefore = expectedBefore,
            changes = com.saif.mosque.data.ledger.PrototypeCorrectionOutbox.normalizeLedgerMap(changes)
        )
        try {
            com.saif.mosque.data.ledger.PrototypeCorrectionWriter(db) { auth.currentUser?.uid }.commit(draft)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val isTransient = e is com.google.firebase.FirebaseNetworkException ||
                (e is com.google.firebase.firestore.FirebaseFirestoreException && e.code in setOf(
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE,
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
                ))
            val ctx = context
            if (isTransient && ctx != null) {
                com.saif.mosque.data.ledger.PrototypeCorrectionOutbox.get(ctx).enqueue(draft)
                try {
                    com.saif.mosque.data.ledger.PrototypeCorrectionSync.schedule(ctx, draft)
                } catch (_: Exception) { }
            } else {
                throw e
            }
        }
    }

    // Legacy v1 callable fallback for pre-migration tenants on Blaze.
    private suspend fun mutate(kind: String, action: String, mosqueId: String,
                               id: String? = null, value: Any? = null, expected: Any? = null) {
        val data = mutableMapOf<String, Any>("operation" to if (kind in setOf("funds", "donors", "members")) "directory" else "ledger",
            "kind" to kind, "action" to action, "mosqueId" to mosqueId)
        if (id != null) data["id"] = id
        if (value != null) data["value"] = callableMap(value)
        if (expected != null) data["expected"] = callableMap(expected)
        com.google.firebase.functions.FirebaseFunctions.getInstance().getHttpsCallable("mutate").call(data).await()
    }

    // Direct directory writes are paired with their counters in one atomic batch.
    // The matching Firestore rules independently validate both sides of the batch.
    private suspend fun addDirectory(mosqueId: String, kind: String, fields: Map<String, Any>) {
        val parent = mosqueRef(mosqueId)
        val ref = parent.collection(kind).document()
        val counter = if (kind == "funds") "fundCount" else "donorCount"
        db.batch().set(ref, fields).update(parent, mapOf(
            counter to com.google.firebase.firestore.FieldValue.increment(1),
            "directoryOpId" to ref.id, "directoryOpKind" to kind
        )).commit().await()
    }

    suspend fun addFund(mosqueId: String, fund: Fund) = addDirectory(mosqueId, "funds", mapOf(
        "mosqueId" to mosqueId, "name" to fund.name.trim(), "code" to fund.code.trim(),
        "nameEn" to fund.nameEn.trim(), "description" to fund.description.trim(),
        "active" to fund.active, "balance" to 0, "balancePoisha" to 0L
    ))
    suspend fun updateFund(mosqueId: String, old: Fund, fund: Fund) {
        val ref = coll(mosqueId, "funds").document(old.id)
        db.runTransaction { tx ->
            val current = tx.get(ref).toObject(Fund::class.java) ?: throw StaleRecordException()
            if (!com.saif.mosque.util.MetadataVersion.sameFund(current, old)) throw StaleRecordException()
            tx.update(ref, mapOf(
                "name" to fund.name.trim(), "code" to fund.code.trim(), "nameEn" to fund.nameEn.trim(),
                "description" to fund.description.trim(), "active" to fund.active
            ))
        }.await()
    }
    suspend fun deleteFund(mosqueId: String, fundId: String) {
        coll(mosqueId, "funds").document(fundId).update("active", false).await()
    }
    suspend fun addDonor(mosqueId: String, donor: Donor) = addDirectory(mosqueId, "donors", mapOf(
        "mosqueId" to mosqueId, "name" to donor.name.trim(), "phone" to donor.phone.trim(),
        "address" to donor.address.trim(), "active" to donor.active, "totalDonated" to 0, "totalDonatedPoisha" to 0L,
        "createdAt" to System.currentTimeMillis()
    ))
    suspend fun updateDonor(mosqueId: String, old: Donor, donor: Donor) {
        val ref = coll(mosqueId, "donors").document(old.id)
        db.runTransaction { tx ->
            val current = tx.get(ref).toObject(Donor::class.java) ?: throw StaleRecordException()
            if (!com.saif.mosque.util.MetadataVersion.sameDonor(current, old)) throw StaleRecordException()
            tx.update(ref, mapOf(
                "name" to donor.name.trim(), "phone" to donor.phone.trim(), "address" to donor.address.trim(),
                "active" to donor.active
            ))
        }.await()
    }
    suspend fun deleteDonor(mosqueId: String, donorId: String) {
        coll(mosqueId, "donors").document(donorId).update("active", false).await()
    }

    suspend fun addContribution(mosqueId: String, c: Contribution, userId: String) =
        mutate("contributions", "add", mosqueId, value = c)

    suspend fun updateContribution(mosqueId: String, old: Contribution, new: Contribution, userId: String) {
        if (isV2Mosque(mosqueId)) {
            val poisha = com.saif.mosque.data.ledger.LedgerMath.poisha(new.amount, positive = true)
            commitOrQueueV2Correction(
                mosqueId = mosqueId,
                kind = "contributions",
                targetId = old.id,
                action = "update",
                verifySnapshot = { snap ->
                    val current = snap.toObject(Contribution::class.java)
                    current != null && current.accountId == old.accountId && current.donorId == old.donorId &&
                        current.donorName == old.donorName && Math.round(current.amount * 100) == Math.round(old.amount * 100) &&
                        current.date == old.date && current.note == old.note && current.receiptNo == old.receiptNo
                },
                changes = mapOf(
                    "amount" to com.saif.mosque.data.ledger.LedgerMath.taka(poisha),
                    "amountPoisha" to poisha,
                    "accountId" to new.accountId,
                    "donorId" to new.donorId,
                    "donorName" to new.donorName.trim(),
                    "date" to new.date,
                    "note" to new.note.trim(),
                    "receiptNo" to new.receiptNo.trim()
                )
            )
        } else {
            mutate("contributions", "update", mosqueId, old.id, new, old)
        }
    }

    suspend fun deleteContribution(mosqueId: String, c: Contribution) {
        if (isV2Mosque(mosqueId)) {
            commitOrQueueV2Correction(
                mosqueId = mosqueId,
                kind = "contributions",
                targetId = c.id,
                action = "delete",
                verifySnapshot = { snap ->
                    val current = snap.toObject(Contribution::class.java)
                    current != null && current.accountId == c.accountId &&
                        Math.round(current.amount * 100) == Math.round(c.amount * 100)
                }
            )
        } else {
            mutate("contributions", "delete", mosqueId, c.id, expected = c)
        }
    }

    suspend fun addExpense(mosqueId: String, e: Expense, userId: String) =
        mutate("expenses", "add", mosqueId, value = e)

    suspend fun updateExpense(mosqueId: String, old: Expense, new: Expense, userId: String) {
        if (isV2Mosque(mosqueId)) {
            val poisha = com.saif.mosque.data.ledger.LedgerMath.poisha(new.amount, positive = true)
            commitOrQueueV2Correction(
                mosqueId = mosqueId,
                kind = "expenses",
                targetId = old.id,
                action = "update",
                verifySnapshot = { snap ->
                    val current = snap.toObject(Expense::class.java)
                    current != null && current.accountId == old.accountId && current.title == old.title &&
                        Math.round(current.amount * 100) == Math.round(old.amount * 100) &&
                        current.date == old.date && current.paidTo == old.paidTo &&
                        current.category == old.category && current.note == old.note && current.receiptNo == old.receiptNo
                },
                changes = mapOf(
                    "amount" to com.saif.mosque.data.ledger.LedgerMath.taka(poisha),
                    "amountPoisha" to poisha,
                    "accountId" to new.accountId,
                    "title" to new.title.trim(),
                    "category" to new.category.trim(),
                    "paidTo" to new.paidTo.trim(),
                    "date" to new.date,
                    "note" to new.note.trim(),
                    "receiptNo" to new.receiptNo.trim()
                )
            )
        } else {
            mutate("expenses", "update", mosqueId, old.id, new, old)
        }
    }

    suspend fun deleteExpense(mosqueId: String, e: Expense) {
        if (isV2Mosque(mosqueId)) {
            commitOrQueueV2Correction(
                mosqueId = mosqueId,
                kind = "expenses",
                targetId = e.id,
                action = "delete",
                verifySnapshot = { snap ->
                    val current = snap.toObject(Expense::class.java)
                    current != null && current.accountId == e.accountId &&
                        Math.round(current.amount * 100) == Math.round(e.amount * 100)
                }
            )
        } else {
            mutate("expenses", "delete", mosqueId, e.id, expected = e)
        }
    }

    suspend fun addTransfer(mosqueId: String, t: Transfer, userId: String) =
        mutate("transfers", "add", mosqueId, value = t)

    suspend fun deleteTransfer(mosqueId: String, t: Transfer) {
        if (isV2Mosque(mosqueId)) {
            commitOrQueueV2Correction(
                mosqueId = mosqueId,
                kind = "transfers",
                targetId = t.id,
                action = "delete",
                verifySnapshot = { snap ->
                    val current = snap.toObject(Transfer::class.java)
                    current != null && current.fromAccountId == t.fromAccountId &&
                        current.toAccountId == t.toAccountId &&
                        Math.round(current.amount * 100) == Math.round(t.amount * 100)
                }
            )
        } else {
            mutate("transfers", "delete", mosqueId, t.id, expected = t)
        }
    }

    /**
     * Verify the tenant's receipt chain (`ledgerMutations`), voided tombstones, and fund/mosque balance agreement.
     */
    suspend fun verifyReceiptChain(mosqueId: String): com.saif.mosque.data.model.ReceiptChainVerification {
        val mosqueSnap = mosqueRef(mosqueId).get().await()
        val fundsSnap = coll(mosqueId, "funds").get().await()
        val contribsSnap = coll(mosqueId, "contributions").get().await()
        val expensesSnap = coll(mosqueId, "expenses").get().await()
        val transfersSnap = coll(mosqueId, "transfers").get().await()
        val mutationsSnap = coll(mosqueId, "ledgerMutations").get().await()

        val issues = mutableListOf<String>()
        val mutationMap = mutationsSnap.documents.associateBy { it.id }
        val prevIdsByTarget = mutableMapOf<String, MutableSet<String>>()

        for (doc in mutationsSnap.documents) {
            val kind = doc.getString("kind") ?: ""
            val targetId = doc.getString("targetId") ?: ""
            val before = doc.get("before") as? Map<*, *>
            val after = doc.get("after") as? Map<*, *>
            if (before == null || after == null || after["ledgerMutationId"] != doc.id) {
                issues.add("invalid_receipt:${doc.id}")
                continue
            }
            val prevId = (before["ledgerMutationId"] as? String).orEmpty()
            if (prevId.isNotEmpty() && !mutationMap.containsKey(prevId)) {
                issues.add("broken_chain:${doc.id}")
            }
            val seen = prevIdsByTarget.getOrPut("$kind/$targetId") { mutableSetOf() }
            if (!seen.add(prevId)) {
                issues.add("forked_chain:${doc.id}")
            }
        }

        var activeEntries = 0
        var voidedEntries = 0
        for ((kind, snap) in listOf(
            "contributions" to contribsSnap,
            "expenses" to expensesSnap,
            "transfers" to transfersSnap
        )) {
            for (doc in snap.documents) {
                val voided = doc.getBoolean("voided") == true
                if (voided) voidedEntries++ else activeEntries++
                val mutId = doc.getString("ledgerMutationId").orEmpty()
                if ((voided || mutId.isNotEmpty()) && !mutationMap.containsKey(mutId)) {
                    issues.add("missing_receipt:$kind/${doc.id}")
                }
            }
        }

        val mosqueTotalPoisha = mosqueSnap.getLong("totalBalancePoisha")
            ?: Math.round((mosqueSnap.getDouble("totalBalance") ?: 0.0) * 100L)
        val fundsSumPoisha = fundsSnap.documents.sumOf {
            it.getLong("balancePoisha") ?: Math.round((it.getDouble("balance") ?: 0.0) * 100L)
        }
        if (mosqueTotalPoisha != fundsSumPoisha) {
            issues.add("fund_total_mismatch:$mosqueTotalPoisha!=$fundsSumPoisha")
        }

        return com.saif.mosque.data.model.ReceiptChainVerification(
            valid = issues.isEmpty(),
            mutationsCount = mutationsSnap.size(),
            activeEntriesCount = activeEntries,
            voidedEntriesCount = voidedEntries,
            totalBalancePoisha = mosqueTotalPoisha,
            issues = issues
        )
    }

    suspend fun updateMember(mosqueId: String, old: AppUser, member: AppUser, editor: AppUser) {
        if (old.id == editor.id && !member.active) throw SelfActionException()
        val ref = db.collection("users").document(old.id)
        db.runTransaction { tx ->
            val current = tx.get(ref).toObject(AppUser::class.java) ?: throw StaleRecordException()
            if (current.mosqueId != mosqueId || !com.saif.mosque.util.MetadataVersion.sameMember(current, old))
                throw StaleRecordException()
            tx.update(ref, mapOf(
                "name" to member.name.trim(), "phone" to member.phone.trim(),
                "designation" to member.designation.trim(), "role" to member.role.name,
                "active" to member.active
            ))
        }.await()
    }
    suspend fun deleteMember(mosqueId: String, memberId: String, editor: AppUser) {
        if (memberId == editor.id) throw SelfActionException()
        val member = db.collection("users").document(memberId)
        // The rules require a corresponding deletion and decrement in the same batch.
        db.batch().delete(member).update(mosqueRef(mosqueId), mapOf(
            "memberCount" to com.google.firebase.firestore.FieldValue.increment(-1),
            "memberOpId" to memberId
        )).commit().await()
    }

    // ---------- month queries (reports) ----------

    suspend fun contributionsInRange(mosqueId: String, startIso: String, endIso: String): List<Contribution> =
        coll(mosqueId, "contributions")
            .whereGreaterThanOrEqualTo("date", startIso)
            .whereLessThanOrEqualTo("date", endIso)
            .get().await().visibleLedgerRecords(Contribution::class.java)

    suspend fun expensesInRange(mosqueId: String, startIso: String, endIso: String): List<Expense> =
        coll(mosqueId, "expenses")
            .whereGreaterThanOrEqualTo("date", startIso)
            .whereLessThanOrEqualTo("date", endIso)
            .get().await().visibleLedgerRecords(Expense::class.java)
}

class InsufficientBalanceException : Exception("insufficient balance")
class SameAccountException : Exception("same account")
class SelfActionException : Exception("self action not allowed")
class StaleRecordException : Exception("Record changed elsewhere. Refresh and try again.")

/** Convert a Firestore model to the plain JSON-compatible data accepted by callable functions. */
internal fun callableMap(value: Any): Map<*, *> {
    val gson = com.google.gson.Gson()
    return gson.fromJson(gson.toJson(value), Map::class.java)
}

