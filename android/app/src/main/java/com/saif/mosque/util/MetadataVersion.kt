package com.saif.mosque.util

import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.Donor
import com.saif.mosque.data.model.Fund

/** Editable metadata only: unrelated financial aggregates may legitimately change. */
object MetadataVersion {
    fun sameFund(a: Fund, b: Fund): Boolean =
        a.name == b.name && a.code == b.code && a.nameEn == b.nameEn &&
            a.description == b.description && a.active == b.active

    fun sameDonor(a: Donor, b: Donor): Boolean =
        a.name == b.name && a.phone == b.phone && a.address == b.address && a.active == b.active

    fun sameMember(a: AppUser, b: AppUser): Boolean =
        a.mosqueId == b.mosqueId && a.name == b.name && a.phone == b.phone &&
            a.designation == b.designation && a.role == b.role && a.active == b.active
}
