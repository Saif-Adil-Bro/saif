package com.saif.mosque.data.ledger

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Test-only correction request. expectedBefore must be the complete server record,
 * not a partial UI model. Persist the exact request before retrying a write. */
data class PrototypeCorrectionDraft(
    val id: String, val uid: String, val mosqueId: String, val kind: String,
    val targetId: String, val action: String, val expectedBefore: Map<String, Any>,
    val changes: Map<String, Any> = emptyMap()
)

/**
 * Isolated prototype; production rules reject these writes. It is deliberately
 * not connected to screens or the outbox. The reciprocal rules must validate
 * every write; a client transaction alone cannot secure aggregate balances.
 */
class PrototypeCorrectionWriter(private val db: FirebaseFirestore, private val currentUid: () -> String?) {
    suspend fun commit(draft: PrototypeCorrectionDraft) {
        fun validId(id: String) = id.isNotEmpty() && id.length <= 1500 && !id.contains('/')
        require(validId(draft.id) && validId(draft.uid) && validId(draft.mosqueId) && validId(draft.targetId))
        require(draft.kind in setOf("contributions", "expenses", "transfers"))
        require(draft.action in setOf("update", "delete"))
        require(draft.expectedBefore["mosqueId"] == draft.mosqueId && draft.expectedBefore["voided"] != true)
        val editable = when (draft.kind) {
            "contributions" -> setOf("amount", "amountPoisha", "accountId", "donorId", "donorName", "date", "note", "receiptNo")
            "expenses" -> setOf("amount", "amountPoisha", "accountId", "title", "category", "paidTo", "date", "note", "receiptNo")
            else -> emptySet()
        }
        require(if (draft.action == "delete") draft.changes.isEmpty() else
            draft.kind != "transfers" && editable.containsAll(draft.changes.keys))
        if (currentUid() != draft.uid) throw LedgerIdentityChanged()
        val parent = db.collection("mosques").document(draft.mosqueId)
        val receipt = parent.collection("ledgerMutations").document(draft.id)
        val target = parent.collection(draft.kind).document(draft.targetId)
        val after = draft.expectedBefore + draft.changes + mapOf("ledgerMutationId" to draft.id) +
            if (draft.action == "delete") mapOf("voided" to true) else emptyMap()
        val old = entry(draft.kind, draft.expectedBefore)
        val next = if (draft.action == "delete") null else entry(draft.kind, after)
        val delta = LedgerCorrectionMath.difference(old, next)
        val receiptData = mapOf<String, Any>("uid" to draft.uid, "kind" to draft.kind, "targetId" to draft.targetId,
            "action" to draft.action, "oldAmountPoisha" to old.amount, "newAmountPoisha" to (next?.amount ?: 0L),
            "before" to draft.expectedBefore, "after" to after)
        db.runTransaction { tx ->
            if (currentUid() != draft.uid) throw LedgerIdentityChanged()
            val existing = tx.get(receipt)
            if (existing.exists()) {
                if (existing.data != receiptData) throw LedgerOperationConflict()
            } else {
                val record = tx.get(target)
                if (!record.exists() || record.data != draft.expectedBefore) throw LedgerOperationConflict()
                val mosque = tx.get(parent)
                if (mosque.getLong("ledgerVersion") != 2L) throw LedgerVersionPending()
                if (mosque.getBoolean("active") != true) throw LedgerUnavailableAccount()
                val funds = delta.funds.keys.associateWith { tx.get(parent.collection("funds").document(it)) }
                val donors = delta.donors.keys.associateWith { tx.get(parent.collection("donors").document(it)) }
                val newFund = when (next) {
                    is LedgerCorrectionMath.Contribution -> next.fund
                    is LedgerCorrectionMath.Expense -> next.fund
                    else -> null
                }
                val oldFund = when (old) {
                    is LedgerCorrectionMath.Contribution -> old.fund
                    is LedgerCorrectionMath.Expense -> old.fund
                    else -> null
                }
                for ((id, snap) in funds) {
                    if (snap.getString("mosqueId") != draft.mosqueId ||
                        (id == newFund && id != oldFund && snap.getBoolean("active") != true)) throw LedgerUnavailableAccount()
                }
                for ((id, snap) in donors) {
                    val newDonor = (next as? LedgerCorrectionMath.Contribution)?.donor
                    val oldDonor = (old as? LedgerCorrectionMath.Contribution)?.donor
                    if (snap.getString("mosqueId") != draft.mosqueId ||
                        (id == newDonor && id != oldDonor && snap.getBoolean("active") != true) ||
                        (id == newDonor && snap.getString("name") != after["donorName"])) throw LedgerUnavailableAccount()
                }
                val totals = LedgerCorrectionMath.apply(delta,
                    funds.mapValues { storedMoney(it.value, "balance", "balancePoisha") },
                    donors.mapValues { storedMoney(it.value, "totalDonated", "totalDonatedPoisha") },
                    storedMoney(mosque, "totalBalance", "totalBalancePoisha"))
                if (currentUid() != draft.uid) throw LedgerIdentityChanged()
                val marker = mapOf("ledgerOpId" to draft.id, "ledgerOpKind" to "ledgerMutations")
                tx.set(receipt, receiptData)
                tx.set(target, after)
                for ((id, value) in totals.funds) tx.update(funds.getValue(id).reference, marker + mapOf("balancePoisha" to value, "balance" to LedgerMath.taka(value)))
                for ((id, value) in totals.donors) tx.update(donors.getValue(id).reference, marker + mapOf("totalDonatedPoisha" to value, "totalDonated" to LedgerMath.taka(value)))
                if (delta.mosque != 0L) tx.update(parent, marker + mapOf("totalBalancePoisha" to totals.mosque, "totalBalance" to LedgerMath.taka(totals.mosque)))
            }
        }.await()
    }

    private fun entry(kind: String, data: Map<String, Any>): LedgerCorrectionMath.Entry {
        val amount = LedgerMath.poisha((data["amount"] as? Number)?.toDouble() ?: throw InvalidLedgerAmount(), positive = true)
        val integer = data["amountPoisha"]
        if (integer != null && (integer !is Long || integer != amount)) throw InvalidLedgerAmount()
        fun field(name: String) = data[name] as? String ?: throw InvalidLedgerAccount()
        return when (kind) {
            "contributions" -> LedgerCorrectionMath.Contribution(amount, field("accountId"), field("donorId"))
            "expenses" -> LedgerCorrectionMath.Expense(amount, field("accountId"))
            else -> LedgerCorrectionMath.Transfer(amount, field("fromAccountId"), field("toAccountId"))
        }
    }
    private fun storedMoney(doc: DocumentSnapshot, amount: String, integer: String): Long {
        val value = doc.get(integer) as? Long ?: throw LedgerMigrationRequired()
        val taka = doc.get(amount) as? Number ?: throw LedgerMigrationRequired()
        if (value < 0 || value > LedgerMath.MAX_POISHA || taka.toDouble() != LedgerMath.taka(value)) throw LedgerMigrationRequired()
        return value
    }
}
