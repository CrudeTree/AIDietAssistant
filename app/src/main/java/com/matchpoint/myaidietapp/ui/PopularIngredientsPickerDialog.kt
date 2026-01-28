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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.matchpoint.myaidietapp.R
import com.matchpoint.myaidietapp.model.RecipeDifficulty

@Composable
fun PopularIngredientsPickerDialog(
    enabled: Boolean,
    resolveIcon: (String) -> Int?,
    onDismiss: () -> Unit,
    onGenerate: (picked: List<String>, targetCalories: Int?, difficulty: RecipeDifficulty) -> Unit
) {
    val popular = remember {
        listOf(
            "Chicken",
            "Eggs",
            "Rice",
            "Spinach",
            "Salmon",
            "Avocado"
        )
    }
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sliderValue by rememberSaveable { mutableStateOf(500f) }
    var difficultyName by rememberSaveable { mutableStateOf(RecipeDifficulty.SIMPLE.name) }
    val green = Color(0xFF22C55E)

    Dialog(onDismissRequest = onDismiss) {
        // Centered card (no custom dim/scrim)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 10.dp,
                // Constrain dialog height so bottom CTA remains reachable on small screens.
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pick 1 or more Ingredients",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onDismiss,
                            enabled = enabled,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "Close",
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Text(
                        text = "(You can remove ingredients anytime)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

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

                    Text(
                        text = "Difficulty",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val pickedDifficulty = runCatching { RecipeDifficulty.valueOf(difficultyName) }.getOrNull()
                            ?: RecipeDifficulty.SIMPLE
                        listOf(
                            RecipeDifficulty.SIMPLE to "Simple",
                            RecipeDifficulty.ADVANCED to "Advanced",
                            RecipeDifficulty.EXPERT to "Expert"
                        ).forEach { (diff, label) ->
                            val isOn = pickedDifficulty == diff
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                tonalElevation = 0.dp,
                                color = if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = enabled) { difficultyName = diff.name }
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Only the list scrolls, so the Generate button stays reachable.
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                    ) {
                        items(popular, key = { it.lowercase() }) { name ->
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
                                        picked = if (isOn) picked - name else picked + name
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (iconId != null) {
                                    Box(
                                        modifier = Modifier.size(iconSlotSize),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
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

                    Spacer(modifier = Modifier.height(4.dp))

                    val canGenerate = enabled && picked.isNotEmpty()
                    ImageCtaButton(
                        resId = R.drawable.generate_meal,
                        contentDescription = "Generate meal",
                        enabled = canGenerate,
                        onClick = {
                            val diff = runCatching { RecipeDifficulty.valueOf(difficultyName) }.getOrNull()
                                ?: RecipeDifficulty.SIMPLE
                            onGenerate(picked.toList(), sliderValue.toInt().coerceIn(100, 1000), diff)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageCtaButton(
    resId: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.45f
    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = resId),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .background(Color.Transparent)
                .padding(horizontal = 6.dp)
                .then(Modifier),
            contentScale = ContentScale.Fit,
            alpha = alpha
        )
    }
}

