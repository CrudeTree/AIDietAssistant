package com.matchpoint.myaidietapp.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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

    fun ensureAutoPilotLoop() {
        val request = PeriodicWorkRequestBuilder<AutoPilotLoopWorker>(15, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_AUTOPILOT_LOOP,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelAutoPilotLoop() {
        workManager.cancelUniqueWork(UNIQUE_AUTOPILOT_LOOP)
    }

    private companion object {
        const val UNIQUE_AUTOPILOT_LOOP = "digital_stomach_autopilot_loop"
    }
}






