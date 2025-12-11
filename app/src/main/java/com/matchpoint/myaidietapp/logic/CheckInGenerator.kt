package com.matchpoint.myaidietapp.logic

import com.matchpoint.myaidietapp.model.HungerLevel
import com.matchpoint.myaidietapp.model.MealHistoryEntry
import com.matchpoint.myaidietapp.model.WeightEntry
import kotlin.random.Random

class CheckInGenerator {

    fun generatePostMealCheckIn(
        lastMeal: MealHistoryEntry?,
        recentHunger: List<HungerLevel>,
        weightHistory: List<WeightEntry>,
        minutesSinceMeal: Long
    ): String {
        val random = Random(System.currentTimeMillis())

        val weightTrend = estimateWeightTrend(weightHistory)
        val avgHunger = if (recentHunger.isEmpty()) 0.0
        else recentHunger.sumOf { it.score }.toDouble() / recentHunger.size

        val mealText = lastMeal?.let {
            if (it.items.isNotEmpty()) {
                it.items.joinToString(", ")
            } else {
                it.mealName.ifBlank { "that last meal" }
            }
        } ?: "that last meal"

        val openers = mutableListOf<String>()
        when {
            minutesSinceMeal < 45 -> {
                openers += listOf(
                    "Quick check:",
                    "Mini vibe check:",
                    "Real talk:"
                )
            }
            minutesSinceMeal < 120 -> {
                openers += listOf(
                    "How's the cruise going?",
                    "Still riding the wave?",
                    "Steady so far?"
                )
            }
            else -> {
                openers += listOf(
                    "Been a minute:",
                    "Long stretch since food:",
                    "Okay stamina check:"
                )
            }
        }

        val bodyOptions = mutableListOf<String>()

        if (avgHunger < -0.2) {
            bodyOptions += listOf(
                "is $mealText still holding you down or are you starting to fade?",
                "did $mealText brick your hunger in a good way or too much?",
                "still weirdly full from $mealText or finally easing off?"
            )
        } else if (avgHunger > 0.2) {
            bodyOptions += listOf(
                "did $mealText actually do its job or you lowkey hungry again?",
                "be honest, was $mealText kind of mid on satiety?",
                "did $mealText ghost you already or still okay?"
            )
        } else {
            bodyOptions += listOf(
                "how's $mealText sitting? comfy full or just okay?",
                "did $mealText land well or feel like a warm-up?",
                "is $mealText keeping the engine smooth or just background noise?"
            )
        }

        if (weightTrend < -0.3) {
            bodyOptions += listOf(
                "you're dropping nicely, so if that felt light we can bump next round.",
                "weight’s sliding down; if that felt tiny we can safely scale up.",
            )
        } else if (weightTrend > 0.3) {
            bodyOptions += listOf(
                "scale’s creeping a bit, so if that felt huge we might chill next one.",
                "if that felt like overkill, next feeding gets a tiny trim."
            )
        }

        val weirdHumanAddons = listOf(
            "Be real, did that fill you up or was it mid?",
            "Tell me if $mealText betrayed you again lol.",
            "Still riding the steak high or nah?"
        )

        val opener = openers[random.nextInt(openers.size)]
        val body = bodyOptions[random.nextInt(bodyOptions.size)]
        val addon = if (random.nextBoolean()) " ${weirdHumanAddons.random(random)}" else ""

        return "$opener $body$addon"
    }

    private fun estimateWeightTrend(weights: List<WeightEntry>): Double {
        if (weights.size < 2) return 0.0
        val sorted = weights.sortedBy { it.date }
        val first = sorted.first()
        val last = sorted.last()
        return last.weight - first.weight
    }
}






