package com.matchpoint.myaidietapp.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.model.SubscriptionTier

@Composable
fun PaymentScreen(
    selectedTier: SubscriptionTier?,
    billingCycle: com.matchpoint.myaidietapp.ui.BillingCycle,
    onBack: () -> Unit
) {
    val tierLabel = when (selectedTier) {
        SubscriptionTier.REGULAR -> if (billingCycle == com.matchpoint.myaidietapp.ui.BillingCycle.YEARLY) "Regular — $99.99/year" else "Regular — $9.99/month"
        SubscriptionTier.PRO -> if (billingCycle == com.matchpoint.myaidietapp.ui.BillingCycle.YEARLY) "Pro — $199.99/year" else "Pro — $19.99/month"
        SubscriptionTier.FREE, null -> "Free"
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
            Text(
                text = "Payment screen coming next. We’ll wire Google Play Billing soon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { /* TODO: launch Play Billing flow */ },
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue to payment (Coming soon)")
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


