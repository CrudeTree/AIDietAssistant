package com.matchpoint.myaidietapp.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Local-only helper preferences.
 *
 * Requirements:
 * - Show the helper intro the very first time the user opens the app.
 * - After that, keep the helper available on every screen.
 * - User can disable the helper in Settings (hides everywhere).
 */
class HelperManager(
    context: Context,
    private val userId: String
) {
    private val prefs = context.getSharedPreferences("helper_prefs", Context.MODE_PRIVATE)

    private val enabledState = mutableStateOf(prefs.getBoolean(keyEnabled(), true))
    private val introSeenState = mutableStateOf(prefs.getBoolean(keyIntroSeen(), false))

    fun isEnabled(): Boolean = enabledState.value
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(keyEnabled(), enabled).apply()
        enabledState.value = enabled
    }

    fun shouldShowIntro(): Boolean = isEnabled() && !introSeenState.value
    fun markIntroSeen() {
        prefs.edit().putBoolean(keyIntroSeen(), true).apply()
        introSeenState.value = true
    }

    private fun safeUserKey(): String = userId.ifBlank { "signed_out" }
    private fun keyEnabled(): String = "${KEY_ENABLED}_${safeUserKey()}"
    private fun keyIntroSeen(): String = "${KEY_INTRO_SEEN}_${safeUserKey()}"

    private companion object {
        private const val KEY_ENABLED = "enabled_v1"
        private const val KEY_INTRO_SEEN = "intro_seen_v1"
    }
}

