package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import com.matchpoint.myaidietapp.R
import com.matchpoint.myaidietapp.model.RecipeTitleFontStyle
import com.matchpoint.myaidietapp.model.SavedRecipe

@Composable
fun RecipesScreen(
    recipes: List<SavedRecipe>,
    onBack: () -> Unit,
    onOpenRecipe: (SavedRecipe) -> Unit,
    onDeleteRecipe: (recipeId: String) -> Unit,
    titleFontStyle: RecipeTitleFontStyle,
    onSetTitleFontStyle: (RecipeTitleFontStyle) -> Unit,
    wallpaperSeed: Int,
    showWallpaperFoodIcons: Boolean
) {
    Surface {
        Box(modifier = Modifier.fillMaxSize()) {
            // Random wallpaper icons behind the list.
            if (showWallpaperFoodIcons) {
                RandomFoodWallpaper(seed = wallpaperSeed, count = 32, baseAlpha = 0.08f)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Keep vertical breathing room, but allow rows to go flush left/right.
                    .padding(PaddingValues(vertical = 20.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Keep the back button nicely inset even if rows are full-bleed.
                    .padding(horizontal = 20.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }

                Image(
                    painter = painterResource(id = R.drawable.recipes_header),
                    contentDescription = "Recipes",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .height(210.dp),
                    contentScale = ContentScale.Fit
                )

                Text(
                    text = "Recipes: ${recipes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            if (recipes.isEmpty()) {
                Text(
                    text = "No saved recipes yet. Generate a recipe and tap the save icon.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        ,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            } else {
                recipes.forEachIndexed { idx, r ->
                    RecipeRow(
                        recipe = r,
                        index = idx,
                        titleFontStyle = titleFontStyle,
                        onOpen = { onOpenRecipe(r) },
                        onDelete = { onDeleteRecipe(r.id) }
                    )
                    if (idx != recipes.lastIndex) {
                        Image(
                            painter = painterResource(id = R.drawable.recipe_divider),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RecipeRow(
    recipe: SavedRecipe,
    index: Int,
    titleFontStyle: RecipeTitleFontStyle,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val titleStyle = recipeTitleStyleFor(titleFontStyle)
    val cardMaxWidth = 520.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title above the "recipe page" art
            Text(
                text = "${index + 1}) ${recipe.title.ifBlank { "Recipe" }}",
                style = titleStyle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                // Align the title with the centered paper area (not the full-bleed row),
                // and cap width so tablets don't let text spill outside the paper art.
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = cardMaxWidth)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Artistic paper background with excerpt overlay
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = cardMaxWidth)
                    // ~20% larger than 210dp
                    .height(252.dp)
                    .clip(shape)
                    .clipToBounds()
                    .clickable(onClick = onOpen)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.recipe_page),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    // Avoid cutting off the art at top/bottom.
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(modifier = Modifier.size(8.dp))

        AlphaHitImageButton(
            resId = R.drawable.remove,
            size = DpSize(width = 54.dp, height = 54.dp),
            contentDescription = "Delete recipe",
            enabled = true,
            onClick = onDelete
        )
    }
}

@Composable
private fun recipeTitleStyleFor(style: RecipeTitleFontStyle): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    return when (style) {
        RecipeTitleFontStyle.HANDWRITTEN_SERIF ->
            base.copy(fontFamily = FontFamily.Cursive, fontStyle = FontStyle.Italic)
        RecipeTitleFontStyle.VINTAGE_COOKBOOK ->
            base.copy(fontFamily = FontFamily.Serif)
        RecipeTitleFontStyle.RUSTIC_SCRIPT ->
            base.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
        RecipeTitleFontStyle.FARMHOUSE_ARTISAN ->
            base.copy(fontFamily = FontFamily.SansSerif, letterSpacing = base.letterSpacing * 0.5f)
    }
}

@Composable
private fun rememberRecipeExcerpt(text: String, title: String): String {
    val titleClean = title.trim()
    val lines = text
        .replace("\r", "")
        .lines()
        .map { it.trimEnd() }
        .dropWhile { it.trim().isBlank() }
        .toMutableList()

    // If the recipe text starts with the title, drop it (title is already rendered above the card).
    if (titleClean.isNotBlank() && lines.isNotEmpty()) {
        val first = lines.first().trim()
        if (first.equals(titleClean, ignoreCase = true) ||
            first.startsWith(titleClean, ignoreCase = true)
        ) {
            lines.removeAt(0)
            // Also remove one extra blank line after the title if present.
            while (lines.isNotEmpty() && lines.first().trim().isBlank()) {
                lines.removeAt(0)
            }
        }
    }

    val cleaned = lines.joinToString("\n") { it }.trim()
    // Avoid extremely long blocks; Text will ellipsize anyway.
    return cleaned.take(900).ifBlank { "Recipe" }
}


