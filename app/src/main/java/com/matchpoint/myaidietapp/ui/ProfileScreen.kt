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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.model.DietType
import com.matchpoint.myaidietapp.model.FastingPreset
import com.matchpoint.myaidietapp.model.SubscriptionTier
import com.matchpoint.myaidietapp.model.UserProfile

@Composable
fun ProfileScreen(
    profile: UserProfile,
    onBack: () -> Unit,
    onDietChange: (DietType) -> Unit,
    onRemoveFood: (String) -> Unit,
    onAutoPilotChange: (Boolean) -> Unit,
    onUpdateWeightGoal: (Double?) -> Unit,
    onLogWeight: (Double) -> Unit,
    onOpenFoodList: (String?) -> Unit,
    onUpdateFastingPreset: (FastingPreset) -> Unit,
    onUpdateEatingWindowStart: (Int) -> Unit,
    onSignOut: () -> Unit,
    onOpenChoosePlan: () -> Unit
) {
    val tier = profile.subscriptionTier
    val tierLabel = when (tier) {
        SubscriptionTier.FREE -> "Free"
        SubscriptionTier.REGULAR -> "Regular"
        SubscriptionTier.PRO -> "Pro"
    }

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

            Text(
                text = "Name: ${profile.name}",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Plan: $tierLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = onOpenChoosePlan) {
                    Text("View / Upgrade")
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

            // Diet selection as a dropdown, with custom text when "Other" is selected
            var dietExpanded by remember { mutableStateOf(false) }
            val dietOptions = listOf(
                DietType.NO_DIET,
                DietType.CARNIVORE,
                DietType.KETO,
                DietType.OMNIVORE,
                DietType.PALEO,
                DietType.VEGAN,
                DietType.VEGETARIAN,
                DietType.OTHER
            )
            val selectedDiet = profile.dietType
            val selectedLabel = when (selectedDiet) {
                DietType.NO_DIET -> "No Diet"
                else -> selectedDiet.name.lowercase().replaceFirstChar { it.uppercase() }
            }

            Text(
                text = "Diet type",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { dietExpanded = !dietExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedLabel)
                        Text("▼")
                    }
                }
                DropdownMenu(
                    expanded = dietExpanded,
                    onDismissRequest = { dietExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    dietOptions.forEach { type ->
                        val label = when (type) {
                            DietType.NO_DIET -> "No Diet"
                            else -> type.name.lowercase().replaceFirstChar { it.uppercase() }
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                dietExpanded = false
                                onDietChange(type)
                            }
                        )
                    }
                }
            }

            if (profile.dietType == DietType.OTHER) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = "",
                    onValueChange = { /* TODO: wire to a stored custom diet/allergy field if desired */ },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom diet / allergy notes (e.g. Peanut allergy)") }
                )
            }

            // Fasting preset (simple daily fasts)
            Text(
                text = "Fasting schedule",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            var fastExpanded by remember { mutableStateOf(false) }
            val fastLabel = profile.fastingPreset.label
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { fastExpanded = !fastExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(fastLabel)
                        Text("▼")
                    }
                }
                DropdownMenu(
                    expanded = fastExpanded,
                    onDismissRequest = { fastExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FastingPreset.values().forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                fastExpanded = false
                                onUpdateFastingPreset(preset)
                            }
                        )
                    }
                }
            }

            // Window start preference (only when fasting is enabled)
            if (profile.fastingPreset != FastingPreset.NONE && profile.fastingPreset.eatingWindowHours != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Eating window starts",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                fun mm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
                val startMin = profile.eatingWindowStartMinutes
                val endMin = profile.eatingWindowEndMinutes
                val currentLabel = if (startMin != null && endMin != null) "${mm(startMin)}–${mm(endMin)}" else "Set start time"

                var startExpanded by remember { mutableStateOf(false) }
                val options = listOf(
                    6 * 60 to "Morning (06:00)",
                    8 * 60 to "Morning (08:00)",
                    10 * 60 to "Late morning (10:00)",
                    12 * 60 to "Midday (12:00)",
                    14 * 60 to "Afternoon (14:00)",
                    16 * 60 to "Late afternoon (16:00)",
                    18 * 60 to "Evening (18:00)"
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { startExpanded = !startExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(currentLabel)
                            Text("▼")
                        }
                    }
                    DropdownMenu(
                        expanded = startExpanded,
                        onDismissRequest = { startExpanded = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        options.forEach { (mins, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    startExpanded = false
                                    onUpdateEatingWindowStart(mins)
                                }
                            )
                        }
                    }
                }
                Text(
                    text = "Tip: pick a start time that won’t put your window in the middle of the night.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign out")
            }
        }
    }
}
