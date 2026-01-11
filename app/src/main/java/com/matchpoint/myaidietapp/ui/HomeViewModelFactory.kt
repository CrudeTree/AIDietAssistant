package com.matchpoint.myaidietapp.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.matchpoint.myaidietapp.data.DailyQuotaManager
import com.matchpoint.myaidietapp.data.ReviewPromptManager
import com.matchpoint.myaidietapp.notifications.NotificationScheduler

class HomeViewModelFactory(
    private val appContext: Context,
    private val userId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            val scheduler = NotificationScheduler(appContext)
            val quotaManager = DailyQuotaManager(appContext)
            val reviewPromptManager = ReviewPromptManager(appContext)
            return HomeViewModel(
                userId = userId,
                notificationScheduler = scheduler,
                quotaManager = quotaManager,
                reviewPromptManager = reviewPromptManager
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

