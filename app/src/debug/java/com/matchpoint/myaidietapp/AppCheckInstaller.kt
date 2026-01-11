package com.matchpoint.myaidietapp

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug build: use the Debug App Check provider (token is shown in Logcat).
 */
object AppCheckInstaller {
    fun install() {
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())

        // Force token generation on startup so the debug token shows up in Logcat.
        // Look for tags like "FirebaseAppCheck" and "DebugAppCheckProvider", or this tag: "AppCheck".
        appCheck.getAppCheckToken(false)
            .addOnSuccessListener { token ->
                Log.d("AppCheck", "App Check token acquired (len=${token.token.length}).")
            }
            .addOnFailureListener { e ->
                Log.w("AppCheck", "Failed to fetch App Check token.", e)
            }
    }
}






