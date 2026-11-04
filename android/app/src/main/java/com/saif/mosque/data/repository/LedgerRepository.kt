package com.saif.mosque.data.repository

import com.google.firebase.firestore.FieldValue
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
import com.saif.mosque.util.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * All mosque-tenant data lives under /mosques/{mosqueId}/{collection}.
 * Balance-affecting writes (contribution/expense/transfer + their edits/deletes)
 * run inside Firestore transactions so fund balances and the mosque's cached
 * stats (totalBalance / donorCount / fundCount / memberCount) stay consistent.
 *
 * Business rules (spec section 5):
 *  - contribution: fund.balance += amount, mosque.totalBalance += amount
 *  - expense:      fund.balance -= amount, mosque.totalBalance -= amount
 *  - transfer:     from.balance -= amount, to.balance += amount (must differ,
 *                  from.balance must cover amount)
 *  - donor.totalDonated maintained with contributions
 */
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

    fun observeMosque(mosqueId: String): Flow<Mosque?> =
        mosqueRef(mosqueId).snapshotsFlow().map { it.toObject(Mosque::class.java) }

    // ---------- funds ----------

    suspend fun addFund(mosqueId: String, fund: Fund) {
        val ref = coll(mosqueId, "funds").document()
        db.runTransaction { tx ->
            tx.set(ref, fund.copy(id = "", mosqueId = mosqueId))
            tx.update(mosqueRef(mosqueId), "fundCount", FieldValue.increment(1))
            null
        }.await()
    }

    suspend fun updateFund(mosqueId: String, fund: Fund) {
        coll(mosqueId, "funds").document(fund.id).set(fund.copy(id = "", mosqueId = mosqueId)).await()
    }

    suspend fun deleteFund(mosqueId: String, fundId: String) {
        db.runTransaction { tx ->
            tx.delete(coll(mosqueId, "funds").document(fundId))
            tx.update(mosqueRef(mosqueId), "fundCount", FieldValue.increment(-1))
            null
        }.await()
    }

    // ---------- donors ----------

    suspend fun addDonor(mosqueId: String, donor: Donor) {
        val ref = coll(mosqueId, "donors").document()
        db.runTransaction { tx ->
            tx.set(ref, donor.copy(id = "", mosqueId = mosqueId, createdAt = System.currentTimeMillis()))
            tx.update(mosqueRef(mosqueId), "donorCount", FieldValue.increment(1))
            null
        }.await()
    }

    suspend fun updateDonor(mosqueId: String, donor: Donor) {
        coll(mosqueId, "donors").document(donor.id)
            .set(donor.copy(id = "", mosqueId = mosqueId)).await()
    }

    suspend fun deleteDonor(mosqueId: String, donorId: String) {
        db.runTransaction { tx ->
            tx.delete(coll(mosqueId, "donors").document(donorId))
            tx.update(mosqueRef(mosqueId), "donorCount", FieldValue.increment(-1))
            null
        }.await()
    }

    // ---------- contributions ----------

    suspend fun addContribution(mosqueId: String, c: Contribution, userId: String) {
        val fundRef = coll(mosqueId, "funds").document(c.accountId)
        val mosque = mosqueRef(mosqueId)
        val donorRef = if (c.donorId.isNotBlank()) coll(mosqueId, "donors").document(c.donorId) else null
        db.runTransaction { tx ->
            val fund = tx.get(fundRef).toObject(Fund::class.java)
                ?: throw IllegalStateException("fund not found")
            tx.update(fundRef, "balance", fund.balance + c.amount)
            tx.update(mosque, "totalBalance", FieldValue.increment(c.amount))
            donorRef?.let { tx.update(it, "totalDonated", FieldValue.increment(c.amount)) }
            tx.set(coll(mosqueId, "contributions").document(),
                c.copy(id = "", mosqueId = mosqueId, createdBy = userId, createdAt = System.currentTimeMillis()))
            null
        }.await()
    }

    /** Edit = adjust balances by the delta, then overwrite the document. */
    suspend fun updateContribution(mosqueId: String, old: Contribution, new: Contribution, userId: String) {
        val mosque = mosqueRef(mosqueId)
        db.runTransaction { tx ->
            val oldFund = tx.get(coll(mosqueId, "funds").document(old.accountId)).toObject(Fund::class.java)!!
            val newFund = tx.get(coll(mosqueId, "funds").document(new.accountId)).toObject(Fund::class.java)!!
            tx.update(coll(mosqueId, "funds").document(old.accountId), "balance", oldFund.balance - old.amount)
            tx.update(coll(mosqueId, "funds").document(new.accountId), "balance", newFund.balance + new.amount)
            tx.update(mosque, "totalBalance", FieldValue.increment(new.amount - old.amount))
            tx.set(coll(mosqueId, "contributions").document(old.id),
                new.copy(id = "", mosqueId = mosqueId, createdBy = userId, createdAt = old.createdAt))
            null
        }.await()
    }

    suspend fun deleteContribution(mosqueId: String, c: Contribution) {
        val fundRef = coll(mosqueId, "funds").document(c.accountId)
        val mosque = mosqueRef(mosqueId)
        db.runTransaction { tx ->
            val fund = tx.get(fundRef).toObject(Fund::class.java)!!
            tx.update(fundRef, "balance", fund.balance - c.amount)
            tx.update(mosque, "totalBalance", FieldValue.increment(-c.amount))
            tx.delete(coll(mosqueId, "contributions").document(c.id))
            null
        }.await()
    }

    // ---------- expenses ----------

    suspend fun addExpense(mosqueId: String, e: Expense, userId: String) {
        val fundRef = coll(mosqueId, "funds").document(e.accountId)
        val mosque = mosqueRef(mosqueId)
        db.runTransaction { tx ->
            val fund = tx.get(fundRef).toObject(Fund::class.java)
                ?: throw IllegalStateException("fund not found")
            if (fund.balance < e.amount) throw InsufficientBalanceException()
            tx.update(fundRef, "balance", fund.balance - e.amount)
            tx.update(mosque, "totalBalance", FieldValue.increment(-e.amount))
            tx.set(coll(mosqueId, "expenses").document(),
                e.copy(id = "", mosqueId = mosqueId, createdAt = System.currentTimeMillis()))
            null
        }.await()
    }

    suspend fun updateExpense(mosqueId: String, old: Expense, new: Expense, userId: String) {
        val mosque = mosqueRef(mosqueId)
        db.runTransaction { tx ->
            val oldFund = tx.get(coll(mosqueId, "funds").document(old.accountId)).toObject(Fund::class.java)!!
            val newFund = tx.get(coll(mosqueId, "funds").document(new.accountId)).toObject(Fund::class.java)!!
            tx.update(coll(mosqueId, "funds").document(old.accountId), "balance", oldFund.balance + old.amount)
            tx.update(coll(mosqueId, "funds").document(new.accountId), "balance", newFund.balance - new.amount)
            tx.update(mosque, "totalBalance", FieldValue.increment(old.amount - new.amount))
            tx.set(coll(mosqueId, "expenses").document(old.id),
                new.copy(id = "", mosqueId = mosqueId, createdAt = old.createdAt))
            null
        }.await()
    }

    suspend fun deleteExpense(mosqueId: String, e: Expense) {
        val fundRef = coll(mosqueId, "funds").document(e.accountId)
        val mosque = mosqueRef(mosqueId)
        db.runTransaction { tx ->
            val fund = tx.get(fundRef).toObject(Fund::class.java)!!
            tx.update(fundRef, "balance", fund.balance + e.amount)
            tx.update(mosque, "totalBalance", FieldValue.increment(e.amount))
            tx.delete(coll(mosqueId, "expenses").document(e.id))
            null
        }.await()
    }

    // ---------- transfers ----------

    suspend fun addTransfer(mosqueId: String, t: Transfer, userId: String) {
        if (t.fromAccountId == t.toAccountId) throw SameAccountException()
        val fromRef = coll(mosqueId, "funds").document(t.fromAccountId)
        val toRef = coll(mosqueId, "funds").document(t.toAccountId)
        db.runTransaction { tx ->
            val from = tx.get(fromRef).toObject(Fund::class.java)
                ?: throw IllegalStateException("from fund not found")
            val to = tx.get(toRef).toObject(Fund::class.java)
                ?: throw IllegalStateException("to fund not found")
            if (from.balance < t.amount) throw InsufficientBalanceException()
            tx.update(fromRef, "balance", from.balance - t.amount)
            tx.update(toRef, "balance", to.balance + t.amount)
            // mosque totalBalance unchanged by a transfer
            tx.set(coll(mosqueId, "transfers").document(),
                t.copy(id = "", mosqueId = mosqueId, createdBy = userId, createdAt = System.currentTimeMillis()))
            null
        }.await()
    }

    suspend fun deleteTransfer(mosqueId: String, t: Transfer) {
        val fromRef = coll(mosqueId, "funds").document(t.fromAccountId)
        val toRef = coll(mosqueId, "funds").document(t.toAccountId)
        db.runTransaction { tx ->
            val from = tx.get(fromRef).toObject(Fund::class.java)!!
            val to = tx.get(toRef).toObject(Fund::class.java)!!
            tx.update(fromRef, "balance", from.balance + t.amount)
            tx.update(toRef, "balance", to.balance - t.amount)
            tx.delete(coll(mosqueId, "transfers").document(t.id))
            null
        }.await()
    }

    // ---------- members (users collection) ----------

    suspend fun updateMember(mosqueId: String, member: AppUser, editor: AppUser) {
        // spec 2.3: cannot deactivate yourself
        if (member.id == editor.id && !member.active) throw SelfActionException()
        db.collection("users").document(member.id)
            .set(member.copy(id = ""), com.google.firebase.firestore.SetOptions.merge()).await()
    }

    suspend fun deleteMember(mosqueId: String, memberId: String, editor: AppUser) {
        // spec 2.3: cannot delete yourself
        if (memberId == editor.id) throw SelfActionException()
        db.runTransaction { tx ->
            tx.delete(db.collection("users").document(memberId))
            tx.update(mosqueRef(mosqueId), "memberCount", FieldValue.increment(-1))
            null
        }.await()
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
