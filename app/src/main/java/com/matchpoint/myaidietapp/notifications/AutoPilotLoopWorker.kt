package com.matchpoint.myaidietapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.matchpoint.myaidietapp.MainActivity
import com.matchpoint.myaidietapp.data.MessagesRepository
import com.matchpoint.myaidietapp.data.OpenAiProxyRepository
import com.matchpoint.myaidietapp.data.ScheduledMealsRepository
import com.matchpoint.myaidietapp.data.UserIdProvider
import com.matchpoint.myaidietapp.data.UserRepository
import com.matchpoint.myaidietapp.logic.DailyPlanner
import com.matchpoint.myaidietapp.model.MessageEntry
import com.matchpoint.myaidietapp.model.MessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/**
 * Periodic background loop that keeps the "AI Food Coach" alive:
 * - Ensures today's schedule exists (and schedules meal notifications)
 * - Sends "poke" messages when it's time to check in
 * - Sends "time to eat" messages when a meal is due
 *
 * This is intentionally conservative and spam-resistant; it will not poke overnight.
 */
class AutoPilotLoopWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val zoneId: ZoneId = ZoneId.systemDefault()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            FirebaseApp.initializeApp(applicationContext)

            val context = applicationContext
            val userId = UserIdProvider(context).getUserId()
            val db = FirebaseFirestore.getInstance()

            val userRepo = UserRepository(db, userId)
            val scheduleRepo = ScheduledMealsRepository(db, userId)
            val messagesRepo = MessagesRepository(db, userId)
            val proxyRepo = OpenAiProxyRepository()
            val dailyPlanner = DailyPlanner(zoneId)
            val notifier = Notifier(context)

            val profile = userRepo.getUserProfile() ?: return@withContext Result.success()
            if (!profile.autoPilotEnabled) return@withContext Result.success()

            val nowMillis = Instant.now().toEpochMilli()

            // Quiet hours: don't ping or feed at 3am.
            val localHour = java.time.ZonedDateTime.now(zoneId).hour
            val isQuietHours = localHour < 7 || localHour >= 23
            if (isQuietHours) {
                // If nextCheckIn is overdue during quiet hours, bump it to 8am.
                val nextCheck = profile.nextCheckInAtMillis
                if (nextCheck != null && nowMillis >= nextCheck) {
                    val nextMorning = java.time.ZonedDateTime.now(zoneId)
                        .withHour(8).withMinute(0).withSecond(0).withNano(0)
                        .let { if (it.toInstant().toEpochMilli() <= nowMillis) it.plusDays(1) else it }
                        .toInstant().toEpochMilli()
                    userRepo.updateNextTimes(nextCheckInAt = nextMorning, nextMealAt = profile.nextMealAtMillis)
                }
                return@withContext Result.success()
            }

            // Ensure today's schedule exists (and schedule notifications for it).
            val today = LocalDate.now(zoneId)
            val todayId = today.toString()
            val schedule = scheduleRepo.getDaySchedule(todayId)
                ?: dailyPlanner.planForDay(today, profile).also { scheduleRepo.saveDaySchedule(it) }
            NotificationScheduler(context).scheduleForDay(schedule)

            // Helper: avoid spamming if we recently sent an AI message.
            suspend fun recentlyMessagedAi(withinMinutes: Long): Boolean {
                val log = messagesRepo.getMessageLog().log
                val last = log.maxByOrNull { it.timestamp } ?: return false
                if (last.sender != MessageSender.AI) return false
                val delta = nowMillis - last.timestamp.toDate().time
                return delta < withinMinutes * 60_000
            }

            // If no next times are set, initialize them.
            if (profile.nextMealAtMillis == null) {
                val nextMeal = schedule.decidedMeals
                    .filter { it.exactTimeMillis >= nowMillis }
                    .minByOrNull { it.exactTimeMillis }
                userRepo.updateNextTimes(
                    nextCheckInAt = profile.nextCheckInAtMillis ?: (nowMillis + 60 * 60 * 1000),
                    nextMealAt = nextMeal?.exactTimeMillis
                )
            }
            if (profile.nextCheckInAtMillis == null) {
                userRepo.updateNextTimes(
                    nextCheckInAt = nowMillis + randomMinutes(45, 120) * 60_000,
                    nextMealAt = profile.nextMealAtMillis
                )
            }

            // Reload (because we may have updated).
            val refreshed = userRepo.getUserProfile() ?: return@withContext Result.success()

            // 1) Meal due -> "time to eat" message + notification.
            val mealDue = refreshed.nextMealAtMillis?.let { nowMillis >= it } == true
            if (mealDue && !recentlyMessagedAi(withinMinutes = 20)) {
                val dueMeal = schedule.decidedMeals
                    .filter { it.exactTimeMillis <= nowMillis }
                    .maxByOrNull { it.exactTimeMillis }
                    ?: schedule.decidedMeals.minByOrNull { it.exactTimeMillis }

                val text = if (dueMeal != null) {
                    "Time to eat. Go make: ${dueMeal.mealSuggestion}"
                } else {
                    "Time to eat. Tell me what you’re thinking and I’ll pick something from your foods."
                }

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = text
                )
                messagesRepo.appendMessage(aiEntry)
                notifier.notify(text)

                // After a feed prompt, set a check-in later and move nextMeal forward to the next scheduled slot.
                val nextMealAfterDue = dueMeal?.let { dm ->
                    schedule.decidedMeals
                        .filter { it.exactTimeMillis > dm.exactTimeMillis }
                        .minByOrNull { it.exactTimeMillis }
                }
                userRepo.updateNextTimes(
                    nextCheckInAt = nowMillis + 90 * 60_000,
                    nextMealAt = nextMealAfterDue?.exactTimeMillis
                )

                return@withContext Result.success()
            }

            // 2) Check-in due -> ask how they feel (LLM) + notification.
            val checkDue = refreshed.nextCheckInAtMillis?.let { nowMillis >= it } == true
            if (checkDue && !recentlyMessagedAi(withinMinutes = 20)) {
                val lastMeal = refreshed.mealHistory.maxByOrNull { it.timestamp }
                val minutesSinceMeal = lastMeal?.let { (nowMillis - it.timestamp.toDate().time) / 60_000 }

                // If the user logged a big meal recently, don't poke too fast even if nextCheckIn got set badly.
                val lastCals = lastMeal?.estimatedCalories
                val lastGrams = lastMeal?.totalGrams
                val minMinutesBetweenPokes = when {
                    lastCals != null && lastCals >= 1000 -> 180L
                    lastCals != null && lastCals >= 700 -> 150L
                    lastGrams != null && lastGrams >= 700 -> 150L
                    lastGrams != null && lastGrams >= 450 -> 120L
                    else -> 75L
                }
                if (minutesSinceMeal != null && minutesSinceMeal < minMinutesBetweenPokes) {
                    userRepo.updateNextTimes(
                        nextCheckInAt = nowMillis + (minMinutesBetweenPokes - minutesSinceMeal) * 60_000,
                        nextMealAt = refreshed.nextMealAtMillis
                    )
                    return@withContext Result.success()
                }

                val inventorySummary = buildInventorySummary(refreshed)

                val reply = runCatching {
                    proxyRepo.generateCheckIn(
                        com.matchpoint.myaidietapp.data.CheckInRequest(
                            lastMeal = lastMeal?.mealName ?: lastMeal?.items?.joinToString(),
                            hungerSummary = buildString {
                                append(refreshed.hungerSignals.takeLast(5).joinToString { it.level.name })
                                lastMeal?.estimatedCalories?.let { append(" | lastMealCalories=$it") }
                                lastMeal?.totalGrams?.let { append(" | lastMealGrams=$it") }
                            },
                            weightTrend = null,
                            minutesSinceMeal = minutesSinceMeal,
                            mode = "auto_poke",
                            inventorySummary = inventorySummary
                        )
                    )
                }.getOrElse {
                    "No service. Check internet connectivity."
                }

                val aiEntry = MessageEntry(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.AI,
                    text = reply
                )
                messagesRepo.appendMessage(aiEntry)
                notifier.notify(reply)

                userRepo.updateNextTimes(
                    nextCheckInAt = nowMillis + randomMinutes(45, 120) * 60_000,
                    nextMealAt = refreshed.nextMealAtMillis
                )
            }

            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    private fun randomMinutes(min: Long, max: Long): Long = Random.nextLong(min, max + 1)

    private fun buildInventorySummary(profile: com.matchpoint.myaidietapp.model.UserProfile): String {
        return buildString {
            val today = LocalDate.now(zoneId).toString()
            val todaysCalories = profile.mealHistory
                .filter { it.timestamp.toDate().let { d ->
                    java.time.Instant.ofEpochMilli(d.time).atZone(zoneId).toLocalDate().toString() == today
                } }
                .mapNotNull { it.estimatedCalories }
                .sum()
                .takeIf { it > 0 }

            if (profile.foodItems.isNotEmpty()) {
                append("Food items: ")
                append(profile.foodItems.joinToString { item ->
                    val health = item.rating?.let { r -> "H${r}/10" } ?: ""
                    val diet = item.dietFitRating?.let { r -> "D${r}/10" } ?: ""
                    listOf(item.name, health, diet).filter { it.isNotBlank() }.joinToString(" ")
                })
                append(". ")
            }
            if (profile.inventory.isNotEmpty()) {
                append("Inventory counts: ")
                append(profile.inventory.entries.joinToString { "${it.key} x${it.value}" })
                append(". ")
            }
            todaysCalories?.let {
                append("Calories logged today: $it. ")
            }
            append("Diet: ${profile.dietType.name}.")
        }
    }

    private class Notifier(private val context: Context) {
        fun notify(message: String) {
            createNotificationChannel()
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlags)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("AI Food Coach")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            with(NotificationManagerCompat.from(context)) {
                notify((System.currentTimeMillis() % 10_000).toInt(), notification)
            }
        }

        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "AI Food Coach"
                val descriptionText = "AI Food Coach check-ins"
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        companion object {
            private const val CHANNEL_ID = "ai_food_coach_channel"
        }
    }
}


