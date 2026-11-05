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
class PlatformRepository(
    private val db: FirebaseFirestore,
    private val context: android.content.Context? = null
) {

    fun observeMosques(): Flow<List<Mosque>> =
        db.collection("mosques").orderBy("name")
            .snapshotsFlow()
            .map { it.toObjects(Mosque::class.java) }

    /**
     * Provision a mosque and first admin. Uses the trusted callable backend when available
     * (Blaze) and falls back to Spark-compatible isolated Auth + rules-validated atomic batch
     * when Cloud Functions are not deployed.
     */
    suspend fun createMosque(
        mosque: Mosque,
        adminName: String,
        adminEmail: String,
        adminPhone: String,
        adminPassword: String?
    ): Result<CreatedMosque> {
        val tempPassword = adminPassword?.takeIf { it.isNotBlank() }
            ?: AuthRepository.generateTempPassword()
        val normalizedEmail = adminEmail.trim().lowercase(java.util.Locale.ROOT)

        return try {
            val result = com.google.firebase.functions.FirebaseFunctions.getInstance()
                .getHttpsCallable("mutate").call(mapOf(
                    "operation" to "createMosque", "value" to callableMap(mosque),
                    "admin" to mapOf("name" to adminName.trim(), "email" to normalizedEmail,
                        "phone" to adminPhone.trim(), "password" to tempPassword)
                )).await().getData() as Map<*, *>
            Result.success(CreatedMosque(mosque.copy(id = result["id"] as String),
                normalizedEmail, tempPassword))
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val code = (ex as? com.google.firebase.functions.FirebaseFunctionsException)?.code
            val shouldFallback = context != null && (code == null || code in setOf(
                com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNIMPLEMENTED,
                com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
                com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL
            ))
            if (!shouldFallback) return Result.failure(ex)
            createMosqueSpark(mosque, adminName.trim(), normalizedEmail, adminPhone.trim(), tempPassword)
        }
    }

    private suspend fun createMosqueSpark(
        mosque: Mosque,
        adminName: String,
        normalizedEmail: String,
        adminPhone: String,
        tempPassword: String
    ): Result<CreatedMosque> {
        val ctx = context ?: return Result.failure(IllegalStateException("Context required"))
        return try {
            val secondaryApp = com.saif.mosque.data.invitations.PrototypeOnboardingEnvironment.get(ctx)
            val secondaryAuth = com.google.firebase.auth.FirebaseAuth.getInstance(secondaryApp)
            secondaryAuth.signOut()
            val authResult = try {
                secondaryAuth.createUserWithEmailAndPassword(normalizedEmail, tempPassword).await()
            } catch (collision: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                secondaryAuth.signInWithEmailAndPassword(normalizedEmail, tempPassword).await()
            }
            val adminUid = authResult.user?.uid ?: throw IllegalStateException("Admin user creation failed")
            secondaryAuth.signOut()

            val now = System.currentTimeMillis()
            val mosqueRef = db.collection("mosques").document()
            val adminRef = db.collection("users").document(adminUid)
            val mosqueData = mutableMapOf<String, Any?>(
                "name" to mosque.name.trim(),
                "nameEn" to mosque.nameEn.trim(),
                "address" to mosque.address.trim(),
                "district" to mosque.district.trim(),
                "upazila" to mosque.upazila.trim(),
                "phone" to mosque.phone.trim(),
                "email" to mosque.email.trim(),
                "establishedYear" to mosque.establishedYear,
                "registrationNo" to mosque.registrationNo.trim(),
                "active" to true,
                "createdAt" to now,
                "totalBalance" to 0,
                "totalBalancePoisha" to 0L,
                "ledgerVersion" to 2L,
                "memberCount" to 1L,
                "fundCount" to 0L,
                "donorCount" to 0L,
                "firstAdminUid" to adminUid
            )
            val adminData = mapOf(
                "name" to adminName,
                "email" to normalizedEmail,
                "phone" to adminPhone,
                "role" to "ADMIN",
                "mosqueId" to mosqueRef.id,
                "designation" to "প্রশাসক",
                "active" to true,
                "createdAt" to now
            )
            db.batch().set(mosqueRef, mosqueData).set(adminRef, adminData).commit().await()

            val starterFunds = listOf(
                "GEN" to "সাধারণ তহবিল",
                "CHD" to "চাঁদা তহবিল",
                "CON" to "নির্মাণ তহবিল",
                "WAK" to "ওয়াকফ তহবিল",
                "UTL" to "ইউটিলিটি তহবিল"
            )
            for ((code, fundName) in starterFunds) {
                val fundRef = mosqueRef.collection("funds").document()
                db.batch().set(fundRef, mapOf(
                    "mosqueId" to mosqueRef.id,
                    "code" to code,
                    "name" to fundName,
                    "nameEn" to "",
                    "description" to "",
                    "active" to true,
                    "balance" to 0,
                    "balancePoisha" to 0L
                )).update(mosqueRef, mapOf(
                    "fundCount" to com.google.firebase.firestore.FieldValue.increment(1),
                    "directoryOpId" to fundRef.id,
                    "directoryOpKind" to "funds"
                )).commit().await()
            }

            Result.success(
                CreatedMosque(
                    mosque.copy(id = mosqueRef.id, active = true, fundCount = starterFunds.size, memberCount = 1),
                    normalizedEmail,
                    tempPassword
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
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

