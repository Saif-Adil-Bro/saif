package com.saif.mosque.data.ledger

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException

/** Durable queue boundary. A scheduling error never undoes a saved draft. */
class LedgerQueue(private val context: Context, private val auth: FirebaseAuth) {
    val store = PrototypeLedgerOutbox.get(context)
    suspend fun enqueue(draft: PrototypeLedgerDraft) {
        check(auth.currentUser?.uid == draft.uid) { "Signed-in account changed" }
        store.enqueue(draft)
        try { PrototypeLedgerSync.schedule(context, draft) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* startup/periodic recovery */ }
    }
    suspend fun retry(draft: PrototypeLedgerDraft) {
        if (auth.currentUser?.uid == draft.uid && store.retry(draft.id, draft.uid, draft.mosqueId)) {
            PrototypeLedgerSync.schedule(context, draft)
        }
    }
    suspend fun discard(draft: PrototypeLedgerDraft) {
        if (auth.currentUser?.uid == draft.uid && store.discardBlocked(draft.id, draft.uid, draft.mosqueId)) {
            try { PrototypeLedgerSync.schedulePending(context, draft.uid, draft.mosqueId) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* startup/periodic recovery */ }
        }
    }
}
