package com.matchpoint.myaidietapp.ui

import android.content.Context

/**
 * Minimal, reversible first-run activation gate.
 *
 * - We only show the First Action screen when an account was just created (pending flag set).
 * - We record whether the user has interacted with AI at least once and/or saved foods once.
 */
class FirstRunOnboardingManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markPendingAfterSignUp() {
        prefs.edit().putBoolean(KEY_PENDING, true).apply()
    }

    fun isPendingAfterSignUp(): Boolean = prefs.getBoolean(KEY_PENDING, false)

    fun consumePending() {
        prefs.edit().putBoolean(KEY_PENDING, false).apply()
    }

    fun markInteractedWithAi() {
        prefs.edit().putBoolean(KEY_INTERACTED, true).apply()
    }

    fun markSavedFoodsOnce() {
        prefs.edit().putBoolean(KEY_SAVED, true).apply()
    }

    fun hasInteractedOrSaved(): Boolean =
        prefs.getBoolean(KEY_INTERACTED, false) || prefs.getBoolean(KEY_SAVED, false)

    private companion object {
        private const val PREFS = "first_run_onboarding"
        private const val KEY_PENDING = "pending_after_signup_v1"
        private const val KEY_INTERACTED = "interacted_with_ai_v1"
        private const val KEY_SAVED = "saved_foods_once_v1"
    }
}

