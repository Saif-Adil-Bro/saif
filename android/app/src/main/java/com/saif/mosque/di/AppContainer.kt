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
    val ledgerQueue by lazy { com.saif.mosque.data.ledger.LedgerQueue(context, auth) }

    val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true) // offline cache (replaces Room)
                .build()
        }
    }

    val authRepository = AuthRepository(auth, db, session, context)
    val platformRepository = PlatformRepository(db, context)
    val ledgerRepository = LedgerRepository(db, session, context, auth)
    val dashboardRepository = DashboardRepository(db, session)

    val invitationOutbox by lazy { com.saif.mosque.data.invitations.PrototypeInvitationOutbox.get(context) }
    val invitationService by lazy { com.saif.mosque.data.invitations.PrototypeInvitationService(db, auth) }
    val joinCoordinator by lazy {
        val onboardingApp = com.saif.mosque.data.invitations.PrototypeOnboardingEnvironment.get(context)
        val onboardingSession = com.saif.mosque.data.invitations.prototypeOnboardingSession(onboardingApp)
        com.saif.mosque.data.invitations.PrototypeJoinCoordinator(
            com.saif.mosque.data.invitations.PrototypeJoinFirebaseGateway(onboardingSession),
            com.saif.mosque.data.invitations.PrototypeJoinPreferences(context)
        )
    }
}

