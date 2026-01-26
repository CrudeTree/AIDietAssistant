package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun TutorialSpotlightOverlay(
    rects: List<Rect>,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.72f
) {
    if (rects.isEmpty()) return

    Canvas(
        modifier = modifier
            // Needed for BlendMode.Clear to "punch through"
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        drawRect(Color.Black.copy(alpha = scrimAlpha))
        val pad = 6.dp.toPx()
        val corner = CornerRadius(18.dp.toPx(), 18.dp.toPx())

        rects.forEach { r ->
            val cut = Rect(
                left = (r.left - pad).coerceAtLeast(0f),
                top = (r.top - pad).coerceAtLeast(0f),
                right = (r.right + pad).coerceAtMost(size.width),
                bottom = (r.bottom + pad).coerceAtMost(size.height)
            )
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
}

@Composable
fun TutorialMessageCard(
    title: String = "AI Diet Assistant",
    text: String,
    onSkip: () -> Unit,
    onNext: (() -> Unit)?,
    containerColor: Color = Color.Unspecified,
    extraContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    nextLabel: String = "Next"
) {
    var shown by remember(text) { mutableStateOf("") }
    LaunchedEffect(text) {
        shown = ""
        for (i in text.indices) {
            shown = text.substring(0, i + 1)
            // Pause on "..." for readability.
            if (i >= 2 && text[i - 2] == '.' && text[i - 1] == '.' && text[i] == '.') {
                delay(1500L)
            } else if (text[i] == '…') {
                delay(1500L)
            } else {
                delay(12L)
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (containerColor == Color.Unspecified) {
                MaterialTheme.colorScheme.surface
            } else {
                containerColor
            }
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = com.matchpoint.myaidietapp.R.drawable.robot_head),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }

            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            extraContent?.invoke()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) { Text("Skip") }
                if (onNext != null) {
                    Button(onClick = onNext) { Text(nextLabel) }
                }
            }
        }
    }
}

@Composable
fun TutorialSpotlightBlocker(
    targetRect: () -> Rect?,
    onTargetTap: () -> Unit,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.72f
) {
    val rect = targetRect() ?: return

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(rect) {
                detectTapGestures { pos ->
                    // Consume all taps; only forward when inside the target.
                    if (rect.contains(pos)) {
                        onTargetTap()
                    }
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(Color.Black.copy(alpha = scrimAlpha))
            val pad = 6.dp.toPx()
            val corner = CornerRadius(18.dp.toPx(), 18.dp.toPx())
            val cut = Rect(
                left = (rect.left - pad).coerceAtLeast(0f),
                top = (rect.top - pad).coerceAtLeast(0f),
                right = (rect.right + pad).coerceAtMost(size.width),
                bottom = (rect.bottom + pad).coerceAtMost(size.height)
            )
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
}

