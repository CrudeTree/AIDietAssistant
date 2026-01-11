package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import com.matchpoint.myaidietapp.R
import kotlin.random.Random

private val ALL_IC_FOOD_DRAWABLES: List<Int> by lazy {
    R.drawable::class.java.fields
        .mapNotNull { f ->
            val name = f.name
            if (!name.startsWith("ic_food_")) return@mapNotNull null
            runCatching { f.getInt(null) }.getOrNull()?.takeIf { it != 0 }
        }
        .distinct()
}

/**
 * Caches decoded ImageBitmaps across screens so navigating between screens doesn't re-decode
 * a couple dozen ic_food_* WEBPs every time.
 */
private object FoodIconBitmapCache {
    private val map = HashMap<Int, ImageBitmap>()

    fun get(resId: Int, loader: () -> ImageBitmap): ImageBitmap {
        return synchronized(map) {
            map[resId] ?: loader().also { map[resId] = it }
        }
    }
}

/**
 * Subtle background wallpaper that places ic_food_* icons in a predictable
 * "vertical checkerboard" pattern.
 *
 * - Deterministic for a given [seed] (so it doesn't change every recomposition)
 * - Changes when [seed] changes (we bump seed when navigating to a new screen)
 * - The *layout* is stable; only which icons appear changes
 */
@Composable
fun RandomFoodWallpaper(
    seed: Int,
    count: Int = 24,
    baseAlpha: Float = 0.12f
) {
    val all = ALL_IC_FOOD_DRAWABLES
    if (all.isEmpty()) return

    val resources = LocalContext.current.resources

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rand = remember(seed) { Random(seed) }
        val scale = when {
            maxWidth >= 900.dp -> 2.0f
            maxWidth >= 700.dp -> 1.6f
            maxWidth >= 600.dp -> 1.35f
            else -> 1.0f
        }

        // Stable "wallpaper grid" anchors: multi-column vertical checkerboard.
        // Odd columns are shifted down by half a row to create the checker look.
        //
        // We intentionally overscan (start above the top) so on larger screens it feels like
        // a continuous wallpaper rather than a few icons stuck in one corner.
        val columns = 5
        // Make the wallpaper ~2x more spaced out (wider columns + taller rows). Overscan is OK.
        // This reduces visual clutter and makes the pattern feel "lighter" on navigation.
        val xAnchors = listOf(-0.30f, 0.05f, 0.40f, 0.75f, 1.10f) // normalized screen x offsets (can go off-screen)
        val startY = -0.22f
        val stepY = 0.60f

        val picks = remember(seed, count, all.size) {
            val c = count.coerceIn(1, 60)
            val shuffled = all.shuffled(rand)
            (0 until c).map { idx ->
                val resId = shuffled[idx % shuffled.size]
                val row = idx / columns
                val col = idx % columns
                val ax = xAnchors.getOrElse(col) { xAnchors.last() }
                val ay = startY + row * stepY + if (col % 2 == 1) (stepY / 2f) else 0f

                val sizeDp = (145f + (idx % 4) * 16f) * scale
                val rot = if (col % 2 == 0) -14f else 14f
                val alpha = (baseAlpha + (idx % 4) * 0.015f).coerceIn(0.06f, 0.20f)
                WallpaperItem(
                    resId = resId,
                    anchorX = ax,
                    anchorY = ay,
                    sizeDp = sizeDp,
                    rotationDeg = rot,
                    alpha = alpha
                )
            }
        }

        picks.forEach { it ->
            val bmp = FoodIconBitmapCache.get(it.resId) {
                ImageBitmap.imageResource(resources, it.resId)
            }
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .size(it.sizeDp.dp)
                    .offset(
                        x = (maxWidth.value * it.anchorX).dp,
                        y = (maxHeight.value * it.anchorY).dp
                    )
                    .rotate(it.rotationDeg),
                alpha = it.alpha,
                contentScale = ContentScale.Fit
            )
        }
    }
}

private data class WallpaperItem(
    val resId: Int,
    val anchorX: Float,
    val anchorY: Float,
    val sizeDp: Float,
    val rotationDeg: Float,
    val alpha: Float
)


