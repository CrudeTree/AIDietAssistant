package com.matchpoint.myaidietapp.logic

import com.matchpoint.myaidietapp.model.DecidedMeal
import com.matchpoint.myaidietapp.model.MealWindow
import com.matchpoint.myaidietapp.model.ScheduledMealDay
import com.matchpoint.myaidietapp.model.UserProfile
import java.time.LocalDate
import java.time.ZoneId

/**
 * Very simple daily planner: creates three meal windows (breakfast, lunch, dinner)
 * and corresponding decided meals at fixed times. This is enough to drive
 * notification scheduling and history tracking.
 */
class DailyPlanner(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    fun planForDay(date: LocalDate, profile: UserProfile): ScheduledMealDay {
        val dayId = date.toString()

        val base = listOf(
            Triple("breakfast", 9, "Breakfast"),
            Triple("lunch", 13, "Lunch"),
            Triple("dinner", 19, "Dinner")
        )

        val windows = mutableListOf<MealWindow>()
        val decidedMeals = mutableListOf<DecidedMeal>()

        for ((id, hour, label) in base) {
            val start = date.atTime(hour, 0).atZone(zoneId).toInstant().toEpochMilli()
            val end = date.atTime(hour + 1, 0).atZone(zoneId).toInstant().toEpochMilli()

            windows += MealWindow(
                id = id,
                startMillis = start,
                endMillis = end
            )

            decidedMeals += DecidedMeal(
                windowId = id,
                exactTimeMillis = start,
                mealSuggestion = "$label for your ${profile.dietType} plan",
                portionSizeOz = 8
            )
        }

        return ScheduledMealDay(
            dayId = dayId,
            windows = windows,
            decidedMeals = decidedMeals
        )
    }
}







