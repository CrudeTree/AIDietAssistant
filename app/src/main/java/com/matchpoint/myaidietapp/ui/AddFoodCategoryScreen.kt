package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.R

@Composable
fun AddFoodCategoryScreen(
    enabled: Boolean,
    onPickMeals: () -> Unit,
    onPickSnacks: () -> Unit,
    onPickIngredients: () -> Unit,
    onBack: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Image(
                    painter = painterResource(id = R.drawable.header_add_food),
                    contentDescription = "Add Food",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .height(210.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Text(
                text = "What do you want to add?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Pick a list. You can add by text or photos on the next screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Match the Profile button look/feel: image buttons with alpha-hit testing.
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
                        enabled = enabled,
                        onClick = onPickIngredients
                    )
                    AlphaHitImageButton(
                        resId = R.drawable.btn_snacks,
                        size = DpSize(width = fullW, height = listH),
                        contentDescription = "Snacks",
                        enabled = enabled,
                        onClick = onPickSnacks
                    )
                    AlphaHitImageButton(
                        resId = R.drawable.btn_meals,
                        size = DpSize(width = fullW, height = listH),
                        contentDescription = "Meals",
                        enabled = enabled,
                        onClick = onPickMeals
                    )
                }
            }
        }
    }
}

