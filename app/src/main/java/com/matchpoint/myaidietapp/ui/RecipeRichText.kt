package com.matchpoint.myaidietapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    includeAllIconMatchesInText: Boolean = false
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
        style = textStyle
    )
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
            append(" ")
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


