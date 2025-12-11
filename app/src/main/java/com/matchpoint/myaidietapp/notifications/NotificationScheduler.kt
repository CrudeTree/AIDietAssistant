package com.matchpoint.myaidietapp.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.matchpoint.myaidietapp.model.ScheduledMealDay
import java.util.concurrent.TimeUnit

class NotificationScheduler(
    private val context: Context
) {

    private val workManager = WorkManager.getInstance(context)

    fun scheduleForDay(schedule: ScheduledMealDay) {
        val now = System.currentTimeMillis()
        schedule.decidedMeals.forEach { decidedMeal ->
            val delay = (decidedMeal.exactTimeMillis - now).coerceAtLeast(0L)
            val data = Data.Builder()
                .putString(MealNotificationWorker.KEY_MESSAGE, "Feed time. Go make: ${decidedMeal.mealSuggestion}")
                .build()

            val request = OneTimeWorkRequestBuilder<MealNotificationWorker>()
                .setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            workManager.enqueue(request)
        }
    }
}






