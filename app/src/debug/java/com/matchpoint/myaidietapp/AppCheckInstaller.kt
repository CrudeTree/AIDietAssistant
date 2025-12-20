package com.matchpoint.myaidietapp

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug build: use the Debug App Check provider (token is shown in Logcat).
 */
object AppCheckInstaller {
    fun install() {
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}


