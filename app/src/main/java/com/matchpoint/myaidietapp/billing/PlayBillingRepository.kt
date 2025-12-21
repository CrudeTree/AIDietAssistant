package com.matchpoint.myaidietapp.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class BillingEvent {
    data class PurchaseCompleted(val purchase: Purchase, val productId: String) : BillingEvent()
    data class Error(val message: String) : BillingEvent()
}

class PlayBillingRepository(
    appContext: Context,
    private val scope: CoroutineScope
) : PurchasesUpdatedListener {

    private val context = appContext.applicationContext

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _productDetails = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetails: StateFlow<Map<String, ProductDetails>> = _productDetails.asStateFlow()

    private val _events = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun connect() {
        if (billingClient.isReady) {
            _isReady.value = true
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                _isReady.value = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                if (!_isReady.value) {
                    _events.tryEmit(BillingEvent.Error("Billing setup failed: ${billingResult.debugMessage}"))
                }
            }

            override fun onBillingServiceDisconnected() {
                _isReady.value = false
            }
        })
    }

    fun queryProducts(productIds: List<String>) {
        if (!_isReady.value) {
            connect()
        }
        val products = productIds.distinct().map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, detailsList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                _events.tryEmit(BillingEvent.Error("Failed to load products: ${billingResult.debugMessage}"))
                return@queryProductDetailsAsync
            }
            val map = detailsList.associateBy { it.productId }
            _productDetails.value = _productDetails.value + map
        }
    }

    fun launchSubscriptionPurchase(activity: Activity, productDetails: ProductDetails) {
        val offer = productDetails.subscriptionOfferDetails?.firstOrNull()
        val offerToken = offer?.offerToken
        if (offerToken.isNullOrBlank()) {
            _events.tryEmit(BillingEvent.Error("No subscription offer available for ${productDetails.productId}."))
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _events.tryEmit(BillingEvent.Error("Unable to launch billing: ${result.debugMessage}"))
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                list.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                acknowledgeIfNeeded(purchase)
                                val productId = purchase.products.firstOrNull().orEmpty()
                                _events.emit(BillingEvent.PurchaseCompleted(purchase, productId))
                            } catch (e: Exception) {
                                _events.emit(BillingEvent.Error(e.message ?: "Purchase acknowledgement failed."))
                            }
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // no-op
            }
            else -> {
                _events.tryEmit(BillingEvent.Error("Purchase failed: ${billingResult.debugMessage}"))
            }
        }
    }

    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        suspendCancellableCoroutine { cont ->
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(Unit)
                } else {
                    cont.resumeWithException(
                        IllegalStateException("Acknowledge failed: ${result.debugMessage}")
                    )
                }
            }
        }
    }
}


