package com.matchpoint.myaidietapp.billing

import android.app.Activity
import com.matchpoint.myaidietapp.model.SubscriptionTier
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.getCustomerInfoWith
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchasePackageWith
import com.revenuecat.purchases.restorePurchasesWith
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class RevenueCatEvent {
    data class Error(val message: String) : RevenueCatEvent()
    data class TierUpdated(val tier: SubscriptionTier) : RevenueCatEvent()
}

/**
 * Thin wrapper around RevenueCat SDK for use from Compose screens.
 *
 * Entitlements expected in RevenueCat dashboard:
 * - "basic"   -> maps to SubscriptionTier.REGULAR
 * - "premium" -> maps to SubscriptionTier.PRO
 */
class RevenueCatRepository {

    private val _offerings = MutableStateFlow<Offerings?>(null)
    val offerings = _offerings.asStateFlow()

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo = _customerInfo.asStateFlow()

    private val _events = MutableSharedFlow<RevenueCatEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private fun purchasesOrNull(): Purchases? {
        return try {
            Purchases.sharedInstance
        } catch (_: UninitializedPropertyAccessException) {
            null
        } catch (_: IllegalStateException) {
            // Defensive: some SDK versions throw IllegalStateException when not configured.
            null
        }
    }

    fun refresh() {
        val p = purchasesOrNull()
        if (p == null) {
            _events.tryEmit(
                RevenueCatEvent.Error(
                    "Subscriptions unavailable: RevenueCat is not configured on this build (missing REVENUECAT_API_KEY)."
                )
            )
            return
        }

        p.getOfferingsWith(
            onError = { err -> _events.tryEmit(RevenueCatEvent.Error(err.safeMessage())) },
            onSuccess = { o -> _offerings.value = o }
        )
        p.getCustomerInfoWith(
            onError = { err -> _events.tryEmit(RevenueCatEvent.Error(err.safeMessage())) },
            onSuccess = { info ->
                _customerInfo.value = info
                _events.tryEmit(RevenueCatEvent.TierUpdated(tierFrom(info)))
            }
        )
    }

    fun purchase(activity: Activity, pkg: Package) {
        val p = purchasesOrNull()
        if (p == null) {
            _events.tryEmit(
                RevenueCatEvent.Error(
                    "Subscriptions unavailable: RevenueCat is not configured on this build (missing REVENUECAT_API_KEY)."
                )
            )
            return
        }

        p.purchasePackageWith(
            activity = activity,
            packageToPurchase = pkg,
            onError = { err, userCancelled ->
                if (!userCancelled) _events.tryEmit(RevenueCatEvent.Error(err.safeMessage()))
            },
            onSuccess = { _, info ->
                _customerInfo.value = info
                _events.tryEmit(RevenueCatEvent.TierUpdated(tierFrom(info)))
            }
        )
    }

    fun restore() {
        val p = purchasesOrNull()
        if (p == null) {
            _events.tryEmit(
                RevenueCatEvent.Error(
                    "Subscriptions unavailable: RevenueCat is not configured on this build (missing REVENUECAT_API_KEY)."
                )
            )
            return
        }

        p.restorePurchasesWith(
            onError = { err -> _events.tryEmit(RevenueCatEvent.Error(err.safeMessage())) },
            onSuccess = { info ->
                _customerInfo.value = info
                _events.tryEmit(RevenueCatEvent.TierUpdated(tierFrom(info)))
            }
        )
    }

    fun tierFrom(customerInfo: CustomerInfo): SubscriptionTier {
        val active = customerInfo.entitlements.active
        return when {
            active["premium"] != null -> SubscriptionTier.PRO
            active["basic"] != null -> SubscriptionTier.REGULAR
            else -> SubscriptionTier.FREE
        }
    }

    fun currentOffering(): Offering? = offerings.value?.current
}

private fun PurchasesError.safeMessage(): String =
    this.message.takeIf { it.isNotBlank() } ?: "RevenueCat error"
