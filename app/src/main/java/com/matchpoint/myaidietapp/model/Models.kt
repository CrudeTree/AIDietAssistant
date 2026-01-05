package com.matchpoint.myaidietapp.model

import com.google.firebase.Timestamp

enum class DietType {
    NO_DIET,
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

enum class FastingPreset(
    val label: String,
    val eatingWindowHours: Int? // null means "no fasting / no fixed window"
) {
    NONE("No fasting window", null),
    FAST_12_12("12:12 (12h eating window)", 12),
    FAST_14_10("14:10 (10h eating window)", 10),
    FAST_16_8("16:8 (8h eating window)", 8),
    FAST_18_6("18:6 (6h eating window)", 6),
    FAST_20_4("20:4 (4h eating window)", 4),
    OMAD("OMAD (1h eating window)", 1)
}

enum class SubscriptionTier {
    FREE,
    REGULAR,
    PRO
}

enum class WeightUnit {
    LB,
    KG
}

data class WeightEntry(
    val date: String = "",
    /**
     * Stored in pounds (lb) internally; UI converts based on [UserProfile.weightUnit].
     */
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
    val satietyFeedback: HungerLevel? = null,
    /**
     * Optional logging fields used by the "AI Food Coach" to infer satiety timing.
     */
    val mealPhotoUrl: String? = null,
    val totalGrams: Int? = null,
    val estimatedCalories: Int? = null,
    val estimatedProteinG: Int? = null,
    val estimatedCarbsG: Int? = null,
    val estimatedFatG: Int? = null,
    val aiNotes: String? = null,
    /**
     * Optional ratings for the logged meal itself (useful for UI + future planning).
     */
    val healthRating: Int? = null,
    val dietFitRating: Int? = null,
    val dietRatings: Map<String, Int> = emptyMap(),
    val allergyRatings: Map<String, Int> = emptyMap()
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
    /**
     * Categories for how the AI should treat this item.
     * Allowed values: "MEAL", "INGREDIENT", "SNACK"
     * (Multiple allowed: e.g. banana can be INGREDIENT + SNACK.)
     */
    val categories: List<String> = emptyList(),
    val photoUrl: String? = null,
    val labelUrl: String? = null,
    val nutritionFactsUrl: String? = null,
    val notes: String? = null,
    /**
     * Optional nutrition + ingredient estimates.
     * - For text-only: these are best guesses.
     * - For photo-based: these should come from label/nutrition facts when readable,
     *   otherwise a best guess.
     */
    val estimatedCalories: Int? = null,
    val estimatedProteinG: Int? = null,
    val estimatedCarbsG: Int? = null,
    val estimatedFatG: Int? = null,
    val ingredientsText: String? = null,
    /**
     * General health rating (1-10) regardless of diet.
     */
    val rating: Int? = null,
    /**
     * How well this food fits the current diet (1-10),
     * e.g. fig bars might be 7/10 for health but 1/10 for carnivore.
     */
    val dietFitRating: Int? = null,
    /**
     * Ratings for many diets so switching diets doesn't create "missing" ratings.
     * Keys are DietType.name (e.g. "CARNIVORE", "KETO", "VEGAN", ...).
     */
    val dietRatings: Map<String, Int> = emptyMap(),
    /**
     * Optional allergy "safety/fit" ratings (1-10) so we can warn users.
     * Keys are strings like "PEANUT", "TREE_NUT", "DAIRY", "EGG", "SOY",
     * "SHELLFISH", "FISH", "SESAME", "GLUTEN", etc.
     */
    val allergyRatings: Map<String, Int> = emptyMap()
)

data class UserProfile(
    val name: String = "",
    /**
     * Preferred display/input unit for weights. Defaults to LB for new users.
     * Internally, weights are stored in pounds (lb).
     */
    val weightUnit: WeightUnit = WeightUnit.LB,
    val weightGoal: Double? = null,
    val dietType: DietType = DietType.CARNIVORE,
    // Pricing tier (local MVP; later should be driven by Play Billing + server checks)
    val subscriptionTier: SubscriptionTier = SubscriptionTier.FREE,
    /**
     * UI preference: show cute food/ingredient icons (ic_food_*) in lists and recipes.
     */
    val showFoodIcons: Boolean = true,
    /**
     * UI preference: base font size (sp) for long-form content like recipes and chat.
     * Kept in a safe range in UI (e.g. 12..40) to avoid breaking layouts.
     */
    val uiFontSizeSp: Float = 18f,
    val fastingPreset: FastingPreset = FastingPreset.NONE,
    /**
     * Local-time eating window start/end (minutes from midnight).
     * Used when fastingPreset != NONE.
     * Defaults to 12:00–20:00 for common 16:8-ish behavior, but user can change later.
     */
    val eatingWindowStartMinutes: Int? = null,
    val eatingWindowEndMinutes: Int? = null,
    val allowedFoods: List<String> = emptyList(),
    val inventory: Map<String, Int> = emptyMap(),
    val foodItems: List<FoodItem> = emptyList(),
    val weightHistory: List<WeightEntry> = emptyList(),
    val hungerSignals: List<HungerSignal> = emptyList(),
    val mealHistory: List<MealHistoryEntry> = emptyList(),
    val wearableData: WearableData? = null,
    val nextCheckInAtMillis: Long? = null,
    val nextMealAtMillis: Long? = null,
    /**
     * When true, the AI/app is allowed to drive meal timing and recommendations
     * instead of just passively logging data.
     */
    val autoPilotEnabled: Boolean = false
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
    val text: String = "",
    /**
     * Optional message type hint for richer UI behaviors.
     * Examples: "RECIPE"
     */
    val kind: String? = null
)

data class MessageLog(
    val userId: String = "",
    val log: List<MessageEntry> = emptyList()
)

/**
 * Saved AI recipe stored under users/{uid}/recipes/{id}.
 *
 * `id` is typically the source MessageEntry.id to make saves idempotent.
 */
data class SavedRecipe(
    val id: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val title: String = "",
    val text: String = "",
    val ingredients: List<String> = emptyList()
)
