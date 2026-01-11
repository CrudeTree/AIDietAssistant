package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchpoint.myaidietapp.model.SavedRecipe
import com.matchpoint.myaidietapp.model.UserProfile

@Composable
fun RecipeDetailScreen(
    recipe: SavedRecipe,
    profile: UserProfile,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    // Temporary per-screen toggle. Defaults to Settings each time you open this screen.
    var showIcons by remember { mutableStateOf(profile.showFoodIcons) }
    LaunchedEffect(recipe.id) {
        showIcons = profile.showFoodIcons
    }
    val fontSp = profile.uiFontSizeSp.coerceIn(12f, 40f)

    val userHasIconIds = remember(profile.foodItems) {
        val out = linkedSetOf<Int>()
        for (fi in profile.foodItems) {
            val cats = fi.categories.map { it.trim().uppercase() }.toSet().ifEmpty { setOf("SNACK") }
            val isPantry = cats.contains("INGREDIENT") || cats.contains("SNACK")
            if (!isPantry) continue
            val id = FoodIconResolver.resolveFoodIconResId(ctx, fi.name)
            if (id != null) out.add(id)
        }
        out
    }

    val detected = remember(recipe.text) { RecipeIngredientDetector.detect(ctx, recipe.text) }
    val have = detected.filter { userHasIconIds.contains(it.resId) }
    val missing = detected.filterNot { userHasIconIds.contains(it.resId) }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.weight(1f))

                // Temporary toggle: does not persist; resets to Settings after leaving this screen.
                Switch(
                    checked = showIcons,
                    onCheckedChange = { showIcons = it }
                )
            }

            Text(
                text = recipe.title.ifBlank { "Recipe" },
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = (fontSp + 6f).coerceAtMost(46f).sp),
                fontWeight = FontWeight.Bold
            )

            if (showIcons && have.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Available ingredients",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(26.dp)
                    )
                }
                val base = (fontSp * 4.0f).coerceIn(48f, 144f)
                IngredientIconFlow(items = have, iconSize = (base * 0.8f).dp)
            }

            if (showIcons && missing.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Missing ingredients",
                        tint = Color(0xFFB00020),
                        modifier = Modifier.size(26.dp)
                    )
                }
                val base = (fontSp * 4.0f).coerceIn(48f, 144f)
                IngredientIconFlow(items = missing, iconSize = (base * 0.8f).dp)
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (showIcons) {
                RecipeTextWithIngredientIcons(
                    text = recipe.text,
                    ingredients = recipe.ingredients,
                    iconSize = (((fontSp + 4f) * 2f) * 0.8f).sp,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSp.sp, lineHeight = (fontSp * 1.35f).sp),
                    includeAllIconMatchesInText = true
                )
            } else {
                Text(
                    text = recipe.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSp.sp, lineHeight = (fontSp * 1.35f).sp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun IngredientIconFlow(
    items: List<RecipeIngredientDetector.DetectedIngredient>,
    iconSize: androidx.compose.ui.unit.Dp
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.take(60).forEach { ing ->
            Image(
                painter = painterResource(id = ing.resId),
                contentDescription = ing.label,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}


