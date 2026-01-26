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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
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
    tutorialManager: HomeTutorialManager,
    onPickMeals: () -> Unit,
    onPickSnacks: () -> Unit,
    onPickIngredients: () -> Unit,
    onBack: () -> Unit
) {
    val tutorialStep = tutorialManager.step()
    val tutorialActive = tutorialManager.shouldShow() && tutorialStep == 3
    var rectIngredients by remember { mutableStateOf<Rect?>(null) }

    Surface {
        Box(modifier = Modifier.fillMaxSize()) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords -> rectIngredients = coords.boundsInRoot() }
                    ) {
                        AlphaHitImageButton(
                            resId = R.drawable.btn_ingredients,
                            size = DpSize(width = fullW, height = listH),
                            contentDescription = "Ingredients",
                            enabled = enabled,
                            onClick = onPickIngredients
                        )
                    }
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

            if (tutorialActive) {
                CoachMarkOverlay(
                    steps = listOf(
                        CoachStep(
                            title = "AI Diet Assistant",
                            body = "Great job! Here you can add ingredients, snacks, or even meals for me to access and evaluate. Let’s start by adding an ingredient!",
                            targetRect = { rectIngredients },
                            cardPosition = CoachCardPosition.BOTTOM,
                            showRobotHead = true,
                            typewriterBody = true,
                            allowTargetTapToAdvance = true,
                            hideNextButton = true,
                            onTargetTap = {
                                tutorialManager.setStep(4)
                                onPickIngredients()
                            }
                        )
                    ),
                    onSkip = { tutorialManager.markDone() },
                    onComplete = { /* tap-to-advance */ },
                    modifier = Modifier.zIndex(10000f)
                )
            }
        }
    }
}

