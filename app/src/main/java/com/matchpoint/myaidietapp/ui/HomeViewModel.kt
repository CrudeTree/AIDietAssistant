package com.matchpoint.myaidietapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.matchpoint.myaidietapp.data.MessagesRepository
import com.matchpoint.myaidietapp.data.RecipesRepository
import com.matchpoint.myaidietapp.data.ScheduledMealsRepository
import com.matchpoint.myaidietapp.data.StorageRepository
import com.matchpoint.myaidietapp.data.AuthRepository
import com.matchpoint.myaidietapp.data.UserRepository
import com.matchpoint.myaidietapp.data.OpenAiProxyRepository
import com.matchpoint.myaidietapp.data.CheckInRequest
import com.matchpoint.myaidietapp.logic.CheckInGenerator
import com.matchpoint.myaidietapp.logic.DailyPlanner
import com.matchpoint.myaidietapp.logic.RecipeParser
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
import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val todaySchedule: ScheduledMealDay? = null,
    val messages: List<MessageEntry> = emptyList(),
    val savedRecipes: List<SavedRecipe> = emptyList(),
    val chatSessions: List<ChatSession> = emptyList(),
    val activeChatId: String = "default",
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

private fun HttpException.safeErrorBodySnippet(maxChars: Int = 220): String? {
    return try {
        val raw = response()?.errorBody()?.string()
        val normalized = raw
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "…"
    } catch (_: Exception) {
        null
    }
}

private fun describeProxyHttpError(e: HttpException): String {
    val body = e.safeErrorBodySnippet()
    return if (body.isNullOrBlank()) "proxy HTTP ${e.code()}" else "proxy HTTP ${e.code()} $body"
}

class HomeViewModel(
    private val userId: String,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notificationScheduler: NotificationScheduler? = null,
    private val quotaManager: com.matchpoint.myaidietapp.data.DailyQuotaManager? = null,
    private val reviewPromptManager: com.matchpoint.myaidietapp.data.ReviewPromptManager? = null
) : ViewModel() {
    private fun normalizeFoodNameKey(s: String?): String {
        return (s ?: "")
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    private fun lbToKg(lb: Double): Double = lb / 2.2046226218
    private fun kgToLb(kg: Double): Double = kg * 2.2046226218

    private fun toPounds(value: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.KG) kgToLb(value) else value

    private fun fromPounds(valueLb: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.KG) lbToKg(valueLb) else valueLb

    private fun formatWeight(valueLb: Double, unit: WeightUnit): String {
        val v = fromPounds(valueLb, unit)
        val rounded = kotlin.math.round(v * 10.0) / 10.0
        val suffix = if (unit == WeightUnit.KG) "kg" else "lb"
        return "${rounded} $suffix"
    }


    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val dailyPlanner = DailyPlanner(zoneId)
    private val checkInGenerator = CheckInGenerator()

    private val userRepo by lazy { UserRepository(db, userId) }
    private val scheduleRepo by lazy { ScheduledMealsRepository(db, userId) }
    private val messagesRepo by lazy { MessagesRepository(db, userId) }
    private val recipesRepo by lazy { RecipesRepository(db, userId) }
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

            // Always start on a fresh draft chat on app startup so users don't see the previous conversation by default.
            // This draft is not persisted (so it won't show up as "New chat" in history).
            messagesRepo.startDraftChat()

            // One-time-only friendly intro: show it ONLY the very first time the user enters the app.
            // Do not persist it to chat history.
            val (effectiveProfile, initialMessages, introPending) = if (!profile.hasSeenWelcomeIntro) {
                val welcomeMessage = MessageEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = "AI Diet Assistant online. Add foods to your list and I’ll help you manage your diet, generate recipes, and scan groceries/menus."
                )
                val introMessage = MessageEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = introText(profile)
                )
                val updated = profile.copy(hasSeenWelcomeIntro = true)
                runCatching { userRepo.saveUserProfile(updated) }
                Triple(updated, listOf(welcomeMessage, introMessage), true)
            } else {
                Triple(profile, emptyList(), false)
            }

            val chatSessions = runCatching { messagesRepo.listChats() }.getOrElse { emptyList() }
            val activeChatId = messagesRepo.getActiveChatId()
            val messages = initialMessages.ifEmpty { messagesRepo.getMessageLog().log }
            val savedRecipes = runCatching { recipesRepo.listRecipesNewestFirst() }.getOrElse { emptyList() }

            _uiState.value = UiState(
                isLoading = false,
                profile = effectiveProfile,
                todaySchedule = schedule,
                messages = messages,
                savedRecipes = savedRecipes,
                chatSessions = chatSessions,
                activeChatId = activeChatId,
                introPending = introPending
            )

        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        }
    }

    fun newChat() {
        viewModelScope.launch {
            try {
                // Start a draft chat (does not appear in history until the user sends a message).
                messagesRepo.startDraftChat()
                val sessions = messagesRepo.listChats()
                val log = messagesRepo.getMessageLog().log
                _uiState.value = _uiState.value.copy(
                    chatSessions = sessions,
                    activeChatId = messagesRepo.getActiveChatId(),
                    messages = log
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to create chat.")
            }
        }
    }

    fun selectChat(chatId: String) {
        viewModelScope.launch {
            try {
                messagesRepo.setActiveChat(chatId)
                val log = messagesRepo.getMessageLog().log
                val sessions = messagesRepo.listChats()
                _uiState.value = _uiState.value.copy(
                    chatSessions = sessions,
                    activeChatId = chatId,
                    messages = log
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load chat.")
            }
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            try {
                val currentActive = messagesRepo.getActiveChatId()
                val ok = messagesRepo.deleteChat(chatId)
                if (!ok) return@launch

                // If we deleted the active chat, immediately move to a new blank chat.
                if (currentActive == chatId) {
                    messagesRepo.createNewChat()
                }

                val sessions = messagesRepo.listChats()
                val log = messagesRepo.getMessageLog().log
                _uiState.value = _uiState.value.copy(
                    chatSessions = sessions,
                    activeChatId = messagesRepo.getActiveChatId(),
                    messages = log
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to delete chat.")
            }
        }
    }

    fun updateSubscriptionTier(tier: SubscriptionTier) {
        viewModelScope.launch {
            try {
                // Client should not write subscriptionTier. Server verifies entitlements and writes tier.
                // Keep this method as a no-op for backward compatibility.
                val current = userRepo.getUserProfile() ?: return@launch
                _uiState.value = _uiState.value.copy(profile = current)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to update plan.")
            }
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
                // IMPORTANT: Re-auth FIRST. If password is wrong, do NOT delete user data.
                authRepo.reauthenticateCurrentUser(password)

                // Delete app data (best-effort). This requires the user still being signed in.
                deleteUserDataBestEffort()

                // Finally, delete Firebase Auth user (requires recent login).
                authRepo.deleteCurrentUser()
                // After this, authUid becomes null and UI returns to AuthScreen
            } catch (e: Exception) {
                val msg = when (e) {
                    is FirebaseAuthInvalidCredentialsException ->
                        "Incorrect password. Please try again."
                    is FirebaseAuthRecentLoginRequiredException ->
                        "For security, please sign out and sign back in, then try deleting your account again."
                    else -> e.message ?: "Failed to delete account."
                }
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = msg
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
        } catch (e: Exception) {
            Log.w("DigitalStomach", "Delete account: failed deleting schedules", e)
        }

        try {
            // Delete message log doc
            db.collection("users").document(userId).collection("meta").document("messageLog").delete().await()
        } catch (e: Exception) {
            Log.w("DigitalStomach", "Delete account: failed deleting messageLog", e)
        }

        try {
            // Delete quota docs (server-side daily usage)
            val days = db.collection("users").document(userId)
                .collection("meta").document("usageDaily")
                .collection("days").get().await()
            days.documents.forEach { it.reference.delete().await() }
            db.collection("users").document(userId).collection("meta").document("usageDaily").delete().await()
        } catch (e: Exception) {
            Log.w("DigitalStomach", "Delete account: failed deleting usageDaily", e)
        }

        try {
            // Delete user profile doc last
            db.collection("users").document(userId).delete().await()
        } catch (e: Exception) {
            Log.w("DigitalStomach", "Delete account: failed deleting user profile doc", e)
        }

        try {
            // Delete Storage content (best-effort)
            storageRepo.deleteAllUserContent(userId)
        } catch (e: Exception) {
            Log.w("DigitalStomach", "Delete account: failed deleting storage content", e)
        }
    }

    // NOTE:
    // Chat quota is enforced server-side (RevenueCat-verified) in the Cloud Function.
    // We intentionally avoid local-only gating here because it can get stuck on stale Firestore tier
    // immediately after a purchase (user upgrades, but profile.subscriptionTier hasn't synced yet).

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
                "Upgrade to Basic for \$9.99/month (\$99.99/year) for up to 100 items, or Premium for \$19.99/month (\$199.99/year) for up to 500 items."
            SubscriptionTier.REGULAR ->
                "Upgrade to Premium for \$19.99/month (\$199.99/year) to raise your limit to 500 items."
            SubscriptionTier.PRO ->
                "You’ve hit the Premium item limit."
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
        weightUnit: WeightUnit,
        fastingPreset: FastingPreset,
        eatingWindowStartMinutes: Int?
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
                val todayDate = LocalDate.now(zoneId).toString()

                val hours = fastingPreset.eatingWindowHours
                val start = if (hours == null) null else eatingWindowStartMinutes
                val end = if (hours == null || start == null) null else (start + hours * 60) % (24 * 60)

                fun cleaned(x: Double?): Double? = x?.takeIf { it.isFinite() && it > 0.0 }
                val rawStart = cleaned(startingWeight)
                val rawGoal = cleaned(weightGoal)

                // Only persist the user's unit choice if they actually entered a weight.
                val unitToStore = if (rawStart != null || rawGoal != null) weightUnit else WeightUnit.LB

                // Store weights in pounds internally.
                val startLb = rawStart?.let { toPounds(it, unitToStore) }
                val goalLb = rawGoal?.let { toPounds(it, unitToStore) }

                val profile = UserProfile(
                    name = name,
                    weightUnit = unitToStore,
                    weightGoal = goalLb,
                    dietType = dietType,
                    subscriptionTier = SubscriptionTier.FREE,
                    fastingPreset = fastingPreset,
                    eatingWindowStartMinutes = start,
                    eatingWindowEndMinutes = end,
                    allowedFoods = emptyList(),
                    inventory = emptyMap(),
                    foodItems = emptyList(),
                    weightHistory = startLb?.let {
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
                    text = "AI Diet Assistant online. Add foods to your list and I’ll help you manage your diet, generate recipes, and scan groceries/menus."
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
                    profile = profile.copy(nextMealAtMillis = firstMealTime, hasSeenWelcomeIntro = true),
                    todaySchedule = schedule,
                    messages = listOf(welcomeMessage, introMessage),
                    introPending = true
                )

                // Persist one-time intro flag so it never shows again on later launches.
                runCatching { userRepo.saveUserProfile(profile.copy(nextMealAtMillis = firstMealTime, hasSeenWelcomeIntro = true)) }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun addFoodItemSimple(
        name: String,
        categories: Set<String>,
        quantity: Int,
        productUrl: String?,
        labelUrl: String?,
        nutritionFactsUrl: String?,
        // Optional qualifier like "fresh"/"frozen" for UI naming (e.g., "carrots (fresh)").
        // Important: this does NOT change the food name sent to the analysis/caching service.
        nameQualifier: String? = null
    ) {
        viewModelScope.launch {
            addFoodItemSimpleNow(
                name = name,
                categories = categories,
                quantity = quantity,
                productUrl = productUrl,
                labelUrl = labelUrl,
                nutritionFactsUrl = nutritionFactsUrl,
                nameQualifier = nameQualifier
            )
        }
    }

    private suspend fun addFoodItemSimpleNow(
        name: String,
        categories: Set<String>,
        quantity: Int,
        productUrl: String?,
        labelUrl: String?,
        nutritionFactsUrl: String?,
        nameQualifier: String?
    ) {
        val setProcessing = !_uiState.value.isProcessing
        try {
            if (setProcessing) _uiState.value = _uiState.value.copy(isProcessing = true)

            val current = userRepo.getUserProfile() ?: return
            val diet = current.dietType

            val analysisAttempt = runCatching {
                if (productUrl != null) {
                    proxyRepo.analyzeFood(
                        productUrl = productUrl,
                        labelUrl = labelUrl,
                        nutritionFactsUrl = nutritionFactsUrl,
                        dietType = diet
                    )
                } else {
                    // Text-only path: analyze based on the typed food name (this also warms the server-side foodCatalog cache).
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
                // Prefer per-diet rating so UI matches the user's selected diet.
                val dietFitRating = analysis.dietRatings[diet.name] ?: analysis.dietFitRating ?: analysis.rating
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
                    val cats = categories.map { it.trim().uppercase() }.distinct()
                    val baseName = analysis.normalizedName.ifBlank { name }.trim()
                    val q = nameQualifier
                        ?.trim()
                        ?.lowercase()
                        ?.replace(Regex("\\s+"), " ")
                        ?.takeIf { it.isNotBlank() }
                    val finalName = if (q == null) baseName else "$baseName ($q)"
                    val key = normalizeFoodNameKey(finalName)

                    // If user already has this item, don't add a duplicate row; increment quantity instead.
                    val existingIdx = current.foodItems.indexOfFirst { fi ->
                        normalizeFoodNameKey(fi.name) == key
                    }
                    if (existingIdx >= 0) {
                        val existing = current.foodItems[existingIdx]
                        val updatedItem = existing.copy(
                            quantity = (existing.quantity + quantity).coerceAtLeast(1),
                            // Merge categories so "Rice" can be Meal+Ingredient etc without duplicates.
                            categories = (existing.categories + cats).map { it.trim().uppercase() }.distinct(),
                            // Prefer to keep any existing photos; if this add has a photo and existing doesn't, keep it.
                            photoUrl = existing.photoUrl ?: productUrl,
                            labelUrl = existing.labelUrl ?: labelUrl,
                            nutritionFactsUrl = existing.nutritionFactsUrl ?: nutritionFactsUrl
                        )
                        val updatedList = current.foodItems.toMutableList().also { it[existingIdx] = updatedItem }
                        val updatedProfile = current.copy(foodItems = updatedList)
                        userRepo.saveUserProfile(updatedProfile)
                        messagesRepo.appendMessage(
                            MessageEntry(
                                id = UUID.randomUUID().toString(),
                                sender = MessageSender.AI,
                                text = "Already had '${existing.name}'. Increased quantity to x${updatedItem.quantity}."
                            )
                        )
                        _uiState.value = _uiState.value.copy(
                            profile = updatedProfile,
                            messages = messagesRepo.getMessageLog().log
                        )
                        return
                    }

                    // Only new unique items should consume a slot.
                    if (!checkFoodListLimitOrPrompt(current, addingCount = 1)) {
                        return
                    }

                    val newItem = FoodItem(
                        id = UUID.randomUUID().toString(),
                        name = finalName,
                        quantity = quantity.coerceAtLeast(1),
                        categories = cats,
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
                    is retrofit2.HttpException -> describeProxyHttpError(e)
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
            if (setProcessing) _uiState.value = _uiState.value.copy(isProcessing = false)
        }
    }

    fun addFoodItemsBatch(
        names: List<String>,
        categories: Set<String>
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
                val current = userRepo.getUserProfile() ?: return@launch
                val diet = current.dietType

                val cleaned = names.map { it.trim() }.filter { it.isNotBlank() }
                if (cleaned.isEmpty()) return@launch

                val cats = categories.map { it.trim().uppercase() }.distinct()

                // Deduplicate within the submission, but DO NOT filter out items already in the list yet—
                // duplicates should increment quantity instead of being dropped on the floor.
                val deduped = cleaned
                    .map { it.replace(Regex("\\s+"), " ").trim() }
                    .distinctBy { normalizeFoodNameKey(it) }

                if (deduped.isEmpty()) {
                    messagesRepo.appendMessage(
                        MessageEntry(
                            id = UUID.randomUUID().toString(),
                            sender = MessageSender.AI,
                            text = "Those items are already in your list."
                        )
                    )
                    _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
                    return@launch
                }

                // Backend has a batch max; chunk requests to stay under it.
                val CHUNK_SIZE = 25
                val analyses = mutableListOf<com.matchpoint.myaidietapp.data.AnalyzeFoodResponse>()
                val chunks = deduped.chunked(CHUNK_SIZE)

                for (chunk in chunks) {
                    val batch = runCatching {
                        proxyRepo.analyzeFoodsByNameBatch(chunk, diet)
                    }.getOrNull()

                    if (batch != null && batch.size == chunk.size) {
                        analyses.addAll(batch)
                    } else {
                        // Fallback (still after one Submit): analyze one-by-one for this chunk.
                        chunk.forEach { n ->
                            val a = runCatching { proxyRepo.analyzeFoodByName(n, diet) }.getOrNull()
                            if (a != null) analyses.add(a)
                        }
                    }
                }

                // Build a mutable working list so we can increment quantities for existing items.
                val working = current.foodItems.toMutableList()
                val indexByKey = working
                    .mapIndexed { idx, fi -> normalizeFoodNameKey(fi.name) to idx }
                    .toMap()
                    .toMutableMap()

                var updatedCount = 0
                val newItems = mutableListOf<FoodItem>()

                analyses.forEach { analysis ->
                    if (!analysis.accepted) return@forEach
                    val normalized = analysis.normalizedName.ifBlank { "" }.trim()
                    val nameToUse = if (normalized.isNotBlank()) normalized else null
                    val finalName = nameToUse ?: return@forEach
                    val key = normalizeFoodNameKey(finalName)

                    val existingIdx = indexByKey[key]
                    if (existingIdx != null) {
                        val existing = working[existingIdx]
                        val updated = existing.copy(
                            quantity = (existing.quantity + 1).coerceAtLeast(1),
                            categories = (existing.categories + cats).map { it.trim().uppercase() }.distinct()
                        )
                        working[existingIdx] = updated
                        updatedCount += 1
                        return@forEach
                    }

                    // Prefer per-diet rating so UI matches the user's selected diet.
                    val dietFitRating = analysis.dietRatings[diet.name] ?: analysis.dietFitRating ?: analysis.rating
                    val dietRatings = analysis.dietRatings.toMutableMap().apply {
                        putIfAbsent(diet.name, dietFitRating)
                    }.toMap()

                    newItems.add(
                        FoodItem(
                            id = UUID.randomUUID().toString(),
                            name = finalName,
                            quantity = 1,
                            categories = cats,
                            notes = analysis.summary,
                            estimatedCalories = analysis.estimatedCalories,
                            estimatedProteinG = analysis.estimatedProteinG,
                            estimatedCarbsG = analysis.estimatedCarbsG,
                            estimatedFatG = analysis.estimatedFatG,
                            ingredientsText = analysis.ingredientsText,
                            rating = analysis.rating,
                            dietFitRating = dietFitRating,
                            dietRatings = dietRatings,
                            allergyRatings = analysis.allergyRatings
                        )
                    )
                    // Track new keys so later analyses in the same batch increment instead of duplicating.
                    indexByKey[key] = -1 // placeholder, will be resolved after we append
                }

                if (newItems.isEmpty() && updatedCount == 0) {
                    messagesRepo.appendMessage(
                        MessageEntry(
                            id = UUID.randomUUID().toString(),
                            sender = MessageSender.AI,
                            text = "I couldn’t recognize any of those as food items."
                        )
                    )
                    _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
                    return@launch
                }

                // Only NEW unique items consume slots.
                val newUniqueCount = newItems.size
                if (newUniqueCount > 0 && !checkFoodListLimitOrPrompt(current, addingCount = newUniqueCount)) {
                    return@launch
                }

                // Append new items and fix placeholder indexes.
                val startIdx = working.size
                working.addAll(newItems)
                // Replace placeholder -1 with actual indices for completeness (not strictly needed afterwards).
                for (i in 0 until newItems.size) {
                    val k = normalizeFoodNameKey(newItems[i].name)
                    indexByKey[k] = startIdx + i
                }

                val updatedProfile = current.copy(foodItems = working)
                userRepo.saveUserProfile(updatedProfile)

                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = when {
                            newItems.isNotEmpty() && updatedCount > 0 ->
                                "Added ${newItems.size} new item(s) and updated $updatedCount existing item(s)."
                            newItems.isNotEmpty() ->
                                "Added ${newItems.size} item(s) to your list."
                            else ->
                                "Updated $updatedCount existing item(s)."
                        }
                    )
                )

                _uiState.value = _uiState.value.copy(
                    profile = updatedProfile,
                    messages = messagesRepo.getMessageLog().log
                )
            } catch (e: Exception) {
                Log.e("DigitalStomach", "addFoodItemsBatch failed", e)
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
        val foodLine = if (names.isNotEmpty()) "I see you have $names on your list." else "Start by adding a few foods to your list."
        val goalLine = profile.weightGoal?.takeIf { it > 0.0 }?.let { " Goal noted: ${formatWeight(it, profile.weightUnit)}." } ?: ""
        return "Hey ${profile.name.ifBlank { "there" }}. $foodLine$goalLine Want a recipe idea, a grocery opinion, or a menu recommendation?"
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

    fun updateShowFoodIcons(show: Boolean) {
        viewModelScope.launch {
            try {
                userRepo.updateShowFoodIcons(show)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateShowWallpaperFoodIcons(show: Boolean) {
        viewModelScope.launch {
            try {
                userRepo.updateShowWallpaperFoodIcons(show)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateUiFontSizeSp(fontSizeSp: Float) {
        viewModelScope.launch {
            try {
                userRepo.updateUiFontSizeSp(fontSizeSp)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateRecipeTitleFontStyle(style: RecipeTitleFontStyle) {
        viewModelScope.launch {
            try {
                userRepo.updateRecipeTitleFontStyle(style)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
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
                val profile = userRepo.getUserProfile() ?: return@launch
                val cleaned = weightGoal?.takeIf { it.isFinite() && it > 0.0 }
                val goalLb = cleaned?.let { toPounds(it, profile.weightUnit) }
                userRepo.updateWeightGoal(goalLb)
                val updated = profile.copy(weightGoal = goalLb)
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
                val profile = userRepo.getUserProfile() ?: return@launch
                val cleaned = weight.takeIf { it.isFinite() && it > 0.0 } ?: return@launch
                val weightLb = toPounds(cleaned, profile.weightUnit)
                val today = LocalDate.now(zoneId).toString()
                userRepo.appendWeightEntry(WeightEntry(date = today, weight = weightLb))
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun updateWeightUnit(weightUnit: WeightUnit) {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUserProfile() ?: return@launch
                userRepo.updateWeightUnit(weightUnit)
                val updated = profile.copy(weightUnit = weightUnit)
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to update weight unit.")
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
                // Optimistic UI update: show the user's message immediately while the AI is thinking.
                // Also refresh chat sessions so the "New chat" title becomes a topic title immediately.
                val refreshedChats = runCatching { messagesRepo.listChats() }.getOrElse { _uiState.value.chatSessions }
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + userEntry,
                    chatSessions = refreshedChats,
                    activeChatId = messagesRepo.getActiveChatId()
                )

                val currentProfile = userRepo.getUserProfile()
                val lastMeal = currentProfile?.mealHistory?.maxByOrNull { it.timestamp }
                val hungerLevels = currentProfile?.hungerSignals?.map { it.level } ?: emptyList()

                val inventorySummary = buildInventorySummary(currentProfile)
                val chatContext = buildChatContextForAi(_uiState.value.messages)

                val minutesSinceMeal = lastMeal?.let {
                    val now = Instant.now()
                    val diffMillis = now.toEpochMilli() - it.timestamp.toDate().time
                    diffMillis / 60_000
                } // if null -> first meal of the app; don't assume "too early"

                val replyResult = runCatching {
                    val now = java.time.LocalTime.now(zoneId)
                    val clientLocalMinutes = now.hour * 60 + now.minute
                    val tzOffsetMinutes = java.time.ZonedDateTime.now(zoneId).offset.totalSeconds / 60
                    proxyRepo.generateCheckIn(
                        CheckInRequest(
                            lastMeal = lastMeal?.mealName ?: lastMeal?.items?.joinToString(),
                            hungerSummary = buildString {
                                append("recent hunger: ")
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
                            timezoneOffsetMinutes = tzOffsetMinutes,
                            chatContext = chatContext
                        )
                    )
                }.onFailure { e ->
                    Log.e("DigitalStomach", "Freeform proxy failed", e)
                    if (e is HttpException && e.code() == 429) {
                        val detail = e.safeErrorBodySnippet(420)
                        _uiState.value = _uiState.value.copy(
                            planGateNotice = if (!detail.isNullOrBlank())
                                "You have reached your daily limit.\n\n$detail"
                            else
                                "You have reached your daily limit."
                        )
                    }
                }

                if (replyResult.isSuccess) {
                    reviewPromptManager?.recordSuccessfulChat()
                }

                val replyText = replyResult.getOrElse { e ->
                    when (e) {
                        is HttpException -> {
                            if (e.code() == 429) {
                                // Don't show "No service" for quota: this is an expected, user-facing state.
                                "Daily limit reached. Upgrade your plan to keep chatting."
                            } else {
                                "No service: ${describeProxyHttpError(e)}"
                            }
                        }
                        else -> "No service: ${e.message ?: "unknown error"}"
                    }
                }

                // If the AI includes app metadata / actions, parse and strip them from visible text.
                val (withoutKind, appKind) = extractAppKindFromAiText(replyText)
                val (visibleText, appAction) = extractAppActionFromAiText(withoutKind)

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = visibleText,
                    kind = appKind ?: if (looksLikeRecipe(visibleText)) "RECIPE" else null
                )
                messagesRepo.appendMessage(aiEntry)

                // Execute action(s) after storing the AI message so the chat reflects the flow.
                if (appAction != null) {
                    // Safety: only execute list-mutation actions if the USER explicitly asked to add.
                    val shouldExecute = when (appAction.type) {
                        "ADD_FOODS" -> userAskedToAddFoods(message)
                        else -> true
                    }
                    if (!shouldExecute) {
                        Log.w(
                            "DigitalStomach",
                            "Ignoring APP_ACTION type=${appAction.type} because user message didn't ask to add. userMessage=" +
                                message.replace("\n", " ").take(220)
                        )
                    } else {
                        runCatching { applyAppAction(appAction) }
                        .onFailure { e -> Log.e("DigitalStomach", "applyAppAction failed", e) }
                    }
                }

                val updatedLog = messagesRepo.getMessageLog().log
                // Re-fetch profile after any action execution so we don't overwrite the updated profile
                // with the stale snapshot we read earlier.
                val latestProfile = runCatching { userRepo.getUserProfile() }.getOrNull() ?: currentProfile
                _uiState.value = _uiState.value.copy(
                    profile = latestProfile,
                    messages = updatedLog
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    // (Removed legacy PendingFoodAdd / regex intent logic in favor of <APP_ACTION> protocol.)

    private fun extractAppKindFromAiText(text: String): Pair<String, String?> {
        val openRe = Regex("<\\s*APP_KIND\\s*>", RegexOption.IGNORE_CASE)
        val closeRe = Regex("</\\s*APP_KIND\\s*>", RegexOption.IGNORE_CASE)
        val open = openRe.find(text) ?: return text.trim() to null
        val start = open.range.last + 1
        val close = closeRe.find(text, startIndex = start)
        val end = close?.range?.first ?: text.length
        val payload = text.substring(start, end).trim()
        val kind = payload.uppercase().takeIf { it == "RECIPE" }
        val visible = buildString {
            append(text.substring(0, open.range.first))
            if (close != null) {
                append(text.substring(close.range.last + 1))
            }
        }.trim()
        return visible to kind
    }

    private fun looksLikeRecipe(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false

        // Primary: use the same parser used for saved recipes.
        // If we can extract a plausible title + some ingredients, treat it as a recipe so UI shows Save.
        val parsed = runCatching { RecipeParser.parse(t) }.getOrNull()
        val hasParsedTitle = parsed?.title?.isNotBlank() == true
        val ingredientCount = parsed?.ingredients?.size ?: 0

        // Secondary heuristics (for slightly off-format replies)
        val hasIngredientsHeader = Regex("(?m)^\\s*ingredients\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
        val hasStepsHeader = Regex("(?m)^\\s*(steps|instructions|directions|method)\\b\\s*:", RegexOption.IGNORE_CASE)
            .containsMatchIn(t)
        val hasNumberedSteps = Regex("(?m)^\\s*\\d+\\)\\s+").containsMatchIn(t)
        val bulletCount = Regex("(?m)^\\s*[-•]\\s+\\S+").findAll(t).count()
        val numberedCount = Regex("(?m)^\\s*\\d+\\)\\s+\\S+").findAll(t).count()

        return (hasParsedTitle && ingredientCount >= 2) ||
            // Parser can miss title; if we have several ingredients and some step structure, call it a recipe.
            (ingredientCount >= 2 && (hasStepsHeader || hasNumberedSteps)) ||
            // If model didn't use our exact headers, infer from bullets + numbered instructions.
            (bulletCount >= 2 && numberedCount >= 2) ||
            (hasIngredientsHeader && (hasStepsHeader || hasNumberedSteps) && (bulletCount >= 1 || numberedCount >= 1))
    }

    private fun userAskedToAddFoods(userMessage: String): Boolean {
        val t = userMessage.trim().lowercase()
        if (t.isBlank()) return false
        // Keep this conservative: we only want to mutate state on explicit user intent.
        val hasAddVerb = Regex("\\b(add|save|put|include)\\b").containsMatchIn(t)
        val hasListTarget = Regex("\\b(list|foods?|ingredients?|snacks?|meals?)\\b").containsMatchIn(t)
        return hasAddVerb && hasListTarget
    }

    private data class AppActionFood(
        val name: String,
        val category: String?,
        val qualifier: String?
    )

    private data class AppAction(
        val type: String,
        val items: List<AppActionFood>
    )

    private fun extractAppActionFromAiText(text: String): Pair<String, AppAction?> {
        // Be tolerant:
        // - Tag casing may vary.
        // - The model may omit the closing tag.
        // - The model may include extra text around the JSON.
        val openRe = Regex("<\\s*APP_ACTION\\s*>", RegexOption.IGNORE_CASE)
        val closeRe = Regex("</\\s*APP_ACTION\\s*>", RegexOption.IGNORE_CASE)

        val open = openRe.find(text) ?: return text.trim() to null
        val payloadStart = open.range.last + 1
        val close = closeRe.find(text, startIndex = payloadStart)
        val payloadEnd = close?.range?.first ?: text.length
        val payload = text.substring(payloadStart, payloadEnd).trim()

        val visible = buildString {
            append(text.substring(0, open.range.first))
            if (close != null) {
                append(text.substring(close.range.last + 1))
            }
        }.trim()

        val action = runCatching { parseAppActionJson(payload) }.getOrNull()
        if (action == null) {
            Log.w(
                "DigitalStomach",
                "APP_ACTION present but failed to parse. Payload snippet: " +
                    payload.replace("\n", " ").take(280)
            )
        }
        return visible to action
    }

    private fun parseAppActionJson(json: String): AppAction? {
        if (json.isBlank()) return null
        val obj = runCatching { JSONObject(json) }.getOrElse {
            // Be tolerant: if the model wraps text around the JSON, try to slice to the outermost {...}.
            val start = json.indexOf('{')
            val end = json.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(json.substring(start, end + 1)) else return null
        }
        val type = obj.optString("type").trim().uppercase()
        if (type.isBlank()) return null
        val itemsArr = obj.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                val it = itemsArr.optJSONObject(i) ?: continue
                val name = it.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    AppActionFood(
                        name = name,
                        category = it.optString("category").trim().ifBlank { null },
                        qualifier = it.optString("qualifier").trim().ifBlank { null }
                    )
                )
            }
        }
        return AppAction(type = type, items = items)
    }

    private fun normalizeActionCategory(raw: String?): String {
        val c = raw?.trim()?.uppercase()
        return when (c) {
            "MEAL", "MEALS" -> "MEAL"
            "SNACK", "SNACKS" -> "SNACK"
            "INGREDIENT", "INGREDIENTS" -> "INGREDIENT"
            else -> "INGREDIENT"
        }
    }

    private fun buildActionFoodName(name: String, qualifier: String?): String {
        val base = name.trim().replace(Regex("\\s+"), " ")
        val q = qualifier?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }
        return if (q == null) base else "$base ($q)"
    }

    private suspend fun applyAppAction(action: AppAction) {
        when (action.type) {
            "ADD_FOODS" -> applyAddFoodsAction(action.items)
            else -> Unit
        }
    }

    private suspend fun applyAddFoodsAction(items: List<AppActionFood>) {
        if (items.isEmpty()) return

        // For chat-driven adds, behave like manual add (ratings/notes), BUT:
        // - Do NOT change quantity for items already in the list (quantity isn't visible in UI).
        // - Do NOT spam the chat with per-item analysis lines; keep confirmations short.
        _uiState.value = _uiState.value.copy(isProcessing = true)
        try {
            val profile = userRepo.getUserProfile() ?: return

            val toAdd = items.mapNotNull { it ->
                val name = it.name.trim()
                if (name.isBlank()) null else it
            }
            if (toAdd.isEmpty()) return

            val newUniqueCandidates = mutableListOf<AppActionFood>()
            val alreadyHad = mutableListOf<String>()

            // First pass: determine which are new (based on final display name).
            for (it in toAdd) {
                val finalDisplayName = buildActionFoodName(it.name, it.qualifier)
                val key = normalizeFoodNameKey(finalDisplayName)
                val exists = profile.foodItems.any { fi -> normalizeFoodNameKey(fi.name) == key }
                if (exists) {
                    alreadyHad.add(finalDisplayName)
                } else {
                    newUniqueCandidates.add(it)
                }
            }

            if (newUniqueCandidates.isNotEmpty() &&
                !checkFoodListLimitOrPrompt(profile, addingCount = newUniqueCandidates.size)
            ) {
                return
            }

            val working = profile.foodItems.toMutableList()
            val added = mutableListOf<String>()

            for (it in newUniqueCandidates) {
                val cat = normalizeActionCategory(it.category)
                val analysis = runCatching {
                    proxyRepo.analyzeFoodByName(it.name, profile.dietType)
                }.getOrNull() ?: continue

                if (!analysis.accepted) continue

                val baseName = analysis.normalizedName.ifBlank { it.name }.trim()
                val q = it.qualifier
                    ?.trim()
                    ?.lowercase()
                    ?.replace(Regex("\\s+"), " ")
                    ?.takeIf { it.isNotBlank() }
                val finalName = if (q == null) baseName else "$baseName ($q)"

                val healthRating = analysis.rating
                val dietFitRating = analysis.dietRatings[profile.dietType.name]
                    ?: analysis.dietFitRating
                    ?: analysis.rating
                val dietRatings = analysis.dietRatings.toMutableMap().apply {
                    putIfAbsent(profile.dietType.name, dietFitRating)
                }.toMap()

                val newItem = FoodItem(
                    id = UUID.randomUUID().toString(),
                    name = finalName,
                    quantity = 1,
                    categories = listOf(cat),
                    notes = analysis.summary,
                    estimatedCalories = analysis.estimatedCalories,
                    estimatedProteinG = analysis.estimatedProteinG,
                    estimatedCarbsG = analysis.estimatedCarbsG,
                    estimatedFatG = analysis.estimatedFatG,
                    ingredientsText = analysis.ingredientsText,
                    rating = healthRating,
                    dietFitRating = dietFitRating,
                    dietRatings = dietRatings,
                    allergyRatings = analysis.allergyRatings
                )
                working.add(newItem)
                added.add(finalName)
            }

            val updatedProfile = profile.copy(foodItems = working)
            userRepo.saveUserProfile(updatedProfile)

            if (added.isNotEmpty()) {
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Added to your list: ${added.joinToString(", ")}."
                    )
                )
            }
            if (alreadyHad.isNotEmpty()) {
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Already in your list: ${alreadyHad.distinctBy { it.lowercase() }.joinToString(", ")}."
                    )
                )
            }

            _uiState.value = _uiState.value.copy(
                profile = updatedProfile,
                messages = messagesRepo.getMessageLog().log
            )
        } finally {
            _uiState.value = _uiState.value.copy(isProcessing = false)
        }
    }

    private fun buildChatContextForAi(messages: List<MessageEntry>): String? {
        // Include recent turns so follow-up questions stay on-topic (e.g., same dish/recipe).
        // Keep it short to avoid prompt bloat.
        val recent = messages.takeLast(12)
        if (recent.isEmpty()) return null

        fun clip(s: String, max: Int): String {
            val t = s.replace("\r", "").trim()
            return if (t.length <= max) t else t.take(max) + "…"
        }

        val lines = recent.mapNotNull { m ->
            val role = if (m.sender == MessageSender.USER) "User" else "AI"
            val text = clip(m.text, if (m.kind == "RECIPE") 420 else 240)
            if (text.isBlank()) null else "$role: $text"
        }
        val joined = lines.joinToString("\n").trim()
        return joined.ifBlank { null }
    }

    fun generateMeal(requiredIngredients: List<String> = emptyList()) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val profile = userRepo.getUserProfile() ?: return@launch
                val inventorySummary = buildInventorySummary(profile)
                val existingTitles = _uiState.value.savedRecipes
                    .mapNotNull { it.title.trim().ifBlank { null } }
                    .distinctBy { it.lowercase() }
                    .take(30)
                val required = requiredIngredients
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .take(6)

                val replyResult = runCatching {
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
                            userMessage = buildString {
                                append("Generate a dinner recipe I can cook now. ")
                                append("Use my INGREDIENTS list (and SNACKS if useful). ")
                                append("Do not use emojis. ")
                                if (required.isNotEmpty()) {
                                    append("MUST include these ingredients: ")
                                    append(required.joinToString(", "))
                                    append(". ")
                                }
                                append("Give step-by-step instructions with times/temps. ")
                                append("If I don't have enough ingredients, say what I'm missing and suggest a short shopping list.")
                            },
                            tone = "recipe mode: detailed steps; be specific; no fluff",
                            inventorySummary = inventorySummary,
                            dietType = profile.dietType.name,
                            fastingPreset = profile.fastingPreset.name,
                            eatingWindowStartMinutes = profile.eatingWindowStartMinutes,
                            eatingWindowEndMinutes = profile.eatingWindowEndMinutes,
                            clientLocalMinutes = clientLocalMinutes,
                            timezoneOffsetMinutes = tzOffsetMinutes,
                            existingRecipeTitles = existingTitles,
                            requiredIngredients = required
                        )
                    )
                }.onFailure { e ->
                    if (e is HttpException && e.code() == 429) {
                        val detail = e.safeErrorBodySnippet(420)
                        _uiState.value = _uiState.value.copy(
                            planGateNotice = if (!detail.isNullOrBlank())
                                "You have reached your daily limit.\n\n$detail"
                            else
                                "You have reached your daily limit."
                        )
                    }
                }

                if (replyResult.isSuccess) {
                    reviewPromptManager?.recordSuccessfulChat()
                }

                val replyText = replyResult.getOrElse { e ->
                    when (e) {
                        is HttpException -> {
                            if (e.code() == 429) {
                                "Daily limit reached. Upgrade your plan to keep chatting."
                            } else {
                                "No service: ${describeProxyHttpError(e)}"
                            }
                        }
                        else -> "No service. Check internet connectivity. (${e.javaClass.simpleName})"
                    }
                }

                // Safety: even with prompt instructions, models occasionally emit emojis.
                // Strip them from stored recipe text so headers like "Missing / recommended" don't get random emoji icons.
                val cleanedReplyText = replyText
                    .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "") // surrogate pairs (most emojis)
                    .replace("\uFE0F", "") // variation selector-16

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = cleanedReplyText,
                    kind = "RECIPE"
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

    fun saveRecipeFromMessage(messageId: String) {
        viewModelScope.launch {
            try {
                val msg = _uiState.value.messages.firstOrNull { it.id == messageId }
                    ?: run { _uiState.value = _uiState.value.copy(error = "Recipe message not found."); return@launch }

                if (msg.sender != MessageSender.AI || msg.kind != "RECIPE") {
                    _uiState.value = _uiState.value.copy(error = "That message isn't a recipe.")
                    return@launch
                }

                // Idempotent-ish: don't re-save the same chat recipe message.
                if (_uiState.value.savedRecipes.any { it.id == msg.id || it.sourceMessageId == msg.id }) return@launch

                val parsed = RecipeParser.parse(msg.text)
                val recipe = SavedRecipe(
                    id = UUID.randomUUID().toString(),
                    sourceMessageId = msg.id,
                    title = parsed.title.ifBlank { "Recipe" },
                    text = msg.text,
                    ingredients = parsed.ingredients
                )
                recipesRepo.saveRecipe(recipe)

                val updated = recipesRepo.listRecipesNewestFirst()
                _uiState.value = _uiState.value.copy(savedRecipes = updated)

                // Optional confirmation message in chat (kept short)
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Saved to Profile → Recipes."
                    )
                )
                _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to save recipe.")
            }
        }
    }

    fun deleteSavedRecipe(recipeId: String) {
        viewModelScope.launch {
            try {
                recipesRepo.deleteRecipe(recipeId)
                val updated = recipesRepo.listRecipesNewestFirst()
                _uiState.value = _uiState.value.copy(savedRecipes = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to delete recipe.")
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

    suspend fun uploadMenuPhoto(scanId: String, uri: android.net.Uri): String {
        val path = "menuPhotos/$userId/$scanId/menu.jpg"
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

    fun evaluateMenuScan(menuPhotoUrl: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)
                val profile = userRepo.getUserProfile() ?: return@launch
                val inventorySummary = buildInventorySummary(profile)

                val text = runCatching {
                    proxyRepo.analyzeMenu(
                        menuPhotoUrl = menuPhotoUrl,
                        dietType = profile.dietType,
                        inventorySummary = inventorySummary
                    )
                }.getOrElse { e ->
                    when (e) {
                        is HttpException -> "No service: ${describeProxyHttpError(e)}"
                        else -> "No service. Check internet connectivity. (${e.javaClass.simpleName})"
                    }
                }

                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Menu scan:\n$text"
                    )
                )
                _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
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
            when (e) {
                is HttpException -> "No service: ${describeProxyHttpError(e)}"
                else -> "No service: ${e.message ?: "unknown error"}"
            }
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
