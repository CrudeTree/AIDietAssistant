package com.matchpoint.myaidietapp.data

import com.matchpoint.myaidietapp.BuildConfig
import com.matchpoint.myaidietapp.model.DietType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

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
    val dietType: String? = null
)

data class CheckInResponse(val text: String)

data class AnalyzeFoodResponse(
    val accepted: Boolean,
    val rating: Int,
    val normalizedName: String,
    val summary: String,
    val concerns: String
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

    private val service: CheckInService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
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
        dietType: DietType
    ): AnalyzeFoodResponse = withContext(Dispatchers.IO) {
        // Reuse the /checkin endpoint with mode=\"analyze_food\".
        // The Cloud Function returns { text: \"{...json...}\" } for this mode.
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
                dietType = dietType.name
            )
        )
        val raw = response.text
        analyzeAdapter.fromJson(raw)
            ?: throw IllegalStateException("Empty food analysis response")
    }
}


