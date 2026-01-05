package com.matchpoint.myaidietapp.data

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

class StorageRepository(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    /**
     * Uploads a file to Firebase Storage at the given [path] and returns the download URL.
     */
    suspend fun uploadToPath(uri: Uri, path: String): String {
        val ref = storage.reference.child(path)
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }

    private suspend fun deleteAllUnder(prefixPath: String) {
        val root = storage.reference.child(prefixPath)
        val result = root.listAll().await()
        // Delete all files
        result.items.forEach { it.delete().await() }
        // Recurse into folders
        result.prefixes.forEach { deleteAllUnder(it.path) }
    }

    /**
     * Best-effort removal of all user images from known folders.
     */
    suspend fun deleteAllUserContent(userId: String) {
        // These are the only folders we currently write to.
        val prefixes = listOf(
            "foodPhotos/$userId",
            "mealPhotos/$userId",
            "groceryPhotos/$userId",
            "menuPhotos/$userId"
        )
        prefixes.forEach { prefix ->
            try {
                deleteAllUnder(prefix)
            } catch (_: Exception) {
                // Best-effort: ignore missing paths / permission issues
            }
        }
    }
}







