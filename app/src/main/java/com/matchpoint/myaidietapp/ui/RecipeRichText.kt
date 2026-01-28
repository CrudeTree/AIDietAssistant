package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchpoint.myaidietapp.R

/**
 * Renders recipe text and injects small ingredient icons inline "kids book" style
 * whenever we spot a known ingredient that has an ic_food_* drawable.
 *
 * This is intentionally conservative: if we can't find an icon, we leave text as-is.
 */
@Composable
fun RecipeTextWithIngredientIcons(
    text: String,
    ingredients: List<String>,
    modifier: Modifier = Modifier,
    iconSize: TextUnit = 20.sp,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    includeAllIconMatchesInText: Boolean = false,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    val context = LocalContext.current

    // Build known icon phrases from the ingredients list by probing FoodIconResolver.
    val phraseToResId = remember(ingredients, text, includeAllIconMatchesInText) {
        val map = linkedMapOf<String, Int>() // lowercased phrase -> drawable id

        fun wordsOnly(s: String): List<String> {
            return s.lowercase()
                .replace(Regex("\\([^)]*\\)"), " ")
                .replace(Regex("[^a-z0-9\\s]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .split(" ")
                .filter { it.isNotBlank() }
        }

        for (raw in ingredients) {
            val words = wordsOnly(raw)
            if (words.isEmpty()) continue

            // Try best phrases first (3-grams down to 1-grams).
            val maxN = minOf(3, words.size)
            var found: Pair<String, Int>? = null
            for (n in maxN downTo 1) {
                for (i in 0..(words.size - n)) {
                    val phrase = words.subList(i, i + n).joinToString(" ")
                    val id = FoodIconResolver.resolveFoodIconResId(context, phrase)
                    if (id != null) {
                        found = phrase to id
                        break
                    }
                }
                if (found != null) break
            }
            if (found != null) {
                val (phrase, id) = found
                map.putIfAbsent(phrase, id)
            }
        }

        if (includeAllIconMatchesInText) {
            // Also detect icon-backed ingredient phrases directly from the text so we can show
            // icons even for ingredients not listed in the saved recipe's "Ingredients" section.
            //
            // We do the scan here (instead of reusing detect()) so we can keep the *longest*
            // phrase for a given icon id (e.g. prefer "olive oil" over "oil").
            fun tokenizeRecipeText(s: String): List<String> {
                return s.lowercase()
                    .replace(Regex("\\([^)]*\\)"), " ")
                    .replace("/", " ")
                    .replace("+", " ")
                    .replace(Regex("[^a-z0-9\\s]+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .split(" ")
                    .filter { it.isNotBlank() }
            }

            val tokens = tokenizeRecipeText(text)
            val bestPhraseById = linkedMapOf<Int, String>()
            for (n in 3 downTo 1) {
                for (i in 0..(tokens.size - n)) {
                    val phrase = tokens.subList(i, i + n).joinToString(" ")
                    val id = FoodIconResolver.resolveFoodIconResId(context, phrase, allowFuzzy = false) ?: continue
                    val prev = bestPhraseById[id]
                    if (prev == null || phrase.length > prev.length) {
                        bestPhraseById[id] = phrase
                    }
                }
            }

            for ((id, phrase) in bestPhraseById) {
                map.putIfAbsent(phrase, id)
            }
        }
        map
    }

    val (annotated, inline) = remember(text, phraseToResId) {
        buildAnnotatedWithIcons(
            original = text,
            phraseToResId = phraseToResId,
            iconSize = iconSize
        )
    }

    Text(
        text = annotated,
        inlineContent = inline,
        modifier = modifier,
        style = textStyle,
        color = textColor
    )
}

private sealed interface RecipeBlock {
    data class TextBlock(val text: String) : RecipeBlock
    data class PhaseBlock(val key: String) : RecipeBlock
}

private val phaseRegex = Regex("""\{phase:\s*([A-Za-z0-9_\- ]+)\s*\}""")
private val headingRegex = Regex("""^(#{1,6})\s*(.+)$""")
private val titleLineRegex = Regex("""(?i)^\s*title\s*:\s*(.+)\s*$""")
private val difficultyLineRegex = Regex("""(?i)^\s*\(\s*(simple|advanced|expert)\s*\)\s*$""")
private val difficultyLabelLineRegex = Regex("""(?i)^\s*difficulty\s*:\s*(simple|advanced|expert)\s*$""")

private fun titleFromLine(line: String): String? {
    val m = titleLineRegex.matchEntire(line.trim()) ?: return null
    return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun difficultyFromLine(line: String): String? {
    val m = difficultyLineRegex.matchEntire(line.trim()) ?: return null
    val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
    if (raw.isBlank()) return null
    return raw.replaceFirstChar { it.uppercase() }
}

private fun difficultyLabelFromLine(line: String): String? {
    val m = difficultyLabelLineRegex.matchEntire(line.trim()) ?: return null
    val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
    if (raw.isBlank()) return null
    return raw.replaceFirstChar { it.uppercase() }
}

private fun titleStyle(base: TextStyle): TextStyle {
    val baseSp = (if (base.fontSize != TextUnit.Unspecified) base.fontSize.value else 16f)
    val sizeSp = (baseSp * 1.65f).coerceAtMost(baseSp + 14f)
    return base.copy(
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.12f).sp,
        fontWeight = FontWeight.Bold
    )
}

private fun headingInfo(line: String): Pair<Int, String>? {
    val m = headingRegex.matchEntire(line.trim()) ?: return null
    val level = m.groupValues.getOrNull(1)?.length ?: return null
    val text = m.groupValues.getOrNull(2)?.trim().orEmpty()
    if (text.isBlank()) return null
    // We only expect H2-ish from the AI (## Cook), but allow a couple levels.
    return level to text
}

private fun headingStyle(base: TextStyle, level: Int): TextStyle {
    val baseSp = (if (base.fontSize != TextUnit.Unspecified) base.fontSize.value else 16f)
    val sizeSp = when (level) {
        1 -> baseSp * 1.50f
        2 -> baseSp * 1.35f
        3 -> baseSp * 1.22f
        else -> baseSp * 1.15f
    }
    return base.copy(
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.15f).sp,
        fontWeight = FontWeight.Bold
    )
}

private fun normalizePhaseKey(raw: String): String {
    return raw.trim()
        .uppercase()
        .replace("-", "_")
        .replace(" ", "_")
        .replace(Regex("_+"), "_")
}

private fun phaseDrawableResId(key: String): Int? {
    return when (normalizePhaseKey(key)) {
        "PREP", "MISE_EN_PLACE" -> R.drawable.ic_phase_prep
        "WASH" -> R.drawable.ic_phase_wash
        "CHOP" -> R.drawable.ic_phase_chop
        "MEASURE" -> R.drawable.ic_phase_measure
        "MIX", "COMBINE" -> R.drawable.ic_phase_mix
        "MARINATE" -> R.drawable.ic_phase_marinate
        "PREHEAT" -> R.drawable.ic_phase_preheat
        "BAKE" -> R.drawable.ic_phase_bake
        "BOIL" -> R.drawable.ic_phase_boil
        "SIMMER" -> R.drawable.ic_phase_simmer
        "SEAR" -> R.drawable.ic_phase_sear
        "SEASON", "SEASONING" -> R.drawable.ic_phase_season
        "SAUTE", "SAUTÉ" -> R.drawable.ic_phase_saute
        "DRAIN", "STRAIN" -> R.drawable.ic_phase_drain
        "GRATE", "SHRED" -> R.drawable.ic_phase_grate
        "PEEL" -> R.drawable.ic_phase_peel
        "POUR", "ADD_LIQUID" -> R.drawable.ic_phase_pour
        "REDUCE_HEAT", "LOWER_HEAT" -> R.drawable.ic_phase_reduce_heat
        "SET_TIMER", "TIMER" -> R.drawable.ic_phase_set_timer
        "SKILLET", "PAN" -> R.drawable.ic_phase_skillet
        "WHISK", "WHIP", "BEAT" -> R.drawable.ic_phase_whisk
        "MEDIUM_BOWL", "BOWL" -> R.drawable.ic_phase_medium_bowl
        else -> null
    }
}

private fun parseRecipeBlocks(text: String): List<RecipeBlock> {
    if (text.isBlank()) return listOf(RecipeBlock.TextBlock(text))
    val out = mutableListOf<RecipeBlock>()
    var last = 0
    for (m in phaseRegex.findAll(text)) {
        val start = m.range.first
        val end = m.range.last + 1
        if (start > last) {
            out.add(RecipeBlock.TextBlock(text.substring(last, start)))
        }
        val key = m.groupValues.getOrNull(1).orEmpty()
        out.add(RecipeBlock.PhaseBlock(key))
        last = end
    }
    if (last < text.length) out.add(RecipeBlock.TextBlock(text.substring(last)))
    return out.ifEmpty { listOf(RecipeBlock.TextBlock(text)) }
}

@Composable
fun RecipeTextWithPhasesAndIngredientIcons(
    text: String,
    ingredients: List<String>,
    modifier: Modifier = Modifier,
    iconSize: TextUnit = 20.sp,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    includeAllIconMatchesInText: Boolean = false,
    showIngredientIcons: Boolean = true,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    renderTitle: Boolean = true
) {
    val blocks = remember(text) { parseRecipeBlocks(text) }
    var titleRendered = false
    var titleSkipped = false
    var difficultySkipped = false

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (b in blocks) {
            when (b) {
                is RecipeBlock.TextBlock -> {
                    // Render markdown-ish headings (## Cook) as actual headers (bigger/bold),
                    // while still allowing ingredient icons in body lines.
                    val lines = b.text.trim('\n').lines()
                    val paragraph = StringBuilder()

                    @Composable
                    fun flushParagraph() {
                        val p = paragraph.toString().trim()
                        paragraph.clear()
                        if (p.isBlank()) return
                        if (showIngredientIcons) {
                            // Strip simple markdown markers so we don't show raw "##" or "**".
                            val cleaned = p
                                .replace("**", "")
                                .replace("__", "")
                            RecipeTextWithIngredientIcons(
                                text = cleaned,
                                ingredients = ingredients,
                                iconSize = iconSize,
                                textStyle = textStyle,
                                includeAllIconMatchesInText = includeAllIconMatchesInText,
                                textColor = textColor,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = MarkdownLite.toAnnotatedString(p),
                                style = textStyle,
                                color = textColor,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    for (rawLine in lines) {
                        val line = rawLine.trimEnd()
                        val title = if (renderTitle && !titleRendered) titleFromLine(line) else null
                        val heading = headingInfo(line)
                        when {
                            !renderTitle && !titleSkipped && titleFromLine(line) != null -> {
                                // If the screen renders its own title (e.g. RecipeDetailScreen),
                                // drop the raw "Title: ..." line from the body so it doesn't duplicate.
                                flushParagraph()
                                titleSkipped = true
                            }
                            !renderTitle && !titleSkipped && heading != null && heading.first == 1 -> {
                                // Same for "# Title" markdown headings.
                                flushParagraph()
                                titleSkipped = true
                            }
                            !renderTitle && titleSkipped && !difficultySkipped && difficultyFromLine(line) != null -> {
                                // If we show the difficulty under the title in the screen header,
                                // drop the raw "(Expert)" line from the body to avoid duplication.
                                flushParagraph()
                                difficultySkipped = true
                            }
                            !renderTitle && titleSkipped && !difficultySkipped && difficultyLabelFromLine(line) != null -> {
                                flushParagraph()
                                difficultySkipped = true
                            }
                            title != null -> {
                                flushParagraph()
                                Text(
                                    text = title,
                                    style = titleStyle(textStyle),
                                    color = textColor,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                titleRendered = true
                            }
                            heading != null && renderTitle && !titleRendered && heading.first == 1 -> {
                                // Treat leading "# Title" as the recipe title (centered).
                                flushParagraph()
                                Text(
                                    text = heading.second,
                                    style = titleStyle(textStyle),
                                    color = textColor,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                titleRendered = true
                            }
                            heading != null -> {
                                flushParagraph()
                                val (level, title) = heading
                                Text(
                                    text = title,
                                    style = headingStyle(textStyle, level),
                                    color = textColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            line.isBlank() -> {
                                flushParagraph()
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            else -> {
                                paragraph.append(line).append('\n')
                            }
                        }
                    }
                    flushParagraph()
                }
                is RecipeBlock.PhaseBlock -> {
                    val resId = phaseDrawableResId(b.key) ?: continue
                    Image(
                        painter = painterResource(id = resId),
                        contentDescription = "Phase ${normalizePhaseKey(b.key)}",
                        modifier = Modifier
                            .fillMaxWidth()
                            // ~2x larger
                            .height(108.dp)
                            .alpha(0.95f),
                        alignment = androidx.compose.ui.Alignment.Center,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
fun RecipeMarkdownWithPhases(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    renderTitle: Boolean = true
) {
    val blocks = remember(text) { parseRecipeBlocks(text) }
    var titleRendered = false
    var titleSkipped = false
    var difficultySkipped = false
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (b in blocks) {
            when (b) {
                is RecipeBlock.TextBlock -> {
                    // Render headings (## Cook) as actual headers.
                    val lines = b.text.trim('\n').lines()
                    val paragraph = StringBuilder()

                    @Composable
                    fun flushParagraph() {
                        val p = paragraph.toString().trim()
                        paragraph.clear()
                        if (p.isBlank()) return
                        Text(
                            text = MarkdownLite.toAnnotatedString(p),
                            style = textStyle,
                            color = textColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    for (rawLine in lines) {
                        val line = rawLine.trimEnd()
                        val title = if (renderTitle && !titleRendered) titleFromLine(line) else null
                        val heading = headingInfo(line)
                        when {
                            !renderTitle && !titleSkipped && titleFromLine(line) != null -> {
                                flushParagraph()
                                titleSkipped = true
                            }
                            !renderTitle && !titleSkipped && heading != null && heading.first == 1 -> {
                                flushParagraph()
                                titleSkipped = true
                            }
                            !renderTitle && titleSkipped && !difficultySkipped && difficultyFromLine(line) != null -> {
                                flushParagraph()
                                difficultySkipped = true
                            }
                            !renderTitle && titleSkipped && !difficultySkipped && difficultyLabelFromLine(line) != null -> {
                                flushParagraph()
                                difficultySkipped = true
                            }
                            title != null -> {
                                flushParagraph()
                                Text(
                                    text = title,
                                    style = titleStyle(textStyle),
                                    color = textColor,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                titleRendered = true
                            }
                            heading != null && renderTitle && !titleRendered && heading.first == 1 -> {
                                flushParagraph()
                                Text(
                                    text = heading.second,
                                    style = titleStyle(textStyle),
                                    color = textColor,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                titleRendered = true
                            }
                            heading != null -> {
                                flushParagraph()
                                val (level, title) = heading
                                Text(
                                    text = title,
                                    style = headingStyle(textStyle, level),
                                    color = textColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            line.isBlank() -> {
                                flushParagraph()
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            else -> {
                                paragraph.append(line).append('\n')
                            }
                        }
                    }
                    flushParagraph()
                }
                is RecipeBlock.PhaseBlock -> {
                    val resId = phaseDrawableResId(b.key) ?: continue
                    Image(
                        painter = painterResource(id = resId),
                        contentDescription = "Phase ${normalizePhaseKey(b.key)}",
                        modifier = Modifier
                            .fillMaxWidth()
                            // ~2x larger
                            .height(108.dp)
                            .alpha(0.95f),
                        alignment = androidx.compose.ui.Alignment.Center,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
            }
        }
    }
}

private fun buildAnnotatedWithIcons(
    original: String,
    phraseToResId: Map<String, Int>,
    iconSize: TextUnit
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    if (original.isBlank() || phraseToResId.isEmpty()) {
        return AnnotatedString(original) to emptyMap()
    }

    val lower = original.lowercase()
    val phrases = phraseToResId.keys
        .sortedByDescending { it.length } // prefer longer matches like "olive oil"

    data class Hit(val start: Int, val end: Int, val phrase: String, val resId: Int)
    val hits = mutableListOf<Hit>()

    // Greedy scan: find non-overlapping hits, prefer longer phrases first.
    // IMPORTANT: Use a regex that allows punctuation separators so we match:
    // - "garlic-powder" when phrase is "garlic powder"
    // - "salt/pepper" when phrase is "salt pepper"
    val occupied = BooleanArray(lower.length)
    fun overlaps(start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (i in occupied.indices && occupied[i]) return true
        }
        return false
    }
    fun mark(start: Int, end: Int) {
        for (i in start until end) {
            if (i in occupied.indices) occupied[i] = true
        }
    }

    for (phrase in phrases) {
        val resId = phraseToResId[phrase] ?: continue
        val words = phrase.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) continue

        // Boundaries: don't match inside other words/numbers.
        // Separator between words: one-or-more non-alphanumeric characters.
        val sep = "[^a-z0-9]+"
        val pattern = buildString {
            append("(?<![a-z0-9])")
            words.forEachIndexed { idx, w ->
                if (idx > 0) append(sep)
                append(Regex.escape(w))
            }
            append("(?![a-z0-9])")
        }
        val re = Regex(pattern)

        for (m in re.findAll(lower)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start < 0 || end > lower.length || start >= end) continue
            if (overlaps(start, end)) continue
            mark(start, end)
            hits.add(Hit(start = start, end = end, phrase = phrase, resId = resId))
        }
    }

    if (hits.isEmpty()) return AnnotatedString(original) to emptyMap()
    hits.sortBy { it.start }

    val inline = linkedMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        var cursor = 0
        var counter = 0
        for (h in hits) {
            if (h.start < cursor) continue
            if (cursor < h.start) append(original.substring(cursor, h.start))

            // Insert the original matched text, then the icon (kids-book style).
            val key = "ingIcon_${counter++}_${h.resId}"
            append(original.substring(h.start, h.end))
            // Keep the icon glued to the preceding word/phrase so it doesn't wrap onto the next line alone.
            // (This prevents cases where the icon appears to belong to the next line/section.)
            append("\u00A0")
            appendInlineContent(key, alternateText = "•")

            inline[key] = InlineTextContent(
                placeholder = Placeholder(
                    width = iconSize,
                    height = iconSize,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                )
            ) {
                Image(
                    painter = painterResource(id = h.resId),
                    contentDescription = h.phrase,
                    // Let the icon scale to the placeholder size (driven by iconSize param).
                    modifier = Modifier.fillMaxSize()
                )
            }

            cursor = h.end
        }
        if (cursor < original.length) append(original.substring(cursor))
    }

    return annotated to inline
}


