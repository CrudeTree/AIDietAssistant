package com.matchpoint.myaidietapp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Cute, friendly "AI is thinking" indicator.
 *
 * To enable the robot logo, add a transparent image at:
 *   app/src/main/res/drawable-nodpi/ic_robot_head.webp  (or .png)
 *
 * If the drawable isn't present, we fall back to a normal spinner.
 */
@Composable
fun AiThinkingIndicator(
    modifier: Modifier = Modifier,
    label: String = "AI Food Coach Thinking…"
) {
    val context = LocalContext.current
    val robotId = context.resources.getIdentifier("ic_robot_head", "drawable", context.packageName)

    val t = rememberInfiniteTransition(label = "aiThinking")
    // Total cycle: ~1.0s motion + 0.5s pause.
    // Use keyframes to mimic gravity:
    // - jump up fast, ease into apex
    // - accelerate down
    // - quick "floor hit" stop
    val bobPx = t.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1500

                // Upward jump: fast, then slow into apex
                0f at 0 using LinearEasing
                (-26f) at 140 using CubicBezierEasing(0.2f, 0.0f, 0.2f, 1.0f) // fast up
                (-30f) at 360 using CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f) // slow stop at top

                // Fall: accelerate down
                0f at 920 using CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

                // Impact: tiny overshoot then snap back quickly
                3f at 960 using LinearEasing
                0f at 1020 using LinearEasing

                // Pause on the floor
                0f at 1500
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "bobPx"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (robotId != 0) {
            Image(
                painter = painterResource(id = robotId),
                contentDescription = "AI thinking",
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer { translationY = bobPx.value }
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}


