package com.matchpoint.myaidietapp.ui

import android.content.Context

/**
 * Detects ingredient-like phrases inside recipe text by leveraging the existing ic_food_* icon set.
 *
 * We treat "having an icon" as "this is a known ingredient concept". Then we can compare
 * the detected icon IDs against the user's own ingredient list to determine what's missing.
 */
object RecipeIngredientDetector {
    data class DetectedIngredient(
        val resId: Int,
        val label: String
    )

    /**
     * Scan [text] for ingredient phrases (1..[maxNgram] words) that resolve to an ic_food_* icon.
     * Returns unique results keyed by icon resId.
     */
    fun detect(context: Context, text: String, maxNgram: Int = 3, limit: Int = 60): List<DetectedIngredient> {
        val cleanedWords = tokenizeWords(text)
        if (cleanedWords.isEmpty()) return emptyList()

        val foundById = linkedMapOf<Int, String>()

        val maxN = maxNgram.coerceIn(1, 4)
        for (n in maxN downTo 1) {
            if (foundById.size >= limit) break
            for (i in 0..(cleanedWords.size - n)) {
                if (foundById.size >= limit) break
                val phrase = cleanedWords.subList(i, i + n).joinToString(" ")
                val id = resolveWithPepperHeuristics(context, cleanedWords, i, n, phrase) ?: continue
                foundById.putIfAbsent(id, phrase)
            }
        }

        return foundById.entries.map { (id, label) -> DetectedIngredient(resId = id, label = label) }
    }

    private fun tokenizeWords(text: String): List<String> {
        // Keep it simple and stable (same behavior across devices):
        // - lowercased
        // - remove parenthetical notes
        // - keep only [a-z0-9 ] as token separators
        return text
            .lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            // Make sure common separators become token boundaries.
            .replace("/", " ")
            .replace(Regex("[^a-z0-9\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
    }

    /**
     * Disambiguation for pepper:
     * - If the phrase already specifies type ("black pepper", "red pepper", "bell pepper"), let FoodIconResolver handle it.
     * - If it's just "pepper", only map it to BLACK pepper in a seasoning context like "salt and pepper" / "salt pepper" / "salt/pepper".
     *   Otherwise, skip it to avoid showing the wrong pepper icon.
     */
    private fun resolveWithPepperHeuristics(
        context: Context,
        tokens: List<String>,
        start: Int,
        n: Int,
        phrase: String
    ): Int? {
        // First try normal resolution.
        val direct = FoodIconResolver.resolveFoodIconResId(context, phrase, allowFuzzy = false)
        if (direct != null) return direct

        // Special-case: "pepper"
        if (n == 1 && phrase == "pepper") {
            // Look for "salt" within 2 tokens on either side: "salt and pepper", "salt pepper", "salt/pepper".
            val left = (start - 2).coerceAtLeast(0)
            val right = (start + 2).coerceAtMost(tokens.lastIndex)
            val hasSaltNearby = (left..right).any { idx -> tokens[idx] == "salt" }
            if (!hasSaltNearby) return null

            // Prefer black pepper icon for the generic "pepper" seasoning reference.
            return FoodIconResolver.resolveFoodIconResId(context, "black pepper", allowFuzzy = false)
                ?: FoodIconResolver.resolveFoodIconResId(context, "black_pepper", allowFuzzy = false)
        }

        return null
    }
}


