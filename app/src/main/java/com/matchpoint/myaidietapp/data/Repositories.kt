package com.matchpoint.myaidietapp.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import com.matchpoint.myaidietapp.model.*
import kotlinx.coroutines.tasks.await

class UserRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {

    private val usersCollection get() = db.collection("users")

    suspend fun getUserProfile(): UserProfile? {
        val snapshot = usersCollection.document(userId).get().await()
        return snapshot.toObject<UserProfile>()
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        usersCollection.document(userId).set(profile).await()
    }

    suspend fun updateFoodItems(foodItems: List<FoodItem>) {
        val current = getUserProfile() ?: UserProfile()
        val updated = current.copy(foodItems = foodItems)
        saveUserProfile(updated)
    }

    suspend fun appendWeight(weight: Double, date: String) {
        val current = getUserProfile() ?: UserProfile()
        val updated = current.copy(
            weightHistory = current.weightHistory + WeightEntry(date = date, weight = weight)
        )
        saveUserProfile(updated)
    }

    suspend fun appendHungerSignal(level: HungerLevel) {
        val current = getUserProfile() ?: UserProfile()
        val updated = current.copy(
            hungerSignals = current.hungerSignals + HungerSignal(
                timestamp = Timestamp.now(),
                level = level
            )
        )
        saveUserProfile(updated)
    }

    suspend fun appendMealHistory(entry: MealHistoryEntry) {
        val current = getUserProfile() ?: UserProfile()
        val updated = current.copy(
            mealHistory = current.mealHistory + entry
        )
        saveUserProfile(updated)
    }

    suspend fun updateNextTimes(nextCheckInAt: Long?, nextMealAt: Long?) {
        val current = getUserProfile() ?: UserProfile()
        val updated = current.copy(
            nextCheckInAtMillis = nextCheckInAt,
            nextMealAtMillis = nextMealAt
        )
        saveUserProfile(updated)
    }

    suspend fun removeFoodItem(foodId: String) {
        val current = getUserProfile() ?: UserProfile()
        val updated = current.copy(
            foodItems = current.foodItems.filterNot { it.id == foodId }
        )
        saveUserProfile(updated)
    }
}

class ScheduledMealsRepository(
    private val db: FirebaseFirestore,
    private val userId: String
) {

    private val scheduledMealsCollection
        get() = db.collection("scheduledMeals")

    private fun dayDoc(dayId: String) =
        scheduledMealsCollection.document(userId).collection("days").document(dayId)

    suspend fun getDaySchedule(dayId: String): ScheduledMealDay? {
        val snapshot = dayDoc(dayId).get().await()
        return snapshot.toObject<ScheduledMealDay>()
    }

    suspend fun saveDaySchedule(schedule: ScheduledMealDay) {
        dayDoc(schedule.dayId).set(schedule).await()
    }
}

class MessagesRepository(
    private val db: FirebaseFirestore,
    private val userId: String
){

    private val messagesCollection get() = db.collection("messages")

    private fun userDoc() = messagesCollection.document(userId)

    suspend fun getMessageLog(): MessageLog {
        val snapshot = userDoc().get().await()
        return snapshot.toObject<MessageLog>() ?: MessageLog(userId = userId, log = emptyList())
    }

    suspend fun appendMessage(entry: MessageEntry) {
        val current = getMessageLog()
        val updated = current.copy(
            log = current.log + entry
        )
        userDoc().set(updated).await()
    }
}





