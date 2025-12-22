package com.matchpoint.myaidietapp.ui

import android.content.Context
import com.matchpoint.myaidietapp.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Resolves food icons stored as drawables named:
 *   ic_food_<slugified_food_name>
 *
 * Examples:
 * - "Ground Beef" -> ic_food_ground_beef
 * - "Olive oil"   -> ic_food_olive_oil
 *
 * If no matching drawable exists, returns null.
 */
object FoodIconResolver {
    /**
     * Cache by normalized input name (lowercased, trimmed). 0 means "not found".
     */
    private val cache = ConcurrentHashMap<String, Int>()

    /**
     * All available ic_food_* drawables in this app, keyed by slug (name without the "ic_food_" prefix).
     *
     * Uses reflection so we can do typo-tolerant lookups without needing the Android Context.
     */
    private val foodSlugToResId: Map<String, Int> by lazy {
        val out = HashMap<String, Int>()
        for (f in R.drawable::class.java.fields) {
            val name = f.name
            if (!name.startsWith("ic_food_")) continue
            val slug = name.removePrefix("ic_food_")
            val id = runCatching { f.getInt(null) }.getOrNull() ?: continue
            if (id != 0) out[slug] = id
        }
        out
    }

    fun resolveFoodIconResId(context: Context, foodName: String?, allowFuzzy: Boolean = true): Int? {
        val normalizedKey = foodName?.trim()?.lowercase().orEmpty()
        if (normalizedKey.isBlank()) return null

        val cacheKey = (if (allowFuzzy) "fuzzy|" else "strict|") + normalizedKey
        val cached = cache[cacheKey]
        if (cached != null) return cached.takeIf { it != 0 }

        // 1) Exact / heuristic candidates (fast)
        val candidates = buildCandidates(foodName)
        for (slug in candidates) {
            val id = foodSlugToResId[slug]
            if (id != null) {
                cache[cacheKey] = id
                return id
            }
        }

        if (!allowFuzzy) {
            cache[cacheKey] = 0
            return null
        }

        // 2) Fuzzy fallback (typos like "banan" vs "banana", "sweet_potatoe" vs "sweet_potato")
        // Keep this conservative to avoid wrong icons.
        val best = findFuzzyMatch(candidates)
        cache[cacheKey] = best ?: 0
        return best
    }

    private fun slugify(foodName: String?): String {
        val raw = foodName?.trim()?.lowercase() ?: return ""
        if (raw.isBlank()) return ""

        // Keep letters/numbers; convert everything else to underscores; collapse repeats.
        val sb = StringBuilder(raw.length)
        var prevUnderscore = false
        for (ch in raw) {
            val isOk = ch in 'a'..'z' || ch in '0'..'9'
            if (isOk) {
                sb.append(ch)
                prevUnderscore = false
            } else {
                if (!prevUnderscore) {
                    sb.append('_')
                    prevUnderscore = true
                }
            }
        }
        return sb.toString().trim('_')
    }

    private fun buildCandidates(foodName: String?): List<String> {
        val raw = foodName?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()

        // Common qualifiers in your data like "Banana (raw)".
        val noParen = raw
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*\\]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val beforeComma = raw.substringBefore(",").trim()

        val baseStrings = linkedSetOf(raw, noParen, beforeComma)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val out = linkedSetOf<String>()

        fun addSlugVariants(from: String) {
            val slug = slugify(from)
            if (slug.isBlank()) return
            out.add(slug)

            // Token-window fallbacks:
            // - "granulated sugar" -> try "sugar"
            // - "whole milk" -> try "milk"
            val tokens = slug.split('_').filter { it.isNotBlank() }
            if (tokens.isEmpty()) return

            // Single tokens (prefer the last word: often the noun)
            out.add(tokens.last())
            for (t in tokens) out.add(t)

            // Two-token windows (catch "peanut_butter", "ground_beef", etc.)
            for (i in 0 until tokens.size - 1) {
                out.add(tokens[i] + "_" + tokens[i + 1])
            }

            // Drop common "descriptor" tokens if present (esp. from parentheses)
            val stop = setOf(
                "raw", "fresh", "frozen", "cooked", "uncooked", "dried", "canned", "smoked",
                "boneless", "skinless", "lean", "whole", "lowfat", "low_fat", "fatfree", "fat_free"
            )
            val filtered = tokens.filter { it !in stop }
            if (filtered.isNotEmpty() && filtered.size != tokens.size) {
                out.add(filtered.joinToString("_"))
                out.add(filtered.last())
            }
        }

        baseStrings.forEach { addSlugVariants(it) }
        return out.toList()
    }

    private fun findFuzzyMatch(candidates: List<String>): Int? {
        if (foodSlugToResId.isEmpty() || candidates.isEmpty()) return null

        var bestSlug: String? = null
        var bestId: Int? = null
        var bestDist = Int.MAX_VALUE

        for (cand in candidates) {
            if (cand.isBlank()) continue
            val threshold = if (cand.length <= 5) 1 else 2
            for ((slug, id) in foodSlugToResId) {
                // Very cheap prefilter
                if (slug.isEmpty() || slug[0] != cand[0]) continue
                val dist = levenshtein(cand, slug, bestDist)
                if (dist < bestDist && dist <= threshold) {
                    bestDist = dist
                    bestSlug = slug
                    bestId = id
                    if (bestDist == 0) return bestId
                }
            }
        }

        return bestId
    }

    /**
     * Bounded Levenshtein distance with an optional early-exit bound.
     */
    private fun levenshtein(a: String, b: String, upperBound: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // If length difference already exceeds upper bound, no need to compute.
        val lenDiff = kotlin.math.abs(a.length - b.length)
        if (lenDiff > upperBound) return upperBound + 1

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in a.indices) {
            curr[0] = i + 1
            var rowBest = curr[0]
            val ca = a[i]
            for (j in b.indices) {
                val cost = if (ca == b[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost
                )
                rowBest = min(rowBest, curr[j + 1])
            }
            if (rowBest > upperBound) return upperBound + 1
            // swap
            for (k in prev.indices) prev[k] = curr[k]
        }
        return prev[b.length]
    }
}


