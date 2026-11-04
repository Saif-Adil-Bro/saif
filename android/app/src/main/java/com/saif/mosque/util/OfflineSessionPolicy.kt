package com.saif.mosque.util

import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Role

/** A cache never authenticates a user on its own; it must match Firebase's persisted user. */
object OfflineSessionPolicy {
    fun mayOpen(firebaseUid: String, cached: AppUser?): Boolean =
        cached != null && cached.id == firebaseUid && cached.active &&
            cached.role in Role.entries && (cached.role == Role.SUPERADMIN || !cached.mosqueId.isNullOrBlank())
}
