package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.matchpoint.myaidietapp.R
import kotlin.math.roundToInt
import android.view.WindowManager
import android.view.Gravity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.MotionEvent

data class HelperTarget(
    val id: String,
    val rect: Rect,
    val title: String,
    val body: String
)

private fun translateRect(rect: Rect, dx: Float, dy: Float): Rect {
    return Rect(
        left = rect.left + dx,
        top = rect.top + dy,
        right = rect.right + dx,
        bottom = rect.bottom + dy
    )
}

private fun View.offsetOnScreen(): Offset {
    val loc = IntArray(2)
    getLocationOnScreen(loc)
    return Offset(loc[0].toFloat(), loc[1].toFloat())
}

class HelperRegistry {
    private val targets = mutableStateOf<Map<String, HelperTarget>>(emptyMap())

    fun upsert(id: String, rect: Rect, title: String, body: String) {
        targets.value = targets.value + (id to HelperTarget(id, rect, title, body))
    }

    fun remove(id: String) {
        targets.value = targets.value - id
    }

    // `pos` is in SCREEN coordinates.
    fun findAtScreen(pos: Offset): HelperTarget? {
        return targets.value.values.firstOrNull { it.rect.contains(pos) }
    }
}

private val LocalHelperRegistry = androidx.compose.runtime.staticCompositionLocalOf<HelperRegistry> {
    error("HelperRegistry not provided")
}

@Composable
fun ProvideHelperRegistry(
    registry: HelperRegistry,
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalHelperRegistry provides registry) {
        content()
    }
}

fun Modifier.helperTarget(
    id: String,
    title: String,
    body: String
): Modifier = composed {
    val registry = LocalHelperRegistry.current
    val view = LocalView.current
    DisposableEffect(id) {
        onDispose { registry.remove(id) }
    }
    this.onGloballyPositioned { coords: LayoutCoordinates ->
        // Store in SCREEN coordinates so it can be matched from overlay dialogs (separate windows).
        // Use boundsInRoot() (ComposeView-local) + the root view's screen offset for stable results.
        val rootRect = coords.boundsInRoot()
        val off = view.offsetOnScreen()
        registry.upsert(id, translateRect(rootRect, off.x, off.y), title, body)
    }
}

@Composable
fun HelperOverlay(
    helperManager: HelperManager,
    registry: HelperRegistry,
    bringToFrontKey: Any? = null,
    modifier: Modifier = Modifier
) {
    if (!helperManager.isEnabled()) return

    // Shared state:
    // - Not armed: app remains fully interactive (helper floats, no dimming, no blocking).
    // - Armed (blue): the next tap is interpreted as "help" (still no dimming).
    var selecting by remember { mutableStateOf(false) }
    var activeTarget by remember { mutableStateOf<HelperTarget?>(null) }

    // Saveable drag state (Bundle-friendly primitives only).
    var dragX by rememberSaveable { mutableIntStateOf(0) }
    var dragY by rememberSaveable { mutableIntStateOf(0) }
    val dragOffset = IntOffset(dragX, dragY)

    @Composable
    fun NoDimDialog(
        onDismissRequest: () -> Unit,
        dismissOnClickOutside: Boolean,
        dismissOnBackPress: Boolean,
        content: @Composable BoxScope.() -> Unit
    ) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                dismissOnBackPress = dismissOnBackPress,
                dismissOnClickOutside = dismissOnClickOutside,
                usePlatformDefaultWidth = false
            )
        ) {
            // Remove the platform "dim behind" effect.
            val view = LocalView.current
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            Box(modifier = Modifier.fillMaxSize(), content = content)
        }
    }

    // Intro: the only standalone message.
    if (helperManager.shouldShowIntro()) {
        var introRect by remember { mutableStateOf<Rect?>(null) }
        NoDimDialog(
            onDismissRequest = { helperManager.markIntroSeen() },
            // Fullscreen dialogs have no "outside" area; handle outside-taps manually below.
            dismissOnClickOutside = false,
            dismissOnBackPress = true
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(introRect) {
                        detectTapGestures { pos ->
                            // Tap anywhere outside the card dismisses it.
                            if (introRect?.contains(pos) != true) {
                                helperManager.markIntroSeen()
                            }
                        }
                    }
            )
            HelperMessageCard(
                title = "Hi, I’m your Helper",
                text = "Tap me anytime. I’ll turn blue, then tap something on the screen and I’ll explain what it does.\n\nYou can turn me off anytime in Settings.",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
                    .onGloballyPositioned { coords -> introRect = coords.boundsInRoot() }
                    .zIndex(2f)
            )
        }
    }

    // Armed mode: intercept exactly one tap to choose a target (no dimming, no scrim).
    if (selecting && activeTarget == null) {
        NoDimDialog(
            onDismissRequest = { /* exit via helper tap */ },
            dismissOnClickOutside = false,
            dismissOnBackPress = false
        ) {
            val overlayOff = LocalView.current.offsetOnScreen()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Use raw screen coordinates to avoid window/status-bar offsets.
                    .pointerInteropFilter { e ->
                        if (e.actionMasked == MotionEvent.ACTION_UP) {
                            val screenPos = Offset(e.rawX, e.rawY)
                            val hit = registry.findAtScreen(screenPos)
                            if (hit != null) {
                                // Convert screen rect back into THIS dialog's local coordinates for drawing.
                                activeTarget = hit.copy(rect = translateRect(hit.rect, -overlayOff.x, -overlayOff.y))
                            }
                            selecting = false
                            true
                        } else {
                            // Consume while armed so taps don't leak through.
                            true
                        }
                    }
            )
        }
    }

    // Explanation: spotlight + card (no dimming).
    activeTarget?.let { t ->
        NoDimDialog(
            onDismissRequest = { activeTarget = null },
            dismissOnClickOutside = false,
            dismissOnBackPress = true
        ) {
            SpotlightBlocker(
                targetRect = t.rect,
                onDismiss = { activeTarget = null },
                // Tapping the highlighted thing counts as "off the box" → close.
                onTargetTap = { activeTarget = null },
                scrimAlpha = 0f,
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(1f)
            )
            HelperMessageCard(
                title = t.title,
                text = t.body,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp, start = 16.dp, end = 16.dp)
                    .zIndex(2f)
            )

            // Keep the helper button clickable while a description is showing.
            // This allows: tap helper -> close description AND arm (blue) in one tap.
            Surface(
                shape = CircleShape,
                color = if (selecting) Color(0xFF1E88E5) else MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .offset { dragOffset }
                    .size(56.dp)
                    .zIndex(10f)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Close the current description and arm help mode.
                                activeTarget = null
                                selecting = true
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragX += dragAmount.x.roundToInt()
                                dragY += dragAmount.y.roundToInt()
                            }
                        )
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.robot_head),
                        contentDescription = "Helper",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }

    // Helper button in its own tiny window, above other dialogs.
    // Re-created only when bringToFrontKey changes, so it can jump above other app popups
    // without visually "flipping" on every color change.
    androidx.compose.runtime.key(bringToFrontKey) {
        Dialog(
            onDismissRequest = { /* never dismiss */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                // Keep this a small window (not fullscreen), and position it manually.
                usePlatformDefaultWidth = true
            )
        ) {
            val view = LocalView.current
            val density = LocalDensity.current
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
                // Never dim.
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                // IMPORTANT: Don't steal focus from the app, otherwise TextFields (chat input)
                // won't be able to focus and the keyboard won't appear.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                // Let touches outside this tiny window go to the underlying UI/dialog.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                // Transparent window background (no rectangle).
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                // Size + position.
                window.setLayout(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                val base = with(density) { 12.dp.roundToPx() }
                val lp = window.attributes
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = base + dragX
                lp.y = base + dragY
                window.attributes = lp
            }

            Surface(
                shape = CircleShape,
                color = if (selecting) Color(0xFF1E88E5) else MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(56.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // If a description is open, tapping helper should arm immediately
                                // (the description layer will close itself via activeTarget=null).
                                activeTarget = null
                                selecting = !selecting
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragX += dragAmount.x.roundToInt()
                                dragY += dragAmount.y.roundToInt()
                            }
                        )
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.robot_head),
                        contentDescription = "Helper",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HelperMessageCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A3A3A)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.robot_head),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = Color.White)
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun SpotlightBlocker(
    targetRect: Rect,
    onDismiss: () -> Unit,
    onTargetTap: () -> Unit,
    scrimAlpha: Float = 0.35f,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(targetRect) {
                detectTapGestures { pos ->
                    if (targetRect.contains(pos)) onTargetTap() else onDismiss()
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            if (scrimAlpha > 0f) {
                drawRect(Color.Black.copy(alpha = scrimAlpha))
            }
            // Keep the highlight tight to the actual bounds.
            val pad = 0.dp.toPx()
            val corner = CornerRadius(18.dp.toPx(), 18.dp.toPx())
            val cut = Rect(
                left = (targetRect.left - pad).coerceAtLeast(0f),
                top = (targetRect.top - pad).coerceAtLeast(0f),
                right = (targetRect.right + pad).coerceAtMost(size.width),
                bottom = (targetRect.bottom + pad).coerceAtMost(size.height)
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

