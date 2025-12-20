package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.model.SubscriptionTier

@Composable
fun ChoosePlanScreen(
    currentTier: SubscriptionTier,
    notice: String?,
    onClose: () -> Unit,
    onPickPlan: (SubscriptionTier, BillingCycle) -> Unit
) {
    var cycle by remember { mutableStateOf(BillingCycle.MONTHLY) }
    var selectedTier by remember { mutableStateOf(currentTier) }

    fun tierRank(t: SubscriptionTier) = when (t) {
        SubscriptionTier.FREE -> 0
        SubscriptionTier.REGULAR -> 1
        SubscriptionTier.PRO -> 2
    }
    val canUpgrade = tierRank(selectedTier) > tierRank(currentTier)

    val regularPrice = if (cycle == BillingCycle.YEARLY) "$99.99/year" else "$9.99/month"
    val proPrice = if (cycle == BillingCycle.YEARLY) "$199.99/year" else "$19.99/month"

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
                enabled = tierRank(SubscriptionTier.FREE) > tierRank(currentTier),
                isCurrent = currentTier == SubscriptionTier.FREE,
                onClick = { selectedTier = SubscriptionTier.FREE }
            )
            PlanCard(
                title = "Regular — $regularPrice",
                subtitle = "Best value for most people",
                bullets = listOf("50 chats/day", "100 food items"),
                selected = selectedTier == SubscriptionTier.REGULAR,
                enabled = tierRank(SubscriptionTier.REGULAR) > tierRank(currentTier),
                isCurrent = currentTier == SubscriptionTier.REGULAR,
                onClick = { selectedTier = SubscriptionTier.REGULAR }
            )
            PlanCard(
                title = "Pro — $proPrice",
                subtitle = "For power users",
                bullets = listOf("150 chats/day", "500 food items"),
                selected = selectedTier == SubscriptionTier.PRO,
                enabled = tierRank(SubscriptionTier.PRO) > tierRank(currentTier),
                isCurrent = currentTier == SubscriptionTier.PRO,
                onClick = { selectedTier = SubscriptionTier.PRO }
            )

            HorizontalDivider()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose) { Text("Not now") }
                Button(
                    onClick = { onPickPlan(selectedTier, cycle) },
                    enabled = canUpgrade
                ) {
                    Text("Upgrade")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Current plan: ${currentTier.name.lowercase().replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            bullets.forEach { b -> Text("• $b", style = MaterialTheme.typography.bodySmall) }
            if (isCurrent) {
                Text(
                    "Current plan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (selected) {
                Text(
                    "Selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}


