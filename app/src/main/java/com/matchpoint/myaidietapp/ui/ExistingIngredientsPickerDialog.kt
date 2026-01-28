package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.matchpoint.myaidietapp.R
import com.matchpoint.myaidietapp.model.RecipeDifficulty
import kotlinx.coroutines.delay

@Composable
fun ExistingIngredientsPickerDialog(
    candidates: List<String>,
    enabled: Boolean,
    resolveIcon: (String) -> Int?,
    onDismiss: () -> Unit,
    onGenerate: (picked: List<String>, targetCalories: Int?, strictOnly: Boolean, difficulty: RecipeDifficulty, cookTimeMinutes: Int?) -> Unit,
    tutorialStep: Int = -1,
    tutorialActive: Boolean = false,
    onTutorialSkip: () -> Unit = {},
    onTutorialNext: () -> Unit = {}
) {
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var page by rememberSaveable { mutableStateOf(1) } // 1=ingredients, 2=options
    var caloriesEnabled by rememberSaveable { mutableStateOf(true) }
    var sliderValue by rememberSaveable { mutableStateOf(500f) }
    var query by rememberSaveable { mutableStateOf("") }
    var strictOnly by rememberSaveable { mutableStateOf(false) }
    var difficultyName by rememberSaveable { mutableStateOf(RecipeDifficulty.SIMPLE.name) }
    var cookTimeEnabled by rememberSaveable { mutableStateOf(false) }
    var cookTimeValue by rememberSaveable { mutableStateOf(30f) }
    val green = Color(0xFF22C55E)
    val blue = Color(0xFF1E88E5)

    val filtered = remember(candidates, query) {
        val q = query.trim()
        if (q.isBlank()) {
            candidates
        } else {
            // Prefix match only (startsWith), not "contains".
            candidates.filter { it.trim().startsWith(q, ignoreCase = true) }
        }
    }

    val lockDismiss = tutorialActive && tutorialStep == 19
    LaunchedEffect(lockDismiss) {
        if (lockDismiss) page = 2
    }
    Dialog(onDismissRequest = { if (!lockDismiss) onDismiss() }) {
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
                            text = if (page == 1) "Pick ingredients to include" else "Meal options",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { if (!lockDismiss) onDismiss() },
                            enabled = enabled && !lockDismiss,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(text = "Close", maxLines = 1, softWrap = false)
                        }
                    }

                    val canProceedFromPage1 = enabled && if (strictOnly) picked.isNotEmpty() else (picked.isNotEmpty() || candidates.isNotEmpty())

                    if (page == 1) {
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
                                // Only the list scrolls, so Next stays reachable.
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

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = { page = 2 },
                                enabled = canProceedFromPage1
                            ) { Text("Next") }
                        }
                    } else {
                        // Make options scrollable while keeping the CTA at the bottom.
                        val optionsScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true)
                                .verticalScroll(optionsScroll),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Calories",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Optional: turn off if you don’t care about calories.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = caloriesEnabled,
                                onCheckedChange = { caloriesEnabled = it },
                                enabled = enabled
                            )
                        }

                        if (caloriesEnabled) {
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
                        }

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
                                    border = BorderStroke(
                                        1.dp,
                                        if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = enabled) { difficultyName = diff.name }
                                ) {
                                    Text(
                                        text = label,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp, horizontal = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cook time",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Optional: set a time target.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = cookTimeEnabled,
                                onCheckedChange = { cookTimeEnabled = it },
                                enabled = enabled
                            )
                        }

                        if (cookTimeEnabled) {
                            val mins = cookTimeValue.toInt().coerceIn(10, 120)
                            Text(
                                text = "About $mins minutes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = cookTimeValue,
                                onValueChange = { cookTimeValue = it },
                                valueRange = 10f..120f,
                                steps = 10,
                                enabled = enabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = blue,
                                    activeTrackColor = blue,
                                    activeTickColor = blue,
                                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        } // end scrollable options

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { page = 1 },
                                enabled = enabled && !lockDismiss
                            ) { Text("Back") }
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // Generate Meal CTA pinned at the very bottom of the popup.
                        val canGenerate = canProceedFromPage1
                        val alpha = if (canGenerate) 1f else 0.45f
                        androidx.compose.foundation.Image(
                            painter = painterResource(id = R.drawable.generate_meal),
                            contentDescription = "Generate meal",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .clickable(enabled = canGenerate) {
                                    val diff = runCatching { RecipeDifficulty.valueOf(difficultyName) }.getOrNull()
                                        ?: RecipeDifficulty.SIMPLE
                                    val calories = if (caloriesEnabled) sliderValue.toInt().coerceIn(100, 1000) else null
                                    val cookMins = if (cookTimeEnabled) cookTimeValue.toInt().coerceIn(10, 120) else null
                                    onGenerate(picked.toList(), calories, strictOnly, diff, cookMins)
                                },
                            contentScale = ContentScale.Fit,
                            alpha = alpha
                        )
                    }

                    // Locked tutorial step: hide the dialog instructions and require Generate Meal to proceed.
                    // Provide a blue Skip link at the bottom center to exit tutorial.
                    if (lockDismiss) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(onClick = onTutorialSkip) {
                                Text("Skip", color = blue, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Tutorial modal that sits "in front of" the Generate Meal popup (steps 16–18).
    // This blocks interaction until the user taps Next/Skip, then we transition into the locked step.
    if (tutorialActive && tutorialStep in 16..18) {
        val msg = when (tutorialStep) {
            16 -> "Here we have access to the list you started creating earlier."
            17 -> "(If you don’t see any items here, something may have happened and you may not have any items in your ingredients list.)"
            else -> "Select a few ingredients. If you want me to stick to ONLY what you picked, turn on \"Only use selected ingredients\". Tap Next to set calories (optional), difficulty, and cook time. Then tap Generate Meal."
        }

        Dialog(onDismissRequest = { /* block outside dismiss during tutorial pages */ }) {
            // Keep the tutorial popup near the top so it doesn't cover the Generate Meal button.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                TutorialCard(
                    text = msg,
                    onSkip = onTutorialSkip,
                    onNext = {
                        // Move to next tutorial page; after 18, transition into locked step 19.
                        onTutorialNext()
                    },
                    showNext = true
                )
            }
        }
    }
}

@Composable
private fun TutorialCard(
    text: String,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    showNext: Boolean
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

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.robot_head),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) { Text("Skip") }
                if (showNext) {
                    Button(onClick = onNext) { Text("Next") }
                }
            }
        }
    }
}

