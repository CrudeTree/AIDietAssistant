package com.matchpoint.myaidietapp.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * Simple local-only daily quota tracker (per device).
 * This is NOT tamper-proof; it’s meant for MVP before real accounts + server enforcement.
 */
class DailyQuotaManager(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Status(
        val used: Int,
        val limit: Int
    ) {
        val remaining: Int get() = (limit - used).coerceAtLeast(0)
        val isOverLimit: Boolean get() = used >= limit
    }

    fun getStatus(tier: com.matchpoint.myaidietapp.model.SubscriptionTier): Status {
        val today = LocalDate.now(zoneId).toString()
        val savedDay = prefs.getString(KEY_DAY, null)
        val used = if (savedDay == today) prefs.getInt(KEY_USED, 0) else 0
        val limit = when (tier) {
            com.matchpoint.myaidietapp.model.SubscriptionTier.PRO -> 150
            com.matchpoint.myaidietapp.model.SubscriptionTier.REGULAR -> 50
            com.matchpoint.myaidietapp.model.SubscriptionTier.FREE -> 5
        }
        return Status(used = used, limit = limit)
    }

    /**
     * Attempts to consume 1 chat credit for today.
     * Returns updated status after consumption attempt.
     */
    fun tryConsume(tier: com.matchpoint.myaidietapp.model.SubscriptionTier): Status {
        val today = LocalDate.now(zoneId).toString()
        val savedDay = prefs.getString(KEY_DAY, null)
        val used0 = if (savedDay == today) prefs.getInt(KEY_USED, 0) else 0
        val limit = when (tier) {
            com.matchpoint.myaidietapp.model.SubscriptionTier.PRO -> 150
            com.matchpoint.myaidietapp.model.SubscriptionTier.REGULAR -> 50
            com.matchpoint.myaidietapp.model.SubscriptionTier.FREE -> 5
        }
        if (used0 >= limit) return Status(used = used0, limit = limit)

        val used1 = used0 + 1
        prefs.edit()
            .putString(KEY_DAY, today)
            .putInt(KEY_USED, used1)
            .apply()
        return Status(used = used1, limit = limit)
    }

    fun resetForTesting() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "ai_food_coach_daily_quota"
        private const val KEY_DAY = "day"
        private const val KEY_USED = "used"
    }
}


