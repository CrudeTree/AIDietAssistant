package com.matchpoint.myaidietapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.matchpoint.myaidietapp.R

class MealNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val message = inputData.getString(KEY_MESSAGE) ?: "Digital Stomach says it’s time to feed."
        createChannelIfNeeded()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Digital Stomach")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID_MEAL, notification)

        return Result.success()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Digital Stomach",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Meal and check-in notifications from your AI Digital Stomach"
            }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "digital_stomach_channel"
        const val KEY_MESSAGE = "message"
        const val NOTIFICATION_ID_MEAL = 1001
    }
}






