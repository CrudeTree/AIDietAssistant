package com.matchpoint.myaidietapp.data

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

class StorageRepository(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    suspend fun uploadToPath(uri: Uri, path: String): String {
        val ref = storage.getReference(path)
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }
}



