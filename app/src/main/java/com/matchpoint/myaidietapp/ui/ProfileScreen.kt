package com.matchpoint.myaidietapp.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.matchpoint.myaidietapp.model.SavedRecipe
import com.matchpoint.myaidietapp.model.SubscriptionTier
import com.matchpoint.myaidietapp.model.UserProfile
import com.matchpoint.myaidietapp.model.WeightUnit
import com.matchpoint.myaidietapp.R
import android.content.Intent
import android.net.Uri

@Composable
fun ProfileScreen(
    profile: UserProfile,
    savedRecipes: List<SavedRecipe>,
    onBack: () -> Unit,
    onRemoveFood: (String) -> Unit,
    onAutoPilotChange: (Boolean) -> Unit,
    onUpdateWeightGoal: (Double?) -> Unit,
    onLogWeight: (Double) -> Unit,
    onOpenFoodList: (String?) -> Unit,
    onOpenRecipeList: () -> Unit,
    onSignOut: () -> Unit,
    onOpenChoosePlan: () -> Unit,
    isProcessing: Boolean,
    errorText: String?,
    onOpenRecipe: (SavedRecipe) -> Unit,
    onOpenSettings: () -> Unit
) {
    val unit = profile.weightUnit
    fun lbToKg(lb: Double): Double = lb / 2.2046226218
    fun fromLb(lb: Double): Double = if (unit == WeightUnit.KG) lbToKg(lb) else lb
    fun formatWeight(lb: Double): String {
        val v = fromLb(lb)
        val rounded = kotlin.math.round(v * 10.0) / 10.0
        val suffix = if (unit == WeightUnit.KG) "kg" else "lb"
        return "${rounded} $suffix"
    }

    val tier = profile.subscriptionTier
    val tierLabel = when (tier) {
        SubscriptionTier.FREE -> "Free"
        SubscriptionTier.REGULAR -> "Basic"
        SubscriptionTier.PRO -> "Premium"
    }
    val context = LocalContext.current

    Surface {
        Box(modifier = Modifier.fillMaxSize()) {
            // Cute background wallpaper (subtle, behind everything)
            Image(
                painter = painterResource(id = R.drawable.strawberry),
                contentDescription = null,
                modifier = Modifier
                    .size(155.dp)
                    .offset(x = (-30).dp, y = 110.dp)
                    .rotate(-16f),
                alpha = 0.14f
            )
            Image(
                painter = painterResource(id = R.drawable.mushroom),
                contentDescription = null,
                modifier = Modifier
                    .size(170.dp)
                    .offset(x = 210.dp, y = 260.dp)
                    .rotate(14f),
                alpha = 0.12f
            )
            Image(
                painter = painterResource(id = R.drawable.cinnamon),
                contentDescription = null,
                modifier = Modifier
                    .size(180.dp)
                    .offset(x = (-24).dp, y = 520.dp)
                    .rotate(18f),
                alpha = 0.10f
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Keep bottom actions (e.g. "View all items") above the system nav/gesture bar and keyboard.
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start
            ) {
            // Centered title image
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.profile),
                    contentDescription = "Profile",
                    modifier = Modifier
                        .align(Alignment.Center)
                        // ~3x bigger than before
                        .height(189.dp)
                )

                // Settings gear: align vertically with the Profile image and push further right.
                // The asset has lots of transparent padding, so we scale the image inside the button.
                IconButton(
                    onClick = onOpenSettings,
                    enabled = !isProcessing,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = 16.dp)
                        .size(98.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.gear),
                        contentDescription = "Settings",
                        modifier = Modifier.size(119.dp)
                    )
                }
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    color = Color(0xFFB00020),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Align Upgrade with the right-column buttons (same horizontal alignment as Snacks).
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                val gap = 10.dp
                val cellW = (maxWidth - gap) / 2
                // Left side: plan text + badge
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Plan: $tierLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (tier == SubscriptionTier.REGULAR || tier == SubscriptionTier.PRO) {
                        val badge = if (tier == SubscriptionTier.REGULAR) R.drawable.ic_basic_badge else R.drawable.ic_premium_badge
                        Image(
                            painter = painterResource(badge),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(34.dp)
                        )
                    }
                }
                // Right column: center inside the same "cell" width as Snacks.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(cellW),
                    contentAlignment = Alignment.Center
                ) {
                    AlphaHitImageButton(
                        resId = R.drawable.upgrade,
                        size = DpSize(width = 170.dp, height = 64.dp),
                        contentDescription = "View / Upgrade",
                        enabled = !isProcessing,
                        onClick = onOpenChoosePlan
                    )
                }
            }

            if (tier != SubscriptionTier.FREE) {
                OutlinedButton(
                    onClick = {
                        val uri = Uri.parse("https://play.google.com/store/account/subscriptions?package=${context.packageName}")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage / cancel subscription (Google Play)")
                }
            }

            // Food list usage (tier-based caps)
            val foodLimit = when (tier) {
                SubscriptionTier.FREE -> 20
                SubscriptionTier.REGULAR -> 100
                SubscriptionTier.PRO -> 500
            }
            Text(
                text = "Food items: ${profile.foodItems.size} / $foodLimit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Feeding schedule control removed (for now).
            Spacer(modifier = Modifier.height(8.dp))

            // Saved recipes: counter pinned left; button on the right-half (left edge aligned to screen center).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 10.dp
                val cellW = (maxWidth - gap) / 2
                val recipeBtnSize = DpSize(width = 182.dp, height = 78.dp) // ~35% smaller

                Text(
                    text = "Recipes: ${savedRecipes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterStart)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(cellW),
                    contentAlignment = Alignment.Center
                ) {
                    AlphaHitImageButton(
                        resId = R.drawable.btn_recipes,
                        size = recipeBtnSize,
                        contentDescription = "Recipes",
                        enabled = !isProcessing,
                        onClick = { onOpenRecipeList() }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Weight",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            val latestWeight = profile.weightHistory.maxByOrNull { it.date }?.weight
            Text(
                text = "Latest weight: ${latestWeight?.let { formatWeight(it) } ?: "not logged"}",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Weight goal: ${profile.weightGoal?.let { formatWeight(it) } ?: "not set"}",
                style = MaterialTheme.typography.bodyMedium
            )

            var weightText by remember { mutableStateOf("") }
            // Align Log with the right-column buttons (same horizontal alignment as Snacks).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 10.dp
                val cellW = (maxWidth - gap) / 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.width(160.dp)) {
                    // Show the "focused" look by default: keep the label outside the field.
                    Text(
                        text = "Log weight (${if (unit == WeightUnit.KG) "kg" else "lb"})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { raw ->
                            // Keep this field small: accept up to 4 characters (e.g. "165" / "70.5")
                            // and restrict to digits + optional dot.
                            val filtered = raw.filter { it.isDigit() || it == '.' }.take(4)
                            weightText = filtered
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("165") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier.width(cellW),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                val w = weightText.toDoubleOrNull() ?: return@IconButton
                                onLogWeight(w)
                                weightText = ""
                            },
                            enabled = !isProcessing,
                            // Button starts at center.
                            modifier = Modifier
                                // ~15% smaller than 180x96
                                .size(width = 153.dp, height = 82.dp)
                                // Nudge down slightly (~10px total)
                                .offset(y = 10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.btn_log),
                                contentDescription = "Log",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // (Removed "Lists" label)
            // 2x2 grid:
            // Row 1: Meals (left) + Snacks (right)
            // Row 2: Ingredients (left) + View All (right)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 10.dp
                val cellW = (maxWidth - gap) / 2
                val listH = 63.dp
                val viewH = 68.dp

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AlphaHitImageButton(
                                resId = R.drawable.btn_meals,
                                size = DpSize(width = cellW, height = listH),
                                contentDescription = "Meals",
                                enabled = !isProcessing,
                                onClick = { onOpenFoodList("MEAL") }
                            )
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AlphaHitImageButton(
                                resId = R.drawable.btn_snacks,
                                size = DpSize(width = cellW, height = listH),
                                contentDescription = "Snacks",
                                enabled = !isProcessing,
                                onClick = { onOpenFoodList("SNACK") }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AlphaHitImageButton(
                                resId = R.drawable.btn_ingredients,
                                size = DpSize(width = cellW, height = listH),
                                contentDescription = "Ingredients",
                                enabled = !isProcessing,
                                onClick = { onOpenFoodList("INGREDIENT") }
                            )
                        }
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AlphaHitImageButton(
                                resId = R.drawable.btn_view_all,
                                size = DpSize(width = cellW, height = viewH),
                                contentDescription = "View all items",
                                enabled = !isProcessing,
                                onClick = { onOpenFoodList(null) }
                            )
                        }
                    }

                    // Sign out centered below the grid (same size as View All).
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AlphaHitImageButton(
                            resId = R.drawable.btn_sign_out,
                            size = DpSize(width = cellW, height = viewH),
                            contentDescription = "Sign out",
                            enabled = !isProcessing,
                            onClick = { onSignOut() }
                        )
                    }
                }
            }

            // No explicit Back button: system back returns to AI Food Coach.
        }
        }
    }
}
