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

enum class RecipeTitleFontStyle {
    HANDWRITTEN_SERIF,
    VINTAGE_COOKBOOK,
    RUSTIC_SCRIPT,
    FARMHOUSE_ARTISAN
}

enum class RecipeDifficulty {
    SIMPLE,
    ADVANCED,
    EXPERT
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
     * Serving-size helpers (mainly populated by AI Evaluate Food photo analysis).
     *
     * - PACKAGED: macros are per serving; caloriesPerServing is the serving-size calories
     * - PLATED: the whole pictured plate is considered 1 serving; estimatedCalories/macros are for the whole plate
     */
    val portionKind: String? = null, // "PACKAGED" | "PLATED" | "UNKNOWN"
    val servingSizeText: String? = null, // e.g. "3 cookies (34g)" or "1 plate"
    val caloriesPerServing: Int? = null,
    val servingsPerContainer: Double? = null,
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
    /**
     * Convenience copy of the Firebase Auth email for easier identification in the Firestore console.
     * Nullable for backward compatibility with existing users.
     */
    val email: String? = null,
    /**
     * Same as [email], but prefixed so it appears near the top of the Firestore console field list.
     * (Firestore UI typically sorts fields alphabetically.)
     */
    val _email: String? = null,
    val name: String = "",
    /**
     * Preferred display/input unit for weights. Defaults to LB for new users.
     * Internally, weights are stored in pounds (lb).
     */
    val weightUnit: WeightUnit = WeightUnit.LB,
    val weightGoal: Double? = null,
    // Default to NO_DIET for lowest-friction onboarding.
    val dietType: DietType = DietType.NO_DIET,
    // Pricing tier (local MVP; later should be driven by Play Billing + server checks)
    val subscriptionTier: SubscriptionTier = SubscriptionTier.FREE,
    /**
     * Server-side sync timestamp when subscriptionTier was last updated.
     * Written by the Cloud Function; kept optional for backward compatibility.
     */
    val subscriptionTierUpdatedAt: Timestamp? = null,
    /**
     * UI preference: show cute food/ingredient icons (ic_food_*) in lists and recipes.
     */
    val showFoodIcons: Boolean = true,
    /**
     * UI preference: show the background wallpaper of random ic_food_* icons.
     */
    val showWallpaperFoodIcons: Boolean = true,
    /**
     * UI preference: show the vine overlay above the chat box on Home.
     */
    val showVineOverlay: Boolean = false,
    /**
     * One-time-only friendly intro. When true, we won't auto-insert the intro messages again.
     */
    val hasSeenWelcomeIntro: Boolean = false,
    /**
     * One-time beginner helper: show the popular-ingredients picker once.
     */
    val hasSeenBeginnerIngredientsPicker: Boolean = false,
    /**
     * UI preference: base font size (sp) for long-form content like recipes and chat.
     * Kept in a safe range in UI (e.g. 12..40) to avoid breaking layouts.
     */
    val uiFontSizeSp: Float = 18f,
    /**
     * UI preference: font style for recipe titles on the Recipes screen.
     */
    val recipeTitleFontStyle: RecipeTitleFontStyle = RecipeTitleFontStyle.VINTAGE_COOKBOOK,
    val fastingPreset: FastingPreset = FastingPreset.NONE,
    /**
     * Local-time eating window start/end (minutes from midnight).
     * Used when fastingPreset != NONE.
     * Defaults to 12:00–20:00 for common 16:8-ish behavior, but user can change later.
     */
    val eatingWindowStartMinutes: Int? = null,
    val eatingWindowEndMinutes: Int? = null,
    /**
     * Tutorial analytics: highest step reached (clamped client-side to [tutorialTotalSteps]).
     * This lets us see where users drop off.
     */
    val tutorialProgressSteps: Int = 0,
    /**
     * Tutorial analytics: total steps in the guided tour (for display like "4/32").
     * Kept in Firestore so we can change totals later without confusing old records.
     */
    val tutorialTotalSteps: Int = 32,
    /**
     * Tutorial analytics: convenience string like "4/32" to quickly scan in Firestore console.
     */
    val tutorialProgressText: String? = null,
    /**
     * Tutorial analytics: when we last updated tutorial progress.
     */
    val tutorialProgressUpdatedAt: Timestamp? = null,
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
 * A chat session ("conversation") for the AI chat screen.
 * Stored under users/{uid}/chats/{chatId} as metadata; messages are stored separately.
 */
data class ChatSession(
    val id: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val updatedAt: Timestamp = Timestamp.now(),
    val title: String = "Chat",
    val lastSnippet: String? = null
)

/**
 * Saved AI recipe stored under users/{uid}/recipes/{id}.
 *
 * `id` is typically the source MessageEntry.id to make saves idempotent.
 */
data class SavedRecipe(
    val id: String = "",
    /**
     * The chat message ID this recipe originated from (if saved from chat).
     * Used to prevent accidental duplicates while still allowing unique Firestore doc IDs.
     */
    val sourceMessageId: String? = null,
    val createdAt: Timestamp = Timestamp.now(),
    val title: String = "",
    val text: String = "",
    val ingredients: List<String> = emptyList()
)
