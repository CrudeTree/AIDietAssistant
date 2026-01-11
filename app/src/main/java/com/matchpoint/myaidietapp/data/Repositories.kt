package com.matchpoint.myaidietapp.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.matchpoint.myaidietapp.model.HungerLevel
import com.matchpoint.myaidietapp.model.HungerSignal
import com.matchpoint.myaidietapp.model.MealHistoryEntry
import com.matchpoint.myaidietapp.model.ChatSession
import com.matchpoint.myaidietapp.model.MessageEntry
import com.matchpoint.myaidietapp.model.MessageLog
import com.matchpoint.myaidietapp.model.ScheduledMealDay
import com.matchpoint.myaidietapp.model.UserProfile
import com.matchpoint.myaidietapp.model.WeightUnit
import com.matchpoint.myaidietapp.model.RecipeTitleFontStyle
import kotlinx.coroutines.tasks.await

private const val USERS_COLLECTION = "users"
private const val SCHEDULES_SUBCOLLECTION = "schedules"
private const val MESSAGES_DOC_ID = "messageLog"
private const val CHATS_COLLECTION = "chats"
private const val CHAT_META_SUBCOLLECTION = "meta"

class UserRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    private val userDoc get() = db.collection(USERS_COLLECTION).document(userId)

    suspend fun getUserProfile(): UserProfile? {
        val snapshot = userDoc.get().await()
        return snapshot.toObject(UserProfile::class.java)
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        userDoc.set(profile).await()
    }

    suspend fun updateNextTimes(nextCheckInAt: Long?, nextMealAt: Long?) {
        val updates = hashMapOf<String, Any?>(
            "nextCheckInAtMillis" to nextCheckInAt,
            "nextMealAtMillis" to nextMealAt
        )
        userDoc.update(updates as Map<String, Any?>).await()
    }

    suspend fun removeFoodItem(foodId: String) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            foodItems = profile.foodItems.filterNot { it.id == foodId }
        )
        saveUserProfile(updated)
    }

    suspend fun updateFoodItemCategories(foodId: String, categories: List<String>) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            foodItems = profile.foodItems.map { item ->
                if (item.id == foodId) item.copy(categories = categories) else item
            }
        )
        saveUserProfile(updated)
    }

    suspend fun updateDietType(dietType: com.matchpoint.myaidietapp.model.DietType) {
        userDoc.update("dietType", dietType).await()
    }

    suspend fun updateShowFoodIcons(show: Boolean) {
        userDoc.update("showFoodIcons", show).await()
    }

    suspend fun updateShowWallpaperFoodIcons(show: Boolean) {
        userDoc.update("showWallpaperFoodIcons", show).await()
    }

    suspend fun updateUiFontSizeSp(fontSizeSp: Float) {
        userDoc.update("uiFontSizeSp", fontSizeSp).await()
    }

    suspend fun updateRecipeTitleFontStyle(style: RecipeTitleFontStyle) {
        userDoc.update("recipeTitleFontStyle", style).await()
    }

    suspend fun updateFastingPreset(
        preset: com.matchpoint.myaidietapp.model.FastingPreset,
        startMinutes: Int?,
        endMinutes: Int?
    ) {
        userDoc.update(
            mapOf(
                "fastingPreset" to preset,
                "eatingWindowStartMinutes" to startMinutes,
                "eatingWindowEndMinutes" to endMinutes
            )
        ).await()
    }

    suspend fun updateWeightGoal(weightGoal: Double?) {
        userDoc.update("weightGoal", weightGoal).await()
    }

    suspend fun updateWeightUnit(weightUnit: WeightUnit) {
        userDoc.update("weightUnit", weightUnit).await()
    }

    suspend fun appendWeightEntry(entry: com.matchpoint.myaidietapp.model.WeightEntry) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            weightHistory = profile.weightHistory + entry
        )
        saveUserProfile(updated)
    }

    suspend fun appendHungerSignal(level: HungerLevel) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            hungerSignals = profile.hungerSignals + HungerSignal(Timestamp.now(), level)
        )
        saveUserProfile(updated)
    }

    suspend fun appendMealHistory(entry: MealHistoryEntry) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            mealHistory = profile.mealHistory + entry
        )
        saveUserProfile(updated)
    }

    suspend fun updateAutoPilotEnabled(enabled: Boolean) {
        userDoc.update("autoPilotEnabled", enabled).await()
    }
}

class ScheduledMealsRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    private val schedulesCollection get() =
        db.collection(USERS_COLLECTION).document(userId).collection(SCHEDULES_SUBCOLLECTION)

    suspend fun getDaySchedule(dayId: String): ScheduledMealDay? {
        val snapshot = schedulesCollection.document(dayId).get().await()
        return snapshot.toObject(ScheduledMealDay::class.java)
    }

    suspend fun saveDaySchedule(schedule: ScheduledMealDay) {
        val id = schedule.dayId.ifBlank { throw IllegalArgumentException("ScheduledMealDay.dayId must not be blank") }
        schedulesCollection.document(id).set(schedule).await()
    }
}

class MessagesRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    companion object {
        /**
         * In-memory-only "draft" chat. This should never be persisted or shown in chat history.
         * When the user sends the first message, we create a real chat and switch to it.
         */
        private const val DRAFT_CHAT_ID = "__draft__"
    }

    // Backward-compatible legacy single-log location.
    private val legacyMessagesDoc get() =
        db.collection(USERS_COLLECTION).document(userId).collection("meta").document(MESSAGES_DOC_ID)

    private val chatsCollection get() =
        db.collection(USERS_COLLECTION).document(userId).collection(CHATS_COLLECTION)

    private fun chatMetaDoc(chatId: String) = chatsCollection.document(chatId)
    private fun chatMessagesDoc(chatId: String) =
        chatsCollection.document(chatId).collection(CHAT_META_SUBCOLLECTION).document(MESSAGES_DOC_ID)

    private var activeChatId: String = "default"

    fun getActiveChatId(): String = activeChatId
    fun setActiveChat(chatId: String) {
        activeChatId = chatId.ifBlank { "default" }
    }

    /**
     * Start a fresh, in-memory-only chat. This prevents "New chat" entries from appearing
     * in history until the user actually sends a message.
     */
    fun startDraftChat() {
        activeChatId = DRAFT_CHAT_ID
    }

    private fun inferChatTitleFromFirstUserMessage(message: String): String {
        val cleaned = message.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return "Chat"

        val withoutGreeting = cleaned.replace(
            Regex("^(can you|could you|please|hey|hi|hello|yo)\\s+", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        // Only consider the first sentence for naming.
        val firstSentence = withoutGreeting
            .split(Regex("[\\.!?]"))
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { withoutGreeting }

        // Remove common "prompt wrapper" prefixes so we get a ChatGPT-style short topic title.
        val topic = firstSentence
            .replace(Regex("^how\\s+to\\s+make\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^how\\s+to\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^how\\s+do\\s+i\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^what\\s+is\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^recipe\\s+for\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^make\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        // Max 5 words, no subtext (ChatGPT style).
        val words = Regex("\\b[\\p{L}\\p{N}]+'?[\\p{L}\\p{N}]*\\b")
            .findAll(topic.ifBlank { firstSentence })
            .map { it.value }
            .toList()

        val title = words.take(5).joinToString(" ").trim()
        return title.replaceFirstChar { it.uppercase() }.ifBlank { "Chat" }
    }

    private suspend fun ensureMigratedDefaultChat() {
        // If there's no chats/default yet but legacy exists, copy legacy -> chats/default.
        val defaultMeta = chatMetaDoc("default").get().await()
        if (defaultMeta.exists()) return

        val legacySnap = legacyMessagesDoc.get().await()
        if (!legacySnap.exists()) return

        val legacy = legacySnap.toObject(MessageLog::class.java) ?: MessageLog(userId = userId, log = emptyList())
        val now = Timestamp.now()
        chatMetaDoc("default").set(
            ChatSession(
                id = "default",
                createdAt = now,
                updatedAt = now,
                title = "Chat",
                lastSnippet = legacy.log.lastOrNull()?.text?.take(80)
            ),
            SetOptions.merge()
        ).await()
        chatMessagesDoc("default").set(legacy).await()
        // Keep legacyMessagesDoc untouched for safety; new code uses chats/default going forward.
    }

    suspend fun listChats(limit: Int = 30): List<ChatSession> {
        ensureMigratedDefaultChat()
        val snap = chatsCollection
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get()
            .await()
        return snap.documents.mapNotNull { doc ->
            val c = doc.toObject(ChatSession::class.java) ?: return@mapNotNull null
            if (c.id.isBlank()) c.copy(id = doc.id) else c
        }
    }

    suspend fun createNewChat(): ChatSession {
        ensureMigratedDefaultChat()
        val id = java.util.UUID.randomUUID().toString()
        val now = Timestamp.now()
        val session = ChatSession(
            id = id,
            createdAt = now,
            updatedAt = now,
            title = "New chat",
            lastSnippet = null
        )
        chatMetaDoc(id).set(session, SetOptions.merge()).await()
        chatMessagesDoc(id).set(MessageLog(userId = userId, log = emptyList())).await()
        activeChatId = id
        return session
    }

    /**
     * Deletes a chat session and its messageLog doc.
     * Returns false if the chat is protected (e.g., "default") or if deletion fails.
     */
    suspend fun deleteChat(chatId: String): Boolean {
        ensureMigratedDefaultChat()
        val id = chatId.trim()
        if (id.isBlank()) return false
        if (id == "default") return false

        return runCatching {
            // Only one doc under meta currently.
            chatMessagesDoc(id).delete().await()
            chatMetaDoc(id).delete().await()
            if (activeChatId == id) activeChatId = "default"
            true
        }.getOrElse { false }
    }

    suspend fun getMessageLog(): MessageLog {
        ensureMigratedDefaultChat()
        if (activeChatId == DRAFT_CHAT_ID) {
            return MessageLog(userId = userId, log = emptyList())
        }
        val doc = if (activeChatId == "default") {
            // Prefer the migrated chat doc if present, otherwise fall back to legacy.
            val migrated = chatMessagesDoc("default").get().await()
            if (migrated.exists()) migrated else legacyMessagesDoc.get().await()
        } else {
            chatMessagesDoc(activeChatId).get().await()
        }
        return if (doc.exists()) {
            doc.toObject(MessageLog::class.java) ?: MessageLog(userId = userId, log = emptyList())
        } else {
            MessageLog(userId = userId, log = emptyList())
        }
    }

    suspend fun appendMessage(entry: MessageEntry) {
        ensureMigratedDefaultChat()

        // If we're in a draft chat, promote it to a real chat on first message.
        if (activeChatId == DRAFT_CHAT_ID) {
            // createNewChat() writes a meta doc + empty messageLog doc; after this appendMessage will fill it.
            createNewChat()
        }

        val chatId = activeChatId
        val current = getMessageLog()
        val updated = current.copy(log = current.log + entry)

        // Persist under chat session if possible; default chat falls back to legacy only if needed.
        if (chatId == "default") {
            // Write to chats/default if it exists; otherwise write to legacy.
            val metaSnap = chatMetaDoc("default").get().await()
            if (metaSnap.exists()) {
                chatMessagesDoc("default").set(updated).await()
            } else {
                legacyMessagesDoc.set(updated).await()
            }
        } else {
            // Ensure meta exists (but do NOT overwrite title; title is set below based on first user message).
            val now = Timestamp.now()
            val metaSnap = chatMetaDoc(chatId).get().await()
            if (!metaSnap.exists()) {
                chatMetaDoc(chatId).set(
                    ChatSession(
                        id = chatId,
                        createdAt = now,
                        updatedAt = now,
                        title = "New chat",
                        lastSnippet = null
                    ),
                    SetOptions.merge()
                ).await()
            }
            chatMessagesDoc(chatId).set(updated).await()
        }

        // Update chat session metadata (title/snippet/updatedAt).
        val now = Timestamp.now()
        val lastSnippet = entry.text.trim().take(120).ifBlank { null }
        val currentMeta = chatMetaDoc(chatId).get().await().toObject(ChatSession::class.java)
        val title = run {
            val existing = currentMeta?.title?.trim().orEmpty()
            // Only auto-name when it's a brand new chat and the user sends the first message.
            if (existing.isNotBlank() && existing != "New chat" && existing != "Chat") return@run existing
            if (entry.sender != com.matchpoint.myaidietapp.model.MessageSender.USER) return@run (existing.ifBlank { "Chat" })
            inferChatTitleFromFirstUserMessage(entry.text)
        }
        chatMetaDoc(chatId).set(
            ChatSession(
                id = chatId,
                createdAt = currentMeta?.createdAt ?: now,
                updatedAt = now,
                title = title,
                lastSnippet = lastSnippet
            ),
            SetOptions.merge()
        ).await()
    }
}







