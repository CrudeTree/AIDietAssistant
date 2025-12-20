package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AddEntryScreen(
    onAddMealByText: () -> Unit,
    onAddIngredientByText: () -> Unit,
    onAddItemByPhotos: () -> Unit,
    onBack: () -> Unit
) {
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add Food",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Pick one action. Foods are what you have/plan with; meals are what you ate (with grams/photo).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onAddMealByText,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add meal by text")
            }

            Button(
                onClick = onAddIngredientByText,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add ingredient by text")
            }

            Button(
                onClick = onAddItemByPhotos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add item by photos (1–3)")
            }
        }
    }
}


