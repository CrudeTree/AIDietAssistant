package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.R

@Composable
fun TermsScreen(
    onBack: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Image(
                    painter = painterResource(id = R.drawable.settings),
                    contentDescription = "Terms",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .height(210.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Text(
                text = "Terms & Limits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Premium features are provided on a fair-use basis. We may apply technical, operational, or policy-based controls to maintain service integrity and prevent abuse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Premium fair-use caps (hard limits)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = buildString {
                    append("- 200 chats/day\n")
                    append("- 1000 food items\n")
                    append("- 500 saved recipes\n")
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Why this exists",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "“Unlimited” is a marketing description and does not constitute an unconditional promise of infinite usage. Limits may be enforced, modified, or applied in response to abusive, automated, anomalous, or excessive usage patterns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

