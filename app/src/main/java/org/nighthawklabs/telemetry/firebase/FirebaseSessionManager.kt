package org.nighthawklabs.telemetry.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

private const val TAG = "FirebaseSessionManager"

class FirebaseSessionManager(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    ) {

    suspend fun ensureSignedIn(): FirebaseUser? {
        val current = auth.currentUser
        if (current != null) {
            return current
        }
        return try {
            val result = auth.signInAnonymously().await()
            result.user
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed", e)
            null
        }
    }

    val currentUserId: String?
        get() = auth.currentUser?.uid
}

