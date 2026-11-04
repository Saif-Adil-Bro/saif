package com.saif.mosque.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.repository.AuthRepository
import com.saif.mosque.data.repository.DashboardRepository
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.data.repository.PlatformRepository

/**
 * Manual dependency container.
 *
 * [secondaryAuth] is a second Firebase app instance used to create new users
 * (mosque admin / committee member) without signing out the current user.
 */
class AppContainer(val context: Context) {

    val session = SessionManager(context)

    val auth: FirebaseAuth = FirebaseAuth.getInstance()

    /** Secondary app for creating other users' Auth accounts (spec 1.5, members). */
    val secondaryAuth: FirebaseAuth by lazy {
        val app = try {
            FirebaseApp.getInstance("secondary")
        } catch (e: IllegalStateException) {
            FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, "secondary")
            FirebaseApp.getInstance("secondary")
        }
        FirebaseAuth.getInstance(app)
    }

    val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true) // offline cache (replaces Room)
                .build()
        }
    }

    val authRepository = AuthRepository(auth, secondaryAuth, db, session)
    val platformRepository = PlatformRepository(db, secondaryAuth, session, context)
    val ledgerRepository = LedgerRepository(db, session)
    val dashboardRepository = DashboardRepository(db, session)
}
