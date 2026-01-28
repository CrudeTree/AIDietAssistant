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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchpoint.myaidietapp.model.MessageEntry
import com.matchpoint.myaidietapp.model.MessageSender
import com.matchpoint.myaidietapp.model.SavedRecipe
import com.matchpoint.myaidietapp.model.UserProfile

@Composable
fun RecipeDetailScreen(
    recipe: SavedRecipe,
    profile: UserProfile,
    qaMessages: List<MessageEntry> = emptyList(),
    qaBusy: Boolean = false,
    onAskQuestion: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    onSave: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    // Temporary per-screen toggle. Defaults to Settings each time you open this screen.
    var showIcons by remember { mutableStateOf(profile.showFoodIcons) }
    LaunchedEffect(recipe.id) {
        showIcons = profile.showFoodIcons
    }
    val fontSp = profile.uiFontSizeSp.coerceIn(12f, 40f)
    val difficultyLabel = remember(recipe.text) {
        val parenRe = Regex("""^\(\s*(simple|advanced|expert)\s*\)\s*$""", RegexOption.IGNORE_CASE)
        val lineRe = Regex("""^\s*difficulty\s*:\s*(simple|advanced|expert)\s*$""", RegexOption.IGNORE_CASE)
        recipe.text
            .replace("\r", "")
            .trimStart()
            .lines()
            .take(14)
            .map { it.trim() }
            .firstNotNullOfOrNull { line ->
                val m1 = lineRe.matchEntire(line)
                if (m1 != null) {
                    val raw = m1.groupValues.getOrNull(1)?.trim().orEmpty()
                    return@firstNotNullOfOrNull if (raw.isBlank()) null else raw.lowercase().replaceFirstChar { it.uppercase() }
                }
                val m2 = parenRe.matchEntire(line)
                if (m2 != null) {
                    val raw = m2.groupValues.getOrNull(1)?.trim().orEmpty()
                    return@firstNotNullOfOrNull if (raw.isBlank()) null else raw.lowercase().replaceFirstChar { it.uppercase() }
                }
                null
            }
    }

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
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
                    // Ensure the last lines of the recipe aren't hidden behind the system nav bar.
                    .navigationBarsPadding(),
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
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = (fontSp + 10f).coerceAtMost(54f).sp),
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!difficultyLabel.isNullOrBlank()) {
                    Text(
                        text = "Difficulty: $difficultyLabel",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = (fontSp + 1f).sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

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
                            modifier = Modifier.size(39.dp)
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
                            modifier = Modifier.size(39.dp)
                        )
                    }
                    val base = (fontSp * 4.0f).coerceIn(48f, 144f)
                    IngredientIconFlow(items = missing, iconSize = (base * 0.8f).dp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                RecipeTextWithPhasesAndIngredientIcons(
                    text = recipe.text,
                    ingredients = recipe.ingredients,
                    iconSize = (((fontSp + 4f) * 2f) * 0.8f).sp,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = fontSp.sp,
                        lineHeight = (fontSp * 1.35f).sp
                    ),
                    includeAllIconMatchesInText = true,
                    showIngredientIcons = showIcons,
                    textColor = Color.Black,
                    renderTitle = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(14.dp)
                )

                if (onAskQuestion != null) {
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Questions",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = (fontSp + 2f).sp),
                        fontWeight = FontWeight.SemiBold
                    )

                    if (qaMessages.isEmpty()) {
                        Text(
                            text = "Ask anything about this recipe (substitutions, timing, technique, etc.).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (m in qaMessages.takeLast(80)) {
                                val isUser = m.sender == MessageSender.USER
                                val bubbleBg = if (isUser) Color(0xFFE8F5E9) else Color(0xFFF2F2F2)
                                val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = align
                                ) {
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                        color = bubbleBg,
                                        tonalElevation = 0.dp
                                    ) {
                                        Text(
                                            text = m.text,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSp.sp),
                                            color = Color.Black,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    var question by remember(recipe.id) { mutableStateOf("") }
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        enabled = !qaBusy,
                        placeholder = { Text("Ask a question…") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            disabledTextColor = Color.Black.copy(alpha = 0.55f),
                            cursorColor = Color.Black,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            disabledContainerColor = Color.White,
                            focusedPlaceholderColor = Color(0xFF555555),
                            unfocusedPlaceholderColor = Color(0xFF555555),
                            disabledPlaceholderColor = Color(0xFF777777)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val q = question.trim()
                            if (q.isNotBlank()) {
                                question = ""
                                onAskQuestion(q)
                            }
                        },
                        enabled = !qaBusy && question.trim().isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (qaBusy) "Asking…" else "Ask",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Add some space so long recipes don't hide behind the save button.
                if (onSave != null) {
                    Spacer(modifier = Modifier.height(120.dp))
                }

                // Extra bottom slack for gesture/nav bar even when there's no Save button.
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Daily plan save button: bottom-left, 3x bigger, nav-bar safe.
            if (onSave != null) {
                IconButton(
                    onClick = onSave,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 10.dp, bottom = 10.dp)
                        .size(192.dp)
                ) {
                    Image(
                        painter = painterResource(id = com.matchpoint.myaidietapp.R.drawable.btn_save),
                        contentDescription = "Save recipe",
                        modifier = Modifier.fillMaxSize()
                    )
                }
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


