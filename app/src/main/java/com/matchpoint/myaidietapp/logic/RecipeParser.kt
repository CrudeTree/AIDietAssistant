package com.matchpoint.myaidietapp.logic

/**
 * Parses the recipe format produced by the app's "generate_meal" prompt.
 *
 * Expected-ish format:
 * Title: ...
 * Why it fits: ...
 * Ingredients (from my list):
 * - ...
 * Missing / recommended to buy (if needed):
 * - ...
 * Steps:
 * 1) ...
 */
object RecipeParser {
    data class ParsedRecipe(
        val title: String,
        val ingredients: List<String>
    )

    fun parse(text: String): ParsedRecipe {
        val t = (text ?: "").trim()
        val title = parseTitle(t)
        val ingredients = parseIngredients(t)
        return ParsedRecipe(title = title, ingredients = ingredients)
    }

    private fun parseTitle(text: String): String {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Title:", ignoreCase = true) }
            ?: return ""
        return line.substringAfter(":", "").trim()
    }

    private fun parseIngredients(text: String): List<String> {
        val lines = text.lines()
        if (lines.isEmpty()) return emptyList()

        fun isSectionHeader(s: String): Boolean {
            val x = s.trim()
            if (x.isBlank()) return false
            return x.startsWith("Steps", ignoreCase = true) ||
                x.startsWith("Cook time", ignoreCase = true) ||
                x.startsWith("Temp", ignoreCase = true) ||
                x.startsWith("Notes", ignoreCase = true) ||
                x.startsWith("Missing", ignoreCase = true) ||
                x.startsWith("Why it fits", ignoreCase = true) ||
                x.startsWith("Title", ignoreCase = true)
        }

        // Find the first "Ingredients" header.
        var startIdx = -1
        for (i in lines.indices) {
            val s = lines[i].trim()
            if (s.startsWith("Ingredients", ignoreCase = true)) {
                startIdx = i + 1
                break
            }
        }
        if (startIdx == -1) return emptyList()

        val out = mutableListOf<String>()
        for (i in startIdx until lines.size) {
            val raw = lines[i].trim()
            if (raw.isBlank()) continue
            if (isSectionHeader(raw)) break

            val cleaned = raw
                .removePrefix("-").removePrefix("•").trim()
                .takeIf { it.isNotBlank() }
                ?: continue

            // Keep it relatively raw; FoodIconResolver is robust.
            out.add(cleaned)
        }

        return out.distinctBy { it.trim().lowercase() }
    }
}


