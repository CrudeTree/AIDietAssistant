package com.matchpoint.myaidietapp.data

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Lightweight review prompt state machine (local-only).
 *
 * Rules (per user request):
 * - Show prompt once user has >=8 food items OR >=3 saved recipes.
 * - Buttons: Leave review / Later / No thanks
 * - Later: prompt again only after 7 days have passed AND at least 1 successful chat response occurred
 *   (a chat response that did NOT hit daily limit / 429).
 * - No thanks: never prompt again.
 * - Leave review: open Play Store and never prompt again.
 */
class ReviewPromptManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordSuccessfulChat(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SUCCESS_CHAT_AT, nowMillis).apply()
    }

    fun shouldShowPrompt(
        foodItemCount: Int,
        savedRecipeCount: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (prefs.getBoolean(KEY_DISABLED, false)) return false

        val thresholdMet = foodItemCount >= 8 || savedRecipeCount >= 3
        if (!thresholdMet) return false

        // If user deferred, only show after snooze expires AND we have a successful chat after snooze.
        val snoozeUntil = prefs.getLong(KEY_SNOOZE_UNTIL, 0L)
        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS_CHAT_AT, 0L)
        if (snoozeUntil > 0L) {
            if (nowMillis < snoozeUntil) return false
            if (lastSuccess < snoozeUntil) return false
        }

        // Don't spam: show at most once until the user taps Later (which sets snoozeUntil).
        val lastShownAt = prefs.getLong(KEY_LAST_SHOWN_AT, 0L)
        if (lastShownAt > 0L && snoozeUntil <= 0L) return false

        // If we were snoozed and conditions are satisfied, allow showing again.
        return true
    }

    fun markShown(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SHOWN_AT, nowMillis).apply()
    }

    fun deferOneWeek(nowMillis: Long = System.currentTimeMillis()) {
        val snoozeUntil = nowMillis + TimeUnit.DAYS.toMillis(7)
        prefs.edit()
            .putLong(KEY_SNOOZE_UNTIL, snoozeUntil)
            // allow it to re-show after snooze + successful chat
            .putLong(KEY_LAST_SHOWN_AT, 0L)
            .apply()
    }

    fun disableForever() {
        prefs.edit()
            .putBoolean(KEY_DISABLED, true)
            .apply()
    }

    fun clearSnoozeIfAny() {
        if (prefs.getLong(KEY_SNOOZE_UNTIL, 0L) != 0L) {
            prefs.edit().putLong(KEY_SNOOZE_UNTIL, 0L).apply()
        }
    }

    companion object {
        private const val PREFS = "review_prompt_prefs"
        private const val KEY_DISABLED = "disabled"
        private const val KEY_LAST_SHOWN_AT = "last_shown_at"
        private const val KEY_SNOOZE_UNTIL = "snooze_until"
        private const val KEY_LAST_SUCCESS_CHAT_AT = "last_success_chat_at"
    }
}

