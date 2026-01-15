package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Clickable image that only triggers onClick when the tapped pixel is non-transparent.
 * This solves "huge tap targets" caused by transparent padding inside the image file.
 */
@Composable
fun AlphaHitImageButton(
    resId: Int,
    size: DpSize,
    contentDescription: String?,
    enabled: Boolean = true,
    // Visually "zoom" inside the button to compensate for transparent padding in the asset.
    // 1f = normal. >1f will crop slightly (clipped to bounds).
    visualScale: Float = 1f,
    // Lower threshold = easier to tap (counts anti-aliased edges).
    alphaThreshold: Float = 0.02f,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bitmap: ImageBitmap = remember(resId) { ImageBitmap.imageResource(context.resources, resId) }
    val pixelMap = remember(bitmap) { bitmap.toPixelMap() }
    val latestOnClick by rememberUpdatedState(onClick)

    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .size(size)
            .onSizeChanged { boxSize = it }
            .pointerInput(enabled, resId, boxSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    if (boxSize.width <= 0 || boxSize.height <= 0) return@awaitEachGesture

                    val hit = hitTestAlpha(
                        tap = down.position,
                        boxSize = boxSize,
                        bitmapW = bitmap.width,
                        bitmapH = bitmap.height,
                        visualScale = visualScale,
                        threshold = alphaThreshold,
                        pixelMap = pixelMap
                    )

                    // Critical: only consume if this pixel is actually "solid".
                    // If it's transparent, do NOT consume, so overlapped buttons underneath can handle the tap.
                    if (!hit) return@awaitEachGesture
                    down.consume()

                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        latestOnClick()
                    }
                }
            }
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = visualScale,
                    scaleY = visualScale,
                    clip = true
                ),
            contentScale = ContentScale.Fit
        )
    }
}

private fun hitTestAlpha(
    tap: Offset,
    boxSize: IntSize,
    bitmapW: Int,
    bitmapH: Int,
    visualScale: Float,
    threshold: Float,
    pixelMap: androidx.compose.ui.graphics.PixelMap
): Boolean {
    // ContentScale.Fit math: image is centered and scaled uniformly to fit inside box.
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    val scale = min(bw / bitmapW.toFloat(), bh / bitmapH.toFloat()) * visualScale
    val drawnW = bitmapW * scale
    val drawnH = bitmapH * scale
    val ox = (bw - drawnW) / 2f
    val oy = (bh - drawnH) / 2f

    // If the tap is outside the drawn image (letterboxed area), ignore.
    if (tap.x < ox || tap.y < oy || tap.x > ox + drawnW || tap.y > oy + drawnH) return false

    val imgX = (tap.x - ox) / scale
    val imgY = (tap.y - oy) / scale

    val xi = min(bitmapW - 1, max(0, floor(imgX).toInt()))
    val yi = min(bitmapH - 1, max(0, floor(imgY).toInt()))

    // Be forgiving: sample a tiny neighborhood so taps on thin/anti-aliased edges still register.
    val r = 10
    var best = 0f
    for (dy in -r..r) {
        val y = yi + dy
        if (y !in 0 until bitmapH) continue
        for (dx in -r..r) {
            val x = xi + dx
            if (x !in 0 until bitmapW) continue
            val a = pixelMap[x, y].alpha
            if (a > best) best = a
            if (best >= threshold) return true
        }
    }
    return false
}


