package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.model.DietType
import com.matchpoint.myaidietapp.model.FoodItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoodListScreen(
    dietType: DietType,
    items: List<FoodItem>,
    filterCategory: String? = null,
    onRemoveFood: (String) -> Unit,
    onUpdateCategories: (foodId: String, categories: Set<String>) -> Unit,
    onAddText: (category: String) -> Unit,
    onAddPhotos: (category: String) -> Unit,
    onOpenAddFoodHub: () -> Unit,
    onBack: () -> Unit
) {
    var editing by remember { mutableStateOf<FoodItem?>(null) }
    var editMeal by remember { mutableStateOf(false) }
    var editIngredient by remember { mutableStateOf(false) }
    var editSnack by remember { mutableStateOf(false) }
    var showAddChooser by remember { mutableStateOf(false) }

    Surface {
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
                val pageTitle = when (filterCategory?.trim()?.uppercase()) {
                    "MEAL" -> "Meals"
                    "INGREDIENT" -> "Ingredients"
                    "SNACK" -> "Snacks"
                    else -> "All items"
                }
                Text(
                    text = pageTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        // On category pages, show Text/Photos choice; on "All items", go to Add Food hub.
                        if (filterCategory.isNullOrBlank()) onOpenAddFoodHub() else showAddChooser = true
                    }
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = "Add")
                }
            }

            val title = when (filterCategory) {
                "MEAL" -> "Meals"
                "INGREDIENT" -> "Ingredients"
                "SNACK" -> "Snacks"
                else -> "Foods known"
            }
            Text(
                text = "$title • Diet: ${if (dietType == DietType.NO_DIET) "No Diet" else dietType.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val filtered = if (filterCategory.isNullOrBlank()) {
                items
            } else {
                val key = filterCategory.trim().uppercase()
                items.filter { (it.categories.map { c -> c.trim().uppercase() }.toSet().ifEmpty { setOf("SNACK") }).contains(key) }
            }

            if (filtered.isEmpty()) {
                Text(
                    text = "No foods yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Surface
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { item ->
                    val healthRating = item.rating
                    val dietFitRating = item.dietRatings[dietType.name] ?: item.dietFitRating

                    val healthColor = when {
                        healthRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        healthRating <= 3 -> Color(0xFFB00020)
                        healthRating <= 6 -> Color(0xFFFFC107)
                        healthRating <= 9 -> Color(0xFF4CAF50)
                        else -> Color(0xFF1B5E20)
                    }
                    val dietColor = when {
                        dietFitRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        dietFitRating <= 3 -> Color(0xFFB00020)
                        dietFitRating <= 6 -> Color(0xFFFFC107)
                        dietFitRating <= 9 -> Color(0xFF4CAF50)
                        else -> Color(0xFF1B5E20)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .combinedClickable(
                                onClick = { /* no-op */ },
                                onLongClick = {
                                    editing = item
                                    val cats = item.categories.map { it.trim().uppercase() }.toSet().ifEmpty { setOf("SNACK") }
                                    editMeal = cats.contains("MEAL")
                                    editIngredient = cats.contains("INGREDIENT")
                                    editSnack = cats.contains("SNACK")
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "x${item.quantity}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Health ${healthRating?.let { "$it/10" } ?: "-/10"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = healthColor
                                )
                                if (dietType != DietType.NO_DIET && dietFitRating != null) {
                                    Text(
                                        text = "${dietType.name} ${dietFitRating}/10",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = dietColor
                                    )
                                }
                            }
                            Button(onClick = { onRemoveFood(item.id) }) {
                                Text("X")
                            }
                        }

                            val cats = item.categories.map { it.trim().uppercase() }.toSet().ifEmpty { setOf("SNACK") }
                            Text(
                                text = "Use as: " + cats.joinToString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                        val nutritionLine = buildString {
                            val cals = item.estimatedCalories
                            val p = item.estimatedProteinG
                            val c = item.estimatedCarbsG
                            val f = item.estimatedFatG
                            if (cals != null) append("≈${cals} kcal")
                            if (p != null || c != null || f != null) {
                                if (isNotBlank()) append(" • ")
                                append(
                                    listOfNotNull(
                                        p?.let { "P${it}g" },
                                        c?.let { "C${it}g" },
                                        f?.let { "F${it}g" }
                                    ).joinToString(" ")
                                )
                            }
                        }.ifBlank { null }

                        if (!nutritionLine.isNullOrBlank()) {
                            Text(
                                text = nutritionLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val ingredients = item.ingredientsText
                        if (!ingredients.isNullOrBlank()) {
                            Text(
                                text = "Ingredients: $ingredients",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val notes = item.notes
                        if (!notes.isNullOrBlank()) {
                            Spacer(modifier = Modifier.padding(top = 2.dp))
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

        }
    }

    if (showAddChooser) {
        val cat = filterCategory?.trim()?.uppercase() ?: "SNACK"
        AlertDialog(
            onDismissRequest = { showAddChooser = false },
            title = { Text("Add to ${when (cat) { "MEAL" -> "Meals"; "INGREDIENT" -> "Ingredients"; else -> "Snacks" }}") },
            text = { Text("How do you want to add it?") },
            confirmButton = {
                Button(
                    onClick = {
                        showAddChooser = false
                        onAddText(cat)
                    }
                ) { Text("Text") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showAddChooser = false
                            onAddPhotos(cat)
                        }
                    ) { Text("Photos") }
                    Button(onClick = { showAddChooser = false }) { Text("Cancel") }
                }
            }
        )
    }

    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Change categories") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Item: ${item.name}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = editMeal, onCheckedChange = { editMeal = it })
                        Text("Meal")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = editIngredient, onCheckedChange = { editIngredient = it })
                        Text("Ingredient")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = editSnack, onCheckedChange = { editSnack = it })
                        Text("Snack")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cats = buildSet {
                            if (editMeal) add("MEAL")
                            if (editIngredient) add("INGREDIENT")
                            if (editSnack) add("SNACK")
                        }.ifEmpty { setOf("SNACK") }
                        onUpdateCategories(item.id, cats)
                        editing = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                Button(onClick = { editing = null }) { Text("Cancel") }
            }
        )
    }
}


