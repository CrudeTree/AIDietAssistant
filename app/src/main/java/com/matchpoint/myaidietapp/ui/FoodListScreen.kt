package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchpoint.myaidietapp.model.DietType
import com.matchpoint.myaidietapp.model.FoodItem
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.matchpoint.myaidietapp.R
import androidx.compose.ui.unit.DpSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoodListScreen(
    dietType: DietType,
    items: List<FoodItem>,
    filterCategory: String? = null,
    totalLimit: Int,
    showFoodIcons: Boolean = true,
    fontSizeSp: Float = 18f,
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
    // Toggle to show/hide per-item descriptive lines for easier scanning.
    var showDescriptions by rememberSaveable(filterCategory) { mutableStateOf(true) }
    // Toggle to sort the current list alphabetically by name.
    var alphabetize by rememberSaveable(filterCategory) { mutableStateOf(false) }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keep bottom rows above the system nav/gesture bar and keyboard.
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val headerResId: Int? = when (filterCategory?.trim()?.uppercase()) {
                "MEAL" -> R.drawable.header_meals
                "INGREDIENT" -> R.drawable.header_ingredients
                "SNACK" -> R.drawable.header_snacks
                else -> R.drawable.header_all_items
            }
            val pageTitle = when (filterCategory?.trim()?.uppercase()) {
                "MEAL" -> "Meals"
                "INGREDIENT" -> "Ingredients"
                "SNACK" -> "Snacks"
                else -> "All items"
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                if (headerResId != null) {
                    Image(
                        painter = painterResource(id = headerResId),
                        contentDescription = pageTitle,
                        modifier = Modifier
                            .align(Alignment.Center)
                            // ~3x bigger header
                            .height(210.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }

                IconButton(
                    onClick = {
                        // On category pages, show Text/Photos choice; on "All items", go to Add Food hub.
                        if (filterCategory.isNullOrBlank()) onOpenAddFoodHub() else showAddChooser = true
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(56.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.plus_sign),
                        contentDescription = "Add",
                        // Keep the tap target large but shrink the visual by ~50%.
                        modifier = Modifier.size(28.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            val title = when (filterCategory) {
                "MEAL" -> "Meals"
                "INGREDIENT" -> "Ingredients"
                "SNACK" -> "Snacks"
                else -> "Foods known"
            }
            Text(
                text = run {
                    val used = items.size
                    val remaining = (totalLimit - used).coerceAtLeast(0)
                    val dietLabel = if (dietType == DietType.NO_DIET) "No Diet" else dietType.name
                    if (filterCategory.isNullOrBlank()) {
                        "Items: $used / $totalLimit • Diet: $dietLabel"
                    } else {
                        "$title • $remaining/$totalLimit available • Diet: $dietLabel"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Toggle: Show/hide descriptions (use-as, nutrition, ingredients, notes)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Show descriptions",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = showDescriptions,
                    onCheckedChange = { showDescriptions = it }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Alphabetize (A–Z)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = alphabetize,
                    onCheckedChange = { alphabetize = it }
                )
            }

            val filtered = if (filterCategory.isNullOrBlank()) {
                items
            } else {
                val key = filterCategory.trim().uppercase()
                items.filter { (it.categories.map { c -> c.trim().uppercase() }.toSet().ifEmpty { setOf("SNACK") }).contains(key) }
            }

            val displayList = if (!alphabetize) {
                filtered
            } else {
                filtered.sortedWith(compareBy<FoodItem> { it.name.trim().lowercase() }.thenBy { it.id })
            }

            if (displayList.isEmpty()) {
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
                items(displayList) { item ->
                    val context = LocalContext.current
                    val iconResId = remember(item.name) {
                        FoodIconResolver.resolveFoodIconResId(context, item.name)
                    }
                    val fs = fontSizeSp.coerceIn(12f, 40f)
                    // Make list icons ~1.5x larger.
                    val iconDp = (fs * 3.0f).coerceIn(24f, 96f).dp
                    val healthRating = item.rating
                    val dietFitRating = item.dietRatings[dietType.name] ?: item.dietFitRating

                    val healthColor = when {
                        healthRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        healthRating <= 3 -> Color(0xFFB00020)
                        healthRating <= 6 -> Color(0xFFFFC107)
                        // Make 8–9/10 a more yellow-green; make 10/10 the prior light green.
                        healthRating <= 9 -> Color(0xFFCDDC39)
                        else -> Color(0xFF4CAF50)
                    }
                    val dietColor = when {
                        dietFitRating == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        dietFitRating <= 3 -> Color(0xFFB00020)
                        dietFitRating <= 6 -> Color(0xFFFFC107)
                        // Make 8–9/10 a more yellow-green; make 10/10 the prior light green.
                        dietFitRating <= 9 -> Color(0xFFCDDC39)
                        else -> Color(0xFF4CAF50)
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
                            if (showFoodIcons && iconResId != null) {
                                Image(
                                    painter = painterResource(id = iconResId),
                                    contentDescription = item.name,
                                    modifier = Modifier
                                        .size(iconDp)
                                        .padding(end = 10.dp)
                                )
                            }
                            Text(
                                text = item.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fs.sp)
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
                            AlphaHitImageButton(
                                resId = R.drawable.remove,
                                size = DpSize(width = 44.dp, height = 44.dp),
                                contentDescription = "Remove",
                                enabled = true,
                                onClick = { onRemoveFood(item.id) }
                            )
                        }

                        if (!showDescriptions) return@items

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


