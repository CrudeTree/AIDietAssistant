package com.matchpoint.myaidietapp.billing

import com.matchpoint.myaidietapp.model.SubscriptionTier
import com.matchpoint.myaidietapp.ui.BillingCycle

/**
 * Play Console subscription product IDs (recommended simple setup):
 * - regular_monthly, regular_yearly
 * - pro_monthly, pro_yearly
 *
 * These IDs must exactly match what you create in Google Play Console.
 */
object BillingProducts {
    const val REGULAR_MONTHLY = "regular_monthly"
    const val REGULAR_YEARLY = "regular_yearly"
    const val PRO_MONTHLY = "pro_monthly"
    const val PRO_YEARLY = "pro_yearly"

    fun productIdFor(tier: SubscriptionTier, cycle: BillingCycle): String? {
        return when (tier) {
            SubscriptionTier.FREE -> null
            SubscriptionTier.REGULAR ->
                if (cycle == BillingCycle.YEARLY) REGULAR_YEARLY else REGULAR_MONTHLY
            SubscriptionTier.PRO ->
                if (cycle == BillingCycle.YEARLY) PRO_YEARLY else PRO_MONTHLY
        }
    }

    fun tierForProductId(productId: String): SubscriptionTier? {
        return when (productId) {
            REGULAR_MONTHLY, REGULAR_YEARLY -> SubscriptionTier.REGULAR
            PRO_MONTHLY, PRO_YEARLY -> SubscriptionTier.PRO
            else -> null
        }
    }
}


