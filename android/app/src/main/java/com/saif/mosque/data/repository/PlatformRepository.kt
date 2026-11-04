package com.saif.mosque.data.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.snapshotFlow
import com.saif.mosque.R
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.data.model.CreatedMosque
import com.saif.mosque.data.model.Fund
import com.saif.mosque.data.model.Mosque
import com.saif.mosque.data.model.Role
import com.saif.mosque.util.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Platform console (spec module 1): mosque list with cached stats, search,
 * activate/deactivate, and atomic provisioning of a mosque + its 5 starter
 * funds + first admin (spec 1.5–1.9) with a one-time credential result.
 */
class PlatformRepository(
    private val db: FirebaseFirestore,
    private val secondaryAuth: FirebaseAuth,
    private val session: SessionManager,
    private val context: Context
) {

    fun observeMosques(): Flow<List<Mosque>> =
        db.collection("mosques").orderBy("name")
            .snapshotFlow()
            .map { snap -> snap.toObjects(Mosque::class.java) }

    /** Create mosque + starter funds + admin atomically; returns one-time credentials. */
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
            // 1) auth account for the first admin (secondary app keeps current session)
            val cred = secondaryAuth
                .createUserWithEmailAndPassword(adminEmail.trim().lowercase(), tempPassword)
                .await()
            val adminUid = cred.user!!.uid

            // 2) atomic provisioning: mosque doc + 5 starter funds + admin profile
            val mosqueRef = db.collection("mosques").document()
            val newMosque = mosque.copy(id = "", createdAt = System.currentTimeMillis())
            db.runTransaction { tx ->
                tx.set(mosqueRef, newMosque.copy(id = ""))
                Config.STARTER_FUNDS.forEach { (code, nameRes) ->
                    val fundRef = mosqueRef.collection("funds").document()
                    tx.set(fundRef, Fund(
                        mosqueId = mosqueRef.id,
                        code = code,
                        name = context.getString(nameRes),
                        active = true
                    ))
                }
                tx.set(db.collection("users").document(adminUid), AppUser(
                    id = adminUid,
                    name = adminName.trim(),
                    email = adminEmail.trim().lowercase(),
                    phone = adminPhone.trim(),
                    role = Role.ADMIN,
                    mosqueId = mosqueRef.id,
                    active = true,
                    createdAt = System.currentTimeMillis()
                ))
                tx.update(mosqueRef, mapOf("fundCount" to 5, "memberCount" to 1))
                null
            }.await()
            secondaryAuth.signOut()

            Result.success(CreatedMosque(
                mosque = newMosque.copy(id = mosqueRef.id),
                adminEmail = adminEmail.trim().lowercase(),
                temporaryPassword = tempPassword
            ))
        } catch (ex: Exception) {
            secondaryAuth.signOut()
            Result.failure(ex)
        }
    }

    suspend fun updateMosque(mosque: Mosque) {
        db.collection("mosques").document(mosque.id)
            .set(mosque.copy(id = "")).await()
    }

    /** Spec 1.11: deactivate blocks logins but never deletes data. 1.12: demo mosque protected. */
    suspend fun setActive(mosque: Mosque, active: Boolean): Result<Unit> {
        if (!active && mosque.id == Config.DEMO_MOSQUE_ID) {
            return Result.failure(DemoMosqueProtectedException())
        }
        db.collection("mosques").document(mosque.id)
            .update("active", active).await()
        return Result.success(Unit)
    }
}

class DemoMosqueProtectedException : Exception("demo mosque is protected")
