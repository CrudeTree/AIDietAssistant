package com.matchpoint.myaidietapp.data

import android.content.Context
import java.util.UUID

class UserIdProvider(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("digital_stomach_prefs", Context.MODE_PRIVATE)
    }

    fun getUserId(): String {
        val existing = prefs.getString("user_id", null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("user_id", newId).apply()
        return newId
    }
}






