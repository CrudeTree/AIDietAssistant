package com.matchpoint.myaidietapp.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.matchpoint.myaidietapp.model.ChatSession
import com.matchpoint.myaidietapp.model.MessageEntry
import com.matchpoint.myaidietapp.model.MessageLog
import kotlinx.coroutines.tasks.await

/**
 * Per-recipe Q&A thread storage.
 *
 * Implementation detail:
 * We store Q&A as a dedicated chat session under users/{uid}/chats/{chatId},
 * but we prefix chatId with "recipe:" so the normal chat UI can filter it out.
 */
class RecipeQnaRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    private companion object {
        private const val USERS_COLLECTION = "users"
        private const val CHATS_COLLECTION = "chats"
        private const val CHAT_META_SUBCOLLECTION = "meta"
        private const val MESSAGES_DOC_ID = "messageLog"
        private const val RECIPE_CHAT_PREFIX = "recipe:"
    }

    private val chatsCollection get() =
        db.collection(USERS_COLLECTION).document(userId).collection(CHATS_COLLECTION)

    private fun chatIdForRecipe(recipeId: String): String =
        (RECIPE_CHAT_PREFIX + recipeId.trim()).ifBlank { RECIPE_CHAT_PREFIX + "unknown" }

    private fun chatMetaDoc(chatId: String) = chatsCollection.document(chatId)
    private fun chatMessagesDoc(chatId: String) =
        chatsCollection.document(chatId).collection(CHAT_META_SUBCOLLECTION).document(MESSAGES_DOC_ID)

    suspend fun getLog(recipeId: String): MessageLog {
        val chatId = chatIdForRecipe(recipeId)
        val doc = chatMessagesDoc(chatId).get().await()
        return if (doc.exists()) {
            doc.toObject(MessageLog::class.java) ?: MessageLog(userId = userId, log = emptyList())
        } else {
            // Don't create anything on read; just return empty.
            MessageLog(userId = userId, log = emptyList())
        }
    }

    suspend fun appendMessage(recipeId: String, entry: MessageEntry) {
        val chatId = chatIdForRecipe(recipeId)
        val current = getLog(recipeId)
        val updated = current.copy(log = current.log + entry)

        // Ensure a lightweight meta doc exists (for filtering + stability).
        val now = Timestamp.now()
        val lastSnippet = entry.text.trim().take(120).ifBlank { null }
        chatMetaDoc(chatId).set(
            ChatSession(
                id = chatId,
                createdAt = now,
                updatedAt = now,
                title = "Recipe Q&A",
                lastSnippet = lastSnippet
            ),
            SetOptions.merge()
        ).await()

        chatMessagesDoc(chatId).set(updated).await()

        // Update snippet/updatedAt without overwriting createdAt/title.
        chatMetaDoc(chatId).set(
            mapOf(
                "updatedAt" to now,
                "lastSnippet" to lastSnippet
            ),
            SetOptions.merge()
        ).await()
    }
}

