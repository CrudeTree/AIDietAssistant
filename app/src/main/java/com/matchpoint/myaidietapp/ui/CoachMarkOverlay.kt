package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay

data class CoachStep(
    val title: String,
    val body: String,
    val targetRect: () -> Rect?,
    val cardPosition: CoachCardPosition = CoachCardPosition.BOTTOM,
    val cardOffsetY: Dp = 0.dp,
    val primaryButtonText: String? = null,
    val showRobotHead: Boolean = false,
    val typewriterBody: Boolean = false,
    val allowNullTarget: Boolean = false,
    val allowTargetTapToAdvance: Boolean = false,
    val onTargetTap: (() -> Unit)? = null,
    val hideNextButton: Boolean = false
)

enum class CoachCardPosition {
    TOP,
    TOP_START,
    BOTTOM
}

@Composable
fun CoachMarkOverlay(
    steps: List<CoachStep>,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return

    var index by remember { mutableIntStateOf(0) }

    fun moveNext() {
        val i = index + 1
        if (i >= steps.size) onComplete() else index = i
    }

    fun moveToFirstValid() {
        // Show the first step even if it doesn't have a target (allowNullTarget),
        // otherwise find the first step that has a target rect.
        var i = 0
        while (i < steps.size) {
            val s = steps[i]
            val hasTarget = s.targetRect() != null
            if (hasTarget || s.allowNullTarget) break
            i++
        }
        if (i >= steps.size) onComplete() else index = i
    }

    LaunchedEffect(steps) {
        moveToFirstValid()
    }

    val step = steps.getOrNull(index) ?: return
    val rect = step.targetRect()

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
            .pointerInput(index, rect) {
                detectTapGestures { pos: Offset ->
                    val r = rect ?: return@detectTapGestures
                    if (step.allowTargetTapToAdvance && r.contains(pos)) {
                        step.onTargetTap?.invoke()
                        moveNext()
                    }
                }
            }
    ) {
        // Scrim + cutout
        Canvas(
            modifier = Modifier
                .matchParentSize()
                // Needed for BlendMode.Clear to "punch through"
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawRect(Color.Black.copy(alpha = 0.72f))

            if (rect != null) {
                val pad = 6.dp.toPx()
                val cut = Rect(
                    left = (rect.left - pad).coerceAtLeast(0f),
                    top = (rect.top - pad).coerceAtLeast(0f),
                    right = (rect.right + pad).coerceAtMost(size.width),
                    bottom = (rect.bottom + pad).coerceAtMost(size.height)
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

        val isLast = index >= steps.lastIndex

        val cardModifier = when (step.cardPosition) {
            CoachCardPosition.TOP -> Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
            CoachCardPosition.TOP_START -> Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
            CoachCardPosition.BOTTOM -> Modifier
                .align(Alignment.BottomCenter)
                // Keep the tutorial controls above the Android navigation bar.
                .navigationBarsPadding()
                .padding(16.dp)
        }.offset(y = step.cardOffsetY)

        val typedBody = rememberTypewriterText(fullText = step.body, enabled = step.typewriterBody, key = index)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = cardModifier
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (step.showRobotHead) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = com.matchpoint.myaidietapp.R.drawable.robot_head),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(step.title, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(step.title, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    typedBody,
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
                    // Keep the UI clean for the new guided tour; step counter isn't shown.
                    Spacer(modifier = Modifier.width(1.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSkip) { Text("Skip") }
                        if (!step.hideNextButton) {
                            val nextEnabled = !step.allowTargetTapToAdvance
                            Button(
                                enabled = nextEnabled,
                                onClick = { if (isLast) onComplete() else moveNext() }
                            ) {
                                Text(
                                    step.primaryButtonText
                                        ?: if (isLast) "Done" else "Next"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberTypewriterText(
    fullText: String,
    enabled: Boolean,
    key: Any
): String {
    var shown by rememberSaveable(key) { mutableStateOf(if (enabled) "" else fullText) }

    LaunchedEffect(fullText, enabled, key) {
        if (!enabled) {
            shown = fullText
            return@LaunchedEffect
        }
        shown = ""
        val clean = fullText
        // Simple, cheap typewriter: reveal one character at a time.
        for (i in clean.indices) {
            shown = clean.substring(0, i + 1)
            // If we just typed an ellipsis "...", pause for readability.
            if (i >= 2 && clean[i - 2] == '.' && clean[i - 1] == '.' && clean[i] == '.') {
                delay(1500L)
            } else if (clean[i] == '…') {
                delay(1500L)
            } else {
                delay(14L)
            }
        }
    }
    return shown
}

