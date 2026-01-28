package com.matchpoint.myaidietapp

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug build: use the Debug App Check provider (token is shown in Logcat).
 */
object AppCheckInstaller {
    fun install(context: Context) {
        // Make the Debug App Check *secret* deterministic so it does NOT change on uninstall/reinstall.
        // This avoids repeatedly re-registering tokens in Firebase Console during dev/testing.
        //
        // IMPORTANT: This must be stored exactly where Firebase's DebugAppCheckProvider reads it:
        // SharedPreferences name: "com.google.firebase.appcheck.debug.store.<persistenceKey>"
        // Key: "com.google.firebase.appcheck.debug.DEBUG_SECRET"
        val debugSecret = stableDebugSecret()
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefsName = "com.google.firebase.appcheck.debug.store.$persistenceKey"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val key = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
        val existing = prefs.getString(key, null)
        if (existing != debugSecret) {
            // Force-set it so we don't get stuck using an older secret saved on device.
            prefs.edit().putString(key, debugSecret).apply()
        }
        Log.w("AppCheck", "AppCheck debug persistenceKey=$persistenceKey prefs=$prefsName")
        Log.w("AppCheck", "AppCheck debug secret (existing=$existing)")
        Log.w("AppCheck", "Enter this debug secret into Firebase Console -> App Check -> Manage debug tokens: $debugSecret")

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

    private fun stableDebugSecret(): String {
        // Firebase normally generates a UUID here; we keep that format to be safe.
        // This must be stable across reinstalls so you only add it once in the console.
        // Must be a *version 4* UUID (Firebase Console validates this format).
        // - version nibble (3rd group) is '4'
        // - variant (4th group) is '8' (10xx)
        return "00000000-0000-4000-8000-000000000001"
    }
}






