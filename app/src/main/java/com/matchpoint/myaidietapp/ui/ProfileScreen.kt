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
import androidx.compose.ui.unit.DpSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.matchpoint.myaidietapp.R
import android.content.Intent
import android.net.Uri

@Composable
fun ProfileScreen(
    profile: UserProfile,
    displayTier: SubscriptionTier,
    savedRecipes: List<SavedRecipe>,
    onBack: () -> Unit,
    onRemoveFood: (String) -> Unit,
    onAutoPilotChange: (Boolean) -> Unit,
    onOpenFoodList: (String?) -> Unit,
    onOpenRecipeList: () -> Unit,
    onSignOut: () -> Unit,
    onOpenChoosePlan: () -> Unit,
    isProcessing: Boolean,
    errorText: String?,
    onOpenRecipe: (SavedRecipe) -> Unit,
    onOpenSettings: () -> Unit,
    wallpaperSeed: Int
) {
    val tier = displayTier
    val context = LocalContext.current

    Surface {
        Box(modifier = Modifier.fillMaxSize()) {
            // Random wallpaper icons (ic_food_*): rerolls each time you enter this screen.
            if (profile.showWallpaperFoodIcons) {
                RandomFoodWallpaper(seed = wallpaperSeed, count = 24, baseAlpha = 0.10f)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Keep bottom actions (e.g. "View all items") above the system nav/gesture bar and keyboard.
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.Start
            ) {
            // Centered title image
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
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
                    .padding(vertical = 2.dp)
            ) {
                val gap = 10.dp
                val cellW = (maxWidth - gap) / 2
                // Food list usage (tier-based caps)
                val foodLimit = when (tier) {
                    SubscriptionTier.FREE -> 20
                    SubscriptionTier.REGULAR -> 100
                    SubscriptionTier.PRO -> 500
                }
                // Left side: plan text + badge
                Column(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Plan:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (tier == SubscriptionTier.FREE) {
                            Text(
                                text = "Free",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        } else {
                            val badge =
                                if (tier == SubscriptionTier.REGULAR) R.drawable.ic_basic_badge else R.drawable.ic_premium_badge
                            Image(
                                painter = painterResource(badge),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    // ~30% larger than 68dp
                                    .size(88.dp)
                            )
                        }
                    }
                    Text(
                        text = "Food items: ${profile.foodItems.size} / $foodLimit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        size = DpSize(width = 383.dp, height = 144.dp),
                        contentDescription = "View / Upgrade",
                        enabled = !isProcessing,
                        visualScale = 1.5f,
                        onClick = onOpenChoosePlan
                    )
                }
            }

            if (tier != SubscriptionTier.FREE) {
                // Align "Manage plan" with the Upgrade button above (same right-column width).
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val gap = 10.dp
                    val cellW = (maxWidth - gap) / 2
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(cellW),
                        contentAlignment = Alignment.Center
                    ) {
                        AlphaHitImageButton(
                            resId = R.drawable.btn_manage_plan,
                            size = DpSize(width = 170.dp, height = 64.dp),
                            contentDescription = "Manage plan",
                            enabled = !isProcessing,
                            onClick = {
                                val uri = Uri.parse("https://play.google.com/store/account/subscriptions?package=${context.packageName}")
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                        )
                    }
                }
            }

            // Feeding schedule control removed (for now).

            // Saved recipes: counter pinned left; button on the right-half (left edge aligned to screen center).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 10.dp
                val cellW = (maxWidth - gap) / 2
                val recipeBtnSize = DpSize(width = 473.dp, height = 198.dp)

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
                        visualScale = 1.5f,
                        onClick = { onOpenRecipeList() }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            // (Removed "Lists" label)
            // Stacked list buttons (simple vertical layout):
            // Ingredients (top), Snacks (middle), Meals (bottom)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fullW = maxWidth
                val listH = 68.dp

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AlphaHitImageButton(
                        resId = R.drawable.btn_ingredients,
                        size = DpSize(width = fullW, height = listH),
                        contentDescription = "Ingredients",
                        enabled = !isProcessing,
                        onClick = { onOpenFoodList("INGREDIENT") }
                    )
                    AlphaHitImageButton(
                        resId = R.drawable.btn_snacks,
                        size = DpSize(width = fullW, height = listH),
                        contentDescription = "Snacks",
                        enabled = !isProcessing,
                        onClick = { onOpenFoodList("SNACK") }
                    )
                    AlphaHitImageButton(
                        resId = R.drawable.btn_meals,
                        size = DpSize(width = fullW, height = listH),
                        contentDescription = "Meals",
                        enabled = !isProcessing,
                        onClick = { onOpenFoodList("MEAL") }
                    )

                    // Sign out centered below the grid.
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AlphaHitImageButton(
                            resId = R.drawable.btn_sign_out,
                            size = DpSize(width = fullW, height = listH),
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
