package com.saif.mosque.data.outbox

/** Decisions are rechecked on the server; a cached role alone cannot authorize a sync. */
object DirectorySyncPolicy {
    fun permitted(kind: String, role: String?, active: Boolean, profileMosque: String?, mosqueId: String): Boolean =
        active && profileMosque == mosqueId && when (kind) {
            "funds" -> role in setOf("ADMIN", "SECRETARY", "TREASURER")
            "donors" -> role in setOf("ADMIN", "SECRETARY", "TREASURER", "CASHIER")
            else -> false
        }

    /** Safe, non-sensitive reason codes. Never include profile data or raw server messages. */
    fun profileFailure(exists: Boolean, kind: String, role: String?, active: Boolean,
                       profileMosque: String?, mosqueId: String): String? = when {
        !exists -> "PROFILE_MISSING"
        !active -> "PROFILE_INACTIVE"
        profileMosque != mosqueId -> "PROFILE_TENANT"
        !permitted(kind, role, active, profileMosque, mosqueId) -> "PROFILE_ROLE"
        else -> null
    }

    fun permissionFailure(stage: String): String = when (stage) {
        "PROFILE" -> "PROFILE_DENIED"
        "MOSQUE" -> "MOSQUE_DENIED"
        "RECEIPT" -> "RECEIPT_DENIED"
        "WRITE" -> "WRITE_DENIED"
        else -> "PERMISSION"
    }

    fun ownsReceipt(id: String, uid: String, markerId: String?, markerOwner: String?, pending: Boolean): Boolean =
        !pending && markerId == id && markerOwner == uid
}
