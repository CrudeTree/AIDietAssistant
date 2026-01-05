package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
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
    val isUpgrade = tierRank(selectedTier) > tierRank(currentTier)

    fun isSamePlan(tier: SubscriptionTier, selectedCycle: BillingCycle): Boolean {
        if (tier != currentTier) return false
        // Free plan has no billing cycle.
        if (tier == SubscriptionTier.FREE) return true
        // If we don't know the current cycle (e.g. RevenueCat not yet loaded), fall back to tier-only comparison.
        val cc = currentCycle ?: return true
        return cc == selectedCycle
    }

    val canProceed = !isSamePlan(selectedTier, cycle)

    val basicPrice = if (cycle == BillingCycle.YEARLY) "$99.99/year" else "$9.99/month"
    val premiumPrice = if (cycle == BillingCycle.YEARLY) "$199.99/year" else "$19.99/month"

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Choose your plan",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

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
                bullets = listOf("5 chats/day", "20 food items"),
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
                        if (isUpgrade) onPickPlan(selectedTier, cycle) else onManageSubscription()
                    },
                    enabled = canProceed
                ) {
                    Text(if (isUpgrade) "Upgrade" else "Manage in Google Play")
                }
            }

            if (currentTier != SubscriptionTier.FREE) {
                OutlinedButton(
                    onClick = onManageSubscription,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage / cancel subscription (Google Play)")
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

    Card(
        onClick = { if (enabled) onClick() },
        shape = shape,
        colors = colors,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
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

            if (badgeResId != null) {
                Image(
                    painter = painterResource(badgeResId),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // "Sticker" feel: slightly off-card + rotated.
                        .offset(x = 10.dp, y = (-10).dp)
                        .size(64.dp)
                        .rotate(30f)
                )
            }
        }
    }
}


