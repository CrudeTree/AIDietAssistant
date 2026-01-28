package com.matchpoint.myaidietapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.matchpoint.myaidietapp.data.MessagesRepository
import com.matchpoint.myaidietapp.data.RecipesRepository
import com.matchpoint.myaidietapp.data.RecipeQnaRepository
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
import kotlinx.coroutines.delay
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
    val recipeQaByRecipeId: Map<String, List<MessageEntry>> = emptyMap(),
    val recipeQaBusyRecipeId: String? = null,
    val introPending: Boolean = false,
    val error: String? = null,
    val isProcessing: Boolean = false,
    val pendingGrocery: PendingGrocery? = null,
    val planGateNotice: String? = null,
    /**
     * Client-side override for subscription tier, sourced from RevenueCat (when available).
     *
     * We do this because Firestore rules treat `subscriptionTier` as server-managed and the client
     * often sees a stale FREE tier immediately after purchase. Using this override prevents limits
     * like "10 food items" from getting stuck after upgrading.
     */
    val effectiveTierOverride: SubscriptionTier? = null,
    val dailyPlanMeals: List<DailyPlanUiMeal> = emptyList(),
    val dailyPlanTotalCount: Int = 0,
    val dailyPlanDayId: String? = null
)

// (Old upgrade modal removed in favor of a full "Choose a plan" screen.)

data class PendingGrocery(
    val id: String,
    val aiMessage: String,
    val item: FoodItem,
    // Optional calories metadata for UI (AI Evaluate Food).
    val portionKind: String? = null, // "PACKAGED" | "PLATED" | "UNKNOWN"
    val caloriesPerServing: Int? = null,
    val servingsPerContainer: Double? = null,
    val caloriesTotal: Int? = null,
    val caloriesLow: Int? = null,
    val caloriesHigh: Int? = null
)

data class DailyPlanUiMeal(
    val id: String,
    val title: String,
    val estimatedCalories: Int,
    val recipeText: String,
    val sourceRecipeId: String? = null
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

private fun buildDailyLimitGateNotice(e: HttpException): String {
    val detail = e.safeErrorBodySnippet(420)
    return if (!detail.isNullOrBlank()) {
        "Daily Limit Reached.\n\n$detail"
    } else {
        "Daily Limit Reached."
    }
}

private fun filterUserVisibleChats(sessions: List<ChatSession>): List<ChatSession> {
    // Hide per-recipe Q&A threads from the normal chat history UI.
    return sessions.filterNot { it.id.trim().startsWith("recipe:", ignoreCase = true) }
}

private fun parseRemoveFromListCommand(userMessage: String): String? {
    val s = userMessage.trim()
    if (s.isBlank()) return null

    // We want this to feel natural-language, not "exact command" based.
    // Examples we should catch:
    // - "Remove bacon"
    // - "Can you remove Bacon from my list?"
    // - "I think I want to remove bacon from the list"
    // - "Let's go ahead and remove greek yogurt from my ingredients"
    // - "Please delete cottage cheese"
    val verb = Regex("""\b(remove|delete)\b""", RegexOption.IGNORE_CASE)
    val m = verb.find(s) ?: return null

    var after = s.substring(m.range.last + 1).trim()
    if (after.isBlank()) return null

    // Cut off trailing "from ..." / "off ..." phrases so we isolate the item name.
    val cutoff = listOf(" from ", " off ", " out of ", " in ")
        .mapNotNull { tok ->
            val idx = after.lowercase().indexOf(tok)
            if (idx >= 0) idx else null
        }
        .minOrNull()
    if (cutoff != null) after = after.take(cutoff).trim()

    // Strip some common filler words at the start of the extracted phrase.
    after = after
        .replace(Regex("""^(please|just|kindly|go ahead and|ahead and|the)\s+""", RegexOption.IGNORE_CASE), "")
        .trim()

    // Remove surrounding quotes if present.
    after = after.trim().trim('"', '\'').trim()

    // Trim punctuation at the end.
    val cleaned = after
        .trim()
        .trimEnd('.', '!', '?', ',', ';', ':')
        .trim()
        .takeIf { it.isNotBlank() }
        ?: return null

    // Avoid accidentally treating very long user sentences as a "name".
    if (cleaned.length > 80) return null

    // Guard against ultra-generic removes like "remove it" / "remove that".
    val generic = setOf("it", "that", "this", "them", "those")
    if (generic.contains(cleaned.lowercase())) return null

    return cleaned
}

private data class AddToListIntent(
    val items: List<String>,
    val category: String // "INGREDIENT" | "SNACK" | "MEAL"
)

private fun parseAddToListIntent(userMessage: String): AddToListIntent? {
    val raw = userMessage.trim()
    if (raw.isBlank()) return null
    val t = raw.lowercase()

    val verb = Regex("""\b(add|save|put|include)\b""", RegexOption.IGNORE_CASE)
    val m = verb.find(t) ?: return null

    val hasListTarget = Regex("""\b(my\s+)?(list|food\s*list|foods|ingredients?|snacks?|meals?)\b""")
        .containsMatchIn(t)

    // Avoid false positives like recipe instructions: "add salt", "add onions to the pan".
    // If they don't mention a list/foods target, only accept very short "add X" messages.
    if (!hasListTarget && raw.length > 40) return null

    // Determine target category (default INGREDIENT if unspecified).
    val category = when {
        Regex("""\bingredients?\b""").containsMatchIn(t) -> "INGREDIENT"
        Regex("""\bsnacks?\b""").containsMatchIn(t) -> "SNACK"
        Regex("""\bmeals?\b""").containsMatchIn(t) -> "MEAL"
        else -> "INGREDIENT"
    }

    // Extract the "thing(s)" after the add-verb.
    var after = raw.substring(m.range.last + 1).trim()
    if (after.isBlank()) return null

    // Cut off any trailing "to my list/foods/ingredients..." clause so we isolate item names.
    val cutRe = Regex("""\b(to|into|in)\b\s+(my\s+)?(list|food\s*list|foods|ingredients?|snacks?|meals?)\b""", RegexOption.IGNORE_CASE)
    val cut = cutRe.find(after)
    if (cut != null) {
        after = after.substring(0, cut.range.first).trim()
    }

    // Strip filler at start.
    after = after
        .replace(Regex("""^(please|just|kindly|go ahead and|ahead and|the)\s+""", RegexOption.IGNORE_CASE), "")
        .trim()

    // Normalize separators: commas and "and"/"&".
    val normalized = after
        .replace(" & ", ", ")
        .replace(Regex("""\s+\band\b\s+""", RegexOption.IGNORE_CASE), ", ")
        .trim()

    val parts = normalized
        .split(",")
        .map { it.trim().trim('"', '\'').trim().trimEnd('.', '!', '?', ';', ':') }
        .map { it.replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotBlank() }
        .take(12)

    if (parts.isEmpty()) return null

    val generic = setOf("it", "that", "this", "them", "those", "something", "anything")
    val items = parts.filterNot { generic.contains(it.lowercase()) }
    if (items.isEmpty()) return null

    return AddToListIntent(items = items, category = category)
}

class HomeViewModel(
    private val userId: String,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notificationScheduler: NotificationScheduler? = null,
    private val quotaManager: com.matchpoint.myaidietapp.data.DailyQuotaManager? = null,
    private val reviewPromptManager: com.matchpoint.myaidietapp.data.ReviewPromptManager? = null
) : ViewModel() {
    private var lastTutorialProgressSent: Int? = null
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

    fun setEffectiveTierOverride(tier: SubscriptionTier?) {
        val cur = _uiState.value.effectiveTierOverride
        if (cur == tier) return
        _uiState.value = _uiState.value.copy(effectiveTierOverride = tier)
    }

    private fun tierForLimits(profile: UserProfile): SubscriptionTier {
        return _uiState.value.effectiveTierOverride ?: profile.subscriptionTier
    }

    private val dailyPlanner = DailyPlanner(zoneId)
    private val checkInGenerator = CheckInGenerator()

    private val userRepo by lazy { UserRepository(db, userId) }
    private val scheduleRepo by lazy { ScheduledMealsRepository(db, userId) }
    private val messagesRepo by lazy { MessagesRepository(db, userId) }
    private val recipesRepo by lazy { RecipesRepository(db, userId) }
    private val recipeQnaRepo by lazy { RecipeQnaRepository(db, userId) }
    private val calorieRepo by lazy { com.matchpoint.myaidietapp.data.CalorieRepository(db, userId) }
    private val storageRepo by lazy { StorageRepository() }
    private val proxyRepo by lazy { OpenAiProxyRepository() }
    private val authRepo by lazy { AuthRepository() }

    init {
        viewModelScope.launch {
            bootstrap()
        }
    }

    private suspend fun bootstrap() {
        // App Check can briefly deny Firestore at cold start until the token is available.
        // Retry a few times so we don't get stuck on a permanent "Loading your profile…" screen.
        val maxAttempts = 8
        val baseDelayMs = 450L
        val firebaseProjectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
        val authUid = authRepo.currentUid()
        Log.w("Bootstrap", "bootstrap() start uid=$authUid projectId=$firebaseProjectId")
        for (attempt in 1..maxAttempts) {
            var stage = "start"
            try {
            stage = "auth_token"
            val authDiag = runCatching {
                val u = FirebaseAuth.getInstance().currentUser
                if (u == null) {
                    "null"
                } else {
                    val tok = u.getIdToken(false).await()
                    "ok(uid=${u.uid}, tokenLen=${tok.token?.length ?: 0})"
                }
            }.getOrElse { e ->
                "fail(${e::class.java.simpleName}: ${e.message})"
            }

            // Fetch App Check token early to make the failure mode obvious in logs and UI.
            stage = "app_check"
            val appCheckDiag = runCatching {
                val tok = FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
                "ok(len=${tok.token.length})"
            }.getOrElse { e ->
                "fail(${e::class.java.simpleName}: ${e.message})"
            }
            Log.w("Bootstrap", "auth=$authDiag appCheck=$appCheckDiag attempt=$attempt/$maxAttempts")

            val authEmail = authRepo.currentEmail()?.trim()?.ifBlank { null }
            // If the user doc doesn't exist yet (new account), auto-create defaults.
            // NOTE: tutorial progress writes can create the user doc early with only tutorial fields.
            // In that case, we still treat the profile as "not initialized" and write full defaults once.
            stage = "users_doc_get"
            val userDocRef = db.collection("users").document(userId)
            val snap = userDocRef.get().await()
            val loaded = snap.toObject(UserProfile::class.java)
            val isProfileInitialized = snap.exists() && (
                snap.contains("subscriptionTier") ||
                    snap.contains("dietType") ||
                    snap.contains("showFoodIcons") ||
                    snap.contains("hasSeenWelcomeIntro")
                )

            val profile = if (!snap.exists() || loaded == null || !isProfileInitialized) {
                val base = loaded ?: UserProfile()
                val created = base.copy(
                    email = authEmail ?: base.email,
                    _email = authEmail ?: base._email,
                    dietType = base.dietType,
                    showVineOverlay = base.showVineOverlay,
                    fastingPreset = base.fastingPreset,
                    eatingWindowStartMinutes = base.eatingWindowStartMinutes,
                    eatingWindowEndMinutes = base.eatingWindowEndMinutes
                )
                stage = "users_doc_save"
                // Do not block app startup on a profile write. If rules/App Check are temporarily misconfigured,
                // we can still proceed with an in-memory default profile and retry later.
                runCatching { userRepo.saveUserProfile(created) }
                    .onFailure { e -> Log.w("Bootstrap", "Profile save failed (continuing without blocking).", e) }
                created
            } else {
                loaded
            }

            // Backfill email for existing users (helps identify accounts in Firestore console).
            if (!authEmail.isNullOrBlank() && (profile.email.isNullOrBlank() || profile._email.isNullOrBlank())) {
                stage = "users_doc_backfill_email"
                runCatching { userRepo.saveUserProfile(profile.copy(email = authEmail, _email = authEmail)) }
            }

            val todayDate = LocalDate.now(zoneId)
            val todayId = todayDate.toString()
            stage = "schedule_get_or_create"
            val schedule = scheduleRepo.getDaySchedule(todayId)
                ?: dailyPlanner.planForDay(todayDate, profile).also {
                    scheduleRepo.saveDaySchedule(it)
                }
            if (profile.autoPilotEnabled) {
                notificationScheduler?.scheduleForDay(schedule)
            }

            // Always start on a fresh draft chat on app startup so users don't see the previous conversation by default.
            // This draft is not persisted (so it won't show up as "New chat" in history).
            stage = "messages_start_draft"
            messagesRepo.startDraftChat()

            // Do not inject any automatic welcome/chat messages.
            // Still mark the profile flag once so we don't repeatedly attempt "first-run" behavior.
            var effectiveProfile: UserProfile = profile
            val initialMessages: List<MessageEntry> = emptyList()
            val introPending: Boolean = false
            if (!profile.hasSeenWelcomeIntro) {
                val updated = profile.copy(hasSeenWelcomeIntro = true)
                stage = "users_doc_mark_seen_intro"
                runCatching { userRepo.saveUserProfile(updated) }
                effectiveProfile = updated
            }

            stage = "list_chats"
            val chatSessions = runCatching { filterUserVisibleChats(messagesRepo.listChats()) }.getOrElse { emptyList() }
            val activeChatId = messagesRepo.getActiveChatId()
            stage = "messages_get_log"
            val messages = initialMessages.ifEmpty { messagesRepo.getMessageLog().log }
            stage = "recipes_list"
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
            return
            } catch (e: Exception) {
                val fs = e as? FirebaseFirestoreException
                val isPerm = fs?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                val isUnavailable = fs?.code == FirebaseFirestoreException.Code.UNAVAILABLE

                Log.w("Bootstrap", "bootstrap() failed attempt=$attempt/$maxAttempts uid=$authUid projectId=$firebaseProjectId err=${e::class.java.name}: ${e.message}", e)

                if (attempt < maxAttempts && (isPerm || isUnavailable)) {
                    // Keep loading state; clear stale error.
                    _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                    delay(baseDelayMs * attempt)
                    continue
                }
                val detail = buildString {
                    append("Startup failed.\n")
                    append("projectId=")
                    append(firebaseProjectId ?: "null")
                    append("\n")
                    append("step=")
                    append(stage)
                    append("\n")
                    append("uid=")
                    append(authUid ?: "null")
                    append("\n")
                    if (fs != null) {
                        append("firestore=")
                        append(fs.code)
                        append("\n")
                    } else {
                        append("error=")
                        append(e::class.java.simpleName)
                        append("\n")
                    }
                    append(e.message ?: "(no message)")
                    if (isPerm) {
                        append("\n\nIf this is a debug build, this is usually App Check.\n")
                        append("Logcat: filter tag 'AppCheck' and whitelist the printed debug secret in Firebase App Check.")
                    }
                }
                _uiState.value = _uiState.value.copy(isLoading = false, error = detail)
                return
            }
        }
    }

    fun newChat() {
        viewModelScope.launch {
            try {
                // Start a draft chat (does not appear in history until the user sends a message).
                messagesRepo.startDraftChat()
                val sessions = filterUserVisibleChats(messagesRepo.listChats())
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
                val sessions = filterUserVisibleChats(messagesRepo.listChats())
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

                val sessions = filterUserVisibleChats(messagesRepo.listChats())
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

    private fun gateIfDailyLimit(e: Throwable): Boolean {
        val he = e as? HttpException ?: return false
        if (he.code() != 429) return false
        _uiState.value = _uiState.value.copy(planGateNotice = buildDailyLimitGateNotice(he))
        return true
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
            SubscriptionTier.FREE -> 10
            SubscriptionTier.REGULAR -> 50
            SubscriptionTier.PRO -> 1000
        }
    }

    private fun checkFoodListLimitOrPrompt(profile: UserProfile, addingCount: Int = 1): Boolean {
        val tier = tierForLimits(profile)
        val limit = foodListLimitFor(tier)
        val current = profile.foodItems.size
        if (current + addingCount <= limit) return true

        val upsell = when (tier) {
            SubscriptionTier.FREE ->
                "Upgrade to Basic for \$9.99/month (\$99.99/year) for up to 50 items, or Premium for \$19.99/month (\$199.99/year) for up to 1000 items."
            SubscriptionTier.REGULAR ->
                "Upgrade to Premium for \$19.99/month (\$199.99/year) to raise your limit to 1000 items."
            SubscriptionTier.PRO ->
                "You’ve hit the Premium item limit."
        }

        _uiState.value = _uiState.value.copy(
            planGateNotice = "You’ve reached your plan’s food list limit ($current/$limit).\n\n$upsell"
        )
        return false
    }

    private fun recipeLimitFor(tier: SubscriptionTier): Int {
        return when (tier) {
            SubscriptionTier.FREE -> 3
            SubscriptionTier.REGULAR -> 15
            SubscriptionTier.PRO -> 500
        }
    }

    private fun checkRecipeListLimitOrPrompt(profile: UserProfile, currentSaved: Int, addingCount: Int = 1): Boolean {
        val tier = tierForLimits(profile)
        val limit = recipeLimitFor(tier)
        if (currentSaved + addingCount <= limit) return true

        val upsell = when (tier) {
            SubscriptionTier.FREE ->
                "Upgrade to Basic for \$9.99/month (\$99.99/year) for up to 15 saved recipes, or Premium for \$19.99/month (\$199.99/year) for up to 500 saved recipes."
            SubscriptionTier.REGULAR ->
                "Upgrade to Premium for \$19.99/month (\$199.99/year) to raise your limit to 500 saved recipes."
            SubscriptionTier.PRO ->
                "You’ve hit the Premium saved recipe limit."
        }

        _uiState.value = _uiState.value.copy(
            planGateNotice = "You’ve reached your plan’s saved recipe limit ($currentSaved/$limit).\n\n$upsell"
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

                _uiState.value = UiState(
                    isLoading = false,
                    profile = profile.copy(nextMealAtMillis = firstMealTime, hasSeenWelcomeIntro = true),
                    todaySchedule = schedule,
                    messages = emptyList(),
                    introPending = false
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

            // Fast path: if this item already exists in the user's list, update it locally WITHOUT AI analysis.
            // This prevents consuming quota / hitting daily limits when the user is just re-adding something.
            // Only safe for text-entry adds (no photos), since photo adds often need analysis to normalize the name.
            if (productUrl == null) {
                val q = nameQualifier
                    ?.trim()
                    ?.lowercase()
                    ?.replace(Regex("\\s+"), " ")
                    ?.takeIf { it.isNotBlank() }
                val baseName = name.trim()
                val candidateName = if (q == null) baseName else "$baseName ($q)"
                val candidateKey = normalizeFoodNameKey(candidateName)
                val existingIdx = current.foodItems.indexOfFirst { fi ->
                    normalizeFoodNameKey(fi.name) == candidateKey
                }
                if (existingIdx >= 0) {
                    val cats = categories.map { it.trim().uppercase() }.distinct()
                    val existing = current.foodItems[existingIdx]
                    val updatedItem = existing.copy(
                        quantity = (existing.quantity + quantity).coerceAtLeast(1),
                        categories = (existing.categories + cats).map { it.trim().uppercase() }.distinct(),
                        photoUrl = existing.photoUrl,
                        labelUrl = existing.labelUrl,
                        nutritionFactsUrl = existing.nutritionFactsUrl
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
                    // Text-only path: analyze based on the typed food name (this also warms the server-side foodCatalog cache).
                    proxyRepo.analyzeFoodByName(
                        foodName = name,
                        dietType = diet
                    )
                }
            }.onFailure { e ->
                Log.e("DigitalStomach", "analyzeFood failed", e)
                if (gateIfDailyLimit(e)) return
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
                    append("', health ")
                    append(healthRating)
                    append("/10, diet fit ")
                    append(dietFitRating)
                    append("/10. ")
                    append(analysis.summary)
                    val kind = analysis.portionKind?.trim()?.uppercase()
                    val servingText = analysis.servingSizeText?.trim().orEmpty()
                    val caloriesPerServing = analysis.caloriesPerServing
                    val servingsPerContainer = analysis.servingsPerContainer
                    val cals = analysis.caloriesTotal ?: analysis.estimatedCalories
                    val low = analysis.caloriesLow
                    val high = analysis.caloriesHigh

                    if (kind == "PACKAGED") {
                        if (caloriesPerServing != null) {
                            append(" Calories per serving: ~")
                            append(caloriesPerServing)
                            if (!servingText.isNullOrBlank()) {
                                append(" (")
                                append(servingText)
                                append(")")
                            }
                            append(".")
                        } else if (cals != null) {
                            append(" Estimated calories per serving: ~")
                            append(cals)
                            append(".")
                        }
                        if (servingsPerContainer != null) {
                            append(" Servings per container: ")
                            append(servingsPerContainer)
                            append(".")
                            if (caloriesPerServing != null) {
                                val whole = kotlin.math.round(caloriesPerServing * servingsPerContainer).toInt()
                                append(" Whole package: ~")
                                append(whole)
                                append(" calories.")
                            }
                        }
                    } else if (kind == "PLATED") {
                        if (cals != null) {
                            append(" Estimated calories for the full plate: ~")
                            append(cals)
                            if (low != null && high != null && low > 0 && high > 0) {
                                append(" (")
                                append(low)
                                append("–")
                                append(high)
                                append(")")
                            }
                            append(".")
                        }
                        if (servingText.isNotBlank()) {
                            append(" Serving size: ")
                            append(servingText)
                            append(".")
                        } else {
                            append(" Serving size: 1 plate.")
                        }
                    } else {
                        if (cals != null) {
                            append(" Estimated calories: ~")
                            append(cals)
                            if (low != null && high != null && low > 0 && high > 0) {
                                append(" (")
                                append(low)
                                append("–")
                                append(high)
                                append(")")
                            }
                            append(".")
                        }
                    }

                    val p = analysis.estimatedProteinG
                    val c = analysis.estimatedCarbsG
                    val f = analysis.estimatedFatG
                    if (p != null || c != null || f != null) {
                        append(" Estimated macros")
                        if (kind == "PACKAGED") append(" (per serving)") else if (kind == "PLATED") append(" (full plate)")
                        append(":")
                        if (p != null) {
                            append(" Protein: ")
                            append(p)
                            append("g.")
                        }
                        if (c != null) {
                            append(" Carbohydrates: ")
                            append(c)
                            append("g.")
                        }
                        if (f != null) {
                            append(" Fat: ")
                            append(f)
                            append("g.")
                        }
                    }
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
                        estimatedCalories = analysis.caloriesTotal ?: analysis.estimatedCalories,
                        estimatedProteinG = analysis.estimatedProteinG,
                        estimatedCarbsG = analysis.estimatedCarbsG,
                        estimatedFatG = analysis.estimatedFatG,
                        ingredientsText = analysis.ingredientsText,
                        portionKind = analysis.portionKind,
                        servingSizeText = analysis.servingSizeText,
                        caloriesPerServing = analysis.caloriesPerServing,
                        servingsPerContainer = analysis.servingsPerContainer,
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

                // Enforce tier food-list limit here too (even if UI is bypassed).
            val limit = foodListLimitFor(tierForLimits(current))
                val existingKeys = current.foodItems.map { normalizeFoodNameKey(it.name) }.toSet()
                val existingInBatch = deduped.filter { existingKeys.contains(normalizeFoodNameKey(it)) }
                val newInBatch = deduped.filterNot { existingKeys.contains(normalizeFoodNameKey(it)) }
                val remaining = (limit - current.foodItems.size).coerceAtLeast(0)

                if (remaining <= 0 && newInBatch.isNotEmpty()) {
                    checkFoodListLimitOrPrompt(current, addingCount = 1)
                    return@launch
                }

                val trimmedNew = if (newInBatch.size > remaining) newInBatch.take(remaining) else newInBatch
                if (trimmedNew.size < newInBatch.size) {
                    // Prompt upgrade, but still allow adding what fits.
                    checkFoodListLimitOrPrompt(current, addingCount = newInBatch.size)
                }

                val effectiveDeduped = (existingInBatch + trimmedNew)
                    .distinctBy { normalizeFoodNameKey(it) }
                if (effectiveDeduped.isEmpty()) return@launch

                // Give the user immediate feedback (they'll see this on Home right away).
                if (cats.any { it.equals("INGREDIENT", ignoreCase = true) }) {
                    messagesRepo.appendMessage(
                        MessageEntry(
                            id = UUID.randomUUID().toString(),
                            sender = MessageSender.AI,
                            text = "Hmm… let me analyze these for you."
                        )
                    )
                    _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
                }

                // Backend has a batch max; chunk requests to stay under it.
                val CHUNK_SIZE = 25
                val analyses = mutableListOf<com.matchpoint.myaidietapp.data.AnalyzeFoodResponse>()
                val chunks = effectiveDeduped.chunked(CHUNK_SIZE)

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

    fun generateDailyPlan(
        dailyTargetCalories: Int?,
        mealCount: Int,
        savedRecipesOnly: Boolean
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
                val profile = userRepo.getUserProfile() ?: return@launch
                val inventorySummary = buildInventorySummary(profile)
                val saved = _uiState.value.savedRecipes

                val plan = proxyRepo.generateDailyPlan(
                    dailyTargetCalories = dailyTargetCalories,
                    dailyMealCount = mealCount,
                    savedRecipesOnly = savedRecipesOnly,
                    savedRecipes = saved,
                    dietType = profile.dietType,
                    inventorySummary = inventorySummary
                )

                val meals = plan.meals
                    .map { m ->
                        DailyPlanUiMeal(
                            id = m.id.ifBlank { UUID.randomUUID().toString() },
                            title = m.title.ifBlank { "Meal" },
                            estimatedCalories = (m.estimatedCalories).coerceAtLeast(0),
                            recipeText = m.recipeText.orEmpty(),
                            sourceRecipeId = m.sourceRecipeId
                        )
                    }
                    .take(3)

                val dayId = LocalDate.now(zoneId).toString()
                _uiState.value = _uiState.value.copy(
                    dailyPlanMeals = meals,
                    dailyPlanTotalCount = meals.size,
                    dailyPlanDayId = dayId
                )

                // Chat helper message: tell the user how to view the plan.
                val notice = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = "Your daily plan has been generated. Tap the carrot next to Daily Plan to view it."
                )
                messagesRepo.appendMessage(notice)
                _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
            } catch (e: Exception) {
                if (gateIfDailyLimit(e)) return@launch
                val msg = when (e) {
                    is HttpException -> {
                        if (e.code() == 429) "Daily limit reached. Upgrade your plan to keep chatting."
                        else "No service: ${describeProxyHttpError(e)}"
                    }
                    is IllegalStateException -> {
                        // Make parsing/config errors user-friendly (e.g., daily_plan backend not deployed yet).
                        e.message ?: "Daily plan unavailable."
                    }
                    else -> e.message ?: "Failed to generate daily plan."
                }
                _uiState.value = _uiState.value.copy(error = msg)
            } finally {
                _uiState.value = _uiState.value.copy(isProcessing = false)
            }
        }
    }

    fun completeDailyPlanMeal(mealId: String) {
        val updated = _uiState.value.dailyPlanMeals.filterNot { it.id == mealId }
        _uiState.value = _uiState.value.copy(dailyPlanMeals = updated)
    }

    fun clearDailyPlan() {
        _uiState.value = _uiState.value.copy(
            dailyPlanMeals = emptyList(),
            dailyPlanTotalCount = 0,
            dailyPlanDayId = null
        )
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

    private fun shouldRouteToEvaluateFood(userMessage: String): Boolean {
        val m = userMessage.trim().lowercase()
        if (m.isBlank()) return false

        // Only auto-route on *explicit* requests for the app to take a picture/photo.
        // This avoids false positives like: "I'm looking at this picture... are pickles healthy?"
        val explicitPhrases = listOf(
            "can you take a picture",
            "can you take a photo",
            "can you take a pic",
            "could you take a picture",
            "could you take a photo",
            "could you take a pic",
            "take a picture for me",
            "take a photo for me",
            "take a pic for me"
        )
        return explicitPhrases.any { m.contains(it) }
    }

    private fun recipePhaseList(): String {
        // Keep in sync with available `ic_phase_*` drawables.
        return "PREP, WASH, PEEL, CHOP, GRATE, MEASURE, MIX, WHISK, MEDIUM_BOWL, MARINATE, PREHEAT, SEAR, SAUTE, SIMMER, BOIL, BAKE, POUR, DRAIN, SEASON, REDUCE_HEAT, SET_TIMER, SKILLET"
    }

    private fun shouldEncourageRecipeFormatting(userMessage: String): Boolean {
        val m = userMessage.trim().lowercase()
        if (m.isBlank()) return false

        // Conservative: only trigger when the user explicitly asks for a recipe or to "make/cook" something.
        val asksRecipe = Regex("\\brecipe\\b").containsMatchIn(m) ||
            Regex("\\b(make|cook|create|generate)\\b").containsMatchIn(m) && Regex("\\b(dinner|lunch|breakfast|meal)\\b").containsMatchIn(m) ||
            m.contains("how do i make") ||
            m.contains("how to make")

        return asksRecipe
    }

    private fun recipeFormattingRulesForAi(): String {
        return buildString {
            append("If you respond with a RECIPE, follow these formatting rules:\n")
            append("- No emojis.\n")
            append("- Start with a single title line as a Markdown heading, like:\n")
            append("  # Creamy Bacon & Eggs\n")
            append("- Do NOT write \"Title:\" anywhere.\n")
            append("- Use Markdown section headings (##) to break it into sections (example: ## Ingredients, ## Prep, ## Cook, ## Serve).\n")
            append("- You MAY insert a phase marker on its own line (example: {phase: PREP}).\n")
            append("- Only use these phases: ")
            append(recipePhaseList())
            append(".\n")
            append("- Rules for phase markers: max 4 total, never repeat the same phase, never put a phase marker inline mid-sentence.\n")
            append("- Put the phase marker immediately after a section heading (except Ingredients).\n")
            append("- Give step-by-step instructions with times/temps.\n")
        }
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

    fun updateShowVineOverlay(show: Boolean) {
        viewModelScope.launch {
            try {
                userRepo.updateShowVineOverlay(show)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun markBeginnerIngredientsPickerSeen() {
        viewModelScope.launch {
            try {
                userRepo.updateHasSeenBeginnerIngredientsPicker(true)
                val updated = userRepo.getUserProfile()
                _uiState.value = _uiState.value.copy(profile = updated)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * Tutorial analytics: record how far the user got in the guided tour.
     * We store a simple "X/32" style value in Firestore so you can monitor drop-off.
     */
    fun updateTutorialProgress(maxStepReached: Int, totalSteps: Int = 32) {
        val clamped = maxStepReached.coerceIn(0, totalSteps)
        if (lastTutorialProgressSent == clamped) return

        viewModelScope.launch {
            runCatching {
                userRepo.updateTutorialProgress(
                    progressSteps = clamped,
                    totalSteps = totalSteps,
                    progressText = "$clamped/$totalSteps"
                )
                // Mark as sent only after a successful write so we can retry if App Check
                // briefly denies early startup writes.
                lastTutorialProgressSent = clamped
            }.onFailure { e ->
                // Best-effort analytics: don't surface as a user-facing error.
                // NOTE: We intentionally do NOT update lastTutorialProgressSent on failure,
                // so the next step change can retry.
                Log.w("HomeViewModel", "Failed to update tutorial progress", e)
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
            var handedOffProcessingToBatch = false
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
                val refreshedChats = runCatching { filterUserVisibleChats(messagesRepo.listChats()) }.getOrElse { _uiState.value.chatSessions }
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + userEntry,
                    chatSessions = refreshedChats,
                    activeChatId = messagesRepo.getActiveChatId()
                )

                // If the user is asking to analyze something via a photo/camera in chat,
                // direct them to the in-app "AI Evaluate Food" camera flow instead.
                if (shouldRouteToEvaluateFood(message)) {
                    val aiEntry = MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "I can’t take pictures directly from chat. Tap the AI Evaluate Food button on the home screen to use your camera, then I’ll analyze it."
                    )
                    messagesRepo.appendMessage(aiEntry)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        messages = _uiState.value.messages + aiEntry
                    )
                    return@launch
                }

                // Shortcut: user wants to "save these options" (e.g., a meal plan) — do it locally
                // without consuming another AI request / daily quota.
                if (trySaveMealPlanOptionsLocally(userMessage = message)) {
                    _uiState.value = _uiState.value.copy(isProcessing = false)
                    return@launch
                }

                val currentProfile = userRepo.getUserProfile()

                // Local shortcut: add foods to list by natural language (no quota, always reliable).
                val addIntent = parseAddToListIntent(message)
                if (currentProfile != null && addIntent != null) {
                    // IMPORTANT:
                    // This must behave exactly like the "Add by text" flow:
                    // it should analyze the foods (health/diet ratings, summary, macros, etc.)
                    // before saving to the list. `addFoodItemsBatch()` does that.
                    handedOffProcessingToBatch = true
                    addFoodItemsBatch(addIntent.items, setOf(addIntent.category))
                    // addFoodItemsBatch manages isProcessing + messages; do not call the AI.
                    return@launch
                }

                // Local shortcut: remove foods by name from user's list (no quota, always accurate).
                val removeName = parseRemoveFromListCommand(message)
                if (currentProfile != null && removeName != null) {
                    val targetKey = normalizeFoodNameKey(removeName)
                    fun baseKey(n: String): String =
                        normalizeFoodNameKey(n.substringBefore("(").trim())

                    val candidates = currentProfile.foodItems
                    val exact = candidates.filter { baseKey(it.name) == targetKey }
                    val fallback = if (exact.isNotEmpty()) exact else candidates.filter {
                        val nk = baseKey(it.name)
                        nk == targetKey || nk.contains(targetKey) || targetKey.contains(nk)
                    }

                    val toRemove = fallback
                        .distinctBy { it.id }
                        .takeIf { it.isNotEmpty() }

                    val aiText = if (toRemove != null) {
                        val updated = currentProfile.copy(
                            foodItems = currentProfile.foodItems.filterNot { fi -> toRemove.any { it.id == fi.id } }
                        )
                        userRepo.saveUserProfile(updated)
                        _uiState.value = _uiState.value.copy(profile = updated)

                        if (toRemove.size == 1) {
                            "Removed '${toRemove.first().name}' from your list."
                        } else {
                            "Removed ${toRemove.size} items matching '$removeName' from your list."
                        }
                    } else {
                        "I couldn’t find '$removeName' in your list."
                    }

                    val aiEntry = MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = aiText
                    )
                    messagesRepo.appendMessage(aiEntry)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        messages = _uiState.value.messages + aiEntry
                    )
                    return@launch
                }

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
                    val aiUserMessage = buildString {
                        append(message)
                        if (shouldEncourageRecipeFormatting(message)) {
                            append("\n\n")
                            append(recipeFormattingRulesForAi())
                        }
                    }
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
                            tone = "Be short, casual, and helpful. If the user asks for a recipe, switch to recipe mode with clear sections/headings and detailed step-by-step instructions. If the user is asking you to analyze a photo/image or to use the camera, direct them to the in-app AI Evaluate Food button/camera flow instead of saying you cannot. Otherwise, answer their question normally.",
                            userMessage = aiUserMessage,
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
                        _uiState.value = _uiState.value.copy(
                            planGateNotice = buildDailyLimitGateNotice(e)
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
                        "LOG_CALORIES" -> userLikelyLoggingCalories(message)
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
                // If we handed off to addFoodItemsBatch(), it owns the processing spinner.
                if (!handedOffProcessingToBatch) {
                    _uiState.value = _uiState.value.copy(isProcessing = false)
                }
            }
        }
    }

    private suspend fun trySaveMealPlanOptionsLocally(userMessage: String): Boolean {
        val t = userMessage.trim().lowercase()
        if (t.isBlank()) return false
        // Conservative: only trigger on explicit "save" intent + "options/plan" hint.
        val wantsSave = Regex("\\b(save|add|put)\\b").containsMatchIn(t)
        val mentionsOptions = Regex("\\b(options?|plan|meal\\s+plan)\\b").containsMatchIn(t)
        if (!wantsSave || !mentionsOptions) return false

        // Find the most recent AI message that looks like a meal plan/options list (and isn't a quota error).
        val priorAi = _uiState.value.messages
            .asReversed()
            .firstOrNull { m ->
                m.sender == MessageSender.AI &&
                    m.kind != "RECIPE" &&
                    m.text.isNotBlank() &&
                    !m.text.contains("Daily limit reached", ignoreCase = true) &&
                    !m.text.contains("Service unavailable", ignoreCase = true)
            }
            ?: return false

        val options = extractMealPlanOptions(priorAi.text)
        if (options.isEmpty()) return false

        return try {
            val profile = userRepo.getUserProfile() ?: return true

            // Only add new unique items.
            val existingKeys = profile.foodItems.map { normalizeFoodNameKey(it.name) }.toSet()
            val toAdd = options
                .map { (name, cat) ->
                    val cleanName = name.trim().replace(Regex("\\s+"), " ").take(120)
                    val cleanCat = cat.trim().uppercase()
                    cleanName to cleanCat
                }
                .filter { (name, _) -> name.isNotBlank() }
                .distinctBy { (name, cat) -> normalizeFoodNameKey(name) + "|" + cat }
                .filter { (name, _) -> normalizeFoodNameKey(name) !in existingKeys }

            if (toAdd.isEmpty()) {
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Those are already in your list."
                    )
                )
                _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
                return true
            }

            if (!checkFoodListLimitOrPrompt(profile, addingCount = toAdd.size)) {
                // Also add a short chat message so the user sees why the save didn't happen.
                messagesRepo.appendMessage(
                    MessageEntry(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.AI,
                        text = "Can’t save those options because your food list is full. Upgrade to add more."
                    )
                )
                _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
                return true
            }

            val newItems = toAdd.map { (name, cat) ->
                FoodItem(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    quantity = 1,
                    categories = listOf(cat)
                )
            }

            val updated = profile.copy(foodItems = profile.foodItems + newItems)
            userRepo.saveUserProfile(updated)

            messagesRepo.appendMessage(
                MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = "Saved ${newItems.size} item(s) to your list."
                )
            )

            _uiState.value = _uiState.value.copy(
                profile = updated,
                messages = messagesRepo.getMessageLog().log
            )
            true
        } catch (e: Exception) {
            // If anything goes wrong, fall back to normal AI handling.
            false
        }
    }

    private fun extractMealPlanOptions(text: String): List<Pair<String, String>> {
        val cleaned = text
            .replace("\r", "")
            .replace("**", "")
            .trim()
        if (cleaned.isBlank()) return emptyList()

        val results = mutableListOf<Pair<String, String>>()

        // First pass: split numbered list items ("1. ... 2. ...").
        val parts = cleaned
            .split(Regex("\\s*\\d+\\.\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        fun categoryForLabel(label: String): String {
            val l = label.trim().lowercase()
            return if (l.startsWith("snack")) "SNACK" else "MEAL"
        }

        fun addFromChunk(chunk: String) {
            // Expected shape: "Breakfast: X", "Lunch: Y", "Dinner: Z", "Snacks: A"
            val m = Regex("^(Breakfast|Lunch|Dinner|Snacks?)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
                .find(chunk.trim())
                ?: return
            val label = m.groupValues[1]
            val value = m.groupValues[2].trim()
            val name = value.substringBefore("\n").substringBefore(".").trim()
            if (name.isBlank()) return
            results.add(name to categoryForLabel(label))
        }

        parts.forEach { addFromChunk(it) }

        // Fallback: scan inline if the AI wrote everything in one paragraph.
        if (results.isEmpty()) {
            val re = Regex("\\b(Breakfast|Lunch|Dinner|Snacks?)\\b\\s*:\\s*([^\\n\\.]+)", RegexOption.IGNORE_CASE)
            re.findAll(cleaned).forEach { m ->
                val label = m.groupValues[1]
                val name = m.groupValues[2].trim()
                if (name.isNotBlank()) results.add(name to categoryForLabel(label))
            }
        }

        return results
            .distinctBy { (name, cat) -> normalizeFoodNameKey(name) + "|" + cat }
            .take(12)
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
        val looksLikeShortAdd = t.startsWith("add ") || t.startsWith("please add ") || t.startsWith("can you add ")
        return hasAddVerb && (hasListTarget || looksLikeShortAdd)
    }

    private fun userLikelyLoggingCalories(userMessage: String): Boolean {
        val t = userMessage.trim().lowercase()
        if (t.isBlank()) return false
        // Avoid accidental logs when the user is asking for totals/info.
        if (Regex("\\bhow\\s+many\\s+calories\\b").containsMatchIn(t)) return false
        if (Regex("\\b(total|so\\s+far|today)\\b.*\\bcalories\\b").containsMatchIn(t) && t.contains("?")) return false
        // Require an "intake" verb/phrase.
        val intakeCue = Regex("\\b(i\\s+(ate|had|drank)|for\\s+(breakfast|lunch|dinner)|my\\s+(breakfast|lunch|dinner)|snack(ed)?\\s+on)\\b")
        return intakeCue.containsMatchIn(t)
    }

    private data class AppActionFood(
        val name: String,
        val category: String?,
        val qualifier: String?
    )

    private data class AppActionCalorieEntry(
        val label: String,
        val calories: Int
    )

    private data class AppAction(
        val type: String,
        val items: List<AppActionFood> = emptyList(),
        val entries: List<AppActionCalorieEntry> = emptyList()
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

        val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (i in 0 until entriesArr.length()) {
                val it = entriesArr.optJSONObject(i) ?: continue
                val label = it.optString("label").trim()
                if (label.isBlank()) continue
                val calories = it.optInt("calories", -1)
                if (calories < 0) continue
                add(AppActionCalorieEntry(label = label, calories = calories))
            }
        }

        return AppAction(type = type, items = items, entries = entries)
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
            "LOG_CALORIES" -> applyLogCaloriesAction(action.entries)
            else -> Unit
        }
    }

    private suspend fun applyLogCaloriesAction(entries: List<AppActionCalorieEntry>) {
        if (entries.isEmpty()) return
        // Only log calories on explicit "I ate/had..." style messages to avoid accidental writes.
        // (We still let the AI *talk* about calories freely.)
        val lastUserMessage = _uiState.value.messages.lastOrNull { it.sender == MessageSender.USER }?.text
        if (lastUserMessage != null && !userLikelyLoggingCalories(lastUserMessage)) return

        val dayKey = java.time.LocalDate.now(zoneId).toString()
        val toStore = entries.map { e ->
            com.matchpoint.myaidietapp.data.CalorieEntry(
                label = e.label,
                calories = e.calories,
                createdAt = com.google.firebase.Timestamp.now(),
                source = "chat"
            )
        }
        calorieRepo.appendEntries(dayKey, toStore)

        // Optional: keep it quiet (no extra spam). The AI's visible reply should already mention the estimate.
        // If we want a debug confirmation later, we can add a small toast/message.
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

    fun generateMeal(
        requiredIngredients: List<String> = emptyList(),
        targetCalories: Int? = null,
        strictOnly: Boolean = false,
        difficulty: RecipeDifficulty = RecipeDifficulty.SIMPLE,
        cookTimeMinutes: Int? = null
    ) {
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
                val target = targetCalories?.coerceIn(100, 1000)
                val cookMins = cookTimeMinutes?.coerceIn(10, 240)

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
                                append("Difficulty: ")
                                append(difficulty.name)
                                append(". ")
                                when (difficulty) {
                                    RecipeDifficulty.SIMPLE -> {
                                        append("Make it simple and beginner-friendly: fewer steps, fewer techniques, and minimal multitasking. ")
                                        append("Prefer 1 pan/pot when possible, and keep total active cooking time low. ")
                                    }
                                    RecipeDifficulty.ADVANCED -> {
                                        append("Make it moderately challenging: more detailed technique, but still realistic for a home cook. ")
                                        append("You may use 2 pans/pots and include a simple sauce or side if it fits. ")
                                    }
                                    RecipeDifficulty.EXPERT -> {
                                        append("Make it expert-level, like a gourmet chef teaching a top culinary student. ")
                                        append("Assume I already know the basics (knife skills, how to sauté, how to taste/season). ")
                                        append("You MUST include at least 2 distinct components that are cooked/handled separately and combined at the end (e.g., main + sauce, or main + sauce + garnish/side). ")
                                        append("Design the method so at least some steps run in parallel (multi-tasking), and explicitly call that out. ")
                                        append("Include coordination/timing guidance like: what to start first, what can be done while something simmers/roasts, and what must be done last-minute. ")
                                        append("Use precise culinary language and sensory doneness cues (color, texture, aroma) rather than only timers. ")
                                        append("Include a short plating/finishing note (how to assemble and serve). ")
                                        append("If a technique is advanced (emulsion, reduction, fond, deglaze, etc.), explain it briefly but in a confident instructor tone. ")
                                    }
                                }
                                if (cookMins != null) {
                                    append("Cook time target: about ")
                                    append(cookMins)
                                    append(" minutes total. ")
                                    append("You MUST keep the recipe's stated total time consistent with this target (do not contradict it). ")
                                    append("Include a line right under the title: \"Total time: ~")
                                    append(cookMins)
                                    append(" minutes\". ")
                                    append("If you also include prep/cook time breakdowns, they must add up and still match ~")
                                    append(cookMins)
                                    append(" minutes. ")
                                    append("If the recipe cannot realistically fit, say so and offer the closest alternative and explain why. ")
                                }
                                if (target != null) {
                                    append("Target about ")
                                    append(target)
                                    append(" calories total for the meal. ")
                                }
                                if (required.isNotEmpty()) {
                                    append("MUST include these ingredients: ")
                                    append(required.joinToString(", "))
                                    append(". ")
                                    append("If you cannot include any required ingredient, explicitly state which one(s) you could not include and why. ")
                                    if (strictOnly) {
                                        append("STRICT MODE: Use ONLY the ingredients listed above. ")
                                        append("You MAY use *only* these minimal pantry staples if needed: salt, black pepper, water, cooking oil, butter. ")
                                        append("Do NOT add any other ingredients (for example: eggs, milk, cheese, flour, rice, pasta, bread, sauces, spices, herbs, garlic, onion, sugar, etc.). ")
                                        append("If you cannot make a complete recipe using only the selected ingredients + the allowed pantry staples, say so clearly and ask me which ONE extra ingredient I can allow. ")
                                        append("Also include an 'Ingredients used:' list and ensure it contains ONLY the selected ingredients plus any allowed pantry staples you used. ")
                                    }
                                }
                                append("To make the recipe easier to scan, you MAY insert a phase marker as a section divider on its own line, like:\n{phase: PREP}\n")
                                append("Only use these phases for now: PREP, WASH, PEEL, CHOP, GRATE, MEASURE, MIX, WHISK, MEDIUM_BOWL, MARINATE, PREHEAT, SEAR, SAUTE, SIMMER, BOIL, BAKE, POUR, DRAIN, SEASON, REDUCE_HEAT, SET_TIMER, SKILLET. ")
                                append("Rules: use at most 4 phase markers total, and never repeat the same phase more than once. ")
                                append("Do NOT place phase markers inline after a sentence like \"mix this\" — only use them before a new section of steps. ")
                                append("Start the recipe with a single title line as a Markdown heading, like:\n# Creamy Bacon & Eggs\n")
                                append("Do NOT write \"Title:\" anywhere. ")
                                append("Format the recipe into clear Markdown sections with headings (use ##). ")
                                append("Example structure: ## Ingredients, ## Prep, ## Cook, ## Serve. ")
                                if (difficulty == RecipeDifficulty.EXPERT) {
                                    append("For EXPERT, also include a short ## Game plan section (1–4 bullets) describing parallel workflow and critical timing. ")
                                    append("For EXPERT, also include a short ## Chef notes section (1–4 bullets) with technique tips and common pitfalls. ")
                                }
                                append("Right after each heading (except Ingredients), you MAY add ONE phase marker on its own line that best matches that section. ")
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
                        _uiState.value = _uiState.value.copy(
                            planGateNotice = buildDailyLimitGateNotice(e)
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
                val cleanedReplyTextRaw = replyText
                    .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "") // surrogate pairs (most emojis)
                    .replace("\uFE0F", "") // variation selector-16

                fun ensureDifficultyLine(text: String, difficulty: RecipeDifficulty): String {
                    val t = text.replace("\r", "").trimStart()
                    if (t.isBlank()) return text

                    val wantLevel = difficulty.name.lowercase().replaceFirstChar { it.uppercase() }
                    val wantLine = "Difficulty: $wantLevel"
                    val lines = t.lines()
                    val early = lines.take(10).map { it.trim() }
                    if (early.any { it.equals(wantLine, ignoreCase = true) }) return t
                    if (early.any { it.equals("($wantLevel)", ignoreCase = true) }) return t

                    // Prefer inserting after a leading "# Title" line.
                    val firstIdx = lines.indexOfFirst { it.trim().startsWith("#") }
                    if (firstIdx == -1) {
                        // Fallback: put it at the very top.
                        return (wantLine + "\n" + t).trimEnd()
                    }

                    val out = buildString {
                        for (i in lines.indices) {
                            append(lines[i])
                            if (i != lines.lastIndex) append('\n')
                            if (i == firstIdx) {
                                // Insert right after the title line.
                                append(wantLine)
                                append('\n')
                            }
                        }
                    }
                    return out.trimEnd()
                }

                val cleanedReplyText = if (replyResult.isSuccess) {
                    ensureDifficultyLine(cleanedReplyTextRaw, difficulty)
                } else {
                    cleanedReplyTextRaw
                }

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = cleanedReplyText,
                    kind = if (replyResult.isSuccess) "RECIPE" else null
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

    fun loadRecipeQa(recipeId: String) {
        val id = recipeId.trim()
        if (id.isBlank()) return
        viewModelScope.launch {
            try {
                val log = recipeQnaRepo.getLog(id).log
                val next = _uiState.value.recipeQaByRecipeId.toMutableMap().apply { put(id, log) }.toMap()
                _uiState.value = _uiState.value.copy(recipeQaByRecipeId = next)
            } catch (e: Exception) {
                // Keep it quiet; Q&A is optional. We'll surface errors only via the main error line if needed.
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load recipe questions.")
            }
        }
    }

    fun askRecipeQuestion(recipe: SavedRecipe, question: String) {
        val q = question.trim()
        if (q.isBlank()) return
        val recipeId = recipe.id.trim()
        if (recipeId.isBlank()) return

        viewModelScope.launch {
            try {
                // Prevent double-sends for the same recipe.
                if (_uiState.value.recipeQaBusyRecipeId == recipeId) return@launch
                _uiState.value = _uiState.value.copy(recipeQaBusyRecipeId = recipeId)

                val profile = userRepo.getUserProfile() ?: return@launch
                val inventorySummary = buildInventorySummary(profile)

                val existing = _uiState.value.recipeQaByRecipeId[recipeId].orEmpty()
                val userEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.USER,
                    text = q
                )

                // Optimistic UI update.
                val optimistic = (existing + userEntry).takeLast(60)
                _uiState.value = _uiState.value.copy(
                    recipeQaByRecipeId = _uiState.value.recipeQaByRecipeId.toMutableMap().apply { put(recipeId, optimistic) }.toMap()
                )

                recipeQnaRepo.appendMessage(recipeId, userEntry)

                fun clip(s: String, max: Int): String {
                    val t = s.replace("\r", "").trim()
                    return if (t.length <= max) t else t.take(max) + "\n…"
                }

                fun recipeContextForPrompt(recipe: SavedRecipe): String {
                    val raw = recipe.text.replace("\r", "").trim()
                    if (raw.isBlank()) return ""

                    // Keep it tight: Cloud Function truncates userMessage to 2000 chars.
                    // Include the most useful bits for answering questions (ingredients + key sections).
                    val lines = raw.lines().map { it.trimEnd() }

                    val titleLine = lines.firstOrNull { it.trim().startsWith("#") }?.trim()
                    val metaLines = lines
                        .take(18)
                        .map { it.trim() }
                        .filter { it.startsWith("Difficulty:", ignoreCase = true) || it.startsWith("Total time:", ignoreCase = true) }
                        .distinct()
                        .take(3)

                    val ingredientsBlock = buildString {
                        val ings = recipe.ingredients.mapNotNull { it.trim().ifBlank { null } }.take(24)
                        if (ings.isNotEmpty()) {
                            append("Ingredients:\n")
                            for (i in ings) {
                                append("- ").append(i).append('\n')
                            }
                        }
                    }.trimEnd()

                    // Grab a short excerpt of the steps/notes (skip title + obvious meta lines).
                    val excerpt = buildString {
                        for (ln in lines) {
                            val t = ln.trim()
                            if (t.isBlank()) continue
                            if (t.startsWith("#")) continue
                            if (t.startsWith("Difficulty:", ignoreCase = true)) continue
                            if (t.startsWith("Total time:", ignoreCase = true)) continue
                            append(ln).append('\n')
                        }
                    }.trimEnd()

                    return buildString {
                        if (!titleLine.isNullOrBlank()) {
                            append("Title: ").append(titleLine.removePrefix("#").trim()).append('\n')
                        } else if (recipe.title.isNotBlank()) {
                            append("Title: ").append(recipe.title.trim()).append('\n')
                        }
                        for (m in metaLines) append(m).append('\n')
                        if (ingredientsBlock.isNotBlank()) {
                            append('\n').append(ingredientsBlock).append('\n')
                        }
                        if (excerpt.isNotBlank()) {
                            append("\nRecipe excerpt:\n")
                            append(excerpt)
                        }
                    }.trim()
                }

                val recipeContext = recipeContextForPrompt(recipe)
                val chatContext = buildChatContextForAi(optimistic)

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
                            mode = "freeform",
                            tone = "recipe q&a: answer the user's question about the recipe; be practical and clear; no emojis",
                            userMessage = buildString {
                                // IMPORTANT:
                                // Cloud Function truncates `userMessage` to 2000 chars.
                                // Keep the question fully intact by limiting recipe context size.
                                val header = buildString {
                                    append("You are answering questions about the recipe below. ")
                                    append("Answer based on the recipe context provided and normal cooking knowledge. ")
                                    append("If something is missing from the excerpt, say so and ask ONE short clarifying question. ")
                                    append("Do not use emojis.\n\n")
                                }

                                val questionBlock = buildString {
                                    append("User question:\n")
                                    append(q.trim())
                                    append('\n')
                                }

                                // Reserve space for header + question; keep total under ~1900 chars for safety.
                                val maxTotal = 1900
                                val reserved = header.length + questionBlock.length + 60
                                val maxRecipe = (maxTotal - reserved).coerceAtLeast(300)
                                val recipeBlock = clip(recipeContext, maxRecipe)

                                append(header)
                                append("Recipe context (may be truncated):\n")
                                append(recipeBlock)
                                append("\n\n")
                                append(questionBlock)
                            },
                            chatContext = chatContext,
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
                    if (gateIfDailyLimit(e)) return@launch
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

                val cleaned = replyText
                    .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "")
                    .replace("\uFE0F", "")

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = cleaned
                )

                recipeQnaRepo.appendMessage(recipeId, aiEntry)

                val final = (optimistic + aiEntry).takeLast(80)
                _uiState.value = _uiState.value.copy(
                    recipeQaByRecipeId = _uiState.value.recipeQaByRecipeId.toMutableMap().apply { put(recipeId, final) }.toMap()
                )
            } catch (e: Exception) {
                if (gateIfDailyLimit(e)) return@launch
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to ask recipe question.")
            } finally {
                if (_uiState.value.recipeQaBusyRecipeId == recipeId) {
                    _uiState.value = _uiState.value.copy(recipeQaBusyRecipeId = null)
                }
            }
        }
    }

    fun saveRecipeFromMessage(messageId: String) {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUserProfile() ?: return@launch
                val msg = _uiState.value.messages.firstOrNull { it.id == messageId }
                    ?: run { _uiState.value = _uiState.value.copy(error = "Recipe message not found."); return@launch }

                if (msg.sender != MessageSender.AI || msg.kind != "RECIPE") {
                    _uiState.value = _uiState.value.copy(error = "That message isn't a recipe.")
                    return@launch
                }

                // Idempotent-ish: don't re-save the same chat recipe message.
                if (_uiState.value.savedRecipes.any { it.id == msg.id || it.sourceMessageId == msg.id }) return@launch

                val currentSaved = _uiState.value.savedRecipes.size
                if (!checkRecipeListLimitOrPrompt(profile, currentSaved = currentSaved, addingCount = 1)) return@launch

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

    fun saveDailyPlanMeal(mealId: String) {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUserProfile() ?: return@launch
                val meal = _uiState.value.dailyPlanMeals.firstOrNull { it.id == mealId }
                    ?: run { _uiState.value = _uiState.value.copy(error = "Meal not found."); return@launch }

                val parsed = RecipeParser.parse(meal.recipeText)
                val title = meal.title.ifBlank { parsed.title.ifBlank { "Meal" } }

                // Avoid duplicates: if a recipe with same title and body already exists, do nothing.
                if (_uiState.value.savedRecipes.any { r ->
                        r.title.trim().equals(title.trim(), ignoreCase = true) &&
                            r.text.trim().equals(meal.recipeText.trim(), ignoreCase = false)
                    }
                ) {
                    messagesRepo.appendMessage(
                        MessageEntry(
                            id = UUID.randomUUID().toString(),
                            sender = MessageSender.AI,
                            text = "Already saved in Profile → Recipes."
                        )
                    )
                    _uiState.value = _uiState.value.copy(messages = messagesRepo.getMessageLog().log)
                    return@launch
                }

                val currentSaved = _uiState.value.savedRecipes.size
                if (!checkRecipeListLimitOrPrompt(profile, currentSaved = currentSaved, addingCount = 1)) return@launch

                val recipe = SavedRecipe(
                    id = UUID.randomUUID().toString(),
                    sourceMessageId = null,
                    title = title,
                    text = meal.recipeText,
                    ingredients = parsed.ingredients
                )
                recipesRepo.saveRecipe(recipe)
                val updated = recipesRepo.listRecipesNewestFirst()
                _uiState.value = _uiState.value.copy(savedRecipes = updated)

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
                }.onFailure { e ->
                    if (gateIfDailyLimit(e)) return@launch
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
                    append("', health ")
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
                    val kind = analysis.portionKind?.trim()?.uppercase()
                    val servingText = analysis.servingSizeText?.trim().orEmpty()
                    val caloriesPerServing = analysis.caloriesPerServing
                    val servingsPerContainer = analysis.servingsPerContainer
                    val cals = analysis.caloriesTotal ?: analysis.estimatedCalories
                    val low = analysis.caloriesLow
                    val high = analysis.caloriesHigh

                    if (kind == "PACKAGED") {
                        if (caloriesPerServing != null) {
                            append(" Calories per serving: ~")
                            append(caloriesPerServing)
                            if (servingText.isNotBlank()) {
                                append(" (")
                                append(servingText)
                                append(")")
                            }
                            append(".")
                        } else if (cals != null) {
                            append(" Estimated calories per serving: ~")
                            append(cals)
                            append(".")
                        }
                        if (servingsPerContainer != null) {
                            append(" Servings per container: ")
                            append(servingsPerContainer)
                            append(".")
                            if (caloriesPerServing != null) {
                                val whole = kotlin.math.round(caloriesPerServing * servingsPerContainer).toInt()
                                append(" Whole package: ~")
                                append(whole)
                                append(" calories.")
                            }
                        }
                    } else if (kind == "PLATED") {
                        if (cals != null) {
                            append(" Estimated calories for the full plate: ~")
                            append(cals)
                            if (low != null && high != null && low > 0 && high > 0) {
                                append(" (")
                                append(low)
                                append("–")
                                append(high)
                                append(")")
                            }
                            append(".")
                        }
                        if (servingText.isNotBlank()) {
                            append(" Serving size: ")
                            append(servingText)
                            append(".")
                        } else {
                            append(" Serving size: 1 plate.")
                        }
                    } else {
                        if (cals != null) {
                            append(" Estimated calories: ~")
                            append(cals)
                            if (low != null && high != null && low > 0 && high > 0) {
                                append(" (")
                                append(low)
                                append("–")
                                append(high)
                                append(")")
                            }
                            append(".")
                        }
                    }

                    val p = analysis.estimatedProteinG
                    val c = analysis.estimatedCarbsG
                    val f = analysis.estimatedFatG
                    if (p != null || c != null || f != null) {
                        append(" Estimated macros")
                        if (kind == "PACKAGED") append(" (per serving)") else if (kind == "PLATED") append(" (full plate)")
                        append(":")
                        if (p != null) {
                            append(" Protein: ")
                            append(p)
                            append("g.")
                        }
                        if (c != null) {
                            append(" Carbohydrates: ")
                            append(c)
                            append("g.")
                        }
                        if (f != null) {
                            append(" Fat: ")
                            append(f)
                            append("g.")
                        }
                    }
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

                val suggestedCat = analysis.suggestedCategory?.trim()?.uppercase()
                val catForList = when (suggestedCat) {
                    "MEAL" -> "MEAL"
                    "SNACK" -> "SNACK"
                    "INGREDIENT" -> "INGREDIENT"
                    else -> "INGREDIENT"
                }

                val item = FoodItem(
                    id = UUID.randomUUID().toString(),
                    name = analysis.normalizedName,
                    quantity = 1,
                    categories = listOf(catForList),
                    photoUrl = productUrl,
                    labelUrl = labelUrl,
                    nutritionFactsUrl = nutritionFactsUrl,
                    notes = analysis.summary,
                    estimatedCalories = analysis.caloriesTotal ?: analysis.estimatedCalories,
                    estimatedProteinG = analysis.estimatedProteinG,
                    estimatedCarbsG = analysis.estimatedCarbsG,
                    estimatedFatG = analysis.estimatedFatG,
                    ingredientsText = analysis.ingredientsText,
                    portionKind = analysis.portionKind,
                    servingSizeText = analysis.servingSizeText,
                    caloriesPerServing = analysis.caloriesPerServing,
                    servingsPerContainer = analysis.servingsPerContainer,
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
                        item = item,
                        portionKind = analysis.portionKind,
                        caloriesPerServing = analysis.caloriesPerServing,
                        servingsPerContainer = analysis.servingsPerContainer,
                        caloriesTotal = analysis.caloriesTotal ?: analysis.estimatedCalories,
                        caloriesLow = analysis.caloriesLow,
                        caloriesHigh = analysis.caloriesHigh
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

                val textResult = runCatching {
                    proxyRepo.analyzeMenu(
                        menuPhotoUrl = menuPhotoUrl,
                        dietType = profile.dietType,
                        inventorySummary = inventorySummary
                    )
                }
                textResult.exceptionOrNull()?.let { e ->
                    if (gateIfDailyLimit(e)) return@launch
                }

                val text = textResult.getOrElse { e ->
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

    fun confirmAddPendingGrocery(caloriesOverride: Int? = null) {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val pending = state.pendingGrocery ?: return@launch
                val profile = userRepo.getUserProfile() ?: return@launch

                if (!checkFoodListLimitOrPrompt(profile, addingCount = 1)) {
                    return@launch
                }

                val finalItem = if (caloriesOverride != null && caloriesOverride > 0) {
                    pending.item.copy(estimatedCalories = caloriesOverride)
                } else {
                    pending.item
                }

                val updated = profile.copy(foodItems = profile.foodItems + finalItem)
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
                        text = "Cool, leaving it out."
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
