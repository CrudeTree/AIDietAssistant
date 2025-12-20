package com.matchpoint.myaidietapp.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    fun currentUid(): String? = auth.currentUser?.uid

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
}


