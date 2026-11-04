package com.saif.mosque.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.saif.mosque.util.snapshotsFlow
import com.saif.mosque.data.model.CreatedMosque
import com.saif.mosque.data.model.Mosque
import com.saif.mosque.util.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Platform console (spec module 1): mosque list with cached stats, search,
 * activate/deactivate, and server-side provisioning with a one-time credential result.
 */
class PlatformRepository(private val db: FirebaseFirestore) {

    fun observeMosques(): Flow<List<Mosque>> =
        db.collection("mosques").orderBy("name")
            .snapshotsFlow()
            .map { it.toObjects(Mosque::class.java) }

    /** Provision a mosque and admin via the trusted backend; returns one-time credentials. */
    suspend fun createMosque(
        mosque: Mosque,
        adminName: String,
        adminEmail: String,
        adminPhone: String,
        adminPassword: String?
    ): Result<CreatedMosque> {
        val tempPassword = adminPassword?.takeIf { it.isNotBlank() }
            ?: AuthRepository.generateTempPassword()

        return try {
            val result = com.google.firebase.functions.FirebaseFunctions.getInstance()
                .getHttpsCallable("mutate").call(mapOf(
                    "operation" to "createMosque", "value" to callableMap(mosque),
                    "admin" to mapOf("name" to adminName, "email" to adminEmail,
                        "phone" to adminPhone, "password" to tempPassword)
                )).await().data as Map<*, *>
            Result.success(CreatedMosque(mosque.copy(id = result["id"] as String),
                adminEmail.trim().lowercase(), tempPassword))
        } catch (ex: Exception) { Result.failure(ex) }
    }

    suspend fun updateMosque(mosque: Mosque) {
        db.collection("mosques").document(mosque.id).update(mapOf(
            "name" to mosque.name, "nameEn" to mosque.nameEn, "address" to mosque.address,
            "district" to mosque.district, "upazila" to mosque.upazila, "phone" to mosque.phone,
            "email" to mosque.email, "establishedYear" to mosque.establishedYear,
            "registrationNo" to mosque.registrationNo
        )).await()
    }

    /** Spec 1.11: deactivate blocks logins but never deletes data. 1.12: demo mosque protected. */
    suspend fun setActive(mosque: Mosque, active: Boolean): Result<Unit> {
        if (!active && mosque.id == Config.DEMO_MOSQUE_ID) {
            return Result.failure(DemoMosqueProtectedException())
        }
        return try {
            db.collection("mosques").document(mosque.id).update("active", active).await()
            Result.success(Unit)
        } catch (ex: Exception) { Result.failure(ex) }
    }
}

class DemoMosqueProtectedException : Exception("demo mosque is protected")
