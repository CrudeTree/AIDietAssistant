package com.matchpoint.myaidietapp.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.billing.BillingProducts
import com.matchpoint.myaidietapp.billing.BillingEvent
import com.matchpoint.myaidietapp.billing.PlayBillingRepository
import com.matchpoint.myaidietapp.model.SubscriptionTier
import kotlinx.coroutines.launch

@Composable
fun PaymentScreen(
    selectedTier: SubscriptionTier?,
    billingCycle: com.matchpoint.myaidietapp.ui.BillingCycle,
    onBack: () -> Unit,
    onEntitlementGranted: (SubscriptionTier) -> Unit
) {
    val tierLabel = when (selectedTier) {
        SubscriptionTier.REGULAR -> if (billingCycle == com.matchpoint.myaidietapp.ui.BillingCycle.YEARLY) "Regular — $99.99/year" else "Regular — $9.99/month"
        SubscriptionTier.PRO -> if (billingCycle == com.matchpoint.myaidietapp.ui.BillingCycle.YEARLY) "Pro — $199.99/year" else "Pro — $19.99/month"
        SubscriptionTier.FREE, null -> "Free"
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val billing = remember { PlayBillingRepository(context.applicationContext, scope) }
    val isReady by billing.isReady.collectAsState()
    val detailsMap by billing.productDetails.collectAsState()

    val productId = remember(selectedTier, billingCycle) {
        selectedTier?.let { BillingProducts.productIdFor(it, billingCycle) }
    }
    val productDetails = productId?.let { detailsMap[it] }

    fun priceLabel(): String? {
        val pd = productDetails ?: return null
        val phase = pd.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.firstOrNull()
        return phase?.formattedPrice
    }

    LaunchedEffect(productId) {
        billing.connect()
        if (!productId.isNullOrBlank()) {
            billing.queryProducts(listOf(productId))
        }
    }

    var errorText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(billing) {
        billing.events.collect { e ->
            when (e) {
                is BillingEvent.PurchaseCompleted -> {
                    val tier = BillingProducts.tierForProductId(e.productId)
                    if (tier != null) {
                        onEntitlementGranted(tier)
                    } else {
                        errorText = "Purchase completed, but plan mapping is unknown (${e.productId})."
                    }
                }
                is BillingEvent.Error -> {
                    errorText = e.message
                }
            }
        }
    }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Upgrade",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Selected plan: $tierLabel",
                style = MaterialTheme.typography.bodyMedium
            )
            val playPrice = priceLabel()
            if (!playPrice.isNullOrBlank()) {
                Text(
                    text = "Google Play price: $playPrice",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val act = (context as? Activity)
                    val pd = productDetails
                    if (act == null) {
                        errorText = "Unable to start billing: no Activity."
                        return@Button
                    }
                    if (pd == null) {
                        errorText = "Loading plan details… try again in a moment."
                        return@Button
                    }
                    errorText = null
                    billing.launchSubscriptionPurchase(act, pd)
                },
                enabled = selectedTier != null &&
                    selectedTier != SubscriptionTier.FREE &&
                    isReady &&
                    productDetails != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Subscribe with Google Play")
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Not now")
            }
        }
    }
}


