package com.matchpoint.myaidietapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.matchpoint.myaidietapp.data.MessagesRepository
import com.matchpoint.myaidietapp.data.ScheduledMealsRepository
import com.matchpoint.myaidietapp.data.StorageRepository
import com.matchpoint.myaidietapp.data.UserIdProvider
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
    val isProcessing: Boolean = false
)

class HomeViewModel(
    private val userIdProvider: UserIdProvider,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notificationScheduler: NotificationScheduler? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val dailyPlanner = DailyPlanner(zoneId)
    private val checkInGenerator = CheckInGenerator()

    private val userId: String by lazy { userIdProvider.getUserId() }

    private val userRepo by lazy { UserRepository(db, userId) }
    private val scheduleRepo by lazy { ScheduledMealsRepository(db, userId) }
    private val messagesRepo by lazy { MessagesRepository(db, userId) }
    private val storageRepo by lazy { StorageRepository() }
    private val proxyRepo by lazy { OpenAiProxyRepository() }

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

            maybeAutoCheckIn(profile)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        }
    }

    fun completeOnboarding(
        name: String,
        weightGoal: Double?,
        dietType: DietType,
        startingWeight: Double?
    ) {
        viewModelScope.launch {
            try {
                val todayDate = LocalDate.now(zoneId).toString()

                val profile = UserProfile(
                    name = name,
                    weightGoal = weightGoal,
                    dietType = dietType,
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
                    text = "Digital Stomach online. I’ll tell you when to feed – you just follow."
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

    fun addFoodItemSimple(name: String, quantity: Int, productUrl: String?, labelUrl: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val current = userRepo.getUserProfile() ?: return@launch
                val diet = current.dietType

                val analysis = runCatching {
                    if (productUrl != null) {
                        proxyRepo.analyzeFood(
                            productUrl = productUrl,
                            labelUrl = labelUrl,
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
                }.getOrElse { null }

                if (analysis != null) {
                    val healthRating = analysis.rating
                    val dietFitRating = analysis.dietFitRating ?: analysis.rating

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
                            photoUrl = productUrl,
                            labelUrl = labelUrl,
                            notes = analysis.summary,
                            rating = healthRating,
                            dietFitRating = dietFitRating
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
                    val fallbackItem = FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        quantity = quantity,
                        photoUrl = productUrl,
                        labelUrl = labelUrl,
                        notes = "AI analysis unavailable – added without rating.",
                        rating = null,
                        dietFitRating = null
                    )
                    val updated = current.copy(foodItems = current.foodItems + fallbackItem)
                    userRepo.saveUserProfile(updated)

                    val aiEntry = MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Couldn’t analyze that food right now, so I added it without a rating."
                    )
                    messagesRepo.appendMessage(aiEntry)

                    val updatedLog = messagesRepo.getMessageLog().log
                    _uiState.value = _uiState.value.copy(profile = updated, messages = updatedLog)
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

    fun updateDiet(dietType: DietType) {
        viewModelScope.launch {
            try {
                userRepo.updateDietType(dietType)
                val updatedProfile = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updatedProfile)
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
                val lastMeal = currentProfile?.mealHistory?.maxByOrNull { it.timestamp }
                val hungerLevels = currentProfile?.hungerSignals?.map { it.level } ?: emptyList()

                val inventorySummary = buildInventorySummary(currentProfile)

                val minutesSinceMeal = lastMeal?.let {
                    val now = Instant.now()
                    val diffMillis = now.toEpochMilli() - it.timestamp.toDate().time
                    diffMillis / 60_000
                }

                val replyText = runCatching {
                    proxyRepo.generateCheckIn(
                        CheckInRequest(
                            lastMeal = lastMeal?.mealName ?: lastMeal?.items?.joinToString(),
                            hungerSummary = "USER_MESSAGE: $message | recent hunger: " +
                                hungerLevels.takeLast(5).joinToString { it.name },
                            weightTrend = currentProfile?.let { estimateWeightTrend(it.weightHistory) },
                            minutesSinceMeal = minutesSinceMeal,
                            tone = "user is chatting about meal suggestions, maybe rejecting or asking for swap; be short, casual, and adjust meal plan if needed",
                            userMessage = message,
                            mode = "freeform",
                            inventorySummary = inventorySummary
                        )
                    )
                }.onFailure { e ->
                    Log.e("DigitalStomach", "Freeform proxy failed", e)
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

    suspend fun uploadFoodPhoto(foodId: String, isLabel: Boolean, uri: android.net.Uri): String {
        val type = if (isLabel) "label" else "product"
        val path = "foodPhotos/$userId/$foodId/$type.jpg"
        return storageRepo.uploadToPath(uri, path)
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
                    append("Food items: ")
                    append(p.foodItems.joinToString { item ->
                        val health = item.rating?.let { r -> "H${r}/10" } ?: ""
                        val diet = item.dietFitRating?.let { r -> "D${r}/10" } ?: ""
                        listOf(item.name, health, diet)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    })
                    append(". ")
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
