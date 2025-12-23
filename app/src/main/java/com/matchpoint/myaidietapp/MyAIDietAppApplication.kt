package com.matchpoint.myaidietapp

import android.app.Application
import com.google.firebase.FirebaseApp
import com.matchpoint.myaidietapp.data.UserIdProvider
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

class MyAIDietAppApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        AppCheckInstaller.install()

        // RevenueCat is the source of truth for subscription status.
        val apiKey = BuildConfig.REVENUECAT_API_KEY
        if (apiKey.isNotBlank()) {
            Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO
            Purchases.configure(
                PurchasesConfiguration.Builder(this, apiKey)
                    .appUserID(UserIdProvider(this).getUserId())
                    .build()
            )
        }
    }
}


