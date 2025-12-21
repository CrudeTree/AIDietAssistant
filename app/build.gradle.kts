plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
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
        versionCode = 6
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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
        }
        debug {
            buildConfigField(
                "String",
                "CHECKIN_PROXY_BASE_URL",
                "\"https://us-central1-myaidiet.cloudfunctions.net/\""
            )
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

    // Google Play Billing (subscriptions)
    implementation("com.android.billingclient:billing-ktx:6.2.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}