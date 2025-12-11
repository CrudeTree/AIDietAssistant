package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onRemoveFood: (String) -> Unit
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
            Text(
                text = "Diet:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DietType.NO_DIET,
                    DietType.CARNIVORE,
                    DietType.KETO,
                    DietType.OMNIVORE,
                    DietType.PALEO,
                    DietType.VEGAN,
                    DietType.VEGETARIAN,
                    DietType.OTHER
                ).forEach { type ->
                    val selected = type == profile.dietType
                    OutlinedButton(onClick = { onDietChange(type) }) {
                        Text(
                            type.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
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
                    val rating = item.rating
                    val ratingColor = when {
                        rating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        rating <= 3 -> Color(0xFFB00020) // red
                        rating <= 6 -> Color(0xFFFFC107) // yellow
                        rating <= 9 -> Color(0xFF4CAF50) // green
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
                            Text(
                                text = rating?.let { "${it}/10" } ?: "-/10",
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ratingColor
                            )
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



