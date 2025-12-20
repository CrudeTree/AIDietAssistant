package com.matchpoint.myaidietapp.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    fun currentUid(): String? = auth.currentUser?.uid
    fun currentEmail(): String? = auth.currentUser?.email

    suspend fun signIn(email: String, password: String): String {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        return result.user?.uid ?: error("Sign-in failed")
    }

    suspend fun createAccount(email: String, password: String): String {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        return result.user?.uid ?: error("Account creation failed")
    }

    fun signOut() {
        auth.signOut()
    }

    /**
     * Re-authenticates the currently signed-in user using their password (Email/Password auth),
     * then deletes the Firebase Auth user.
     *
     * Note: Firebase requires recent login to delete an account.
     */
    suspend fun reauthenticateAndDeleteCurrentUser(password: String) {
        val user = auth.currentUser ?: error("Not signed in")
        val email = user.email ?: error("No email associated with this account")
        val cred = EmailAuthProvider.getCredential(email, password)
        user.reauthenticate(cred).await()
        user.delete().await()
        auth.signOut()
    }
}


