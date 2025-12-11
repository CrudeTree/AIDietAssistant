package com.matchpoint.myaidietapp.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.matchpoint.myaidietapp.data.UserIdProvider
import com.matchpoint.myaidietapp.notifications.NotificationScheduler

class HomeViewModelFactory(
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            val userIdProvider = UserIdProvider(appContext)
            val scheduler = NotificationScheduler(appContext)
            return HomeViewModel(
                userIdProvider = userIdProvider,
                notificationScheduler = scheduler
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

