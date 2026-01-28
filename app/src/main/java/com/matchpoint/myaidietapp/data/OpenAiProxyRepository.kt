package com.matchpoint.myaidietapp.data

import com.matchpoint.myaidietapp.BuildConfig
import com.matchpoint.myaidietapp.model.DietType
import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

data class CheckInRequest(
    val lastMeal: String?,
    val hungerSummary: String?,
    val weightTrend: Double?,
    val minutesSinceMeal: Long?,
    val tone: String = "short, casual, varied, human, not templated",
    val userMessage: String? = null,
    val mode: String = "hunger_check",
    val inventorySummary: String? = null,
    val productUrl: String? = null,
    val labelUrl: String? = null,
    val nutritionFactsUrl: String? = null,
    val dietType: String? = null,
    val fastingPreset: String? = null,
    val eatingWindowStartMinutes: Int? = null,
    val eatingWindowEndMinutes: Int? = null,
    // Optional client time helpers so backend aligns with the phone’s local clock
    val clientLocalMinutes: Int? = null,
    // YYYY-MM-DD in the user's local timezone (used by backend for daily quota reset)
    val clientLocalDate: String? = null,
    val timezoneOffsetMinutes: Int? = null,
    // Meal logging support (mode="analyze_meal")
    val mealPhotoUrl: String? = null,
    val mealGrams: Int? = null,
    val mealText: String? = null,
    // Menu scan support (mode="menu_scan")
    val menuPhotoUrl: String? = null,
    // Recipe de-duplication support (mode="generate_meal")
    val existingRecipeTitles: List<String>? = null,
    // Recipe constraints (mode="generate_meal")
    val requiredIngredients: List<String>? = null,
    // Chat continuity support (mode="freeform")
    val chatContext: String? = null,
    // Batch food name analysis (mode="analyze_food_batch")
    val foodNames: List<String>? = null,
    // Daily plan generation (mode="daily_plan")
    val dailyTargetCalories: Int? = null,
    val dailyMealCount: Int? = null,
    val dailySavedRecipesOnly: Boolean? = null,
    val savedRecipesForPlan: List<SavedRecipeForPlan>? = null
)

data class CheckInResponse(val text: String)

data class SavedRecipeForPlan(
    val id: String,
    val title: String,
    val text: String? = null
)

data class DailyPlanResponse(
    val dailyTargetCalories: Int? = null,
    val totalEstimatedCalories: Int = 0,
    val meals: List<DailyPlanMeal> = emptyList()
)

data class DailyPlanMeal(
    val id: String = "",
    val title: String = "",
    val estimatedCalories: Int = 0,
    val recipeText: String = "",
    val sourceRecipeId: String? = null
)

/**
 * Parsed shape of the JSON inside the `text` field when mode="analyze_food".
 */
data class AnalyzeFoodResponse(
    val accepted: Boolean,
    val rating: Int,               // general health rating 1-10
    val normalizedName: String,
    val summary: String,           // short combined health + diet-fit summary
    val concerns: String,
    val dietFitRating: Int? = null, // how well it fits the current diet, 1-10
    /**
     * Optional multi-diet ratings. Keys are DietType.name.
     * Cloud Function can return this; if absent, we fall back to [dietFitRating].
     */
    val dietRatings: Map<String, Int> = emptyMap(),
    /**
     * Optional multi-allergy ratings. Keys like "PEANUT", "DAIRY", etc.
     */
    val allergyRatings: Map<String, Int> = emptyMap(),
    /**
     * Optional nutrition/ingredients estimates.
     * For photo-based, should be extracted if possible; otherwise guessed.
     */
    val estimatedCalories: Int? = null,
    val estimatedProteinG: Int? = null,
    val estimatedCarbsG: Int? = null,
    val estimatedFatG: Int? = null,
    val ingredientsText: String? = null,
    /**
     * Calories context helpers (optional; mainly for AI Evaluate Food).
     * - PACKAGED: use caloriesPerServing (+ servingsPerContainer when readable)
     * - PLATED: caloriesTotal is for the full pictured plate/bowl
     */
    val portionKind: String? = null, // "PACKAGED" | "PLATED" | "UNKNOWN"
    val servingSizeText: String? = null,
    val caloriesPerServing: Int? = null,
    val servingsPerContainer: Double? = null,
    val caloriesTotal: Int? = null,
    val caloriesLow: Int? = null,
    val caloriesHigh: Int? = null,
    /**
     * Suggested category for putting the item into the user's list.
     * Values: "INGREDIENT" | "SNACK" | "MEAL"
     */
    val suggestedCategory: String? = null
)

/**
 * Parsed shape of JSON inside the `text` field when mode="analyze_meal".
 * Note: the Cloud Function must be updated to support this mode. If it isn't,
 * callers should catch exceptions and fall back to "grams-only" logging.
 */
data class AnalyzeMealResponse(
    val accepted: Boolean,
    val normalizedMealName: String,
    val estimatedCalories: Int,
    val estimatedProteinG: Int? = null,
    val estimatedCarbsG: Int? = null,
    val estimatedFatG: Int? = null,
    val notes: String? = null
)

interface CheckInService {
    @Headers("Content-Type: application/json")
    @POST("checkin")
    suspend fun checkIn(@Body body: CheckInRequest): CheckInResponse
}

class OpenAiProxyRepository(
    baseUrl: String = BuildConfig.CHECKIN_PROXY_BASE_URL
) {
    private fun localDayKey(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val analyzeAdapter = moshi.adapter(AnalyzeFoodResponse::class.java)
    private val analyzeMealAdapter = moshi.adapter(AnalyzeMealResponse::class.java)
    private val dailyPlanAdapter = moshi.adapter(DailyPlanResponse::class.java)
    private val analyzeListAdapter = moshi.adapter<List<AnalyzeFoodResponse>>(
        Types.newParameterizedType(List::class.java, AnalyzeFoodResponse::class.java)
    )

    private val service: CheckInService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val authInterceptor = Interceptor { chain ->
            val req = chain.request()
            val token = runBlocking {
                val user = FirebaseAuth.getInstance().currentUser ?: return@runBlocking null
                // Token can be null briefly right after sign-in, or if it needs refresh.
                // Try cached first, then force refresh once.
                val t1 = runCatching { user.getIdToken(false).await().token }.getOrNull()
                if (!t1.isNullOrBlank()) return@runBlocking t1
                runCatching { user.getIdToken(true).await().token }.getOrNull()
            }
            val authed = if (!token.isNullOrBlank()) {
                req.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                // Keep the request as-is, but emit a debug log so 401s are diagnosable.
                // If this fires, the backend will respond with {"text":"AUTH_REQUIRED"}.
                Log.w("OpenAiProxyRepository", "No Firebase ID token available; sending request without Authorization header")
                req
            }
            chain.proceed(authed)
        }
        // Vision requests (and cold starts) can take >10s; default OkHttp timeouts are too aggressive.
        // Use more forgiving timeouts to avoid SocketTimeoutException when the function responds slowly.
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(authInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CheckInService::class.java)
    }

    suspend fun generateCheckIn(request: CheckInRequest): String =
        withContext(Dispatchers.IO) {
            val enriched = if (request.clientLocalDate.isNullOrBlank()) {
                request.copy(clientLocalDate = localDayKey())
            } else {
                request
            }
            service.checkIn(enriched).text
        }

    suspend fun analyzeFood(
        productUrl: String,
        labelUrl: String?,
        nutritionFactsUrl: String?,
        dietType: DietType
    ): AnalyzeFoodResponse = withContext(Dispatchers.IO) {
        // Reuse the /checkin endpoint with mode="analyze_food".
        val response = service.checkIn(
            CheckInRequest(
                lastMeal = null,
                hungerSummary = null,
                weightTrend = null,
                minutesSinceMeal = null,
                mode = "analyze_food",
                inventorySummary = null,
                productUrl = productUrl,
                labelUrl = labelUrl,
                nutritionFactsUrl = nutritionFactsUrl,
                dietType = dietType.name,
                clientLocalDate = localDayKey()
            )
        )
        val raw = response.text
        analyzeAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty food analysis response")
    }

    /**
     * Text-only variant: ask the proxy to analyze a food by name without images.
     */
    suspend fun analyzeFoodByName(
        foodName: String,
        dietType: DietType
    ): AnalyzeFoodResponse = withContext(Dispatchers.IO) {
        val response = service.checkIn(
            CheckInRequest(
                lastMeal = foodName,
                hungerSummary = null,
                weightTrend = null,
                minutesSinceMeal = null,
                mode = "analyze_food",
                inventorySummary = null,
                productUrl = null,
                labelUrl = null,
                dietType = dietType.name,
                clientLocalDate = localDayKey()
            )
        )
        val raw = response.text
        analyzeAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty food analysis response (text)")
    }

    /**
     * Batch text-only variant: analyze multiple food names in one request.
     * The backend enforces a max batch size; callers should chunk as needed.
     */
    suspend fun analyzeFoodsByNameBatch(
        foodNames: List<String>,
        dietType: DietType
    ): List<AnalyzeFoodResponse> = withContext(Dispatchers.IO) {
        val cleaned = foodNames.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return@withContext emptyList()

        val response = service.checkIn(
            CheckInRequest(
                lastMeal = null,
                hungerSummary = null,
                weightTrend = null,
                minutesSinceMeal = null,
                mode = "analyze_food_batch",
                inventorySummary = null,
                dietType = dietType.name,
                foodNames = cleaned,
                clientLocalDate = localDayKey()
            )
        )
        val raw = response.text
        analyzeListAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty batch food analysis response")
    }

    suspend fun analyzeMeal(
        mealPhotoUrl: String?,
        mealGrams: Int?,
        mealText: String?,
        dietType: DietType,
        inventorySummary: String?
    ): AnalyzeMealResponse = withContext(Dispatchers.IO) {
        val response = service.checkIn(
            CheckInRequest(
                lastMeal = null,
                hungerSummary = null,
                weightTrend = null,
                minutesSinceMeal = null,
                mode = "analyze_meal",
                inventorySummary = inventorySummary,
                dietType = dietType.name,
                mealPhotoUrl = mealPhotoUrl,
                mealGrams = mealGrams,
                mealText = mealText,
                clientLocalDate = localDayKey()
            )
        )
        val raw = response.text
        analyzeMealAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty meal analysis response")
    }

    /**
     * Menu scan: analyze a restaurant menu photo and recommend diet-compatible choices.
     * Returns plain text to display directly.
     */
    suspend fun analyzeMenu(
        menuPhotoUrl: String,
        dietType: DietType,
        inventorySummary: String?
    ): String = withContext(Dispatchers.IO) {
        service.checkIn(
            CheckInRequest(
                lastMeal = null,
                hungerSummary = null,
                weightTrend = null,
                minutesSinceMeal = null,
                mode = "menu_scan",
                inventorySummary = inventorySummary,
                dietType = dietType.name,
                menuPhotoUrl = menuPhotoUrl,
                userMessage = "Scan this restaurant menu photo and recommend the best choices for my diet.",
                clientLocalDate = localDayKey()
            )
        ).text
    }

    suspend fun generateDailyPlan(
        dailyTargetCalories: Int?,
        dailyMealCount: Int,
        savedRecipesOnly: Boolean,
        savedRecipes: List<com.matchpoint.myaidietapp.model.SavedRecipe>,
        dietType: DietType,
        inventorySummary: String?
    ): DailyPlanResponse = withContext(Dispatchers.IO) {
        val safeCount = dailyMealCount.coerceIn(1, 3)
        val safeTarget = dailyTargetCalories
            ?.coerceIn(800, 3000)
            ?.takeIf { it > 0 }
        val safeSaved = savedRecipes
            .mapNotNull { r ->
                val id = r.id.trim()
                val title = r.title.trim()
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                SavedRecipeForPlan(
                    id = id,
                    title = title,
                    text = r.text.takeIf { savedRecipesOnly } // only send text when needed
                )
            }
            .take(12)

        val response = service.checkIn(
            CheckInRequest(
                lastMeal = null,
                hungerSummary = null,
                weightTrend = null,
                minutesSinceMeal = null,
                mode = "daily_plan",
                inventorySummary = inventorySummary,
                dietType = dietType.name,
                dailyTargetCalories = safeTarget,
                dailyMealCount = safeCount,
                dailySavedRecipesOnly = savedRecipesOnly,
                savedRecipesForPlan = if (savedRecipesOnly) safeSaved else null,
                clientLocalDate = localDayKey()
            )
        )
        val raw = response.text.trim()
        // If the Cloud Function isn't deployed with daily_plan support yet, it will fall back to normal chat
        // and return plain text (not JSON). Moshi would throw a confusing "setLenient" error; make it explicit.
        if (!raw.startsWith("{")) {
            throw IllegalStateException("Daily plan backend returned non-JSON. Redeploy the Cloud Function. (got: ${raw.take(180)})")
        }
        dailyPlanAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty daily plan response")
    }
}
