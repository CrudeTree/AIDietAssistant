package com.matchpoint.myaidietapp.data

import com.matchpoint.myaidietapp.BuildConfig
import com.matchpoint.myaidietapp.model.DietType
import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
    val timezoneOffsetMinutes: Int? = null,
    // Meal logging support (mode="analyze_meal")
    val mealPhotoUrl: String? = null,
    val mealGrams: Int? = null,
    val mealText: String? = null
)

data class CheckInResponse(val text: String)

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
    val ingredientsText: String? = null
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

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val analyzeAdapter = moshi.adapter(AnalyzeFoodResponse::class.java)
    private val analyzeMealAdapter = moshi.adapter(AnalyzeMealResponse::class.java)

    private val service: CheckInService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val authInterceptor = Interceptor { chain ->
            val req = chain.request()
            val token = runBlocking {
                FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            }
            val authed = if (!token.isNullOrBlank()) {
                req.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
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
            service.checkIn(request).text
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
                dietType = dietType.name
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
                dietType = dietType.name
            )
        )
        val raw = response.text
        analyzeAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty food analysis response (text)")
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
                mealText = mealText
            )
        )
        val raw = response.text
        analyzeMealAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty meal analysis response")
    }
}
