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
import com.revenuecat.purchases.logInWith
import com.revenuecat.purchases.logOutWith
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

    /**
     * Link RevenueCat's current (possibly anonymous/device) user to a stable app user id.
     *
     * We use Firebase Auth uid as the canonical app user id so:
     * - client UI reflects the same subscription the server verifies
     * - subscription tier can be safely enforced server-side
     */
    fun logIn(appUserId: String) {
        val p = purchasesOrNull()
        if (p == null) {
            _events.tryEmit(
                RevenueCatEvent.Error(
                    "Subscriptions unavailable: RevenueCat is not configured on this build (missing REVENUECAT_API_KEY)."
                )
            )
            return
        }

        p.logInWith(
            appUserID = appUserId,
            onError = { err -> _events.tryEmit(RevenueCatEvent.Error(err.safeMessage())) },
            // Signature in Purchases SDK 9.x: (CustomerInfo, created: Boolean)
            onSuccess = { info, _ ->
                _customerInfo.value = info
                _events.tryEmit(RevenueCatEvent.TierUpdated(tierFrom(info)))

                // Important on Android: after switching users, pull any existing Play Store
                // subscriptions into this RevenueCat app user. This fixes cases where Google Play
                // says "already subscribed" but CustomerInfo shows Free.
                restore()
            }
        )
    }

    fun logOut() {
        val p = purchasesOrNull() ?: return
        p.logOutWith(
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
        val activeEntitlements = customerInfo.entitlements.active
        val activeKeys = activeEntitlements.keys.map { it.lowercase() }

        // Primary: map entitlements. We *prefer* entitlements, but we shouldn't depend on exact IDs
        // ("basic"/"premium") because dashboards often evolve (e.g. "basic_plan", "premium_v2", etc.).
        val hasPremiumEntitlement = activeKeys.any { k ->
            k == "premium" || k.contains("premium") || k == "pro" || k.contains("pro")
        }
        if (hasPremiumEntitlement) return SubscriptionTier.PRO

        val hasBasicEntitlement = activeKeys.any { k ->
            k == "basic" || k.contains("basic") || k == "regular" || k.contains("regular")
        }
        if (hasBasicEntitlement) return SubscriptionTier.REGULAR

        // Fallback: infer from active subscription product IDs (store product ids).
        // This helps when entitlements were not set up correctly but products are active.
        val activeProducts = customerInfo.activeSubscriptions.map { it.lowercase() }
        val hasPremiumProduct = activeProducts.any { p ->
            p.contains("premium") || (p.contains("pro") && !p.contains("profile"))
        }
        if (hasPremiumProduct) return SubscriptionTier.PRO

        val hasBasicProduct = activeProducts.any { p ->
            p.contains("basic") || p.contains("regular")
        }
        if (hasBasicProduct) return SubscriptionTier.REGULAR

        return SubscriptionTier.FREE
    }

    fun currentOffering(): Offering? = offerings.value?.current
}

private fun PurchasesError.safeMessage(): String =
    buildString {
        // Include the SDK error code so BillingClient/Google Play errors are diagnosable.
        // Example codes: PurchaseCancelledError, StoreProblemError, StoreProductNotAvailableError, etc.
        append("RevenueCat: ")
        append(runCatching { code.name }.getOrNull() ?: "Error")

        val msg = message.takeIf { it.isNotBlank() }
        if (msg != null) {
            append(": ")
            append(msg)
        }

        // Some SDK versions expose an "underlyingErrorMessage" which can include Play Billing details.
        val underlying = runCatching {
            val f = this::class.java.methods.firstOrNull { it.name == "getUnderlyingErrorMessage" }
            (f?.invoke(this) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!underlying.isNullOrBlank() && underlying != msg) {
            append(" (")
            append(underlying)
            append(")")
        }
    }
