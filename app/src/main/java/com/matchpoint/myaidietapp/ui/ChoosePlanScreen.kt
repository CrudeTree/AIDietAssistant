package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.matchpoint.myaidietapp.R
import com.matchpoint.myaidietapp.model.SubscriptionTier

@Composable
fun ChoosePlanScreen(
    currentTier: SubscriptionTier,
    currentCycle: BillingCycle?,
    notice: String?,
    onClose: () -> Unit,
    onPickPlan: (SubscriptionTier, BillingCycle) -> Unit,
    onManageSubscription: () -> Unit
) {
    var cycle by remember { mutableStateOf(BillingCycle.MONTHLY) }
    var selectedTier by remember { mutableStateOf(currentTier) }

    fun tierRank(t: SubscriptionTier) = when (t) {
        SubscriptionTier.FREE -> 0
        SubscriptionTier.REGULAR -> 1
        SubscriptionTier.PRO -> 2
    }
    val isDowngrade = tierRank(selectedTier) < tierRank(currentTier)

    fun isSamePlan(tier: SubscriptionTier, selectedCycle: BillingCycle): Boolean {
        if (tier != currentTier) return false
        // Free plan has no billing cycle.
        if (tier == SubscriptionTier.FREE) return true
        // If we don't know the current cycle (e.g. RevenueCat not yet loaded), fall back to tier-only comparison.
        val cc = currentCycle ?: return true
        return cc == selectedCycle
    }

    val canProceed = !isSamePlan(selectedTier, cycle)
    val isPaid = selectedTier != SubscriptionTier.FREE
    // If not a downgrade, allow in-app purchase flow for upgrades AND billing-cycle changes.
    val shouldPurchaseInApp = canProceed && isPaid && !isDowngrade

    val basicPrice = if (cycle == BillingCycle.YEARLY) "$99.99/year" else "$9.99/month"
    val premiumPrice = if (cycle == BillingCycle.YEARLY) "$199.99/year" else "$19.99/month"

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                // Keep the bottom CTA row above the system nav bar.
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Image(
                    painter = painterResource(id = R.drawable.header_choose_your_plan),
                    contentDescription = "Choose your plan",
                    modifier = Modifier
                        .align(Alignment.Center)
                        // ~3x bigger header
                        .height(210.dp),
                    contentScale = ContentScale.Fit
                )
            }

            if (!notice.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = notice,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text(
                text = "Pick a plan to unlock higher limits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TabRow(selectedTabIndex = if (cycle == BillingCycle.MONTHLY) 0 else 1) {
                Tab(
                    selected = cycle == BillingCycle.MONTHLY,
                    onClick = { cycle = BillingCycle.MONTHLY },
                    text = { Text("Monthly") }
                )
                Tab(
                    selected = cycle == BillingCycle.YEARLY,
                    onClick = { cycle = BillingCycle.YEARLY },
                    text = { Text("Yearly") }
                )
            }

            PlanCard(
                title = "Free",
                subtitle = "Good for trying it out",
                bullets = listOf("10 chats/day", "20 food items"),
                selected = selectedTier == SubscriptionTier.FREE,
                enabled = true,
                isCurrent = currentTier == SubscriptionTier.FREE,
                badgeResId = null,
                onClick = { selectedTier = SubscriptionTier.FREE }
            )
            PlanCard(
                title = "Basic — $basicPrice",
                subtitle = "Great for consistent progress",
                bullets = listOf("50 chats/day", "100 food items"),
                selected = selectedTier == SubscriptionTier.REGULAR,
                enabled = true,
                isCurrent = currentTier == SubscriptionTier.REGULAR &&
                    // Only mark as current on the correct cycle tab (monthly vs yearly).
                    (currentCycle == null || currentCycle == cycle),
                badgeResId = R.drawable.ic_basic_badge,
                onClick = { selectedTier = SubscriptionTier.REGULAR }
            )
            PlanCard(
                title = "Premium — $premiumPrice",
                subtitle = "For power users",
                bullets = listOf("150 chats/day", "500 food items"),
                selected = selectedTier == SubscriptionTier.PRO,
                enabled = true,
                isCurrent = currentTier == SubscriptionTier.PRO &&
                    // Only mark as current on the correct cycle tab (monthly vs yearly).
                    (currentCycle == null || currentCycle == cycle),
                badgeResId = R.drawable.ic_premium_badge,
                onClick = { selectedTier = SubscriptionTier.PRO }
            )

            HorizontalDivider()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose) { Text("Not now") }
                Button(
                    onClick = {
                        if (shouldPurchaseInApp) onPickPlan(selectedTier, cycle) else onManageSubscription()
                    },
                    enabled = canProceed
                ) {
                    Text(if (shouldPurchaseInApp) "Upgrade" else "Manage in Google Play")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PlanCard(
    title: String,
    subtitle: String,
    bullets: List<String>,
    selected: Boolean,
    enabled: Boolean,
    isCurrent: Boolean,
    badgeResId: Int?,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    // Wrap the Card in a Box and draw the badge OUTSIDE the card content so it can't be clipped by
    // the Card's shape/border.
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            onClick = { if (enabled) onClick() },
            shape = shape,
            colors = colors,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val titleText = if (isCurrent) "$title (Current plan)" else title
                Text(titleText, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                bullets.forEach { b -> Text("• $b", style = MaterialTheme.typography.bodySmall) }
                if (!isCurrent && selected) {
                    Text(
                        "Selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (badgeResId != null) {
            Image(
                painter = painterResource(badgeResId),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // "Sticker" feel: slightly off-card + rotated.
                    .offset(x = 22.dp, y = (-26).dp)
                    // 50% larger than the previous 77dp.
                    .size(116.dp)
                    .rotate(30f)
                    .zIndex(10f)
            )
        }
    }
}


