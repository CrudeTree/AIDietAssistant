package com.matchpoint.myaidietapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.matchpoint.myaidietapp.data.MessagesRepository
import com.matchpoint.myaidietapp.data.ScheduledMealsRepository
import com.matchpoint.myaidietapp.data.StorageRepository
import com.matchpoint.myaidietapp.data.AuthRepository
import com.matchpoint.myaidietapp.data.UserRepository
import com.matchpoint.myaidietapp.data.OpenAiProxyRepository
import com.matchpoint.myaidietapp.data.CheckInRequest
import com.matchpoint.myaidietapp.logic.CheckInGenerator
import com.matchpoint.myaidietapp.logic.DailyPlanner
import com.matchpoint.myaidietapp.notifications.NotificationScheduler
import com.matchpoint.myaidietapp.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import kotlin.random.Random
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class UiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val todaySchedule: ScheduledMealDay? = null,
    val messages: List<MessageEntry> = emptyList(),
    val introPending: Boolean = false,
    val error: String? = null,
    val isProcessing: Boolean = false,
    val pendingGrocery: PendingGrocery? = null,
    val planGateNotice: String? = null
)

// (Old upgrade modal removed in favor of a full "Choose a plan" screen.)

data class PendingGrocery(
    val id: String,
    val aiMessage: String,
    val item: FoodItem
)

private fun FoodItem.effectiveCategories(): Set<String> {
    // Backward compatibility: old items may have empty categories.
    // Treat them as snacks so they still show up somewhere.
    val set = categories.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
    return if (set.isEmpty()) setOf("SNACK") else set
}

class HomeViewModel(
    private val userId: String,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notificationScheduler: NotificationScheduler? = null,
    private val quotaManager: com.matchpoint.myaidietapp.data.DailyQuotaManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val dailyPlanner = DailyPlanner(zoneId)
    private val checkInGenerator = CheckInGenerator()

    private val userRepo by lazy { UserRepository(db, userId) }
    private val scheduleRepo by lazy { ScheduledMealsRepository(db, userId) }
    private val messagesRepo by lazy { MessagesRepository(db, userId) }
    private val storageRepo by lazy { StorageRepository() }
    private val proxyRepo by lazy { OpenAiProxyRepository() }
    private val authRepo by lazy { AuthRepository() }

    init {
        viewModelScope.launch {
            bootstrap()
        }
    }

    private suspend fun bootstrap() {
        try {
            val profile = userRepo.getUserProfile()
            if (profile == null) {
                _uiState.value = UiState(
                    isLoading = false,
                    profile = null,
                    todaySchedule = null,
                    messages = emptyList()
                )
                return
            }

            val todayDate = LocalDate.now(zoneId)
            val todayId = todayDate.toString()
            val schedule = scheduleRepo.getDaySchedule(todayId)
                ?: dailyPlanner.planForDay(todayDate, profile).also {
                    scheduleRepo.saveDaySchedule(it)
                }
            if (profile.autoPilotEnabled) {
                notificationScheduler?.scheduleForDay(schedule)
            }
            val messages = messagesRepo.getMessageLog().log

            _uiState.value = UiState(
                isLoading = false,
                profile = profile,
                todaySchedule = schedule,
                messages = messages
            )

        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        }
    }

    fun dismissUpgradePrompt() {
        // kept for backward compatibility with older UI; no-op now
    }

    fun clearPlanGateNotice() {
        _uiState.value = _uiState.value.copy(planGateNotice = null)
    }

    fun signOut() {
        authRepo.signOut()
    }

    fun deleteAccount(password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter your password to delete your account.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                // 1) Delete app data (best-effort)
                deleteUserDataBestEffort()
                // 2) Delete Firebase Auth user (requires recent login)
                authRepo.reauthenticateAndDeleteCurrentUser(password)
                // After this, authUid becomes null and UI returns to AuthScreen
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = e.message ?: "Failed to delete account."
                )
            }
        }
    }

    private suspend fun deleteUserDataBestEffort() {
        // Firestore documents/collections we use:
        // - users/{uid} (UserProfile)
        // - users/{uid}/schedules/*
        // - users/{uid}/meta/messageLog
        // - users/{uid}/meta/usageDaily/days/*
        try {
            // Delete schedules
            val schedules = db.collection("users").document(userId).collection("schedules").get().await()
            schedules.documents.forEach { it.reference.delete().await() }
        } catch (_: Exception) {
        }

        try {
            // Delete message log doc
            db.collection("users").document(userId).collection("meta").document("messageLog").delete().await()
        } catch (_: Exception) {
        }

        try {
            // Delete quota docs (server-side daily usage)
            val days = db.collection("users").document(userId)
                .collection("meta").document("usageDaily")
                .collection("days").get().await()
            days.documents.forEach { it.reference.delete().await() }
            db.collection("users").document(userId).collection("meta").document("usageDaily").delete().await()
        } catch (_: Exception) {
        }

        try {
            // Delete user profile doc last
            db.collection("users").document(userId).delete().await()
        } catch (_: Exception) {
        }

        try {
            // Delete Storage content (best-effort)
            storageRepo.deleteAllUserContent(userId)
        } catch (_: Exception) {
        }
    }

    private fun checkAndConsumeChatQuotaOrPrompt(profile: UserProfile): Boolean {
        val qm = quotaManager ?: return true // if not injected, don’t block
        val status = qm.tryConsume(profile.subscriptionTier)
        if (status.isOverLimit) {
            val upsell = when (profile.subscriptionTier) {
                SubscriptionTier.FREE ->
                    "Upgrade to Regular for \$9.99/month (\$99.99/year) for 50 chats/day, or Pro for \$19.99/month (\$199.99/year) for 150 chats/day."
                SubscriptionTier.REGULAR ->
                    "Upgrade to Pro for \$19.99/month (\$199.99/year) to raise your limit to 150 chats/day."
                SubscriptionTier.PRO ->
                    "You’ve hit the Pro daily limit."
            }
            _uiState.value = _uiState.value.copy(planGateNotice = "You have reached your daily limit.\n\n$upsell")
            return false
        }
        return true
    }

    private fun foodListLimitFor(tier: SubscriptionTier): Int {
        return when (tier) {
            SubscriptionTier.FREE -> 20
            SubscriptionTier.REGULAR -> 100
            SubscriptionTier.PRO -> 500
        }
    }

    private fun checkFoodListLimitOrPrompt(profile: UserProfile, addingCount: Int = 1): Boolean {
        val limit = foodListLimitFor(profile.subscriptionTier)
        val current = profile.foodItems.size
        if (current + addingCount <= limit) return true

        val upsell = when (profile.subscriptionTier) {
            SubscriptionTier.FREE ->
                "Upgrade to Regular for \$9.99/month (\$99.99/year) for up to 100 items, or Pro for \$19.99/month (\$199.99/year) for up to 500 items."
            SubscriptionTier.REGULAR ->
                "Upgrade to Pro for \$19.99/month (\$199.99/year) to raise your limit to 500 items."
            SubscriptionTier.PRO ->
                "You’ve hit the Pro item limit."
        }

        _uiState.value = _uiState.value.copy(
            planGateNotice = "You’ve reached your plan’s food list limit ($current/$limit).\n\n$upsell"
        )
        return false
    }

    fun completeOnboarding(
        name: String,
        weightGoal: Double?,
        dietType: DietType,
        startingWeight: Double?,
        fastingPreset: FastingPreset,
        eatingWindowStartMinutes: Int?
    ) {
        viewModelScope.launch {
            try {
                val todayDate = LocalDate.now(zoneId).toString()

                val hours = fastingPreset.eatingWindowHours
                val start = if (hours == null) null else eatingWindowStartMinutes
                val end = if (hours == null || start == null) null else (start + hours * 60) % (24 * 60)

                val profile = UserProfile(
                    name = name,
                    weightGoal = weightGoal,
                    dietType = dietType,
                    subscriptionTier = SubscriptionTier.FREE,
                    fastingPreset = fastingPreset,
                    eatingWindowStartMinutes = start,
                    eatingWindowEndMinutes = end,
                    allowedFoods = emptyList(),
                    inventory = emptyMap(),
                    foodItems = emptyList(),
                    weightHistory = startingWeight?.let {
                        listOf(WeightEntry(date = todayDate, weight = it))
                    } ?: emptyList()
                )
                userRepo.saveUserProfile(profile)

                val schedule = dailyPlanner.planForDay(LocalDate.now(zoneId), profile)
                scheduleRepo.saveDaySchedule(schedule)
                var firstMealTime: Long? = null
                if (profile.autoPilotEnabled) {
                    notificationScheduler?.scheduleForDay(schedule)
                    firstMealTime = schedule.decidedMeals.minByOrNull { it.exactTimeMillis }?.exactTimeMillis
                    userRepo.updateNextTimes(
                        nextCheckInAt = null,
                        nextMealAt = firstMealTime
                    )
                }

                val welcomeMessage = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = "AI Food Coach online. I’ll tell you when to eat – you just follow."
                )
                val introMessage = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = introText(profile)
                )
                messagesRepo.appendMessage(welcomeMessage)
                messagesRepo.appendMessage(introMessage)

                _uiState.value = UiState(
                    isLoading = false,
                    profile = profile.copy(nextMealAtMillis = firstMealTime),
                    todaySchedule = schedule,
                    messages = listOf(welcomeMessage, introMessage),
                    introPending = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun addFoodItemSimple(
        name: String,
        categories: Set<String>,
        quantity: Int,
        productUrl: String?,
        labelUrl: String?,
        nutritionFactsUrl: String?
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val current = userRepo.getUserProfile() ?: return@launch
                val diet = current.dietType

                if (!checkFoodListLimitOrPrompt(current, addingCount = 1)) {
                    return@launch
                }

                val analysisAttempt = runCatching {
                    if (productUrl != null) {
                        proxyRepo.analyzeFood(
                            productUrl = productUrl,
                            labelUrl = labelUrl,
                            nutritionFactsUrl = nutritionFactsUrl,
                            dietType = diet
                        )
                    } else {
                        // Text-only path: analyze based on the typed food name
                        proxyRepo.analyzeFoodByName(
                            foodName = name,
                            dietType = diet
                        )
                    }
                }.onFailure { e ->
                    Log.e("DigitalStomach", "analyzeFood failed", e)
                }

                val analysis = analysisAttempt.getOrNull()

                if (analysis != null) {
                    val healthRating = analysis.rating
                    val dietFitRating = analysis.dietFitRating ?: analysis.rating
                    val dietRatings = analysis.dietRatings.toMutableMap().apply {
                        // Back-compat: if server didn't send multi-diet ratings,
                        // at least persist the current diet rating so future diet switches can be filled in.
                        putIfAbsent(diet.name, dietFitRating)
                    }.toMap()
                    val allergyRatings = analysis.allergyRatings

                    val debugMessage = buildString {
                        append("Food '")
                        append(analysis.normalizedName)
                        append("' — health ")
                        append(healthRating)
                        append("/10, diet fit ")
                        append(dietFitRating)
                        append("/10. ")
                        append(analysis.summary)
                        if (analysis.concerns.isNotBlank()) {
                            append(" Concerns: ")
                            append(analysis.concerns)
                        }
                    }

                    val aiEntry = MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = debugMessage
                    )
                    messagesRepo.appendMessage(aiEntry)

                    if (analysis.accepted) {
                        val newItem = FoodItem(
                            id = UUID.randomUUID().toString(),
                            name = analysis.normalizedName.ifBlank { name },
                            quantity = quantity,
                            categories = categories.map { it.uppercase() }.distinct(),
                            photoUrl = productUrl,
                            labelUrl = labelUrl,
                            nutritionFactsUrl = nutritionFactsUrl,
                            notes = analysis.summary,
                            estimatedCalories = analysis.estimatedCalories,
                            estimatedProteinG = analysis.estimatedProteinG,
                            estimatedCarbsG = analysis.estimatedCarbsG,
                            estimatedFatG = analysis.estimatedFatG,
                            ingredientsText = analysis.ingredientsText,
                            rating = healthRating,
                            dietFitRating = dietFitRating,
                            dietRatings = dietRatings,
                            allergyRatings = allergyRatings
                        )
                        val updated = current.copy(foodItems = current.foodItems + newItem)
                        userRepo.saveUserProfile(updated)
                        val updatedLog = messagesRepo.getMessageLog().log
                        _uiState.value = _uiState.value.copy(profile = updated, messages = updatedLog)
                    } else {
                        val updatedLog = messagesRepo.getMessageLog().log
                        _uiState.value = _uiState.value.copy(profile = current, messages = updatedLog)
                    }
                } else {
                    val e = analysisAttempt.exceptionOrNull()
                    val detail = when (e) {
                        is retrofit2.HttpException -> "proxy HTTP ${e.code()}"
                        else -> e?.javaClass?.simpleName
                    }
                    val aiEntry = MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = if (detail != null)
                            "No service. Check internet connectivity. ($detail)"
                        else
                            "No service. Check internet connectivity."
                    )
                    messagesRepo.appendMessage(aiEntry)

                    val updatedLog = messagesRepo.getMessageLog().log
                    // Don't add an unrated placeholder food item.
                    _uiState.value = _uiState.value.copy(profile = current, messages = updatedLog)
                }
            } catch (e: Exception) {
                Log.e("DigitalStomach", "addFoodItemSimple failed", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun markIntroDone() {
        _uiState.value = _uiState.value.copy(introPending = false)
    }

    private fun introText(profile: UserProfile): String {
        val foods = profile.foodItems.takeIf { it.isNotEmpty() } ?: emptyList()
        val names = foods.take(3).joinToString { it.name }
        val foodLine = if (names.isNotEmpty()) "I see you have $names on deck." else "I’ll work with what you logged."
        val goalLine = profile.weightGoal?.let { " Goal noted: $it." } ?: ""
        return "Hey ${profile.name.ifBlank { "there" }}. $foodLine$goalLine Ready to let me drive?"
    }

    private fun estimateWeightTrend(weights: List<WeightEntry>): Double {
        if (weights.size < 2) return 0.0
        val sorted = weights.sortedBy { it.date }
        val first = sorted.first()
        val last = sorted.last()
        return last.weight - first.weight
    }

    fun removeFoodItem(foodId: String) {
        viewModelScope.launch {
            try {
                userRepo.removeFoodItem(foodId)
                val updatedProfile = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updatedProfile)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateFoodItemCategories(foodId: String, categories: Set<String>) {
        viewModelScope.launch {
            try {
                val normalized = categories.map { it.trim().uppercase() }
                    .filter { it == "MEAL" || it == "INGREDIENT" || it == "SNACK" }
                    .distinct()
                userRepo.updateFoodItemCategories(foodId, normalized)
                val updatedProfile = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updatedProfile)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateDiet(dietType: DietType) {
        viewModelScope.launch {
            try {
                // Make the UI switch instant: update dietType first and return.
                // Any legacy foods missing dietRatings will be backfilled in the background.
                userRepo.updateDietType(dietType)
                val updatedProfile = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updatedProfile)

                // Background backfill for legacy items that were added before we stored full dietRatings.
                if (updatedProfile != null) {
                    viewModelScope.launch {
                        backfillFoodRatingsIfNeeded(updatedProfile)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private suspend fun backfillFoodRatingsIfNeeded(profile: UserProfile) {
        // If foods already have dietRatings, diet switches will be instant.
        // For older items where dietRatings is empty/missing, do a one-time name-only analysis
        // that returns ALL diet ratings + allergy ratings in one call per item.
        val needsBackfill = profile.foodItems.filter { it.dietRatings.isEmpty() }
        if (needsBackfill.isEmpty()) return

        val repaired = profile.foodItems.map { item ->
            if (item.dietRatings.isNotEmpty()) return@map item
            val analysis = runCatching {
                // Use NO_DIET as the "current diet" input; the server returns dietRatings for all diets anyway.
                proxyRepo.analyzeFoodByName(item.name, DietType.NO_DIET)
            }.getOrNull() ?: return@map item

            val inferredDietFitForCurrent =
                analysis.dietRatings[profile.dietType.name]
                    ?: analysis.dietFitRating
                    ?: analysis.rating

            item.copy(
                rating = item.rating ?: analysis.rating,
                dietFitRating = inferredDietFitForCurrent,
                dietRatings = analysis.dietRatings,
                allergyRatings = analysis.allergyRatings,
                estimatedCalories = item.estimatedCalories ?: analysis.estimatedCalories,
                estimatedProteinG = item.estimatedProteinG ?: analysis.estimatedProteinG,
                estimatedCarbsG = item.estimatedCarbsG ?: analysis.estimatedCarbsG,
                estimatedFatG = item.estimatedFatG ?: analysis.estimatedFatG,
                ingredientsText = item.ingredientsText ?: analysis.ingredientsText,
                // Keep existing notes unless empty
                notes = item.notes ?: analysis.summary
            )
        }

        val updated = profile.copy(foodItems = repaired)
        userRepo.saveUserProfile(updated)

        // Refresh UI (only if we still have the same user/profile loaded).
        val latest = userRepo.getUserProfile()
        _uiState.value = _uiState.value.copy(profile = latest)
    }

    fun updateWeightGoal(weightGoal: Double?) {
        viewModelScope.launch {
            try {
                userRepo.updateWeightGoal(weightGoal)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateFastingPreset(preset: FastingPreset) {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUserProfile() ?: return@launch
                val hours = preset.eatingWindowHours
                val start = when {
                    hours == null -> null
                    // Default window: end at 20:00 local, start = end - hours
                    else -> (20 * 60 - hours * 60).coerceAtLeast(0)
                }
                val end = when {
                    hours == null -> null
                    else -> 20 * 60
                }
                userRepo.updateFastingPreset(preset, start, end)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateEatingWindowStart(startMinutes: Int) {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUserProfile() ?: return@launch
                val hours = profile.fastingPreset.eatingWindowHours ?: return@launch
                val start = startMinutes.coerceIn(0, 24 * 60 - 1)
                val end = (start + hours * 60) % (24 * 60)
                userRepo.updateFastingPreset(profile.fastingPreset, start, end)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun logCurrentWeight(weight: Double) {
        viewModelScope.launch {
            try {
                val today = LocalDate.now(zoneId).toString()
                userRepo.appendWeightEntry(WeightEntry(date = today, weight = weight))
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun setAutoPilotEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userRepo.updateAutoPilotEnabled(enabled)
                var profile = userRepo.getUserProfile() ?: return@launch

                if (enabled) {
                    val today = LocalDate.now(zoneId)
                    val todayId = today.toString()
                    val schedule = scheduleRepo.getDaySchedule(todayId)
                        ?: dailyPlanner.planForDay(today, profile).also {
                            scheduleRepo.saveDaySchedule(it)
                        }
                    notificationScheduler?.scheduleForDay(schedule)
                    val firstMealTime = schedule.decidedMeals.minByOrNull { it.exactTimeMillis }?.exactTimeMillis
                    userRepo.updateNextTimes(nextCheckInAt = null, nextMealAt = firstMealTime)
                    profile = profile.copy(nextMealAtMillis = firstMealTime, autoPilotEnabled = true)
                } else {
                    profile = profile.copy(autoPilotEnabled = false)
                    userRepo.saveUserProfile(profile)
                    // We leave any already-scheduled WorkManager jobs; new days won't be scheduled while off.
                }

                _uiState.value = _uiState.value.copy(profile = profile)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun sendFreeformMessage(message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val userEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.USER,
                    text = message
                )
                messagesRepo.appendMessage(userEntry)

                val currentProfile = userRepo.getUserProfile()
                if (currentProfile != null && !checkAndConsumeChatQuotaOrPrompt(currentProfile)) {
                    return@launch
                }
                val lastMeal = currentProfile?.mealHistory?.maxByOrNull { it.timestamp }
                val hungerLevels = currentProfile?.hungerSignals?.map { it.level } ?: emptyList()

                val inventorySummary = buildInventorySummary(currentProfile)

                val minutesSinceMeal = lastMeal?.let {
                    val now = Instant.now()
                    val diffMillis = now.toEpochMilli() - it.timestamp.toDate().time
                    diffMillis / 60_000
                } // if null -> first meal of the app; don't assume "too early"

                val replyText = runCatching {
                    val now = java.time.LocalTime.now(zoneId)
                    val clientLocalMinutes = now.hour * 60 + now.minute
                    val tzOffsetMinutes = java.time.ZonedDateTime.now(zoneId).offset.totalSeconds / 60
                    proxyRepo.generateCheckIn(
                        CheckInRequest(
                            lastMeal = lastMeal?.mealName ?: lastMeal?.items?.joinToString(),
                            hungerSummary = buildString {
                                append("USER_MESSAGE: ")
                                append(message)
                                append(" | recent hunger: ")
                                append(hungerLevels.takeLast(5).joinToString { it.name })
                                if (lastMeal == null) {
                                    append(" | NO_MEALS_LOGGED_YET: allow user to start their first meal if they ask to eat")
                                }
                            },
                            weightTrend = currentProfile?.let { estimateWeightTrend(it.weightHistory) },
                            minutesSinceMeal = minutesSinceMeal,
                            tone = "user is chatting about meal suggestions, maybe rejecting or asking for swap; be short, casual, and adjust meal plan if needed",
                            userMessage = message,
                            mode = "freeform",
                            inventorySummary = inventorySummary,
                            dietType = currentProfile?.dietType?.name,
                            fastingPreset = currentProfile?.fastingPreset?.name,
                            eatingWindowStartMinutes = currentProfile?.eatingWindowStartMinutes,
                            eatingWindowEndMinutes = currentProfile?.eatingWindowEndMinutes,
                            clientLocalMinutes = clientLocalMinutes,
                            timezoneOffsetMinutes = tzOffsetMinutes
                        )
                    )
                }.onFailure { e ->
                    Log.e("DigitalStomach", "Freeform proxy failed", e)
                    if (e is HttpException && e.code() == 429) {
                        _uiState.value = _uiState.value.copy(planGateNotice = "You have reached your daily limit.")
                    }
                }.getOrElse { e ->
                    "No service: ${e.message ?: "unknown error"}"
                }

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = replyText
                )
                messagesRepo.appendMessage(aiEntry)

                val updatedLog = messagesRepo.getMessageLog().log
                _uiState.value = _uiState.value.copy(
                    profile = currentProfile,
                    messages = updatedLog
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun generateMeal() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val profile = userRepo.getUserProfile() ?: return@launch
                if (!checkAndConsumeChatQuotaOrPrompt(profile)) {
                    return@launch
                }
                val inventorySummary = buildInventorySummary(profile)

                val replyText = runCatching {
                    val now = java.time.LocalTime.now(zoneId)
                    val clientLocalMinutes = now.hour * 60 + now.minute
                    val tzOffsetMinutes = java.time.ZonedDateTime.now(zoneId).offset.totalSeconds / 60
                    proxyRepo.generateCheckIn(
                        CheckInRequest(
                            lastMeal = profile.mealHistory.maxByOrNull { it.timestamp }?.mealName,
                            hungerSummary = null,
                            weightTrend = estimateWeightTrend(profile.weightHistory),
                            minutesSinceMeal = null,
                            mode = "generate_meal",
                            userMessage = "Generate a dinner recipe I can cook now. Use my INGREDIENTS list (and SNACKS if useful). Give step-by-step instructions with times/temps. If I don't have enough ingredients, say what I'm missing and suggest a short shopping list.",
                            tone = "recipe mode: detailed steps; be specific; no fluff",
                            inventorySummary = inventorySummary,
                            dietType = profile.dietType.name,
                            fastingPreset = profile.fastingPreset.name,
                            eatingWindowStartMinutes = profile.eatingWindowStartMinutes,
                            eatingWindowEndMinutes = profile.eatingWindowEndMinutes,
                            clientLocalMinutes = clientLocalMinutes,
                            timezoneOffsetMinutes = tzOffsetMinutes
                        )
                    )
                }.onFailure { e ->
                    if (e is HttpException && e.code() == 429) {
                        _uiState.value = _uiState.value.copy(planGateNotice = "You have reached your daily limit.")
                    }
                }.getOrElse { e ->
                    "No service. Check internet connectivity. (${e.javaClass.simpleName})"
                }

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = replyText
                )
                messagesRepo.appendMessage(aiEntry)

                val updatedLog = messagesRepo.getMessageLog().log
                _uiState.value = _uiState.value.copy(messages = updatedLog)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    suspend fun uploadFoodPhoto(foodId: String, kind: String, uri: android.net.Uri): String {
        val safeKind = when (kind) {
            "product", "ingredients", "nutrition_facts" -> kind
            else -> "product"
        }
        val path = "foodPhotos/$userId/$foodId/$safeKind.jpg"
        return storageRepo.uploadToPath(uri, path)
    }

    suspend fun uploadMealPhoto(mealId: String, uri: android.net.Uri): String {
        val path = "mealPhotos/$userId/$mealId/meal.jpg"
        return storageRepo.uploadToPath(uri, path)
    }

    suspend fun uploadGroceryPhoto(scanId: String, kind: String, uri: android.net.Uri): String {
        val safeKind = when (kind) {
            "product", "ingredients", "nutrition_facts" -> kind
            else -> "product"
        }
        val path = "groceryPhotos/$userId/$scanId/$safeKind.jpg"
        return storageRepo.uploadToPath(uri, path)
    }

    fun evaluateGroceryScan(productUrl: String, labelUrl: String?, nutritionFactsUrl: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val profile = userRepo.getUserProfile() ?: return@launch
                val diet = profile.dietType

                val analysis = runCatching {
                    proxyRepo.analyzeFood(
                        productUrl = productUrl,
                        labelUrl = labelUrl,
                        nutritionFactsUrl = nutritionFactsUrl,
                        dietType = diet
                    )
                }.getOrNull()

                if (analysis == null) {
                    messagesRepo.appendMessage(
                        MessageEntry(
                            id = UUID.randomUUID().toString(),
                            sender = MessageSender.AI,
                            text = "No service. Check internet connectivity."
                        )
                    )
                    _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
                    return@launch
                }

                val dietFit = analysis.dietRatings[diet.name] ?: analysis.dietFitRating ?: analysis.rating
                val health = analysis.rating
                val message = buildString {
                    append("Grocery scan: '")
                    append(analysis.normalizedName)
                    append("' — health ")
                    append(health)
                    append("/10")
                    if (diet != DietType.NO_DIET && dietFit != null) {
                        append(", ")
                        append(diet.name)
                        append(" ")
                        append(dietFit)
                        append("/10")
                    }
                    append(". ")
                    append(analysis.summary)
                    if (analysis.concerns.isNotBlank()) {
                        append(" Concerns: ")
                        append(analysis.concerns)
                    }
                }

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = message
                )
                messagesRepo.appendMessage(aiEntry)

                val item = FoodItem(
                    id = UUID.randomUUID().toString(),
                    name = analysis.normalizedName,
                    quantity = 1,
                    categories = listOf("SNACK"),
                    photoUrl = productUrl,
                    labelUrl = labelUrl,
                    nutritionFactsUrl = nutritionFactsUrl,
                    notes = analysis.summary,
                    estimatedCalories = analysis.estimatedCalories,
                    estimatedProteinG = analysis.estimatedProteinG,
                    estimatedCarbsG = analysis.estimatedCarbsG,
                    estimatedFatG = analysis.estimatedFatG,
                    ingredientsText = analysis.ingredientsText,
                    rating = analysis.rating,
                    dietFitRating = dietFit,
                    dietRatings = analysis.dietRatings.toMutableMap().apply {
                        if (dietFit != null) putIfAbsent(diet.name, dietFit)
                    }.toMap(),
                    allergyRatings = analysis.allergyRatings
                )

                _uiState.value = _uiState.value.copy(
                    profile = profile,
                    messages = messagesRepo.getMessageLog().log,
                    pendingGrocery = PendingGrocery(
                        id = UUID.randomUUID().toString(),
                        aiMessage = message,
                        item = item
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun confirmAddPendingGrocery() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val pending = state.pendingGrocery ?: return@launch
                val profile = userRepo.getUserProfile() ?: return@launch

                if (!checkFoodListLimitOrPrompt(profile, addingCount = 1)) {
                    return@launch
                }

                val updated = profile.copy(foodItems = profile.foodItems + pending.item)
                userRepo.saveUserProfile(updated)
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Added. I’ll include it in your foods list."
                    )
                )

                _uiState.value = _uiState.value.copy(
                    profile = updated,
                    messages = messagesRepo.getMessageLog().log,
                    pendingGrocery = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun discardPendingGrocery() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                if (state.pendingGrocery == null) return@launch
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Cool — leaving it out."
                    )
                )
                _uiState.value = _uiState.value.copy(
                    messages = messagesRepo.getMessageLog().log,
                    pendingGrocery = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun logMeal(mealName: String?, grams: Int?, mealPhotoUrl: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val profile = userRepo.getUserProfile() ?: return@launch

                // This path may add a new FoodItem (MEAL) into foodItems.
                // Enforce list cap before doing heavy AI work.
                if (!checkFoodListLimitOrPrompt(profile, addingCount = 1)) {
                    return@launch
                }

                val nowMillis = Instant.now().toEpochMilli()

                // Ask the proxy to estimate calories/macros if possible. If not supported / offline,
                // fall back to storing grams + name only.
                val mealAnalysis = runCatching {
                    proxyRepo.analyzeMeal(
                        mealPhotoUrl = mealPhotoUrl,
                        mealGrams = grams,
                        mealText = mealName,
                        dietType = profile.dietType,
                        inventorySummary = buildInventorySummary(profile)
                    )
                }.getOrNull()

                val normalizedName = mealAnalysis?.normalizedMealName
                    ?: mealName
                    ?: "Logged meal"

                // Also ask for health + diet ratings (so we can comment on how healthy it is).
                val ratingAnalysis = runCatching {
                    proxyRepo.analyzeFoodByName(normalizedName, profile.dietType)
                }.getOrNull()

                val healthRating = ratingAnalysis?.rating
                val dietFit = ratingAnalysis?.dietFitRating
                val dietRatings = ratingAnalysis?.dietRatings ?: emptyMap()
                val allergyRatings = ratingAnalysis?.allergyRatings ?: emptyMap()

                val entry = MealHistoryEntry(
                    timestamp = com.google.firebase.Timestamp.now(),
                    mealName = normalizedName,
                    items = listOfNotNull(normalizedName),
                    portionSizeOz = 0,
                    mealPhotoUrl = mealPhotoUrl,
                    totalGrams = grams,
                    estimatedCalories = mealAnalysis?.estimatedCalories,
                    estimatedProteinG = mealAnalysis?.estimatedProteinG,
                    estimatedCarbsG = mealAnalysis?.estimatedCarbsG,
                    estimatedFatG = mealAnalysis?.estimatedFatG,
                    aiNotes = listOfNotNull(mealAnalysis?.notes, ratingAnalysis?.summary).joinToString(" ").ifBlank { null },
                    healthRating = healthRating,
                    dietFitRating = dietFit,
                    dietRatings = dietRatings,
                    allergyRatings = allergyRatings
                )
                userRepo.appendMealHistory(entry)

                // Always: add this meal into the Foods list too (so it shows up on the Profile page),
                // but only if it passes list cap (already checked above) and we have a valid analysis.
                if (ratingAnalysis != null && ratingAnalysis.accepted) {
                    val dietKey = profile.dietType.name
                    val inferredDietFit = ratingAnalysis.dietFitRating ?: ratingAnalysis.rating
                    val item = com.matchpoint.myaidietapp.model.FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = ratingAnalysis.normalizedName.ifBlank { normalizedName },
                        quantity = 1,
                        categories = listOf("MEAL"),
                        photoUrl = mealPhotoUrl,
                        labelUrl = null,
                        notes = ratingAnalysis.summary,
                        rating = ratingAnalysis.rating,
                        dietFitRating = inferredDietFit,
                        dietRatings = ratingAnalysis.dietRatings.toMutableMap().apply {
                            putIfAbsent(dietKey, inferredDietFit)
                        }.toMap(),
                        allergyRatings = ratingAnalysis.allergyRatings
                    )
                    val refreshed = userRepo.getUserProfile()
                    if (refreshed != null && refreshed.foodItems.none { it.name.equals(item.name, ignoreCase = true) }) {
                        userRepo.saveUserProfile(refreshed.copy(foodItems = refreshed.foodItems + item))
                    }
                }

                // Simple timing heuristic for now (this is where your “big meal => wait longer” logic lives).
                val calories = mealAnalysis?.estimatedCalories
                val nextCheckMinutes = when {
                    calories == null && grams != null -> {
                        // very rough fallback: larger meals wait longer
                        when {
                            grams >= 900 -> 240
                            grams >= 600 -> 180
                            grams >= 400 -> 135
                            grams >= 250 -> 105
                            else -> 90
                        }
                    }
                    calories != null -> {
                        when {
                            calories >= 1400 -> 300
                            calories >= 1000 -> 240
                            calories >= 700 -> 195
                            calories >= 450 -> 150
                            else -> 105
                        }
                    }
                    else -> 120
                }

                val nextCheckInAt = nowMillis + nextCheckMinutes * 60_000L
                userRepo.updateNextTimes(nextCheckInAt = nextCheckInAt, nextMealAt = null)

                // Add a short AI-visible debug note into the chat (can remove later).
                val debug = buildString {
                    append("Logged meal: ")
                    append(normalizedName)
                    if (grams != null) append(" (${grams}g)")
                    if (calories != null) append(" ≈ ${calories} kcal")
                    if (healthRating != null) append(". Health ${healthRating}/10")
                    if (dietFit != null && profile.dietType != DietType.NO_DIET) append(", ${profile.dietType.name} ${dietFit}/10")
                    append(". Next check-in in ${nextCheckMinutes}m.")
                    ratingAnalysis?.summary?.takeIf { it.isNotBlank() }?.let {
                        append(" ")
                        append(it)
                    } ?: mealAnalysis?.notes?.takeIf { it.isNotBlank() }?.let {
                        append(" Notes: ")
                        append(it)
                    }
                }
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = debug
                    )
                )

                val updatedProfile = userRepo.getUserProfile()
                val updatedLog = messagesRepo.getMessageLog().log
                _uiState.value = _uiState.value.copy(profile = updatedProfile, messages = updatedLog)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun confirmMealConsumed() {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUserProfile() ?: return@launch
                val now = Instant.now().toEpochMilli()
                val today = LocalDate.now(zoneId).toString()
                val schedule = scheduleRepo.getDaySchedule(today)
                val nextMeal = schedule?.decidedMeals
                    ?.firstOrNull { it.exactTimeMillis <= now }
                    ?: schedule?.decidedMeals?.minByOrNull { it.exactTimeMillis }

                val mealName = nextMeal?.mealSuggestion ?: "Planned meal"
                val portion = nextMeal?.portionSizeOz ?: 0

                userRepo.appendMealHistory(
                    MealHistoryEntry(
                        timestamp = com.google.firebase.Timestamp.now(),
                        mealName = mealName,
                        items = listOf(mealName),
                        portionSizeOz = portion
                    )
                )

                // Set next check-in 90 minutes later, clear next meal
                val nextCheckIn = now + 90 * 60 * 1000
                userRepo.updateNextTimes(nextCheckInAt = nextCheckIn, nextMealAt = null)

                val updatedProfile = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updatedProfile)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun scheduleFollowup(now: Long): Long = now + Random.nextLong(45, 120) * 60 * 1000

    private suspend fun maybeAutoCheckIn(profile: UserProfile) {
        val nextCheck = profile.nextCheckInAtMillis ?: return
        val now = Instant.now().toEpochMilli()
        if (now < nextCheck) return

        // Avoid spamming if there was a recent AI message
        val log = messagesRepo.getMessageLog()
        val lastMsg = log.log.maxByOrNull { it.timestamp }
        if (lastMsg != null && lastMsg.sender == MessageSender.AI) {
            val lastMillis = lastMsg.timestamp.toDate().time
            if (now - lastMillis < 30 * 60 * 1000) return
        }

        val hungerLevels = profile.hungerSignals.map { it.level }
        val lastMeal = profile.mealHistory.maxByOrNull { it.timestamp }
        val minutesSinceMeal = lastMeal?.let {
            val diff = now - it.timestamp.toDate().time
            diff / 60_000
        } ?: 90L

        val reply = runCatching {
            proxyRepo.generateCheckIn(
                CheckInRequest(
                    lastMeal = lastMeal?.mealName ?: lastMeal?.items?.joinToString(),
                    hungerSummary = hungerLevels.takeLast(5).joinToString { it.name },
                    weightTrend = estimateWeightTrend(profile.weightHistory),
                    minutesSinceMeal = minutesSinceMeal,
                    mode = "auto_poke",
                    inventorySummary = buildInventorySummary(profile)
                )
            )
        }.onFailure { e ->
            Log.e("DigitalStomach", "Auto check-in failed", e)
        }.getOrElse { e ->
            "No service: ${e.message ?: "unknown error"}"
        }

        val aiEntry = MessageEntry(
            id = UUID.randomUUID().toString(),
            sender = MessageSender.AI,
            text = reply
        )
        messagesRepo.appendMessage(aiEntry)

        val newNextCheck = scheduleFollowup(now)
        userRepo.updateNextTimes(nextCheckInAt = newNextCheck, nextMealAt = profile.nextMealAtMillis)

        val updatedProfile = userRepo.getUserProfile()
        val updatedLog = messagesRepo.getMessageLog().log
        _uiState.value = _uiState.value.copy(
            profile = updatedProfile,
            messages = updatedLog
        )
    }

    private fun buildInventorySummary(profile: UserProfile?): String? {
        return profile?.let { p ->
            buildString {
                if (p.foodItems.isNotEmpty()) {
                    val meals = p.foodItems.filter { it.effectiveCategories().contains("MEAL") }
                    val ingredients = p.foodItems.filter { it.effectiveCategories().contains("INGREDIENT") }
                    val snacks = p.foodItems.filter { it.effectiveCategories().contains("SNACK") }

                    fun format(label: String, list: List<FoodItem>): String {
                        val maxItems = 25
                        val shown = list.take(maxItems)
                        val remaining = (list.size - shown.size).coerceAtLeast(0)
                        val body = shown.joinToString { item ->
                            val health = item.rating?.let { r -> "H${r}/10" } ?: ""
                            val diet = item.dietFitRating?.let { r -> "D${r}/10" } ?: ""
                            listOf(item.name, health, diet).filter { it.isNotBlank() }.joinToString(" ")
                        }
                        return if (remaining > 0) "$label: $body (and $remaining more)" else "$label: $body"
                    }

                    if (meals.isNotEmpty()) {
                        append(format("Meals", meals))
                        append(". ")
                    }
                    if (ingredients.isNotEmpty()) {
                        append(format("Ingredients", ingredients))
                        append(". ")
                    }
                    if (snacks.isNotEmpty()) {
                        append(format("Snacks", snacks))
                        append(". ")
                    }
                }
                if (p.fastingPreset != FastingPreset.NONE) {
                    val start = p.eatingWindowStartMinutes
                    val end = p.eatingWindowEndMinutes
                    if (start != null && end != null) {
                        fun mm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
                        append("Fasting window: ${p.fastingPreset.name} eat between ${mm(start)}–${mm(end)} local time. ")
                    } else {
                        append("Fasting preset: ${p.fastingPreset.name}. ")
                    }
                }
                if (p.inventory.isNotEmpty()) {
                    append("Inventory counts: ")
                    append(p.inventory.entries.joinToString { "${it.key} x${it.value}" })
                    append(". ")
                }
                if (p.allowedFoods.isNotEmpty()) {
                    append("Allowed foods: ")
                    append(p.allowedFoods.joinToString())
                    append(". ")
                }
            }.ifBlank { null }
        }
    }
}
