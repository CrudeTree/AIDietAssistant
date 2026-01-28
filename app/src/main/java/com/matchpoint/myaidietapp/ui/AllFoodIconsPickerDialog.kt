package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.matchpoint.myaidietapp.R

private data class FoodIconCandidate(
    val valueName: String, // what we add to the user's list
    val displayName: String, // what we show in UI
    val resId: Int
)

private fun titleCaseWords(s: String): String {
    return s.split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { ch -> ch.uppercase() }
        }
}

private fun allFoodIconCandidates(): List<FoodIconCandidate> {
    return R.drawable::class.java.fields
        .asSequence()
        .mapNotNull { f ->
            val n = f.name ?: return@mapNotNull null
            if (!n.startsWith("ic_food_")) return@mapNotNull null
            val resId = runCatching { f.getInt(null) }.getOrNull() ?: return@mapNotNull null
            val value = n.removePrefix("ic_food_").replace('_', ' ').trim()
            if (value.isBlank()) return@mapNotNull null
            FoodIconCandidate(
                valueName = value.lowercase(),
                displayName = titleCaseWords(value),
                resId = resId
            )
        }
        .sortedBy { it.displayName }
        .toList()
}

@Composable
fun AllFoodIconsPickerDialog(
    onDismiss: () -> Unit,
    onAddSelected: (names: List<String>) -> Unit,
    remainingSlots: Int,
    title: String = "Find ingredients"
) {
    val candidates = remember { allFoodIconCandidates() }
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(candidates, query) {
        val q = query.trim()
        if (q.isBlank()) candidates
        else candidates.filter { it.displayName.startsWith(q, ignoreCase = true) || it.valueName.startsWith(q, ignoreCase = true) }
    }
    val maxSelectable = remainingSlots.coerceAtLeast(0)
    val limitReached = maxSelectable >= 0 && picked.size >= maxSelectable

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
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = "Close", maxLines = 1, softWrap = false)
                        }
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (maxSelectable <= 0) {
                        Text(
                            text = "You’ve reached your limit for food items. Upgrade your plan to add more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (limitReached) {
                        Text(
                            text = "You’ve reached the limit for food items allowed. Upgrade to add more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "You can add up to $maxSelectable more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true)
                    ) {
                        items(filtered.take(500), key = { it.valueName }) { c ->
                            val isOn = picked.contains(c.valueName)
                            val enabledRow = (maxSelectable > 0) && (!limitReached || isOn)
                            val rowAlpha = if (enabledRow) 1f else 0.35f
                            val bg = if (isOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg, RoundedCornerShape(14.dp))
                                    .alpha(rowAlpha)
                                    .clickable(enabled = enabledRow) {
                                        picked = if (isOn) {
                                            picked - c.valueName
                                        } else {
                                            // Cap selection at remainingSlots.
                                            if (picked.size >= maxSelectable) picked else picked + c.valueName
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.Icon(
                                        painter = painterResource(id = c.resId),
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = Color.Unspecified
                                    )
                                }
                                Text(
                                    text = c.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val canAdd = picked.isNotEmpty() && maxSelectable > 0
                    Button(
                        onClick = { onAddSelected(picked.toList()) },
                        enabled = canAdd,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (picked.size == 1) "Add 1 ingredient" else "Add ${picked.size} ingredients")
                    }
                }
            }
        }
    }
}

