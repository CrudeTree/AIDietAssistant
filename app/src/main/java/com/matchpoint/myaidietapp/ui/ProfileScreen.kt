package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.matchpoint.myaidietapp.model.SavedRecipe
import com.matchpoint.myaidietapp.model.SubscriptionTier
import com.matchpoint.myaidietapp.model.UserProfile
import com.matchpoint.myaidietapp.R
import android.content.Intent
import android.net.Uri

@Composable
fun ProfileScreen(
    profile: UserProfile,
    savedRecipes: List<SavedRecipe>,
    onBack: () -> Unit,
    onRemoveFood: (String) -> Unit,
    onAutoPilotChange: (Boolean) -> Unit,
    onUpdateWeightGoal: (Double?) -> Unit,
    onLogWeight: (Double) -> Unit,
    onOpenFoodList: (String?) -> Unit,
    onSignOut: () -> Unit,
    onOpenChoosePlan: () -> Unit,
    isProcessing: Boolean,
    errorText: String?,
    onOpenRecipe: (SavedRecipe) -> Unit,
    onOpenSettings: () -> Unit
) {
    val tier = profile.subscriptionTier
    val tierLabel = when (tier) {
        SubscriptionTier.FREE -> "Free"
        SubscriptionTier.REGULAR -> "Basic"
        SubscriptionTier.PRO -> "Premium"
    }
    val context = LocalContext.current

    Surface {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Profile",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (errorText != null) {
                Text(
                    text = errorText,
                    color = Color(0xFFB00020),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Name: ${profile.name}",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedButton(
                onClick = onOpenSettings,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Settings")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Plan: $tierLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (tier == SubscriptionTier.REGULAR || tier == SubscriptionTier.PRO) {
                        val badge = if (tier == SubscriptionTier.REGULAR) R.drawable.ic_basic_badge else R.drawable.ic_premium_badge
                        Image(
                            painter = painterResource(badge),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(34.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenChoosePlan) {
                        Text("View / Upgrade")
                    }
                }
            }

            if (tier != SubscriptionTier.FREE) {
                OutlinedButton(
                    onClick = {
                        val uri = Uri.parse("https://play.google.com/store/account/subscriptions?package=${context.packageName}")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage / cancel subscription (Google Play)")
                }
            }

            // Food list usage (tier-based caps)
            val foodLimit = when (tier) {
                SubscriptionTier.FREE -> 20
                SubscriptionTier.REGULAR -> 100
                SubscriptionTier.PRO -> 500
            }
            Text(
                text = "Food items: ${profile.foodItems.size} / $foodLimit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Feeding schedule control removed (for now).
            Spacer(modifier = Modifier.height(8.dp))

            // Saved recipes
            Text(
                text = "Recipes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (savedRecipes.isEmpty()) {
                Text(
                    text = "No saved recipes yet. Generate a meal and tap “Save recipe”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    savedRecipes.take(10).forEach { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = r.title.ifBlank { "Recipe" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (r.ingredients.isNotEmpty()) {
                                    Text(
                                        text = r.ingredients.take(6).joinToString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            OutlinedButton(onClick = { onOpenRecipe(r) }) {
                                Text("Open")
                            }
                        }
                    }
                    if (savedRecipes.size > 10) {
                        Text(
                            text = "Showing latest 10 of ${savedRecipes.size}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = "Weight",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            val latestWeight = profile.weightHistory.maxByOrNull { it.date }?.weight
            Text(
                text = "Latest weight: ${latestWeight?.toString() ?: "not logged"}",
                style = MaterialTheme.typography.bodyMedium
            )

            // Weight goal inline edit (pencil)
            var editingGoal by remember { mutableStateOf(false) }
            var goalText by remember(profile.weightGoal) { mutableStateOf(profile.weightGoal?.toString() ?: "") }

            if (!editingGoal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Weight goal: ${profile.weightGoal?.toString() ?: "not set"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(onClick = { editingGoal = true }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit weight goal"
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Weight goal") }
                    )
                    Button(
                        onClick = {
                            onUpdateWeightGoal(goalText.toDoubleOrNull())
                            editingGoal = false
                        }
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = {
                            goalText = profile.weightGoal?.toString() ?: ""
                            editingGoal = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            }

            var weightText by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Log current weight") }
                )
                Button(
                    onClick = {
                        val w = weightText.toDoubleOrNull() ?: return@Button
                        onLogWeight(w)
                        weightText = ""
                    }
                ) {
                    Text("Log")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Lists",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onOpenFoodList("MEAL") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Meals") }
                Button(
                    onClick = { onOpenFoodList("INGREDIENT") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ingredients") }
                Button(
                    onClick = { onOpenFoodList("SNACK") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Snacks") }
                OutlinedButton(
                    onClick = { onOpenFoodList(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View all items")
                }
            }

            // No explicit Back button: system back returns to AI Food Coach.

            OutlinedButton(
                onClick = onSignOut,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign out")
            }
        }
    }
}
