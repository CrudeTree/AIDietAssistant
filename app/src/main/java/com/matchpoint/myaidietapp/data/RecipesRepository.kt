package com.matchpoint.myaidietapp.data

import com.google.firebase.firestore.FirebaseFirestore
import com.matchpoint.myaidietapp.model.SavedRecipe
import kotlinx.coroutines.tasks.await

private const val USERS_COLLECTION = "users"
private const val RECIPES_SUBCOLLECTION = "recipes"

class RecipesRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    private val recipesCollection get() =
        db.collection(USERS_COLLECTION).document(userId).collection(RECIPES_SUBCOLLECTION)

    suspend fun listRecipesNewestFirst(limit: Int = 50): List<SavedRecipe> {
        val snap = recipesCollection
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get()
            .await()
        return snap.documents.mapNotNull { doc ->
            val r = doc.toObject(SavedRecipe::class.java) ?: return@mapNotNull null
            // Defensive: if an old doc didn't store the id field, fall back to Firestore doc id.
            if (r.id.isBlank()) r.copy(id = doc.id) else r
        }
    }

    suspend fun saveRecipe(recipe: SavedRecipe) {
        val id = recipe.id.ifBlank { throw IllegalArgumentException("SavedRecipe.id must not be blank") }
        recipesCollection.document(id).set(recipe).await()
    }

    suspend fun deleteRecipe(recipeId: String) {
        if (recipeId.isBlank()) return
        recipesCollection.document(recipeId).delete().await()
    }
}


