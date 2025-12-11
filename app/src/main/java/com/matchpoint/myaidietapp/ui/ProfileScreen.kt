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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.matchpoint.myaidietapp.model.UserProfile

@Composable
fun ProfileScreen(
    profile: UserProfile,
    onBack: () -> Unit,
    onDietChange: (DietType) -> Unit,
    onRemoveFood: (String) -> Unit,
    onAutoPilotChange: (Boolean) -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
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

            // AI Autopilot toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI controls my feeding schedule",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (profile.autoPilotEnabled)
                            "I’ve finished adding foods; let the AI choose when and what to eat."
                        else
                            "Keep this off while you’re still building your food list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.typography.bodySmall.color
                    )
                }
                Switch(
                    checked = profile.autoPilotEnabled,
                    onCheckedChange = { onAutoPilotChange(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
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
            Text(
                text = "Weight goal: ${profile.weightGoal?.toString() ?: "not set"}",
                style = MaterialTheme.typography.bodyMedium
            )

            val latestWeight = profile.weightHistory.maxByOrNull { it.date }?.weight
            Text(
                text = "Latest weight: ${latestWeight?.toString() ?: "not logged"}",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Foods known: ${profile.foodItems.size}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(profile.foodItems) { item ->
                    val healthRating = item.rating
                    val dietFitRating = item.dietFitRating
                    val healthColor = when {
                        healthRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        healthRating <= 3 -> Color(0xFFB00020) // red
                        healthRating <= 6 -> Color(0xFFFFC107) // yellow
                        healthRating <= 9 -> Color(0xFF4CAF50) // green
                        else -> Color(0xFF1B5E20)       // dark green
                    }
                    val dietColor = when {
                        dietFitRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        dietFitRating <= 3 -> Color(0xFFB00020) // red
                        dietFitRating <= 6 -> Color(0xFFFFC107) // yellow
                        dietFitRating <= 9 -> Color(0xFF4CAF50) // green
                        else -> Color(0xFF1B5E20)       // dark green
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "x${item.quantity}",
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Column(
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "Health Rating ${healthRating?.let { "$it/10" } ?: "-/10"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = healthColor
                                )
                                if (dietFitRating != null && profile.dietType != DietType.NO_DIET) {
                                    Text(
                                        text = "Diet Rating ${dietFitRating}/10",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = dietColor
                                    )
                                }
                            }
                            OutlinedButton(onClick = { onRemoveFood(item.id) }) {
                                Text("X")
                            }
                        }
                        val notes = item.notes
                        if (!notes.isNullOrBlank()) {
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to stomach")
            }
        }
    }
}
