plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Firebase / Google Services config is intentionally not checked into git (see .gitignore).
// On a fresh machine, this file may be missing; don't fail the whole build in that case.
val hasGoogleServicesJson = listOf(
    file("google-services.json"),
    file("src/main/google-services.json"),
    file("src/debug/google-services.json"),
    file("src/release/google-services.json")
).any { it.exists() }

if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn(
        "google-services.json not found; Firebase/Google services are disabled for this build. " +
            "To enable Firebase, download google-services.json for applicationId 'com.myaidiet.app' " +
            "from the Firebase console and place it in app/google-services.json (or app/src/debug/google-services.json)."
    )
}

android {
    // You can change this later if you want, it does not need to match applicationId
    namespace = "com.matchpoint.myaidietapp"
    compileSdk = 36

    defaultConfig {
        // This MUST match what you registered in Firebase
        applicationId = "com.myaidiet.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 40
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // RevenueCat public SDK key. Set it in ~/.gradle/gradle.properties:
    // REVENUECAT_API_KEY=public_... (recommended)
    val revenueCatApiKey: String =
        (project.findProperty("REVENUECAT_API_KEY") as String?)?.trim().orEmpty()

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField(
                "String",
                "CHECKIN_PROXY_BASE_URL",
                "\"https://us-central1-myaidiet.cloudfunctions.net/\""
            )
            buildConfigField("String", "REVENUECAT_API_KEY", "\"$revenueCatApiKey\"")
        }
        debug {
            buildConfigField(
                "String",
                "CHECKIN_PROXY_BASE_URL",
                "\"https://us-central1-myaidiet.cloudfunctions.net/\""
            )
            buildConfigField("String", "REVENUECAT_API_KEY", "\"$revenueCatApiKey\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Needed for: androidx.lifecycle.viewmodel.compose.viewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Firebase (Firestore + Analytics + Messaging via BoM)
    implementation(platform("com.google.firebase:firebase-bom:33.2.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-appcheck-ktx")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    // Networking for OpenAI proxy
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // WorkManager for background scheduling of notifications
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coroutines support for Firebase Task.await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Image loading for local photo previews in Compose (FoodPhotoCaptureScreen)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // In-app updates (shows "Update available" inside the app; only works for Play-installed builds)
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // RevenueCat (subscriptions + receipt validation + restores)
    implementation("com.revenuecat.purchases:purchases:9.1.0")

    // Google Play Billing Library.
    // RevenueCat 9.x depends on Billing transitively; we pin the version to match RevenueCat's requirement.
    implementation("com.android.billingclient:billing:8.0.0")
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Ensure transitive dependencies cannot pull an older Billing Library (Play policy requires v7+).
configurations.configureEach {
    resolutionStrategy {
        force(
            "com.android.billingclient:billing:8.0.0",
            "com.android.billingclient:billing-ktx:8.0.0"
        )
    }
}