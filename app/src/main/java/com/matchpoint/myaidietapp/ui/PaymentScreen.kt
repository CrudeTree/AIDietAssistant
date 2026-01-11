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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.BuildConfig
import com.matchpoint.myaidietapp.billing.RevenueCatEvent
import com.matchpoint.myaidietapp.billing.RevenueCatPackages
import com.matchpoint.myaidietapp.billing.RevenueCatRepository
import com.matchpoint.myaidietapp.model.SubscriptionTier

@Composable
fun PaymentScreen(
    authUid: String?,
    rc: RevenueCatRepository,
    selectedTier: SubscriptionTier?,
    billingCycle: com.matchpoint.myaidietapp.ui.BillingCycle,
    onBack: () -> Unit,
    onEntitlementGranted: (SubscriptionTier) -> Unit
) {
    val tierLabel = when (selectedTier) {
        // We keep the internal enum names (REGULAR/PRO) for backward compatibility,
        // but present them as Basic/Premium to users.
        SubscriptionTier.REGULAR -> if (billingCycle == com.matchpoint.myaidietapp.ui.BillingCycle.YEARLY) "Basic — $99.99/year" else "Basic — $9.99/month"
        SubscriptionTier.PRO -> if (billingCycle == com.matchpoint.myaidietapp.ui.BillingCycle.YEARLY) "Premium — $199.99/year" else "Premium — $19.99/month"
        SubscriptionTier.FREE, null -> "Free"
    }

    val context = LocalContext.current
    val offerings by rc.offerings.collectAsState()
    val customerInfo by rc.customerInfo.collectAsState()

    val desiredPackageId = remember(selectedTier, billingCycle) {
        when (selectedTier) {
            SubscriptionTier.REGULAR ->
                if (billingCycle == BillingCycle.YEARLY) RevenueCatPackages.BASIC_ANNUAL else RevenueCatPackages.BASIC_MONTHLY
            SubscriptionTier.PRO ->
                if (billingCycle == BillingCycle.YEARLY) RevenueCatPackages.PREMIUM_ANNUAL else RevenueCatPackages.PREMIUM_MONTHLY
            else -> null
        }
    }
    val activeOffering = remember(offerings) {
        offerings?.current ?: offerings?.all?.values?.firstOrNull()
    }
    val desiredPackage = remember(activeOffering, desiredPackageId, selectedTier, billingCycle) {
        val wantId = desiredPackageId ?: return@remember null
        val pkgs = activeOffering?.availablePackages ?: return@remember null

        fun pkgMatches(pkg: com.revenuecat.purchases.Package): Boolean {
            val pkgId = runCatching { pkg.identifier }.getOrNull()
            val productId = runCatching { pkg.product.id }.getOrNull()
            if (pkgId == wantId || productId == wantId) return true

            // Fallback heuristic: some dashboards use "$rc_monthly"/"$rc_annual" identifiers, so match by keywords.
            val ident = (pkgId ?: "").lowercase()
            val prod = (productId ?: "").lowercase()
            val cycleOk = if (billingCycle == BillingCycle.YEARLY) {
                ident.contains("annual") || ident.contains("year") || prod.contains("annual") || prod.contains("year")
            } else {
                ident.contains("month") || prod.contains("month")
            }
            val tierOk = when (selectedTier) {
                SubscriptionTier.REGULAR -> ident.contains("basic") || ident.contains("regular") || prod.contains("basic") || prod.contains("regular")
                SubscriptionTier.PRO -> ident.contains("premium") || ident.contains("pro") || prod.contains("premium") || prod.contains("pro")
                else -> false
            }
            return tierOk && cycleOk
        }

        pkgs.firstOrNull { pkgMatches(it) }
    }

    LaunchedEffect(Unit) {
        // In dev builds, the key may be unset; RevenueCatRepository will emit a non-fatal error.
        if (BuildConfig.REVENUECAT_API_KEY.isNotBlank()) {
            // Ensure purchases are made under the same user id the rest of the app/server uses.
            // Otherwise Google Play can say "already subscribed" while the app shows Free.
            authUid?.let { rc.logIn(it) }
            rc.refresh()
        }
    }

    var errorText by remember { mutableStateOf<String?>(null) }
    var autoLaunched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(rc) {
        rc.events.collect { e ->
            when (e) {
                is RevenueCatEvent.TierUpdated -> {
                    if (e.tier != SubscriptionTier.FREE) onEntitlementGranted(e.tier)
                }
                is RevenueCatEvent.Error -> {
                    errorText = e.message
                }
            }
        }
    }

    // Auto-launch the Google Play purchase sheet as soon as we have a package.
    LaunchedEffect(desiredPackageId, desiredPackage) {
        if (autoLaunched) return@LaunchedEffect
        if (selectedTier == null || selectedTier == SubscriptionTier.FREE) return@LaunchedEffect
        val act = context as? Activity ?: return@LaunchedEffect
        val pkg = desiredPackage ?: return@LaunchedEffect
        autoLaunched = true
        errorText = null
        rc.purchase(act, pkg)
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
            val currentTier = customerInfo?.let { rc.tierFrom(it) } ?: SubscriptionTier.FREE
            if (currentTier != SubscriptionTier.FREE) {
                val label = if (currentTier == SubscriptionTier.REGULAR) "Basic" else "Premium"
                Text(
                    text = "Current subscription: $label",
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
            if (selectedTier != null &&
                selectedTier != SubscriptionTier.FREE &&
                desiredPackage == null
            ) {
                // User-facing hint (always safe to show)
                Text(
                    text = "Loading subscriptions… If this stays disabled, RevenueCat may not be configured on this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Developer diagnostics (debug builds only)
                if (BuildConfig.DEBUG) {
                    val offeringId = runCatching { activeOffering?.identifier }.getOrNull() ?: "null"
                    val pkgLines = runCatching {
                        (activeOffering?.availablePackages ?: emptyList()).joinToString { p ->
                            val pkgId = runCatching { p.identifier }.getOrNull() ?: "?"
                            val prodId = runCatching { p.product.id }.getOrNull() ?: "?"
                            "$pkgId → $prodId"
                        }
                    }.getOrNull() ?: ""

                    Text(
                        text = buildString {
                            append("DEBUG: Subscribe disabled: no matching RevenueCat package found.\n")
                            append("SelectedTier=$selectedTier, cycle=$billingCycle\n")
                            append("desiredPackageId=$desiredPackageId\n")
                            append("offering=$offeringId\n")
                            if (BuildConfig.REVENUECAT_API_KEY.isBlank()) {
                                append("REVENUECAT_API_KEY is blank.\n")
                            }
                            if (pkgLines.isNotBlank()) {
                                append("Packages:\n$pkgLines")
                            } else {
                                append("Packages: (none loaded)")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Extra developer diagnostics to debug Google Play billing sheet errors.
            if (BuildConfig.DEBUG) {
                val infoLine = runCatching {
                    val appUserId = customerInfo?.originalAppUserId ?: "null"
                    val activeEnts = customerInfo?.entitlements?.active?.keys?.joinToString() ?: "none"
                    val pkgId = runCatching { desiredPackage?.identifier }.getOrNull() ?: "null"
                    val prodId = runCatching { desiredPackage?.product?.id }.getOrNull() ?: "null"
                    "DEBUG: appUserId=$appUserId, activeEntitlements=$activeEnts, pkg=$pkgId → productId=$prodId"
                }.getOrNull()

                if (!infoLine.isNullOrBlank()) {
                    Text(
                        text = infoLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val act = (context as? Activity)
                    val pkg = desiredPackage
                    if (act == null) {
                        errorText = "Unable to start billing: no Activity."
                        return@Button
                    }
                    if (pkg == null) {
                        errorText = "Loading paywall… make sure RevenueCat offering/packages are configured."
                        return@Button
                    }
                    errorText = null
                    rc.purchase(act, pkg)
                },
                enabled = selectedTier != null &&
                    selectedTier != SubscriptionTier.FREE &&
                    desiredPackage != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Subscribe")
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


