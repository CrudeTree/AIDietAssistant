package com.matchpoint.myaidietapp.data

import com.matchpoint.myaidietapp.BuildConfig
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
    val inventorySummary: String? = null
)

data class CheckInResponse(val text: String)

interface CheckInService {
    @Headers("Content-Type: application/json")
    @POST("checkin")
    suspend fun checkIn(@Body body: CheckInRequest): CheckInResponse
}

class OpenAiProxyRepository(
    baseUrl: String = BuildConfig.CHECKIN_PROXY_BASE_URL
) {

    private val service: CheckInService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
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
}


