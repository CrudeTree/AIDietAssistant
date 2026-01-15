package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.matchpoint.myaidietapp.R

@Composable
fun ExistingIngredientsPickerDialog(
    candidates: List<String>,
    enabled: Boolean,
    resolveIcon: (String) -> Int?,
    onDismiss: () -> Unit,
    onGenerate: (picked: List<String>, targetCalories: Int?, strictOnly: Boolean) -> Unit
) {
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sliderValue by rememberSaveable { mutableStateOf(500f) }
    var query by rememberSaveable { mutableStateOf("") }
    var strictOnly by rememberSaveable { mutableStateOf(false) }
    val green = Color(0xFF22C55E)

    val filtered = remember(candidates, query) {
        val q = query.trim()
        if (q.isBlank()) {
            candidates
        } else {
            // Prefix match only (startsWith), not "contains".
            candidates.filter { it.trim().startsWith(q, ignoreCase = true) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 10.dp,
                // Constrain dialog height so bottom actions remain reachable on small screens.
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pick ingredients to include",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onDismiss,
                            enabled = enabled,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(text = "Close", maxLines = 1, softWrap = false)
                        }
                    }

                    if (candidates.isEmpty()) {
                        Text(
                            text = "No ingredients in your list yet. Add ingredients first, or use the beginner picker next time you sign in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            enabled = enabled,
                            singleLine = true,
                            placeholder = { Text("Search ingredients") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (filtered.isEmpty()) {
                            Text(
                                text = "No matches.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // Only the list scrolls, so the Calorie slider + Generate button stay reachable.
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = true)
                            ) {
                                items(filtered.take(250), key = { it.lowercase() }) { name ->
                                    val isOn = picked.contains(name)
                                    val bg = if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
                                    val iconId = resolveIcon(name)
                                    val iconSlotSize = 52.dp
                                    // Increase non-wallpaper food icons by ~1.5x, but keep "Chicken" as-is (already tuned).
                                    val iconDrawSize = when {
                                        name.equals("Chicken", ignoreCase = true) -> 52.dp
                                        else -> 39.dp
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(bg, RoundedCornerShape(14.dp))
                                            .clickable(enabled = enabled) {
                                                if (isOn) {
                                                    picked = picked - name
                                                } else {
                                                    picked = picked + name
                                                    // After selecting from a filtered search, clear the query so the user can quickly add more.
                                                    query = ""
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (iconId != null) {
                                            Box(modifier = Modifier.size(iconSlotSize), contentAlignment = Alignment.Center) {
                                                androidx.compose.material3.Icon(
                                                    painter = painterResource(id = iconId),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(iconDrawSize),
                                                    tint = Color.Unspecified
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.size(iconSlotSize))
                                        }

                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "Calories",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${sliderValue.toInt().coerceIn(100, 1000)} calories",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 100f..1000f,
                        steps = 17, // 50-cal increments between 100..1000
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = green,
                            activeTrackColor = green,
                            activeTickColor = green,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Only use selected ingredients",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = strictOnly,
                            onCheckedChange = { strictOnly = it },
                            enabled = enabled
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val canGenerate = enabled && if (strictOnly) picked.isNotEmpty() else (picked.isNotEmpty() || candidates.isNotEmpty())
                    // Use the same CTA asset for consistency.
                    val alpha = if (canGenerate) 1f else 0.45f
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.generate_meal),
                        contentDescription = "Generate meal",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clickable(enabled = canGenerate) {
                                onGenerate(picked.toList(), sliderValue.toInt().coerceIn(100, 1000), strictOnly)
                            },
                        contentScale = ContentScale.Fit,
                        alpha = alpha
                    )
                }
            }
        }
    }
}

