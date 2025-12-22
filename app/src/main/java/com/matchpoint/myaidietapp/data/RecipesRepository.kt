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
        return snap.documents.mapNotNull { it.toObject(SavedRecipe::class.java) }
    }

    suspend fun saveRecipe(recipe: SavedRecipe) {
        val id = recipe.id.ifBlank { throw IllegalArgumentException("SavedRecipe.id must not be blank") }
        recipesCollection.document(id).set(recipe).await()
    }
}


