package com.matchpoint.myaidietapp.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.matchpoint.myaidietapp.model.HungerLevel
import com.matchpoint.myaidietapp.model.HungerSignal
import com.matchpoint.myaidietapp.model.MealHistoryEntry
import com.matchpoint.myaidietapp.model.MessageEntry
import com.matchpoint.myaidietapp.model.MessageLog
import com.matchpoint.myaidietapp.model.ScheduledMealDay
import com.matchpoint.myaidietapp.model.UserProfile
import kotlinx.coroutines.tasks.await

private const val USERS_COLLECTION = "users"
private const val SCHEDULES_SUBCOLLECTION = "schedules"
private const val MESSAGES_DOC_ID = "messageLog"

class UserRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    private val userDoc get() = db.collection(USERS_COLLECTION).document(userId)

    suspend fun getUserProfile(): UserProfile? {
        val snapshot = userDoc.get().await()
        return snapshot.toObject(UserProfile::class.java)
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        userDoc.set(profile).await()
    }

    suspend fun updateNextTimes(nextCheckInAt: Long?, nextMealAt: Long?) {
        val updates = hashMapOf<String, Any?>(
            "nextCheckInAtMillis" to nextCheckInAt,
            "nextMealAtMillis" to nextMealAt
        )
        userDoc.update(updates as Map<String, Any?>).await()
    }

    suspend fun removeFoodItem(foodId: String) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            foodItems = profile.foodItems.filterNot { it.id == foodId }
        )
        saveUserProfile(updated)
    }

    suspend fun updateFoodItemCategories(foodId: String, categories: List<String>) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            foodItems = profile.foodItems.map { item ->
                if (item.id == foodId) item.copy(categories = categories) else item
            }
        )
        saveUserProfile(updated)
    }

    suspend fun updateDietType(dietType: com.matchpoint.myaidietapp.model.DietType) {
        userDoc.update("dietType", dietType).await()
    }

    suspend fun updateFastingPreset(
        preset: com.matchpoint.myaidietapp.model.FastingPreset,
        startMinutes: Int?,
        endMinutes: Int?
    ) {
        userDoc.update(
            mapOf(
                "fastingPreset" to preset,
                "eatingWindowStartMinutes" to startMinutes,
                "eatingWindowEndMinutes" to endMinutes
            )
        ).await()
    }

    suspend fun updateWeightGoal(weightGoal: Double?) {
        userDoc.update("weightGoal", weightGoal).await()
    }

    suspend fun appendWeightEntry(entry: com.matchpoint.myaidietapp.model.WeightEntry) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            weightHistory = profile.weightHistory + entry
        )
        saveUserProfile(updated)
    }

    suspend fun appendHungerSignal(level: HungerLevel) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            hungerSignals = profile.hungerSignals + HungerSignal(Timestamp.now(), level)
        )
        saveUserProfile(updated)
    }

    suspend fun appendMealHistory(entry: MealHistoryEntry) {
        val profile = getUserProfile() ?: return
        val updated = profile.copy(
            mealHistory = profile.mealHistory + entry
        )
        saveUserProfile(updated)
    }

    suspend fun updateAutoPilotEnabled(enabled: Boolean) {
        userDoc.update("autoPilotEnabled", enabled).await()
    }
}

class ScheduledMealsRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    private val schedulesCollection get() =
        db.collection(USERS_COLLECTION).document(userId).collection(SCHEDULES_SUBCOLLECTION)

    suspend fun getDaySchedule(dayId: String): ScheduledMealDay? {
        val snapshot = schedulesCollection.document(dayId).get().await()
        return snapshot.toObject(ScheduledMealDay::class.java)
    }

    suspend fun saveDaySchedule(schedule: ScheduledMealDay) {
        val id = schedule.dayId.ifBlank { throw IllegalArgumentException("ScheduledMealDay.dayId must not be blank") }
        schedulesCollection.document(id).set(schedule).await()
    }
}

class MessagesRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {
    private val messagesDoc get() =
        db.collection(USERS_COLLECTION).document(userId).collection("meta").document(MESSAGES_DOC_ID)

    suspend fun getMessageLog(): MessageLog {
        val snapshot = messagesDoc.get().await()
        return if (snapshot.exists()) {
            snapshot.toObject(MessageLog::class.java) ?: MessageLog(userId = userId, log = emptyList())
        } else {
            MessageLog(userId = userId, log = emptyList())
        }
    }

    suspend fun appendMessage(entry: MessageEntry) {
        val current = getMessageLog()
        val updated = current.copy(log = current.log + entry)
        messagesDoc.set(updated).await()
    }
}







