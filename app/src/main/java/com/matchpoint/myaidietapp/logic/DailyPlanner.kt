package com.matchpoint.myaidietapp.logic

import com.matchpoint.myaidietapp.model.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

class DailyPlanner(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    /**
     * MVP: two meals per day with soft windows and randomized exact times.
     * - Meal 1 window: late morning / early afternoon
     * - Meal 2 window: evening
     * We nudge window widths and portions slightly based on recent hunger / weight trend.
     */
    fun planForDay(
        day: LocalDate,
        profile: UserProfile
    ): ScheduledMealDay {
        val random = Random(day.toEpochDay())

        val weightTrend = estimateWeightTrend(profile.weightHistory)
        val avgHunger = averageRecentHunger(profile.hungerSignals)

        // Base windows in local time
        var firstStart = LocalTime.of(11, 0)
        var firstEnd = LocalTime.of(13, 0)
        var secondStart = LocalTime.of(17, 0)
        var secondEnd = LocalTime.of(20, 0)

        // Adjust based on hunger: more hungry => earlier and maybe slightly longer
        if (avgHunger > 0) {
            firstStart = firstStart.minusMinutes(30)
            secondStart = secondStart.minusMinutes(30)
        } else if (avgHunger < 0) {
            firstStart = firstStart.plusMinutes(30)
        }

        // Simple caloric modulation by weight trend: losing fast => add a bit, gaining => reduce
        val basePortionMeal1 = 10
        val basePortionMeal2 = 8
        val weightAdjustment = when {
            weightTrend <= -0.5 -> 2 // losing quickly, add a bit
            weightTrend >= 0.5 -> -2 // gaining quickly, trim
            else -> 0
        }

        val meal1Portion = (basePortionMeal1 + weightAdjustment).coerceAtLeast(6)
        val meal2Portion = (basePortionMeal2 + weightAdjustment).coerceAtLeast(6)

        val dayId = day.toString()

        val window1 = MealWindow(
            id = "w1",
            startMillis = day.atTime(firstStart).atZone(zoneId).toInstant().toEpochMilli(),
            endMillis = day.atTime(firstEnd).atZone(zoneId).toInstant().toEpochMilli()
        )
        val window2 = MealWindow(
            id = "w2",
            startMillis = day.atTime(secondStart).atZone(zoneId).toInstant().toEpochMilli(),
            endMillis = day.atTime(secondEnd).atZone(zoneId).toInstant().toEpochMilli()
        )

        val exactMeal1Time = randomTimeInWindow(random, window1)
        val exactMeal2Time = randomTimeInWindow(random, window2)

        val meals = listOf(
            DecidedMeal(
                windowId = window1.id,
                exactTimeMillis = exactMeal1Time,
                mealSuggestion = generateMealSuggestion(profile, meal1Portion, random),
                portionSizeOz = meal1Portion
            ),
            DecidedMeal(
                windowId = window2.id,
                exactTimeMillis = exactMeal2Time,
                mealSuggestion = generateMealSuggestion(profile, meal2Portion, random),
                portionSizeOz = meal2Portion
            )
        )

        return ScheduledMealDay(
            dayId = dayId,
            windows = listOf(window1, window2),
            decidedMeals = meals
        )
    }

    private fun randomTimeInWindow(random: Random, window: MealWindow): Long {
        if (window.endMillis <= window.startMillis) return window.startMillis
        val span = window.endMillis - window.startMillis
        val offset = random.nextLong(0, span)
        return window.startMillis + offset
    }

    private fun estimateWeightTrend(weights: List<WeightEntry>): Double {
        if (weights.size < 2) return 0.0
        val sorted = weights.sortedBy { it.date }
        val first = sorted.first()
        val last = sorted.last()
        return last.weight - first.weight
    }

    private fun averageRecentHunger(signals: List<HungerSignal>): Int {
        if (signals.isEmpty()) return 0
        val recent = signals.takeLast(10)
        return (recent.sumOf { it.level.score }).let {
            if (recent.isEmpty()) 0 else it / recent.size
        }
    }

    private fun generateMealSuggestion(
        profile: UserProfile,
        portionOz: Int,
        random: Random
    ): String {
        val inventoryKeys = profile.inventory.filter { it.value > 0 }.keys
        val baseOptions = if (inventoryKeys.isNotEmpty()) {
            inventoryKeys.toList()
        } else if (profile.allowedFoods.isNotEmpty()) {
            profile.allowedFoods
        } else {
            listOf("ribeye", "ground beef", "bacon", "eggs")
        }

        val food = baseOptions[random.nextInt(baseOptions.size)]
        val style = listOf(
            "cooked in butter",
            "seared in tallow",
            "pan fried crispy",
            "grilled how you like it"
        )[random.nextInt(4)]

        return "$portionOz oz $food $style"
    }
}






