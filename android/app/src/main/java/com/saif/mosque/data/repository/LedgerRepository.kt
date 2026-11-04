package com.saif.mosque.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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
import kotlinx.coroutines.tasks.await

/** Tenant streams and calls to the trusted mutation backend. */
class LedgerRepository(
    private val db: FirebaseFirestore,
    private val session: SessionManager
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
            .snapshotsFlow().map { it.toObjects(Contribution::class.java) }

    fun observeExpenses(mosqueId: String): Flow<List<Expense>> =
        coll(mosqueId, "expenses").orderBy("date", Query.Direction.DESCENDING)
            .snapshotsFlow().map { it.toObjects(Expense::class.java) }

    fun observeTransfers(mosqueId: String): Flow<List<Transfer>> =
        coll(mosqueId, "transfers").orderBy("date", Query.Direction.DESCENDING)
            .snapshotsFlow().map { it.toObjects(Transfer::class.java) }

    fun observeMembers(mosqueId: String): Flow<List<AppUser>> =
        db.collection("users").whereEqualTo("mosqueId", mosqueId).snapshotsFlow()
            .map { it.toObjects(AppUser::class.java) }

    fun observeRecentContributions(mosqueId: String): Flow<List<Contribution>> =
        coll(mosqueId, "contributions").orderBy("date", Query.Direction.DESCENDING).limit(10)
            .snapshotsFlow().map { it.toObjects(Contribution::class.java) }
    fun observeRecentExpenses(mosqueId: String): Flow<List<Expense>> =
        coll(mosqueId, "expenses").orderBy("date", Query.Direction.DESCENDING).limit(10)
            .snapshotsFlow().map { it.toObjects(Expense::class.java) }
    fun observeRecentTransfers(mosqueId: String): Flow<List<Transfer>> =
        coll(mosqueId, "transfers").orderBy("date", Query.Direction.DESCENDING).limit(10)
            .snapshotsFlow().map { it.toObjects(Transfer::class.java) }
    fun observeMonthContributions(mosqueId: String, start: String, end: String): Flow<List<Contribution>> =
        coll(mosqueId, "contributions").whereGreaterThanOrEqualTo("date", start).whereLessThanOrEqualTo("date", end)
            .snapshotsFlow().map { it.toObjects(Contribution::class.java) }
    fun observeMonthExpenses(mosqueId: String, start: String, end: String): Flow<List<Expense>> =
        coll(mosqueId, "expenses").whereGreaterThanOrEqualTo("date", start).whereLessThanOrEqualTo("date", end)
            .snapshotsFlow().map { it.toObjects(Expense::class.java) }

    fun observeMosque(mosqueId: String): Flow<Mosque?> =
        mosqueRef(mosqueId).snapshotsFlow().map { it.toObject(Mosque::class.java) }

    // All mutations run on the trusted backend; direct Firestore writes are denied.
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
    // Accounting entries still use the trusted backend; never write balances here.
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
        "active" to fund.active, "balance" to 0
    ))
    suspend fun updateFund(mosqueId: String, fund: Fund) {
        coll(mosqueId, "funds").document(fund.id).update(mapOf(
            "name" to fund.name.trim(), "code" to fund.code.trim(), "nameEn" to fund.nameEn.trim(),
            "description" to fund.description.trim(), "active" to fund.active
        )).await()
    }
    suspend fun deleteFund(mosqueId: String, fundId: String) {
        coll(mosqueId, "funds").document(fundId).update("active", false).await()
    }
    suspend fun addDonor(mosqueId: String, donor: Donor) = addDirectory(mosqueId, "donors", mapOf(
        "mosqueId" to mosqueId, "name" to donor.name.trim(), "phone" to donor.phone.trim(),
        "address" to donor.address.trim(), "active" to donor.active, "totalDonated" to 0,
        "createdAt" to System.currentTimeMillis()
    ))
    suspend fun updateDonor(mosqueId: String, donor: Donor) {
        coll(mosqueId, "donors").document(donor.id).update(mapOf(
            "name" to donor.name.trim(), "phone" to donor.phone.trim(), "address" to donor.address.trim(),
            "active" to donor.active
        )).await()
    }
    suspend fun deleteDonor(mosqueId: String, donorId: String) {
        coll(mosqueId, "donors").document(donorId).update("active", false).await()
    }

    suspend fun addContribution(mosqueId: String, c: Contribution, userId: String) =
        mutate("contributions", "add", mosqueId, value = c)
    suspend fun updateContribution(mosqueId: String, old: Contribution, new: Contribution, userId: String) =
        mutate("contributions", "update", mosqueId, old.id, new, old)
    suspend fun deleteContribution(mosqueId: String, c: Contribution) =
        mutate("contributions", "delete", mosqueId, c.id, expected = c)
    suspend fun addExpense(mosqueId: String, e: Expense, userId: String) =
        mutate("expenses", "add", mosqueId, value = e)
    suspend fun updateExpense(mosqueId: String, old: Expense, new: Expense, userId: String) =
        mutate("expenses", "update", mosqueId, old.id, new, old)
    suspend fun deleteExpense(mosqueId: String, e: Expense) =
        mutate("expenses", "delete", mosqueId, e.id, expected = e)
    suspend fun addTransfer(mosqueId: String, t: Transfer, userId: String) =
        mutate("transfers", "add", mosqueId, value = t)
    suspend fun deleteTransfer(mosqueId: String, t: Transfer) =
        mutate("transfers", "delete", mosqueId, t.id, expected = t)

    suspend fun updateMember(mosqueId: String, member: AppUser, editor: AppUser) {
        if (member.id == editor.id && !member.active) throw SelfActionException()
        mutate("members", "update", mosqueId, member.id, member)
    }
    suspend fun deleteMember(mosqueId: String, memberId: String, editor: AppUser) {
        if (memberId == editor.id) throw SelfActionException()
        mutate("members", "delete", mosqueId, memberId)
    }

    // ---------- month queries (reports) ----------

    suspend fun contributionsInRange(mosqueId: String, startIso: String, endIso: String): List<Contribution> =
        coll(mosqueId, "contributions")
            .whereGreaterThanOrEqualTo("date", startIso)
            .whereLessThanOrEqualTo("date", endIso)
            .get().await().toObjects(Contribution::class.java)

    suspend fun expensesInRange(mosqueId: String, startIso: String, endIso: String): List<Expense> =
        coll(mosqueId, "expenses")
            .whereGreaterThanOrEqualTo("date", startIso)
            .whereLessThanOrEqualTo("date", endIso)
            .get().await().toObjects(Expense::class.java)
}

class InsufficientBalanceException : Exception("insufficient balance")
class SameAccountException : Exception("same account")
class SelfActionException : Exception("self action not allowed")

/** Convert a Firestore model to the plain JSON-compatible data accepted by callable functions. */
internal fun callableMap(value: Any): Map<*, *> {
    val gson = com.google.gson.Gson()
    return gson.fromJson(gson.toJson(value), Map::class.java)
}
