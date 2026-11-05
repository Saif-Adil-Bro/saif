package com.saif.mosque.data.ledger

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** Immutable append request. This prototype is NOT wired into production or the UI. */
data class PrototypeLedgerDraft(
    val id: String, val uid: String, val mosqueId: String, val kind: String,
    val amountPoisha: Long, val sourceFundId: String, val destinationFundId: String = "",
    val donorId: String = "", val donorName: String = "", val title: String = "",
    val category: String = "", val paidTo: String = "", val receiptNo: String = "",
    val date: String, val note: String = "", val createdAt: Long
)

/**
 * Transaction writer for ledger-prototype.rules only. Not safe with the deployed rules
 * or mixed legacy writers. A future outbox must persist the exact draft before calling
 * this and only remove it after acknowledgement; this class is not an offline queue.
 */
class PrototypeLedgerWriter(private val db: FirebaseFirestore, private val currentUid: () -> String?) {
    suspend fun commit(draft: PrototypeLedgerDraft) {
        validatePrototypeDraft(draft)
        if (currentUid() != draft.uid) throw LedgerIdentityChanged()
        val parent = db.collection("mosques").document(draft.mosqueId)
        val operation = parent.collection(draft.kind).document(draft.id)
        val fund = parent.collection("funds").document(draft.sourceFundId)
        val destination = if (draft.kind == "transfers") parent.collection("funds").document(draft.destinationFundId) else null
        val donor = if (draft.kind == "contributions" && draft.donorId.isNotEmpty()) parent.collection("donors").document(draft.donorId) else null
        val record = record(draft)
        db.runTransaction { tx ->
            if (currentUid() != draft.uid) throw LedgerIdentityChanged()
            // Immutable document ID and exact payload allow safe retry after a lost acknowledgement.
            val existing = tx.get(operation)
            if (existing.exists()) {
                if (existing.data != record) throw LedgerOperationConflict()
            } else {
                val mosqueSnapshot = tx.get(parent)
                if (mosqueSnapshot.getLong("ledgerVersion") != 2L) throw LedgerMigrationRequired()
                val fundSnapshot = tx.get(fund)
                val destinationSnapshot = destination?.let { tx.get(it) }
                val donorSnapshot = donor?.let { tx.get(it) }
                if (mosqueSnapshot.getBoolean("active") != true || fundSnapshot.getBoolean("active") != true ||
                    fundSnapshot.getString("mosqueId") != draft.mosqueId ||
                    (destinationSnapshot != null && (destinationSnapshot.getBoolean("active") != true || destinationSnapshot.getString("mosqueId") != draft.mosqueId)) ||
                    (donorSnapshot != null && (donorSnapshot.getBoolean("active") != true || donorSnapshot.getString("mosqueId") != draft.mosqueId || donorSnapshot.getString("name") != draft.donorName))) {
                    throw LedgerUnavailableAccount()
                }
                val balances = LedgerMath.Balances(
                    fund = storedMoney(fundSnapshot, "balance", "balancePoisha"),
                    mosque = storedMoney(mosqueSnapshot, "totalBalance", "totalBalancePoisha"),
                    destination = destinationSnapshot?.let { storedMoney(it, "balance", "balancePoisha") },
                    donor = donorSnapshot?.let { storedMoney(it, "totalDonated", "totalDonatedPoisha") }
                )
                val next = when (draft.kind) {
                    "contributions" -> LedgerMath.contribution(balances, draft.amountPoisha)
                    "expenses" -> LedgerMath.expense(balances, draft.amountPoisha)
                    else -> LedgerMath.transfer(balances, draft.amountPoisha, draft.sourceFundId, draft.destinationFundId)
                }
                if (currentUid() != draft.uid) throw LedgerIdentityChanged()
                val marker = mapOf("ledgerOpId" to draft.id, "ledgerOpKind" to draft.kind)
                tx.set(operation, record)
                tx.update(fund, marker + mapOf("balancePoisha" to next.fund, "balance" to LedgerMath.taka(next.fund)))
                if (draft.kind != "transfers") {
                    tx.update(parent, marker + mapOf("totalBalancePoisha" to next.mosque, "totalBalance" to LedgerMath.taka(next.mosque)))
                }
                if (destination != null) {
                    val value = next.destination ?: throw LedgerUnavailableAccount()
                    tx.update(destination, marker + mapOf("balancePoisha" to value, "balance" to LedgerMath.taka(value)))
                }
                if (donor != null) {
                    val value = next.donor ?: throw LedgerUnavailableAccount()
                    tx.update(donor, marker + mapOf("totalDonatedPoisha" to value, "totalDonated" to LedgerMath.taka(value)))
                }
            }
        }.await()
    }

    private fun storedMoney(doc: DocumentSnapshot, takaField: String, poishaField: String): Long {
        // Refuse legacy/unreconciled or inconsistent balances; do not invent opening values.
        val n = doc.get(poishaField) as? Long ?: throw LedgerMigrationRequired()
        val taka = doc.get(takaField) as? Number ?: throw LedgerMigrationRequired()
        if (n < 0 || n > LedgerMath.MAX_POISHA || taka.toDouble() != LedgerMath.taka(n)) throw LedgerMigrationRequired()
        return n
    }

    private fun record(d: PrototypeLedgerDraft): Map<String, Any> {
        val base = mapOf<String, Any>("mosqueId" to d.mosqueId, "amountPoisha" to d.amountPoisha,
            "amount" to LedgerMath.taka(d.amountPoisha), "date" to d.date, "note" to d.note,
            "createdBy" to d.uid, "createdAt" to d.createdAt)
        return base + when (d.kind) {
            "contributions" -> mapOf("accountId" to d.sourceFundId, "donorId" to d.donorId,
                "donorName" to d.donorName, "receiptNo" to d.receiptNo)
            "expenses" -> mapOf("accountId" to d.sourceFundId, "title" to d.title, "category" to d.category,
                "paidTo" to d.paidTo, "receiptNo" to d.receiptNo, "status" to "approved", "approvedBy" to "")
            else -> mapOf("fromAccountId" to d.sourceFundId, "toAccountId" to d.destinationFundId)
        }
    }
}
internal fun validatePrototypeDraft(d: PrototypeLedgerDraft) {
    fun id(s: String) = s.isNotBlank() && s.length <= 1500 && !s.contains('/')
    fun text(s: String) = s.length <= 1000
    require(id(d.id) && id(d.uid) && id(d.mosqueId) && id(d.sourceFundId))
    require(d.kind in setOf("contributions", "expenses", "transfers"))
    require(d.amountPoisha in 1..LedgerMath.MAX_POISHA)
    require(listOf(d.note, d.receiptNo, d.donorName, d.title, d.category, d.paidTo).all(::text))
    require(d.createdAt >= 0 && runCatching { LocalDate.parse(d.date).toString() == d.date }.getOrDefault(false))
    when (d.kind) {
        "contributions" -> require(d.donorName.isNotBlank() && (d.donorId.isEmpty() || id(d.donorId)))
        "expenses" -> require(d.title.isNotBlank())
        "transfers" -> require(id(d.destinationFundId) && d.destinationFundId != d.sourceFundId)
    }
}
class LedgerIdentityChanged : IllegalStateException("Signed-in account changed")
class LedgerMigrationRequired : IllegalStateException("Ledger requires reconciliation and migration")
class LedgerOperationConflict : IllegalStateException("Ledger operation ID already exists with different content")
class LedgerUnavailableAccount : IllegalStateException("Mosque, fund or donor is unavailable")
