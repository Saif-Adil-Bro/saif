package com.saif.mosque.util

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Snapshot/auth-state flows implemented with callbackFlow.
 * (The old firestore-ktx `snapshotFlow` / auth-ktx `authStateFlow` helpers are
 * no longer shipped in recent Firebase SDK versions, so we define our own —
 * same semantics: listener attached on collection, removed on cancellation.)
 */

fun Query.snapshotsFlow(): Flow<QuerySnapshot> = callbackFlow {
    val reg = addSnapshotListener { snap, err ->
        if (err != null) {
            close(err)
            return@addSnapshotListener
        }
        if (snap != null) trySend(snap)
    }
    awaitClose { reg.remove() }
}

fun DocumentReference.snapshotsFlow(): Flow<DocumentSnapshot> = callbackFlow {
    val reg = addSnapshotListener { snap, err ->
        if (err != null) {
            close(err)
            return@addSnapshotListener
        }
        if (snap != null) trySend(snap)
    }
    awaitClose { reg.remove() }
}

fun FirebaseAuth.authStateChangesFlow(): Flow<FirebaseUser?> = callbackFlow {
    trySend(currentUser)
    val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}
