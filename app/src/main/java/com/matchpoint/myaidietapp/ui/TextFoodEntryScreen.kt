package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun TextFoodEntryScreen(
    title: String,
    helperText: String,
    defaultCategories: Set<String>,
    isProcessing: Boolean,
    remainingSlots: Int,
    limit: Int,
    tutorialManager: HomeTutorialManager,
    onSubmit: (names: List<String>, categories: Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val tutorialActive = tutorialManager.shouldShow() &&
        tutorialManager.step() == 6 &&
        defaultCategories.any { it.equals("INGREDIENT", ignoreCase = true) }

    val initialRows = remember(remainingSlots) { remainingSlots.coerceIn(0, 20) }
    val rows = remember(remainingSlots) { mutableStateListOf<String>().apply { repeat(initialRows) { add("") } } }
    var error by remember { mutableStateOf<String?>(null) }

    val typedNames = rows.map { it.trim() }.filter { it.isNotBlank() }
    val canSubmit = typedNames.isNotEmpty() && !isProcessing && remainingSlots > 0

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Prevent bottom actions being obscured by gesture/nav bar and keyboard.
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$helperText\n\n${remainingSlots}/${limit} available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tutorialActive) {
                TutorialInlineCard(
                    text = "Go ahead and add some ingredients you have available, or that you’d like included in a recipe.\n\nTap Submit when you’re done.",
                    onSkip = { tutorialManager.markDone() }
                )
            }

            if (remainingSlots <= 0) {
                Text(
                    text = "You’re out of slots for this plan. Remove items or upgrade to add more.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(rows) { idx, value ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { rows[idx] = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Item ${idx + 1}") }
                        )
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (remainingSlots > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            error = null
                            if (rows.size < remainingSlots) rows.add("")
                        },
                        enabled = !isProcessing && rows.size < remainingSlots,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add row")
                    }
                    OutlinedButton(
                        onClick = {
                            error = null
                            if (rows.isNotEmpty()) rows.removeAt(rows.lastIndex)
                        },
                        enabled = !isProcessing && rows.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Remove row")
                    }
                }
            }

            Button(
                onClick = {
                    error = null
                    val cleaned = rows.map { it.trim() }.filter { it.isNotBlank() }
                    if (cleaned.isEmpty()) return@Button
                    if (cleaned.size > remainingSlots) {
                        error = "Too many items (${cleaned.size}). You only have $remainingSlots available."
                        return@Button
                    }
                    onSubmit(cleaned, defaultCategories)
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isProcessing) "Submitting…" else "Submit")
            }
        }
    }
}

@Composable
private fun TutorialInlineCard(
    text: String,
    onSkip: () -> Unit
) {
    var shown by remember(text) { mutableStateOf("") }

    LaunchedEffect(text) {
        shown = ""
        for (i in text.indices) {
            shown = text.substring(0, i + 1)
            // Pause on "..." for readability.
            if (i >= 2 && text[i - 2] == '.' && text[i - 1] == '.' && text[i] == '.') {
                delay(1500L)
            } else if (text[i] == '…') {
                delay(1500L)
            } else {
                delay(12L)
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = com.matchpoint.myaidietapp.R.drawable.robot_head),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("AI Diet Assistant", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }

            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSkip, modifier = Modifier) {
                    Text("Skip", color = Color(0xFF6B7280), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


