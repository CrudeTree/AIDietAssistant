package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HowItWorksScreen(
    onBack: () -> Unit,
    onShowGuidedWalkthrough: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "How it works",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Quick overview of what each part of the app does.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(onClick = onShowGuidedWalkthrough, modifier = Modifier.fillMaxWidth()) {
                Text("Replay the 7-step Home tutorial")
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tip: This page is the non-popup overview. The button above launches the old guided overlay on Home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StepBlock(
                title = "Add Food",
                body = "Start by adding foods you eat. This can be ingredients, snacks, or full meals. Everything else builds from this."
            )
            StepBlock(
                title = "AI Evaluate Food",
                body = "Use your camera to scan food items or menus. The AI analyzes nutrition and provides a health score."
            )
            StepBlock(
                title = "Generate Recipe",
                body = "Create recipes using foods from your list or anything you want to cook. Save your favorite recipes to reuse later."
            )
            StepBlock(
                title = "Daily Plan",
                body = "Generate a daily meal plan (1–3 meals) based on your calorie goal. You can also plan using only your saved recipes."
            )
            StepBlock(
                title = "Chat",
                body = "Chat can see the foods you have added and the calories you have logged today. Get advice, add foods, track calories, or generate recipes all in one place."
            )
            StepBlock(
                title = "Profile & Settings",
                body = "View your food list and saved recipes. Manage fasting and customize app options like text size or food icons."
            )
            StepBlock(
                title = "Fasting Eating Window",
                body = "Enable fasting in Profile to show the eating window bar. Orange shows eating time and blue shows fasting."
            )

            TextButton(onClick = onShowGuidedWalkthrough) {
                Text("Show the guided walkthrough")
            }
        }
    }
}

@Composable
private fun StepBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, fontWeight = FontWeight.SemiBold)
        Text(text = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

