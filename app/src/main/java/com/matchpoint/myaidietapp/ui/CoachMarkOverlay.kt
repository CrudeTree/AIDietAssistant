package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class CoachStep(
    val title: String,
    val body: String,
    val targetRect: () -> Rect?,
    val cardPosition: CoachCardPosition = CoachCardPosition.BOTTOM
)

enum class CoachCardPosition {
    TOP,
    BOTTOM
}

@Composable
fun CoachMarkOverlay(
    steps: List<CoachStep>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return

    var index by remember { mutableIntStateOf(0) }
    var lastSeenValidIndex by remember { mutableStateOf<Int?>(null) }

    fun moveNext() {
        // Skip steps that don't have a rect yet.
        var i = index + 1
        while (i < steps.size && steps[i].targetRect() == null) i++
        if (i >= steps.size) onDone() else index = i
    }

    fun moveToFirstValid() {
        var i = 0
        while (i < steps.size && steps[i].targetRect() == null) i++
        if (i >= steps.size) onDone() else index = i
    }

    LaunchedEffect(steps) {
        moveToFirstValid()
    }

    val rect = steps.getOrNull(index)?.targetRect()
    if (rect != null) lastSeenValidIndex = index

    // Full-screen overlay
    Box(
        modifier = modifier
            .fillMaxSize()
            // Block touches to the app underneath
            .background(Color.Transparent)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { /* consume */ }
    ) {
        // Scrim + cutout
        Canvas(
            modifier = Modifier
                .matchParentSize()
                // Needed for BlendMode.Clear to "punch through"
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(Color.Black.copy(alpha = 0.72f))

            val r = rect
            if (r != null) {
                val pad = 6.dp.toPx()
                val cut = Rect(
                    left = (r.left - pad).coerceAtLeast(0f),
                    top = (r.top - pad).coerceAtLeast(0f),
                    right = (r.right + pad).coerceAtMost(size.width),
                    bottom = (r.bottom + pad).coerceAtMost(size.height)
                )
                val corner = CornerRadius(18.dp.toPx(), 18.dp.toPx())
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(cut.left, cut.top),
                    size = Size(cut.width, cut.height),
                    cornerRadius = corner,
                    blendMode = BlendMode.Clear
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(cut.left, cut.top),
                    size = Size(cut.width, cut.height),
                    cornerRadius = corner,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        val safeIndex = lastSeenValidIndex ?: index
        val isLast = safeIndex >= steps.lastIndex
        val step = steps[safeIndex]

        val cardModifier = when (step.cardPosition) {
            CoachCardPosition.TOP -> Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
            CoachCardPosition.BOTTOM -> Modifier
                .align(Alignment.BottomCenter)
                // Keep the tutorial controls above the Android navigation bar.
                .navigationBarsPadding()
                .padding(16.dp)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = cardModifier
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(step.title, fontWeight = FontWeight.SemiBold)
                Text(
                    step.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${safeIndex + 1}/${steps.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDone) { Text("Skip") }
                        Button(onClick = { if (isLast) onDone() else moveNext() }) {
                            Text(if (isLast) "Done" else "Next")
                        }
                    }
                }
            }
        }
    }
}

