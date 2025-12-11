package com.matchpoint.myaidietapp.model

import com.google.firebase.Timestamp

enum class DietType {
    CARNIVORE,
    KETO,
    VEGAN,
    VEGETARIAN,
    PALEO,
    OMNIVORE,
    OTHER
}

enum class HungerLevel(val score: Int) {
    STILL_FULL(-1),
    LITTLE_HUNGRY(0),
    STARVING(1)
}

data class WeightEntry(
    val date: String = "",
    val weight: Double = 0.0
)

data class HungerSignal(
    val timestamp: Timestamp = Timestamp.now(),
    val level: HungerLevel = HungerLevel.LITTLE_HUNGRY
)

data class MealHistoryEntry(
    val timestamp: Timestamp = Timestamp.now(),
    val mealName: String = "",
    val items: List<String> = emptyList(),
    val portionSizeOz: Int = 0,
    val satietyFeedback: HungerLevel? = null
)

data class WearableData(
    val lastHR: Int? = null,
    val lastHRV: Int? = null,
    val lastSleepHours: Double? = null,
    val lastSteps: Int? = null,
    val lastWorkoutMinutes: Int? = null
)

data class FoodItem(
    val id: String = "",
    val name: String = "",
    val quantity: Int = 1,
    val photoUrl: String? = null,
    val labelUrl: String? = null,
    val notes: String? = null
)

data class UserProfile(
    val name: String = "",
    val weightGoal: Double? = null,
    val dietType: DietType = DietType.CARNIVORE,
    val allowedFoods: List<String> = emptyList(),
    val inventory: Map<String, Int> = emptyMap(),
    val foodItems: List<FoodItem> = emptyList(),
    val weightHistory: List<WeightEntry> = emptyList(),
    val hungerSignals: List<HungerSignal> = emptyList(),
    val mealHistory: List<MealHistoryEntry> = emptyList(),
    val wearableData: WearableData? = null,
    val nextCheckInAtMillis: Long? = null,
    val nextMealAtMillis: Long? = null
)

data class MealWindow(
    val id: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L
)

data class DecidedMeal(
    val windowId: String = "",
    val exactTimeMillis: Long = 0L,
    val mealSuggestion: String = "",
    val portionSizeOz: Int = 0
)

data class ScheduledMealDay(
    val dayId: String = "",
    val windows: List<MealWindow> = emptyList(),
    val decidedMeals: List<DecidedMeal> = emptyList()
)

enum class MessageSender {
    AI, USER
}

data class MessageEntry(
    val id: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val sender: MessageSender = MessageSender.AI,
    val text: String = ""
)

data class MessageLog(
    val userId: String = "",
    val log: List<MessageEntry> = emptyList()
)





