package com.saif.mosque.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.saif.mosque.data.SessionManager
import com.saif.mosque.data.repository.AuthRepository
import com.saif.mosque.data.repository.DashboardRepository
import com.saif.mosque.data.repository.LedgerRepository
import com.saif.mosque.data.repository.PlatformRepository

/** Manual dependency container. */
class AppContainer(val context: Context) {

    val session = SessionManager(context)
    val outbox = com.saif.mosque.data.outbox.DirectoryOutbox.get(context)

    val auth: FirebaseAuth = FirebaseAuth.getInstance()

    val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true) // offline cache (replaces Room)
                .build()
        }
    }

    val authRepository = AuthRepository(auth, db, session)
    val platformRepository = PlatformRepository(db)
    val ledgerRepository = LedgerRepository(db, session)
    val dashboardRepository = DashboardRepository(db, session)
}
