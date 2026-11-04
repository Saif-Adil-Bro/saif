package com.saif.mosque.data.outbox

/** Decisions are rechecked on the server; a cached role alone cannot authorize a sync. */
object DirectorySyncPolicy {
    fun permitted(kind: String, role: String?, active: Boolean, profileMosque: String?, mosqueId: String): Boolean =
        active && profileMosque == mosqueId && when (kind) {
            "funds" -> role in setOf("ADMIN", "SECRETARY", "TREASURER")
            "donors" -> role in setOf("ADMIN", "SECRETARY", "TREASURER", "CASHIER")
            else -> false
        }

    fun ownsReceipt(id: String, uid: String, markerId: String?, markerOwner: String?, pending: Boolean): Boolean =
        !pending && markerId == id && markerOwner == uid
}
